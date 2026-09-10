package com.keiba.ai

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

object NarOddsSnapshotStore {

    private const val ROOT_NAME =
        "nar-odds-snapshots"

    private const val FORMAT_VERSION =
        "1"

    private const val SOURCE_IDENTIFIER =
        "nar-keiba-go-jp-odds-tanfuku"

    private const val COVERED_BET_TYPES =
        "WIN,PLACE"

    private const val MAX_MANIFEST_BYTES =
        32L * 1024L

    enum class SaveStatus {
        CREATED,
        ALREADY_PRESENT
    }

    data class SaveResult(
        val status: SaveStatus,
        val directory: File,
        val rawResponseSha256: String,
        val snapshotSha256: String,
        val observedAtEpochMillis: Long
    )

    fun save(
        context: Context,
        data: NarOddsDownloader.OddsResponse
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
        data: NarOddsDownloader.OddsResponse
    ): SaveResult {
        val prepared =
            validateAndPrepare(
                data
            )

        require(
            root.exists() || root.mkdirs()
        ) {
            "could not create odds snapshot root"
        }

        require(
            root.isDirectory
        ) {
            "odds snapshot root is not a directory"
        }

        val raceDateDir =
            File(
                root,
                prepared.raceDatePath
            )

        requireChildPath(
            parent = root,
            child = raceDateDir
        )

        require(
            raceDateDir.exists() ||
                raceDateDir.mkdir()
        ) {
            "could not create odds race-date directory"
        }

        require(
            raceDateDir.isDirectory
        ) {
            "odds race-date path is not a directory"
        }

        val babaDir =
            File(
                raceDateDir,
                prepared.babaCode
            )

        requireChildPath(
            parent = root,
            child = babaDir
        )

        require(
            babaDir.exists() ||
                babaDir.mkdir()
        ) {
            "could not create odds baba directory"
        }

        require(
            babaDir.isDirectory
        ) {
            "odds baba path is not a directory"
        }

        val raceDir =
            File(
                babaDir,
                prepared.raceNoPath
            )

        requireChildPath(
            parent = root,
            child = raceDir
        )

        require(
            raceDir.exists() ||
                raceDir.mkdir()
        ) {
            "could not create odds race directory"
        }

        require(
            raceDir.isDirectory
        ) {
            "odds race path is not a directory"
        }

        val finalDir =
            File(
                raceDir,
                prepared.observedAtEpochMillis
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
                raceDir,
                "." +
                    prepared.observedAtEpochMillis +
                    ".tmp-" +
                    UUID.randomUUID()
                        .toString()
            )

        requireChildPath(
            parent = root,
            child = tempDir
        )

        require(
            tempDir.mkdir()
        ) {
            "could not create odds snapshot staging directory"
        }

        try {
            writeSynced(
                File(
                    tempDir,
                    "odds.html"
                ),
                prepared.rawResponseBytes
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
                    "could not publish odds snapshot"
                )
            }

            require(
                verifySnapshot(
                    finalDir
                )
            ) {
                "published odds snapshot failed verification"
            }

            return SaveResult(
                status =
                    SaveStatus.CREATED,
                directory =
                    finalDir,
                rawResponseSha256 =
                    prepared.rawResponseSha256,
                snapshotSha256 =
                    prepared.snapshotSha256,
                observedAtEpochMillis =
                    prepared.observedAtEpochMillis
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
                "odds.html"
            )

        val manifestFile =
            File(
                canonicalDirectory,
                "manifest.txt"
            )

        if (
            !isSafeRegularFile(
                canonicalDirectory,
                rawFile,
                NarOddsDownloader
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
                "odds.html",
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
                "source_identifier",
                "race_date",
                "baba_code",
                "race_no",
                "covered_bet_types",
                "request_url",
                "download_started_at_epoch_millis",
                "download_completed_at_epoch_millis",
                "server_date_epoch_millis",
                "observed_at_epoch_millis",
                "raw_response_sha256",
                "snapshot_sha256"
            )

        if (manifest.keys != requiredKeys) {
            return false
        }

        if (
            manifest["format_version"] !=
                FORMAT_VERSION ||
            manifest["source_identifier"] !=
                SOURCE_IDENTIFIER ||
            manifest["covered_bet_types"] !=
                COVERED_BET_TYPES
        ) {
            return false
        }

        val raceDateText =
            manifest["race_date"]
                ?: return false

        val raceDate =
            try {
                LocalDate.parse(
                    raceDateText,
                    DateTimeFormatter.ISO_LOCAL_DATE
                )
            } catch (_: Exception) {
                return false
            }

        val babaCode =
            manifest["baba_code"]
                ?: return false

        if (
            !babaCode.matches(
                Regex("""^[0-9]{2}$""")
            )
        ) {
            return false
        }

        val raceNo =
            manifest["race_no"]
                ?.toIntOrNull()
                ?: return false

        if (raceNo !in 1..99) {
            return false
        }

        val canonicalRequestUrl =
            try {
                NarOddsDownloader
                    .buildRequestUrl(
                        babaCode = babaCode,
                        raceDate = raceDate,
                        raceNo = raceNo
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

        val startedAt =
            manifest[
                "download_started_at_epoch_millis"
            ]
                ?.toLongOrNull()
                ?: return false

        val completedAt =
            manifest[
                "download_completed_at_epoch_millis"
            ]
                ?.toLongOrNull()
                ?: return false

        if (
            startedAt <= 0L ||
            completedAt < startedAt
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
                    ?.takeIf {
                        it > 0L
                    }
                    ?: return false
            }

        val observedAt =
            manifest[
                "observed_at_epoch_millis"
            ]
                ?.toLongOrNull()
                ?: return false

        val expectedObservedAt =
            maxOf(
                completedAt,
                serverDate ?: 0L
            )

        if (
            observedAt !=
                expectedObservedAt
        ) {
            return false
        }

        val rawBytes =
            try {
                readFileLimited(
                    rawFile,
                    NarOddsDownloader
                        .MAX_RESPONSE_BYTES
                )
            } catch (_: Exception) {
                return false
            }

        try {
            NarOddsDownloader
                .validateResponseBytes(
                    rawBytes
                )
        } catch (_: Exception) {
            return false
        }

        val rawSha =
            sha256Hex(
                rawBytes
            )

        if (
            rawSha !=
                manifest[
                    "raw_response_sha256"
                ]
        ) {
            return false
        }

        val snapshotSha =
            try {
                computeSnapshotSha256(
                    formatVersion =
                        manifest["format_version"]
                            ?: return false,
                    sourceIdentifier =
                        manifest["source_identifier"]
                            ?: return false,
                    raceDate =
                        raceDateText,
                    babaCode =
                        babaCode,
                    raceNo =
                        raceNo.toString(),
                    coveredBetTypes =
                        manifest["covered_bet_types"]
                            ?: return false,
                    requestUrl =
                        canonicalRequestUrl,
                    downloadStartedAtEpochMillis =
                        startedAt.toString(),
                    downloadCompletedAtEpochMillis =
                        completedAt.toString(),
                    serverDateEpochMillis =
                        serverDateText,
                    observedAtEpochMillis =
                        observedAt.toString(),
                    rawResponseSha256 =
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
                observedAt.toString() +
                "-" +
                snapshotSha
        ) {
            return false
        }

        val raceDir =
            canonicalDirectory.parentFile
                ?: return false

        if (
            raceDir.name !=
                raceNoPath(
                    raceNo
                )
        ) {
            return false
        }

        val babaDir =
            raceDir.parentFile
                ?: return false

        if (
            babaDir.name !=
                babaCode
        ) {
            return false
        }

        val raceDateDir =
            babaDir.parentFile
                ?: return false

        if (
            raceDateDir.name !=
                raceDatePath(
                    raceDate
                )
        ) {
            return false
        }

        return true
    }

    private data class PreparedSnapshot(
        val raceDateText: String,
        val raceDatePath: String,
        val babaCode: String,
        val raceNo: Int,
        val raceNoPath: String,
        val requestUrl: String,
        val downloadStartedAtEpochMillis: Long,
        val downloadCompletedAtEpochMillis: Long,
        val serverDateEpochMillis: Long?,
        val observedAtEpochMillis: Long,
        val rawResponseBytes: ByteArray,
        val rawResponseSha256: String,
        val snapshotSha256: String,
        val manifestText: String
    )

    private fun validateAndPrepare(
        data: NarOddsDownloader.OddsResponse
    ): PreparedSnapshot {
        val canonicalRequestUrl =
            NarOddsDownloader
                .buildRequestUrl(
                    babaCode =
                        data.babaCode,
                    raceDate =
                        data.raceDate,
                    raceNo =
                        data.raceNo
                )

        require(
            data.requestUrl ==
                canonicalRequestUrl
        ) {
            "odds request URL/provenance mismatch"
        }

        require(
            data.coveredBetTypes ==
                setOf(
                    NarOddsDownloader
                        .BetType.WIN,
                    NarOddsDownloader
                        .BetType.PLACE
                )
        ) {
            "unexpected odds covered bet types"
        }

        val startedAt =
            data.downloadStartedAtEpochMillis

        val completedAt =
            data.downloadCompletedAtEpochMillis

        require(
            startedAt > 0L
        ) {
            "invalid odds download-started-at"
        }

        require(
            completedAt >=
                startedAt
        ) {
            "invalid odds download timestamp order"
        }

        val serverDate =
            data.serverDateEpochMillis
                ?.also {
                    require(
                        it > 0L
                    ) {
                        "invalid odds server date"
                    }
                }

        val expectedObservedAt =
            maxOf(
                completedAt,
                serverDate ?: 0L
            )

        require(
            data.observedAtEpochMillis ==
                expectedObservedAt
        ) {
            "odds observed-at/provenance mismatch"
        }

        val rawBytes =
            data.responseBytes
                .copyOf()

        NarOddsDownloader
            .validateResponseBytes(
                rawBytes
            )

        val rawSha =
            sha256Hex(
                rawBytes
            )

        val raceDateText =
            data.raceDate
                .format(
                    DateTimeFormatter
                        .ISO_LOCAL_DATE
                )

        val raceDatePath =
            raceDatePath(
                data.raceDate
            )

        val raceNoPath =
            raceNoPath(
                data.raceNo
            )

        val serverDateText =
            serverDate
                ?.toString()
                ?: ""

        val snapshotSha =
            computeSnapshotSha256(
                formatVersion =
                    FORMAT_VERSION,
                sourceIdentifier =
                    SOURCE_IDENTIFIER,
                raceDate =
                    raceDateText,
                babaCode =
                    data.babaCode,
                raceNo =
                    data.raceNo
                        .toString(),
                coveredBetTypes =
                    COVERED_BET_TYPES,
                requestUrl =
                    canonicalRequestUrl,
                downloadStartedAtEpochMillis =
                    startedAt.toString(),
                downloadCompletedAtEpochMillis =
                    completedAt.toString(),
                serverDateEpochMillis =
                    serverDateText,
                observedAtEpochMillis =
                    expectedObservedAt
                        .toString(),
                rawResponseSha256 =
                    rawSha
            )

        val manifest =
            buildString {
                append("format_version=")
                append(FORMAT_VERSION)
                append('\n')

                append("source_identifier=")
                append(SOURCE_IDENTIFIER)
                append('\n')

                append("race_date=")
                append(raceDateText)
                append('\n')

                append("baba_code=")
                append(data.babaCode)
                append('\n')

                append("race_no=")
                append(data.raceNo)
                append('\n')

                append("covered_bet_types=")
                append(COVERED_BET_TYPES)
                append('\n')

                append("request_url=")
                append(canonicalRequestUrl)
                append('\n')

                append(
                    "download_started_at_epoch_millis="
                )
                append(startedAt)
                append('\n')

                append(
                    "download_completed_at_epoch_millis="
                )
                append(completedAt)
                append('\n')

                append(
                    "server_date_epoch_millis="
                )
                append(serverDateText)
                append('\n')

                append(
                    "observed_at_epoch_millis="
                )
                append(expectedObservedAt)
                append('\n')

                append("raw_response_sha256=")
                append(rawSha)
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
            "odds snapshot manifest too large"
        }

        return PreparedSnapshot(
            raceDateText =
                raceDateText,
            raceDatePath =
                raceDatePath,
            babaCode =
                data.babaCode,
            raceNo =
                data.raceNo,
            raceNoPath =
                raceNoPath,
            requestUrl =
                canonicalRequestUrl,
            downloadStartedAtEpochMillis =
                startedAt,
            downloadCompletedAtEpochMillis =
                completedAt,
            serverDateEpochMillis =
                serverDate,
            observedAtEpochMillis =
                expectedObservedAt,
            rawResponseBytes =
                rawBytes,
            rawResponseSha256 =
                rawSha,
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
        require(
            finalDir.isDirectory
        ) {
            "odds snapshot path exists but is not a directory"
        }

        require(
            verifySnapshot(
                finalDir
            )
        ) {
            "existing odds snapshot failed verification"
        }

        val manifest =
            parseManifest(
                finalDir
                    .resolve(
                        "manifest.txt"
                    )
                    .readText(
                        Charsets.UTF_8
                    )
            )

        require(
            manifest["snapshot_sha256"] ==
                expected.snapshotSha256 &&
            manifest["raw_response_sha256"] ==
                expected.rawResponseSha256 &&
            manifest["request_url"] ==
                expected.requestUrl &&
            manifest[
                "observed_at_epoch_millis"
            ] ==
                expected.observedAtEpochMillis
                    .toString()
        ) {
            "conflicting existing odds snapshot"
        }

        return SaveResult(
            status =
                SaveStatus.ALREADY_PRESENT,
            directory =
                finalDir,
            rawResponseSha256 =
                expected.rawResponseSha256,
            snapshotSha256 =
                expected.snapshotSha256,
            observedAtEpochMillis =
                expected.observedAtEpochMillis
        )
    }

    private fun raceDatePath(
        raceDate: LocalDate
    ): String =
        raceDate.format(
            DateTimeFormatter
                .BASIC_ISO_DATE
        )

    private fun raceNoPath(
        raceNo: Int
    ): String =
        String.format(
            Locale.ROOT,
            "%02d",
            raceNo
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
            "odds snapshot path escaped root"
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

        return canonicalFile.parentFile ==
            canonicalParent
    }

    private fun writeSynced(
        file: File,
        bytes: ByteArray
    ) {
        require(
            !file.exists()
        ) {
            "refusing to overwrite odds snapshot file"
        }

        FileOutputStream(
            file
        ).use { output ->
            output.write(
                bytes
            )
            output.flush()
            output.fd.sync()
        }
    }

    private fun readFileLimited(
        file: File,
        maxBytes: Long
    ): ByteArray {
        require(
            file.length() <=
                maxBytes
        ) {
            "odds snapshot file too large"
        }

        val output =
            ByteArrayOutputStream()

        val buffer =
            ByteArray(
                8192
            )

        var total =
            0L

        FileInputStream(
            file
        ).use { input ->
            while (true) {
                val count =
                    input.read(
                        buffer
                    )

                if (count < 0) {
                    break
                }

                total =
                    Math.addExact(
                        total,
                        count.toLong()
                    )

                require(
                    total <= maxBytes
                ) {
                    "odds snapshot exceeded size limit"
                }

                output.write(
                    buffer,
                    0,
                    count
                )
            }
        }

        return output
            .toByteArray()
    }

    private fun parseManifest(
        text: String
    ): Map<String, String> {
        val result =
            linkedMapOf<String, String>()

        for (
            line in text.split(
                '\n'
            )
        ) {
            if (line.isEmpty()) {
                continue
            }

            val index =
                line.indexOf(
                    '='
                )

            if (index <= 0) {
                error(
                    "invalid odds snapshot manifest"
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
                "invalid odds manifest key"
            }

            require(
                key !in result
            ) {
                "duplicate odds manifest key"
            }

            result[key] =
                value
        }

        return result
    }

    private fun computeSnapshotSha256(
        formatVersion: String,
        sourceIdentifier: String,
        raceDate: String,
        babaCode: String,
        raceNo: String,
        coveredBetTypes: String,
        requestUrl: String,
        downloadStartedAtEpochMillis: String,
        downloadCompletedAtEpochMillis: String,
        serverDateEpochMillis: String,
        observedAtEpochMillis: String,
        rawResponseSha256: String
    ): String {
        val canonical =
            buildString {
                append("format_version=")
                append(formatVersion)
                append('\n')

                append("source_identifier=")
                append(sourceIdentifier)
                append('\n')

                append("race_date=")
                append(raceDate)
                append('\n')

                append("baba_code=")
                append(babaCode)
                append('\n')

                append("race_no=")
                append(raceNo)
                append('\n')

                append("covered_bet_types=")
                append(coveredBetTypes)
                append('\n')

                append("request_url=")
                append(requestUrl)
                append('\n')

                append(
                    "download_started_at_epoch_millis="
                )
                append(
                    downloadStartedAtEpochMillis
                )
                append('\n')

                append(
                    "download_completed_at_epoch_millis="
                )
                append(
                    downloadCompletedAtEpochMillis
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
                    "observed_at_epoch_millis="
                )
                append(
                    observedAtEpochMillis
                )
                append('\n')

                append("raw_response_sha256=")
                append(rawResponseSha256)
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
                .digest(
                    bytes
                )

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
