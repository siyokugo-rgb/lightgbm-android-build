package com.keiba.ai

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

object NarDailySnapshotStore {

    private const val ROOT_NAME =
        "nar-daily-snapshots"

    private const val FORMAT_VERSION =
        "1"

    private const val MAX_SNAPSHOT_CSV_BYTES =
        8L * 1024L * 1024L

    private const val MAX_MANIFEST_BYTES =
        32L * 1024L

    private val sourceFileRegex =
        Regex(
            """^(\d{8})_(\d{10})_race\.zip$"""
        )

    enum class SaveStatus {
        CREATED,
        ALREADY_PRESENT
    }

    data class SaveResult(
        val status: SaveStatus,
        val directory: File,
        val snapshotSha256: String,
        val pitEvidenceAtEpochMillis: Long
    )

    fun save(
        context: Context,
        data: NarDailyRaceDownloader.DailyRaceData
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
        data: NarDailyRaceDownloader.DailyRaceData
    ): SaveResult {
        val validated =
            validateAndPrepare(data)

        require(
            root.exists() || root.mkdirs()
        ) {
            "could not create snapshot root"
        }

        require(root.isDirectory) {
            "snapshot root is not a directory"
        }

        val dayDir =
            File(
                root,
                validated.date
            )

        require(
            dayDir.exists() || dayDir.mkdirs()
        ) {
            "could not create snapshot day directory"
        }

        val finalDir =
            File(
                dayDir,
                validated.sourceTimestampEpochSecond
                    .toString()
            )

        requireChildPath(
            parent = root,
            child = finalDir
        )

        if (finalDir.exists()) {
            return verifyExistingSameSource(
                finalDir = finalDir,
                expected = validated
            )
        }

        val tempDir =
            File(
                dayDir,
                "." +
                    validated.sourceTimestampEpochSecond +
                    ".tmp-" +
                    UUID.randomUUID()
                        .toString()
            )

        requireChildPath(
            parent = root,
            child = tempDir
        )

        require(tempDir.mkdir()) {
            "could not create snapshot staging directory"
        }

        try {
            writeSynced(
                File(
                    tempDir,
                    "racelist.csv"
                ),
                validated.racelistBytes
            )

            writeSynced(
                File(
                    tempDir,
                    "horselist.csv"
                ),
                validated.horselistBytes
            )

            // paybackCsv is intentionally NOT persisted here.
            // This store is for pre-race/PIT prediction inputs only.

            writeSynced(
                File(
                    tempDir,
                    "manifest.txt"
                ),
                validated.manifestText
                    .toByteArray(
                        StandardCharsets.UTF_8
                    )
            )

            if (!tempDir.renameTo(finalDir)) {
                if (finalDir.exists()) {
                    return verifyExistingSameSource(
                        finalDir = finalDir,
                        expected = validated
                    )
                }

                error(
                    "could not atomically publish snapshot"
                )
            }

            return SaveResult(
                status = SaveStatus.CREATED,
                directory = finalDir,
                snapshotSha256 =
                    validated.snapshotSha256,
                pitEvidenceAtEpochMillis =
                    validated.pitEvidenceAtEpochMillis
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
            directory.canonicalFile

        val manifestFile =
            File(
                canonicalDirectory,
                "manifest.txt"
            )

        if (!isSafeRegularFile(
                canonicalDirectory,
                manifestFile,
                MAX_MANIFEST_BYTES
            )
        ) {
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
                "date",
                "source_file_name",
                "source_timestamp_epoch_second",
                "downloaded_at_epoch_millis",
                "server_date_epoch_millis",
                "pit_evidence_at_epoch_millis",
                "racelist_sha256",
                "horselist_sha256",
                "snapshot_sha256"
            )

        if (manifest.keys != requiredKeys) {
            return false
        }

        if (
            manifest["format_version"] !=
                FORMAT_VERSION
        ) {
            return false
        }

        val racelist =
            File(
                canonicalDirectory,
                "racelist.csv"
            )

        val horselist =
            File(
                canonicalDirectory,
                "horselist.csv"
            )

        if (!isSafeRegularFile(
                canonicalDirectory,
                racelist,
                MAX_SNAPSHOT_CSV_BYTES
            ) ||
            !isSafeRegularFile(
                canonicalDirectory,
                horselist,
                MAX_SNAPSHOT_CSV_BYTES
            )
        ) {
            return false
        }

        // Result data must never coexist in this pre-race snapshot.
        if (
            File(
                canonicalDirectory,
                "payback.csv"
            ).exists()
        ) {
            return false
        }

        val allowedNames =
            setOf(
                "racelist.csv",
                "horselist.csv",
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

        val raceHash =
            try {
                sha256FileHex(
                    racelist,
                    MAX_SNAPSHOT_CSV_BYTES
                )
            } catch (_: Exception) {
                return false
            }

        val horseHash =
            try {
                sha256FileHex(
                    horselist,
                    MAX_SNAPSHOT_CSV_BYTES
                )
            } catch (_: Exception) {
                return false
            }

        if (
            raceHash !=
                manifest["racelist_sha256"] ||
            horseHash !=
                manifest["horselist_sha256"]
        ) {
            return false
        }

        val snapshotHash =
            try {
                computeSnapshotSha256(
                    formatVersion =
                        manifest["format_version"]
                            ?: return false,
                    date =
                        manifest["date"]
                            ?: return false,
                    sourceFileName =
                        manifest["source_file_name"]
                            ?: return false,
                    sourceTimestampEpochSecond =
                        manifest[
                            "source_timestamp_epoch_second"
                        ] ?: return false,
                    downloadedAtEpochMillis =
                        manifest[
                            "downloaded_at_epoch_millis"
                        ] ?: return false,
                    serverDateEpochMillis =
                        manifest[
                            "server_date_epoch_millis"
                        ] ?: return false,
                    pitEvidenceAtEpochMillis =
                        manifest[
                            "pit_evidence_at_epoch_millis"
                        ] ?: return false,
                    racelistSha256 =
                        raceHash,
                    horselistSha256 =
                        horseHash
                )
            } catch (_: Exception) {
                return false
            }

        return snapshotHash ==
            manifest["snapshot_sha256"]
    }

    private data class PreparedSnapshot(
        val date: String,
        val sourceFileName: String,
        val sourceTimestampEpochSecond: Long,
        val pitEvidenceAtEpochMillis: Long,
        val racelistBytes: ByteArray,
        val horselistBytes: ByteArray,
        val racelistSha256: String,
        val horselistSha256: String,
        val snapshotSha256: String,
        val manifestText: String
    )

    private fun validateAndPrepare(
        data: NarDailyRaceDownloader.DailyRaceData
    ): PreparedSnapshot {
        require(
            data.date.matches(
                Regex("""^\d{8}$""")
            )
        ) {
            "invalid snapshot date"
        }

        val sourceFileName =
            data.sourceFileName
                ?: error(
                    "source filename missing"
                )

        val sourceTimestamp =
            data.sourceTimestampEpochSecond
                ?: error(
                    "source timestamp missing"
                )

        val downloadedAt =
            data.downloadedAtEpochMillis
                ?: error(
                    "downloaded-at missing"
                )

        require(downloadedAt > 0L) {
            "invalid downloaded-at"
        }

        val sourceMatch =
            sourceFileRegex
                .matchEntire(
                    sourceFileName
                )
                ?: error(
                    "invalid source filename"
                )

        require(
            sourceMatch.groupValues[1] ==
                data.date
        ) {
            "source filename/date mismatch"
        }

        require(
            sourceMatch.groupValues[2]
                .toLong() ==
                sourceTimestamp
        ) {
            "source filename/timestamp mismatch"
        }

        val serverDate =
            data.serverDateEpochMillis
                ?.also {
                    require(it > 0L) {
                        "invalid server date"
                    }
                }

        val sourceTimestampMillis =
            Math.multiplyExact(
                sourceTimestamp,
                1000L
            )

        val pitEvidenceAt =
            maxOf(
                downloadedAt,
                serverDate ?: 0L,
                sourceTimestampMillis
            )

        val racelistBytes =
            data.racelistCsv
                .toByteArray(
                    StandardCharsets.UTF_8
                )

        val horselistBytes =
            data.horselistCsv
                .toByteArray(
                    StandardCharsets.UTF_8
                )

        require(
            racelistBytes.size.toLong() <=
                MAX_SNAPSHOT_CSV_BYTES
        ) {
            "racelist snapshot too large"
        }

        require(
            horselistBytes.size.toLong() <=
                MAX_SNAPSHOT_CSV_BYTES
        ) {
            "horselist snapshot too large"
        }

        val racelistSha =
            sha256Hex(
                racelistBytes
            )

        val horselistSha =
            sha256Hex(
                horselistBytes
            )

        val serverDateText =
            serverDate
                ?.toString()
                ?: ""

        val snapshotSha =
            computeSnapshotSha256(
                formatVersion =
                    FORMAT_VERSION,
                date =
                    data.date,
                sourceFileName =
                    sourceFileName,
                sourceTimestampEpochSecond =
                    sourceTimestamp
                        .toString(),
                downloadedAtEpochMillis =
                    downloadedAt
                        .toString(),
                serverDateEpochMillis =
                    serverDateText,
                pitEvidenceAtEpochMillis =
                    pitEvidenceAt
                        .toString(),
                racelistSha256 =
                    racelistSha,
                horselistSha256 =
                    horselistSha
            )

        val manifest =
            buildString {
                append(
                    "format_version="
                )
                append(FORMAT_VERSION)
                append('\n')

                append("date=")
                append(data.date)
                append('\n')

                append(
                    "source_file_name="
                )
                append(sourceFileName)
                append('\n')

                append(
                    "source_timestamp_epoch_second="
                )
                append(sourceTimestamp)
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

                append(
                    "racelist_sha256="
                )
                append(racelistSha)
                append('\n')

                append(
                    "horselist_sha256="
                )
                append(horselistSha)
                append('\n')

                append(
                    "snapshot_sha256="
                )
                append(snapshotSha)
                append('\n')
            }

        require(
            manifest.toByteArray(
                StandardCharsets.UTF_8
            ).size.toLong() <=
                MAX_MANIFEST_BYTES
        ) {
            "snapshot manifest too large"
        }

        return PreparedSnapshot(
            date =
                data.date,
            sourceFileName =
                sourceFileName,
            sourceTimestampEpochSecond =
                sourceTimestamp,
            pitEvidenceAtEpochMillis =
                pitEvidenceAt,
            racelistBytes =
                racelistBytes,
            horselistBytes =
                horselistBytes,
            racelistSha256 =
                racelistSha,
            horselistSha256 =
                horselistSha,
            snapshotSha256 =
                snapshotSha,
            manifestText =
                manifest
        )
    }

    private fun verifyExistingSameSource(
        finalDir: File,
        expected: PreparedSnapshot
    ): SaveResult {
        require(finalDir.isDirectory) {
            "snapshot path exists but is not a directory"
        }

        require(
            verifySnapshot(finalDir)
        ) {
            "existing snapshot failed integrity verification"
        }

        val manifestFile =
            File(
                finalDir,
                "manifest.txt"
            )

        require(
            manifestFile.length() <=
                MAX_MANIFEST_BYTES
        ) {
            "existing manifest too large"
        }

        val existing =
            parseManifest(
                manifestFile.readText(
                    Charsets.UTF_8
                )
            )

        require(
            existing["date"] ==
                expected.date &&
                existing["source_file_name"] ==
                    expected.sourceFileName &&
                existing[
                    "source_timestamp_epoch_second"
                ] ==
                    expected
                        .sourceTimestampEpochSecond
                        .toString() &&
                existing["racelist_sha256"] ==
                    expected.racelistSha256 &&
                existing["horselist_sha256"] ==
                    expected.horselistSha256
        ) {
            "conflicting snapshot for same NAR source timestamp"
        }

        val existingSnapshotSha =
            existing["snapshot_sha256"]
                ?: error(
                    "snapshot hash missing"
                )

        val existingPitEvidenceAt =
            existing[
                "pit_evidence_at_epoch_millis"
            ]
                ?.toLongOrNull()
                ?: error(
                    "invalid existing PIT evidence time"
                )

        return SaveResult(
            status =
                SaveStatus.ALREADY_PRESENT,
            directory =
                finalDir,
            snapshotSha256 =
                existingSnapshotSha,
            pitEvidenceAtEpochMillis =
                existingPitEvidenceAt
        )
    }

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
            "snapshot path escaped root"
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
            parent.canonicalFile

        val canonicalFile =
            file.canonicalFile

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
            "refusing to overwrite snapshot file"
        }

        FileOutputStream(file).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
    }

    private fun parseManifest(
        text: String
    ): Map<String, String> {
        val result =
            linkedMapOf<String, String>()

        val lines =
            text.split('\n')

        for (line in lines) {
            if (line.isEmpty()) {
                continue
            }

            val index =
                line.indexOf('=')

            if (index <= 0) {
                error(
                    "invalid snapshot manifest"
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
                "invalid manifest key"
            }

            require(
                key !in result
            ) {
                "duplicate manifest key"
            }

            result[key] = value
        }

        return result
    }

    private fun computeSnapshotSha256(
        formatVersion: String,
        date: String,
        sourceFileName: String,
        sourceTimestampEpochSecond: String,
        downloadedAtEpochMillis: String,
        serverDateEpochMillis: String,
        pitEvidenceAtEpochMillis: String,
        racelistSha256: String,
        horselistSha256: String
    ): String {
        val canonical =
            buildString {
                append("format_version=")
                append(formatVersion)
                append('\n')
                append("date=")
                append(date)
                append('\n')
                append("source_file_name=")
                append(sourceFileName)
                append('\n')
                append(
                    "source_timestamp_epoch_second="
                )
                append(
                    sourceTimestampEpochSecond
                )
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
                append(
                    "racelist_sha256="
                )
                append(racelistSha256)
                append('\n')
                append(
                    "horselist_sha256="
                )
                append(horselistSha256)
                append('\n')
            }

        return sha256Hex(
            canonical.toByteArray(
                StandardCharsets.UTF_8
            )
        )
    }

    private fun sha256FileHex(
        file: File,
        maxBytes: Long
    ): String {
        require(
            file.length() <= maxBytes
        ) {
            "snapshot file too large"
        }

        val digest =
            MessageDigest.getInstance(
                "SHA-256"
            )

        var total = 0L
        val buffer = ByteArray(8192)

        FileInputStream(file).use { input ->
            while (true) {
                val count =
                    input.read(buffer)

                if (count < 0) {
                    break
                }

                total += count.toLong()

                require(total <= maxBytes) {
                    "snapshot file exceeded size limit"
                }

                digest.update(
                    buffer,
                    0,
                    count
                )
            }
        }

        return digestToHex(
            digest.digest()
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

        return digestToHex(digest)
    }

    private fun digestToHex(
        digest: ByteArray
    ): String =
        buildString(
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
