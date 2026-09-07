package com.keiba.ai

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

object NarForecastWeatherSnapshotStore {

    private const val ROOT_NAME =
        "nar-forecast-weather-snapshots"

    private const val FORMAT_VERSION =
        "1"

    private const val PROVIDER =
        "open-meteo"

    private const val MAX_MANIFEST_BYTES =
        32L * 1024L

    enum class SaveStatus {
        CREATED,
        ALREADY_PRESENT
    }

    data class SaveResult(
        val status: SaveStatus,
        val directory: File,
        val forecastSha256: String,
        val snapshotSha256: String,
        val pitEvidenceAtEpochMillis: Long
    )

    fun save(
        context: Context,
        data: NarForecastWeatherDownloader.ForecastResponse
    ): SaveResult =
        saveToRoot(
            root = File(
                context.noBackupFilesDir,
                ROOT_NAME
            ),
            data = data
        )

    internal fun saveToRoot(
        root: File,
        data: NarForecastWeatherDownloader.ForecastResponse
    ): SaveResult {
        val prepared =
            validateAndPrepare(data)

        require(
            root.exists() || root.mkdirs()
        ) {
            "could not create weather snapshot root"
        }

        require(root.isDirectory) {
            "weather snapshot root is not a directory"
        }

        val dayDir =
            File(
                root,
                prepared.captureDateUtc
            )

        require(
            dayDir.exists() || dayDir.mkdirs()
        ) {
            "could not create weather snapshot day directory"
        }

        val finalDir =
            File(
                dayDir,
                prepared.downloadedAtEpochMillis
                    .toString() +
                    "-" +
                    prepared.snapshotSha256
            )

        requireChildPath(
            parent = root,
            child = finalDir
        )

        if (finalDir.exists()) {
            return verifyExistingExact(
                finalDir = finalDir,
                expected = prepared
            )
        }

        val tempDir =
            File(
                dayDir,
                "." +
                    prepared.downloadedAtEpochMillis +
                    ".tmp-" +
                    UUID.randomUUID().toString()
            )

        requireChildPath(
            parent = root,
            child = tempDir
        )

        require(tempDir.mkdir()) {
            "could not create weather snapshot staging directory"
        }

        try {
            writeSynced(
                File(
                    tempDir,
                    "forecast.json"
                ),
                prepared.forecastBytes
            )

            writeSynced(
                File(
                    tempDir,
                    "manifest.txt"
                ),
                prepared.manifestText
                    .toByteArray(
                        StandardCharsets.UTF_8
                    )
            )

            if (!tempDir.renameTo(finalDir)) {
                if (finalDir.exists()) {
                    return verifyExistingExact(
                        finalDir = finalDir,
                        expected = prepared
                    )
                }

                error(
                    "could not atomically publish weather snapshot"
                )
            }

            require(
                verifySnapshot(finalDir)
            ) {
                "published weather snapshot failed verification"
            }

            return SaveResult(
                status = SaveStatus.CREATED,
                directory = finalDir,
                forecastSha256 =
                    prepared.forecastSha256,
                snapshotSha256 =
                    prepared.snapshotSha256,
                pitEvidenceAtEpochMillis =
                    prepared.pitEvidenceAtEpochMillis
            )
        } finally {
            if (tempDir.exists()) {
                tempDir.deleteRecursively()
            }
        }
    }

    internal fun verifySnapshot(
        directory: File
    ): Boolean {
        if (!directory.isDirectory) {
            return false
        }

        val canonicalDirectory =
            try {
                directory.canonicalFile
            } catch (_: Exception) {
                return false
            }

        val forecastFile =
            File(
                canonicalDirectory,
                "forecast.json"
            )

        val manifestFile =
            File(
                canonicalDirectory,
                "manifest.txt"
            )

        if (
            !isSafeRegularFile(
                canonicalDirectory,
                forecastFile,
                NarForecastWeatherDownloader
                    .MAX_RESPONSE_BYTES
            ) ||
            !isSafeRegularFile(
                canonicalDirectory,
                manifestFile,
                MAX_MANIFEST_BYTES
            )
        ) {
            return false
        }

        val allowedNames =
            setOf(
                "forecast.json",
                "manifest.txt"
            )

        val actualNames =
            canonicalDirectory
                .list()
                ?.toSet()
                ?: return false

        if (actualNames != allowedNames) {
            return false
        }

        val manifest =
            try {
                parseManifest(
                    manifestFile.readText(
                        Charsets.UTF_8
                    )
                )
            } catch (_: Exception) {
                return false
            }

        val requiredKeys =
            setOf(
                "format_version",
                "provider",
                "capture_date_utc",
                "requested_latitude",
                "requested_longitude",
                "request_url",
                "downloaded_at_epoch_millis",
                "server_date_epoch_millis",
                "pit_evidence_at_epoch_millis",
                "forecast_sha256",
                "snapshot_sha256"
            )

        if (manifest.keys != requiredKeys) {
            return false
        }

        if (
            manifest["format_version"] !=
                FORMAT_VERSION ||
            manifest["provider"] !=
                PROVIDER
        ) {
            return false
        }

        val captureDate =
            manifest["capture_date_utc"]
                ?: return false

        if (
            !captureDate.matches(
                Regex("""^\d{8}$""")
            )
        ) {
            return false
        }

        val latitude =
            manifest["requested_latitude"]
                ?.toDoubleOrNull()
                ?: return false

        val longitude =
            manifest["requested_longitude"]
                ?.toDoubleOrNull()
                ?: return false

        val canonicalRequestUrl =
            try {
                NarForecastWeatherDownloader
                    .buildRequestUrl(
                        latitude,
                        longitude
                    )
            } catch (_: Exception) {
                return false
            }

        if (
            manifest["request_url"] !=
                canonicalRequestUrl
        ) {
            return false
        }

        val downloadedAt =
            manifest[
                "downloaded_at_epoch_millis"
            ]
                ?.toLongOrNull()
                ?: return false

        if (downloadedAt <= 0L) {
            return false
        }

        val expectedCaptureDate =
            try {
                captureDateUtc(downloadedAt)
            } catch (_: Exception) {
                return false
            }

        if (
            captureDate !=
                expectedCaptureDate
        ) {
            return false
        }

        val serverDateText =
            manifest[
                "server_date_epoch_millis"
            ] ?: return false

        val serverDate =
            if (serverDateText.isEmpty()) {
                null
            } else {
                serverDateText
                    .toLongOrNull()
                    ?.takeIf { it > 0L }
                    ?: return false
            }

        val pitEvidenceAt =
            manifest[
                "pit_evidence_at_epoch_millis"
            ]
                ?.toLongOrNull()
                ?: return false

        val expectedPitEvidenceAt =
            maxOf(
                downloadedAt,
                serverDate ?: 0L
            )

        if (
            pitEvidenceAt !=
                expectedPitEvidenceAt
        ) {
            return false
        }

        val forecastBytes =
            try {
                readFileLimited(
                    forecastFile,
                    NarForecastWeatherDownloader
                        .MAX_RESPONSE_BYTES
                )
            } catch (_: Exception) {
                return false
            }

        try {
            NarForecastWeatherDownloader
                .validateResponseBytes(
                    forecastBytes
                )
        } catch (_: Exception) {
            return false
        }

        val forecastSha =
            sha256Hex(
                forecastBytes
            )

        if (
            forecastSha !=
                manifest["forecast_sha256"]
        ) {
            return false
        }

        val snapshotSha =
            try {
                computeSnapshotSha256(
                    formatVersion =
                        manifest["format_version"]
                            ?: return false,
                    provider =
                        manifest["provider"]
                            ?: return false,
                    captureDateUtc =
                        captureDate,
                    requestedLatitude =
                        manifest["requested_latitude"]
                            ?: return false,
                    requestedLongitude =
                        manifest["requested_longitude"]
                            ?: return false,
                    requestUrl =
                        manifest["request_url"]
                            ?: return false,
                    downloadedAtEpochMillis =
                        downloadedAt.toString(),
                    serverDateEpochMillis =
                        serverDateText,
                    pitEvidenceAtEpochMillis =
                        pitEvidenceAt.toString(),
                    forecastSha256 =
                        forecastSha
                )
            } catch (_: Exception) {
                return false
            }

        if (
            snapshotSha !=
                manifest["snapshot_sha256"]
        ) {
            return false
        }

        if (
            canonicalDirectory.name !=
                downloadedAt.toString() +
                "-" +
                snapshotSha
        ) {
            return false
        }

        if (
            canonicalDirectory
                .parentFile
                ?.name !=
                captureDate
        ) {
            return false
        }

        return true
    }

    private data class PreparedSnapshot(
        val captureDateUtc: String,
        val requestedLatitude: String,
        val requestedLongitude: String,
        val requestUrl: String,
        val downloadedAtEpochMillis: Long,
        val serverDateEpochMillis: Long?,
        val pitEvidenceAtEpochMillis: Long,
        val forecastBytes: ByteArray,
        val forecastSha256: String,
        val snapshotSha256: String,
        val manifestText: String
    )

    private fun validateAndPrepare(
        data: NarForecastWeatherDownloader.ForecastResponse
    ): PreparedSnapshot {
        val canonicalRequestUrl =
            NarForecastWeatherDownloader
                .buildRequestUrl(
                    data.requestedLatitude,
                    data.requestedLongitude
                )

        require(
            data.requestUrl ==
                canonicalRequestUrl
        ) {
            "weather request URL/provenance mismatch"
        }

        val downloadedAt =
            data.downloadedAtEpochMillis

        require(downloadedAt > 0L) {
            "invalid weather downloaded-at"
        }

        val serverDate =
            data.serverDateEpochMillis
                ?.also {
                    require(it > 0L) {
                        "invalid weather server date"
                    }
                }

        val pitEvidenceAt =
            maxOf(
                downloadedAt,
                serverDate ?: 0L
            )

        val forecastBytes =
            data.responseBytes.copyOf()

        NarForecastWeatherDownloader
            .validateResponseBytes(
                forecastBytes
            )

        val forecastSha =
            sha256Hex(
                forecastBytes
            )

        val captureDate =
            captureDateUtc(
                downloadedAt
            )

        val latitudeText =
            data.requestedLatitude
                .toString()

        val longitudeText =
            data.requestedLongitude
                .toString()

        val serverDateText =
            serverDate
                ?.toString()
                ?: ""

        val snapshotSha =
            computeSnapshotSha256(
                formatVersion =
                    FORMAT_VERSION,
                provider =
                    PROVIDER,
                captureDateUtc =
                    captureDate,
                requestedLatitude =
                    latitudeText,
                requestedLongitude =
                    longitudeText,
                requestUrl =
                    canonicalRequestUrl,
                downloadedAtEpochMillis =
                    downloadedAt.toString(),
                serverDateEpochMillis =
                    serverDateText,
                pitEvidenceAtEpochMillis =
                    pitEvidenceAt.toString(),
                forecastSha256 =
                    forecastSha
            )

        val manifest =
            buildString {
                append("format_version=")
                append(FORMAT_VERSION)
                append('\n')

                append("provider=")
                append(PROVIDER)
                append('\n')

                append("capture_date_utc=")
                append(captureDate)
                append('\n')

                append("requested_latitude=")
                append(latitudeText)
                append('\n')

                append("requested_longitude=")
                append(longitudeText)
                append('\n')

                append("request_url=")
                append(canonicalRequestUrl)
                append('\n')

                append(
                    "downloaded_at_epoch_millis="
                )
                append(downloadedAt)
                append('\n')

                append(
                    "server_date_epoch_millis="
                )
                append(serverDateText)
                append('\n')

                append(
                    "pit_evidence_at_epoch_millis="
                )
                append(pitEvidenceAt)
                append('\n')

                append("forecast_sha256=")
                append(forecastSha)
                append('\n')

                append("snapshot_sha256=")
                append(snapshotSha)
                append('\n')
            }

        require(
            manifest.toByteArray(
                StandardCharsets.UTF_8
            ).size.toLong() <=
                MAX_MANIFEST_BYTES
        ) {
            "weather snapshot manifest too large"
        }

        return PreparedSnapshot(
            captureDateUtc =
                captureDate,
            requestedLatitude =
                latitudeText,
            requestedLongitude =
                longitudeText,
            requestUrl =
                canonicalRequestUrl,
            downloadedAtEpochMillis =
                downloadedAt,
            serverDateEpochMillis =
                serverDate,
            pitEvidenceAtEpochMillis =
                pitEvidenceAt,
            forecastBytes =
                forecastBytes,
            forecastSha256 =
                forecastSha,
            snapshotSha256 =
                snapshotSha,
            manifestText =
                manifest
        )
    }

    private fun verifyExistingExact(
        finalDir: File,
        expected: PreparedSnapshot
    ): SaveResult {
        require(finalDir.isDirectory) {
            "weather snapshot path exists but is not a directory"
        }

        require(
            verifySnapshot(finalDir)
        ) {
            "existing weather snapshot failed verification"
        }

        val manifestFile =
            File(
                finalDir,
                "manifest.txt"
            )

        val existing =
            parseManifest(
                manifestFile.readText(
                    Charsets.UTF_8
                )
            )

        require(
            existing["snapshot_sha256"] ==
                expected.snapshotSha256 &&
            existing["forecast_sha256"] ==
                expected.forecastSha256 &&
            existing[
                "downloaded_at_epoch_millis"
            ] ==
                expected
                    .downloadedAtEpochMillis
                    .toString() &&
            existing["request_url"] ==
                expected.requestUrl
        ) {
            "conflicting existing weather snapshot"
        }

        val existingPitEvidenceAt =
            existing[
                "pit_evidence_at_epoch_millis"
            ]
                ?.toLongOrNull()
                ?: error(
                    "invalid existing weather PIT evidence"
                )

        return SaveResult(
            status =
                SaveStatus.ALREADY_PRESENT,
            directory =
                finalDir,
            forecastSha256 =
                expected.forecastSha256,
            snapshotSha256 =
                expected.snapshotSha256,
            pitEvidenceAtEpochMillis =
                existingPitEvidenceAt
        )
    }

    private fun captureDateUtc(
        epochMillis: Long
    ): String =
        Instant.ofEpochMilli(
            epochMillis
        )
            .atZone(
                ZoneOffset.UTC
            )
            .toLocalDate()
            .format(
                DateTimeFormatter.BASIC_ISO_DATE
            )

    private fun requireChildPath(
        parent: File,
        child: File
    ) {
        val parentPath =
            parent.canonicalFile
                .toPath()

        val childPath =
            child.canonicalFile
                .toPath()

        require(
            childPath.startsWith(
                parentPath
            )
        ) {
            "weather snapshot path escaped root"
        }
    }

    private fun isSafeRegularFile(
        parent: File,
        file: File,
        maxBytes: Long
    ): Boolean {
        if (!file.isFile) {
            return false
        }

        if (
            file.length() < 0L ||
            file.length() > maxBytes
        ) {
            return false
        }

        val canonicalParent =
            try {
                parent.canonicalFile
            } catch (_: Exception) {
                return false
            }

        val canonicalFile =
            try {
                file.canonicalFile
            } catch (_: Exception) {
                return false
            }

        if (
            canonicalFile.parentFile !=
                canonicalParent
        ) {
            return false
        }

        return true
    }

    private fun writeSynced(
        file: File,
        bytes: ByteArray
    ) {
        require(!file.exists()) {
            "refusing to overwrite weather snapshot file"
        }

        FileOutputStream(file).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
    }

    private fun readFileLimited(
        file: File,
        maxBytes: Long
    ): ByteArray {
        require(
            file.length() <= maxBytes
        ) {
            "weather snapshot file too large"
        }

        val output =
            ByteArrayOutputStream()

        val buffer =
            ByteArray(8192)

        var total = 0L

        FileInputStream(file).use { input ->
            while (true) {
                val count =
                    input.read(buffer)

                if (count < 0) {
                    break
                }

                total =
                    Math.addExact(
                        total,
                        count.toLong()
                    )

                require(total <= maxBytes) {
                    "weather snapshot exceeded size limit"
                }

                output.write(
                    buffer,
                    0,
                    count
                )
            }
        }

        return output.toByteArray()
    }

    private fun parseManifest(
        text: String
    ): Map<String, String> {
        val result =
            linkedMapOf<String, String>()

        for (line in text.split('\n')) {
            if (line.isEmpty()) {
                continue
            }

            val index =
                line.indexOf('=')

            if (index <= 0) {
                error(
                    "invalid weather snapshot manifest"
                )
            }

            val key =
                line.substring(
                    0,
                    index
                )

            val value =
                line.substring(
                    index + 1
                )

            require(
                key.matches(
                    Regex(
                        """^[a-z0-9_]+$"""
                    )
                )
            ) {
                "invalid weather manifest key"
            }

            require(
                key !in result
            ) {
                "duplicate weather manifest key"
            }

            result[key] = value
        }

        return result
    }

    private fun computeSnapshotSha256(
        formatVersion: String,
        provider: String,
        captureDateUtc: String,
        requestedLatitude: String,
        requestedLongitude: String,
        requestUrl: String,
        downloadedAtEpochMillis: String,
        serverDateEpochMillis: String,
        pitEvidenceAtEpochMillis: String,
        forecastSha256: String
    ): String {
        val canonical =
            buildString {
                append("format_version=")
                append(formatVersion)
                append('\n')

                append("provider=")
                append(provider)
                append('\n')

                append("capture_date_utc=")
                append(captureDateUtc)
                append('\n')

                append("requested_latitude=")
                append(requestedLatitude)
                append('\n')

                append("requested_longitude=")
                append(requestedLongitude)
                append('\n')

                append("request_url=")
                append(requestUrl)
                append('\n')

                append(
                    "downloaded_at_epoch_millis="
                )
                append(
                    downloadedAtEpochMillis
                )
                append('\n')

                append(
                    "server_date_epoch_millis="
                )
                append(
                    serverDateEpochMillis
                )
                append('\n')

                append(
                    "pit_evidence_at_epoch_millis="
                )
                append(
                    pitEvidenceAtEpochMillis
                )
                append('\n')

                append("forecast_sha256=")
                append(forecastSha256)
                append('\n')
            }

        return sha256Hex(
            canonical.toByteArray(
                StandardCharsets.UTF_8
            )
        )
    }

    private fun sha256Hex(
        bytes: ByteArray
    ): String {
        val digest =
            MessageDigest
                .getInstance(
                    "SHA-256"
                )
                .digest(bytes)

        return buildString(
            digest.size * 2
        ) {
            for (b in digest) {
                append(
                    "%02x".format(
                        b.toInt() and 0xff
                    )
                )
            }
        }
    }
}
