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

/**
 * Append-only immutable archive for JMA observed public CSV captures.
 *
 * Layout:
 *   root/<product-id>/<YYYYMMDD>/<downloadedAt>-<snapshotSha>/
 *     observed.csv
 *     manifest.txt
 *
 * Product-wide raw snapshots only. Station/venue identity belongs to
 * a later parser / row-selection layer, not this store.
 */
object NarJmaObservedWeatherSnapshotStore {

    private const val ROOT_NAME =
        "nar-jma-observed-weather-snapshots"

    private const val FORMAT_VERSION =
        "1"

    private const val PROVIDER =
        "jma"

    private const val ROLE =
        "LIVE_CAPTURED_OBSERVED"

    private const val RAW_FILE_NAME =
        "observed.csv"

    private const val MANIFEST_FILE_NAME =
        "manifest.txt"

    private const val MAX_MANIFEST_BYTES =
        32L * 1024L

    private val PRODUCT_ID_PATTERN =
        Regex("""^[a-z0-9_]+$""")

    enum class SaveStatus {
        CREATED,
        ALREADY_PRESENT
    }

    data class SaveResult(
        val status: SaveStatus,
        val directory: File,
        val productId: String,
        val rawSha256: String,
        val snapshotSha256: String,
        val pitEvidenceAtEpochMillis: Long
    )

    fun save(
        context: Context,
        data: NarJmaObservedWeatherDownloader.ObservedCsvResponse
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
        data: NarJmaObservedWeatherDownloader.ObservedCsvResponse
    ): SaveResult {
        val prepared =
            validateAndPrepare(data)

        require(
            root.exists() || root.mkdirs()
        ) {
            "could not create observed weather snapshot root"
        }

        require(root.isDirectory) {
            "observed weather snapshot root is not a directory"
        }

        val productDir =
            File(
                root,
                prepared.productId
            )

        requireChildPath(
            parent = root,
            child = productDir
        )

        require(
            productDir.exists() ||
                productDir.mkdirs()
        ) {
            "could not create observed weather product directory"
        }

        val dayDir =
            File(
                productDir,
                prepared.captureDateUtc
            )

        requireChildPath(
            parent = root,
            child = dayDir
        )

        require(
            dayDir.exists() || dayDir.mkdirs()
        ) {
            "could not create observed weather day directory"
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
            "could not create observed weather staging directory"
        }

        try {
            writeSynced(
                File(
                    tempDir,
                    RAW_FILE_NAME
                ),
                prepared.rawBytes
            )

            writeSynced(
                File(
                    tempDir,
                    MANIFEST_FILE_NAME
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
                    "could not atomically publish observed weather snapshot"
                )
            }

            require(
                verifySnapshot(finalDir)
            ) {
                "published observed weather snapshot failed verification"
            }

            return SaveResult(
                status = SaveStatus.CREATED,
                directory = finalDir,
                productId =
                    prepared.productId,
                rawSha256 =
                    prepared.rawSha256,
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

        val rawFile =
            File(
                canonicalDirectory,
                RAW_FILE_NAME
            )

        val manifestFile =
            File(
                canonicalDirectory,
                MANIFEST_FILE_NAME
            )

        if (
            !isSafeRegularFile(
                canonicalDirectory,
                rawFile,
                NarJmaObservedWeatherDownloader
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
                RAW_FILE_NAME,
                MANIFEST_FILE_NAME
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
                "role",
                "product_id",
                "source_url",
                "capture_date_utc",
                "downloaded_at_epoch_millis",
                "server_date_epoch_millis",
                "last_modified_epoch_millis",
                "pit_evidence_at_epoch_millis",
                "content_type",
                "content_length",
                "raw_sha256",
                "snapshot_sha256"
            )

        if (manifest.keys != requiredKeys) {
            return false
        }

        if (
            manifest["format_version"] !=
                FORMAT_VERSION ||
            manifest["provider"] !=
                PROVIDER ||
            manifest["role"] !=
                ROLE
        ) {
            return false
        }

        val productId =
            manifest["product_id"]
                ?: return false

        if (
            !PRODUCT_ID_PATTERN.matches(
                productId
            ) ||
            !NarJmaObservedWeatherProduct
                .isKnownProductId(
                    productId
                )
        ) {
            return false
        }

        val product =
            try {
                NarJmaObservedWeatherProduct
                    .fromProductId(
                        productId
                    )
            } catch (_: Exception) {
                return false
            }

        val canonicalSourceUrl =
            product.canonicalUrl()

        if (
            manifest["source_url"] !=
                canonicalSourceUrl
        ) {
            return false
        }

        if (
            manifest["content_type"] !=
                "text/csv"
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

        val lastModifiedText =
            manifest[
                "last_modified_epoch_millis"
            ] ?: return false

        if (lastModifiedText.isNotEmpty()) {
            lastModifiedText
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
            try {
                NarJmaObservedWeatherDownloader
                    .computePitEvidenceAtEpochMillis(
                        downloadedAt,
                        serverDate
                    )
            } catch (_: Exception) {
                return false
            }

        if (
            pitEvidenceAt !=
                expectedPitEvidenceAt
        ) {
            return false
        }

        val rawBytes =
            try {
                readFileLimited(
                    rawFile,
                    NarJmaObservedWeatherDownloader
                        .MAX_RESPONSE_BYTES
                )
            } catch (_: Exception) {
                return false
            }

        try {
            NarJmaObservedWeatherDownloader
                .validateResponseBytes(
                    rawBytes
                )
        } catch (_: Exception) {
            return false
        }

        val contentLengthText =
            manifest["content_length"]
                ?: return false

        val contentLength =
            contentLengthText
                .toLongOrNull()
                ?: return false

        if (
            contentLength !=
                rawBytes.size.toLong()
        ) {
            return false
        }

        val rawSha =
            sha256Hex(
                rawBytes
            )

        if (
            rawSha !=
                manifest["raw_sha256"]
        ) {
            return false
        }

        val snapshotSha =
            try {
                computeSnapshotSha256(
                    formatVersion =
                        FORMAT_VERSION,
                    provider =
                        PROVIDER,
                    role =
                        ROLE,
                    productId =
                        productId,
                    sourceUrl =
                        canonicalSourceUrl,
                    captureDateUtc =
                        captureDate,
                    downloadedAtEpochMillis =
                        downloadedAt.toString(),
                    serverDateEpochMillis =
                        serverDateText,
                    lastModifiedEpochMillis =
                        lastModifiedText,
                    pitEvidenceAtEpochMillis =
                        pitEvidenceAt.toString(),
                    contentType =
                        "text/csv",
                    contentLength =
                        contentLengthText,
                    rawSha256 =
                        rawSha
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

        if (
            canonicalDirectory
                .parentFile
                ?.parentFile
                ?.name !=
                productId
        ) {
            return false
        }

        return true
    }

    private data class PreparedSnapshot(
        val productId: String,
        val sourceUrl: String,
        val captureDateUtc: String,
        val downloadedAtEpochMillis: Long,
        val serverDateEpochMillis: Long?,
        val lastModifiedEpochMillis: Long?,
        val pitEvidenceAtEpochMillis: Long,
        val contentType: String,
        val contentLength: Long,
        val rawBytes: ByteArray,
        val rawSha256: String,
        val snapshotSha256: String,
        val manifestText: String
    )

    private fun validateAndPrepare(
        data: NarJmaObservedWeatherDownloader.ObservedCsvResponse
    ): PreparedSnapshot {
        require(
            PRODUCT_ID_PATTERN.matches(
                data.productId
            )
        ) {
            "invalid observed weather product id"
        }

        val product =
            NarJmaObservedWeatherProduct
                .fromProductId(
                    data.productId
                )

        val canonicalSourceUrl =
            product.canonicalUrl()

        require(
            data.requestUrl ==
                canonicalSourceUrl
        ) {
            "observed weather request URL/provenance mismatch"
        }

        require(
            data.contentType ==
                "text/csv"
        ) {
            "unexpected observed weather content type"
        }

        val downloadedAt =
            data.downloadedAtEpochMillis

        require(downloadedAt > 0L) {
            "invalid observed weather downloaded-at"
        }

        val serverDate =
            data.serverDateEpochMillis
                ?.also {
                    require(it > 0L) {
                        "invalid observed weather server date"
                    }
                }

        val lastModified =
            data.lastModifiedEpochMillis
                ?.also {
                    require(it > 0L) {
                        "invalid observed weather last-modified"
                    }
                }

        val pitEvidenceAt =
            NarJmaObservedWeatherDownloader
                .computePitEvidenceAtEpochMillis(
                    downloadedAt,
                    serverDate
                )

        val rawBytes =
            data.responseBytes.copyOf()

        NarJmaObservedWeatherDownloader
            .validateResponseBytes(
                rawBytes
            )

        val rawSha =
            sha256Hex(
                rawBytes
            )

        val captureDate =
            captureDateUtc(
                downloadedAt
            )

        val serverDateText =
            serverDate
                ?.toString()
                ?: ""

        val lastModifiedText =
            lastModified
                ?.toString()
                ?: ""

        val contentLengthText =
            rawBytes.size.toString()

        val snapshotSha =
            computeSnapshotSha256(
                formatVersion =
                    FORMAT_VERSION,
                provider =
                    PROVIDER,
                role =
                    ROLE,
                productId =
                    product.productId,
                sourceUrl =
                    canonicalSourceUrl,
                captureDateUtc =
                    captureDate,
                downloadedAtEpochMillis =
                    downloadedAt.toString(),
                serverDateEpochMillis =
                    serverDateText,
                lastModifiedEpochMillis =
                    lastModifiedText,
                pitEvidenceAtEpochMillis =
                    pitEvidenceAt.toString(),
                contentType =
                    "text/csv",
                contentLength =
                    contentLengthText,
                rawSha256 =
                    rawSha
            )

        val manifest =
            buildString {
                appendLine(
                    "format_version=$FORMAT_VERSION"
                )
                appendLine(
                    "provider=$PROVIDER"
                )
                appendLine(
                    "role=$ROLE"
                )
                appendLine(
                    "product_id=${product.productId}"
                )
                appendLine(
                    "source_url=$canonicalSourceUrl"
                )
                appendLine(
                    "capture_date_utc=$captureDate"
                )
                appendLine(
                    "downloaded_at_epoch_millis=$downloadedAt"
                )
                appendLine(
                    "server_date_epoch_millis=$serverDateText"
                )
                appendLine(
                    "last_modified_epoch_millis=$lastModifiedText"
                )
                appendLine(
                    "pit_evidence_at_epoch_millis=$pitEvidenceAt"
                )
                appendLine(
                    "content_type=text/csv"
                )
                appendLine(
                    "content_length=$contentLengthText"
                )
                appendLine(
                    "raw_sha256=$rawSha"
                )
                appendLine(
                    "snapshot_sha256=$snapshotSha"
                )
            }

        // Forecast store uses trailing newline via append('\n');
        // buildString appendLine already ends with newline.
        requireNoNewlinesInValues(
            canonicalSourceUrl,
            product.productId,
            captureDate,
            serverDateText,
            lastModifiedText,
            rawSha,
            snapshotSha
        )

        require(
            manifest.toByteArray(
                StandardCharsets.UTF_8
            ).size.toLong() <=
                MAX_MANIFEST_BYTES
        ) {
            "observed weather snapshot manifest too large"
        }

        return PreparedSnapshot(
            productId =
                product.productId,
            sourceUrl =
                canonicalSourceUrl,
            captureDateUtc =
                captureDate,
            downloadedAtEpochMillis =
                downloadedAt,
            serverDateEpochMillis =
                serverDate,
            lastModifiedEpochMillis =
                lastModified,
            pitEvidenceAtEpochMillis =
                pitEvidenceAt,
            contentType =
                "text/csv",
            contentLength =
                rawBytes.size.toLong(),
            rawBytes =
                rawBytes,
            rawSha256 =
                rawSha,
            snapshotSha256 =
                snapshotSha,
            manifestText =
                manifest
        )
    }

    private fun requireNoNewlinesInValues(
        vararg values: String
    ) {
        for (value in values) {
            require(
                '\n' !in value &&
                    '\r' !in value
            ) {
                "observed weather manifest value contains newline"
            }
        }
    }

    private fun verifyExistingExact(
        finalDir: File,
        expected: PreparedSnapshot
    ): SaveResult {
        require(finalDir.isDirectory) {
            "observed weather snapshot path exists but is not a directory"
        }

        require(
            verifySnapshot(finalDir)
        ) {
            "existing observed weather snapshot failed verification"
        }

        val manifestFile =
            File(
                finalDir,
                MANIFEST_FILE_NAME
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
            existing["raw_sha256"] ==
                expected.rawSha256 &&
            existing[
                "downloaded_at_epoch_millis"
            ] ==
                expected
                    .downloadedAtEpochMillis
                    .toString() &&
            existing["source_url"] ==
                expected.sourceUrl &&
            existing["product_id"] ==
                expected.productId
        ) {
            "conflicting existing observed weather snapshot"
        }

        val existingPitEvidenceAt =
            existing[
                "pit_evidence_at_epoch_millis"
            ]
                ?.toLongOrNull()
                ?: error(
                    "invalid existing observed weather PIT evidence"
                )

        return SaveResult(
            status =
                SaveStatus.ALREADY_PRESENT,
            directory =
                finalDir,
            productId =
                expected.productId,
            rawSha256 =
                expected.rawSha256,
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
            "observed weather snapshot path escaped root"
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
            "refusing to overwrite observed weather snapshot file"
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
            "observed weather snapshot file too large"
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
                    "observed weather snapshot exceeded size limit"
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
                    "invalid observed weather snapshot manifest"
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
                "invalid observed weather manifest key"
            }

            require(
                '\n' !in value &&
                    '\r' !in value
            ) {
                "observed weather manifest value contains newline"
            }

            require(
                key !in result
            ) {
                "duplicate observed weather manifest key"
            }

            result[key] = value
        }

        return result
    }

    private fun computeSnapshotSha256(
        formatVersion: String,
        provider: String,
        role: String,
        productId: String,
        sourceUrl: String,
        captureDateUtc: String,
        downloadedAtEpochMillis: String,
        serverDateEpochMillis: String,
        lastModifiedEpochMillis: String,
        pitEvidenceAtEpochMillis: String,
        contentType: String,
        contentLength: String,
        rawSha256: String
    ): String {
        val canonical =
            buildString {
                append("format_version=")
                append(formatVersion)
                append('\n')

                append("provider=")
                append(provider)
                append('\n')

                append("role=")
                append(role)
                append('\n')

                append("product_id=")
                append(productId)
                append('\n')

                append("source_url=")
                append(sourceUrl)
                append('\n')

                append("capture_date_utc=")
                append(captureDateUtc)
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
                    "last_modified_epoch_millis="
                )
                append(
                    lastModifiedEpochMillis
                )
                append('\n')

                append(
                    "pit_evidence_at_epoch_millis="
                )
                append(
                    pitEvidenceAtEpochMillis
                )
                append('\n')

                append("content_type=")
                append(contentType)
                append('\n')

                append("content_length=")
                append(contentLength)
                append('\n')

                append("raw_sha256=")
                append(rawSha256)
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
