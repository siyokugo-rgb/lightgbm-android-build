package com.keiba.ai

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object NarPitSnapshotSelector {

    private const val ROOT_NAME =
        "nar-daily-snapshots"

    private const val MAX_MANIFEST_BYTES =
        32L * 1024L

    private val dateRegex =
        Regex("""^\d{8}$""")

    private val basicDateFormatter =
        DateTimeFormatter.BASIC_ISO_DATE

    private val japanZone =
        ZoneId.of("Asia/Tokyo")

    private val snapshotDirectoryRegex =
        Regex("""^\d{10}$""")

    private val stagingDirectoryRegex =
        Regex(
            """^\.\d{10}\.tmp-""" +
                """[0-9a-f]{8}-""" +
                """[0-9a-f]{4}-""" +
                """[0-9a-f]{4}-""" +
                """[0-9a-f]{4}-""" +
                """[0-9a-f]{12}$"""
        )

    private val sourceFileRegex =
        Regex(
            """^(\d{8})_(\d{10})_race\.zip$"""
        )

    private val sha256Regex =
        Regex("""^[0-9a-f]{64}$""")

    private val requiredManifestKeys =
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

    data class Selection(
        val date: String,
        val sourceFileName: String,
        val sourceTimestampEpochSecond: Long,
        val pitEvidenceAtEpochMillis: Long,
        val predictionAsOfEpochMillis: Long,
        val ageMillis: Long,
        val snapshotSha256: String,
        val directory: File
    )

    fun selectLatest(
        context: Context,
        date: String,
        predictionAsOfEpochMillis: Long
    ): Selection? =
        selectLatestFromRoot(
            root = File(
                context.noBackupFilesDir,
                ROOT_NAME
            ),
            date = date,
            predictionAsOfEpochMillis =
                predictionAsOfEpochMillis
        )

    internal fun selectLatestFromRoot(
        root: File,
        date: String,
        predictionAsOfEpochMillis: Long
    ): Selection? {
        require(
            dateRegex.matches(date)
        ) {
            "invalid NAR snapshot date"
        }

        require(
            runCatching {
                LocalDate.parse(
                    date,
                    basicDateFormatter
                )
            }.isSuccess
        ) {
            "invalid NAR snapshot calendar date"
        }

        require(
            predictionAsOfEpochMillis > 0L
        ) {
            "invalid predictionAsOf"
        }

        if (!root.exists()) {
            return null
        }

        require(root.isDirectory) {
            "NAR snapshot root is not a directory"
        }

        val canonicalRoot =
            root.canonicalFile

        val dayDirectory =
            File(
                canonicalRoot,
                date
            ).canonicalFile

        require(
            dayDirectory.parentFile ==
                canonicalRoot
        ) {
            "NAR snapshot day path escaped root"
        }

        if (!dayDirectory.exists()) {
            return null
        }

        require(dayDirectory.isDirectory) {
            "NAR snapshot day path is not a directory"
        }

        val children =
            dayDirectory.listFiles()
                ?: error(
                    "could not list NAR snapshot day directory"
                )

        var best: Selection? = null

        for (child in children) {
            val name =
                child.name

            val canonicalChild =
                child.canonicalFile

            require(
                canonicalChild.parentFile ==
                    dayDirectory &&
                    canonicalChild.name ==
                    name
            ) {
                "NAR snapshot child escaped day directory"
            }

            if (
                stagingDirectoryRegex
                    .matches(name)
            ) {
                require(
                    canonicalChild.isDirectory
                ) {
                    "NAR staging path is not a directory"
                }

                continue
            }

            require(
                snapshotDirectoryRegex
                    .matches(name)
            ) {
                "unexpected entry in NAR snapshot day directory: $name"
            }

            require(
                canonicalChild.isDirectory
            ) {
                "NAR snapshot entry is not a directory: $name"
            }

            val manifestBefore =
                readManifestTextStrict(
                    canonicalChild
                )

            require(
                NarDailySnapshotStore
                    .verifySnapshot(
                        canonicalChild
                    )
            ) {
                "NAR snapshot integrity verification failed: $name"
            }

            val manifestAfter =
                readManifestTextStrict(
                    canonicalChild
                )

            require(
                manifestBefore ==
                    manifestAfter
            ) {
                "NAR snapshot manifest changed during verification"
            }

            val metadata =
                parseVerifiedMetadata(
                    directory = canonicalChild,
                    expectedDate = date,
                    manifestText = manifestAfter
                )

            if (
                metadata.pitEvidenceAtEpochMillis >
                    predictionAsOfEpochMillis
            ) {
                continue
            }

            val candidate =
                Selection(
                    date = metadata.date,
                    sourceFileName =
                        metadata.sourceFileName,
                    sourceTimestampEpochSecond =
                        metadata.sourceTimestampEpochSecond,
                    pitEvidenceAtEpochMillis =
                        metadata.pitEvidenceAtEpochMillis,
                    predictionAsOfEpochMillis =
                        predictionAsOfEpochMillis,
                    ageMillis =
                        Math.subtractExact(
                            predictionAsOfEpochMillis,
                            metadata
                                .pitEvidenceAtEpochMillis
                        ),
                    snapshotSha256 =
                        metadata.snapshotSha256,
                    directory =
                        canonicalChild
                )

            val currentBest =
                best

            if (
                currentBest == null ||
                candidate.pitEvidenceAtEpochMillis >
                    currentBest.pitEvidenceAtEpochMillis ||
                (
                    candidate.pitEvidenceAtEpochMillis ==
                        currentBest.pitEvidenceAtEpochMillis &&
                        candidate.sourceTimestampEpochSecond >
                            currentBest
                                .sourceTimestampEpochSecond
                    )
            ) {
                best = candidate
            }
        }

        return best
    }

    private data class VerifiedMetadata(
        val date: String,
        val sourceFileName: String,
        val sourceTimestampEpochSecond: Long,
        val pitEvidenceAtEpochMillis: Long,
        val snapshotSha256: String
    )

    private fun parseVerifiedMetadata(
        directory: File,
        expectedDate: String,
        manifestText: String
    ): VerifiedMetadata {
        val manifest =
            parseManifest(
                manifestText
            )

        require(
            manifest.keys ==
                requiredManifestKeys
        ) {
            "unexpected NAR snapshot manifest keys"
        }

        require(
            manifest["format_version"] ==
                "1"
        ) {
            "unsupported NAR snapshot format"
        }

        val manifestDate =
            manifest["date"]
                ?: error(
                    "NAR snapshot date missing"
                )

        require(
            manifestDate ==
                expectedDate
        ) {
            "NAR snapshot manifest/date mismatch"
        }

        val sourceFileName =
            manifest["source_file_name"]
                ?: error(
                    "NAR source filename missing"
                )

        val sourceMatch =
            sourceFileRegex
                .matchEntire(
                    sourceFileName
                )
                ?: error(
                    "invalid NAR source filename"
                )

        require(
            sourceMatch.groupValues[1] ==
                expectedDate
        ) {
            "NAR source filename/date mismatch"
        }

        val sourceTimestamp =
            manifest[
                "source_timestamp_epoch_second"
            ]
                ?.toLongOrNull()
                ?: error(
                    "invalid NAR source timestamp"
                )

        require(
            sourceTimestamp > 0L
        ) {
            "invalid NAR source timestamp"
        }

        val sourceTimestampDate =
            Instant.ofEpochSecond(
                sourceTimestamp
            )
                .atZone(japanZone)
                .toLocalDate()
                .format(
                    basicDateFormatter
                )

        require(
            sourceTimestampDate ==
                expectedDate
        ) {
            "NAR source timestamp/date mismatch"
        }

        require(
            sourceMatch.groupValues[2]
                .toLong() ==
                sourceTimestamp
        ) {
            "NAR source filename/timestamp mismatch"
        }

        require(
            directory.name.toLong() ==
                sourceTimestamp
        ) {
            "NAR snapshot directory/timestamp mismatch"
        }

        val downloadedAt =
            manifest[
                "downloaded_at_epoch_millis"
            ]
                ?.toLongOrNull()
                ?: error(
                    "invalid NAR downloaded-at"
                )

        require(downloadedAt > 0L) {
            "invalid NAR downloaded-at"
        }

        val serverDateText =
            manifest[
                "server_date_epoch_millis"
            ]
                ?: error(
                    "NAR server date missing"
                )

        val serverDate =
            if (serverDateText.isEmpty()) {
                null
            } else {
                serverDateText
                    .toLongOrNull()
                    ?: error(
                        "invalid NAR server date"
                    )
            }

        require(
            serverDate == null ||
                serverDate > 0L
        ) {
            "invalid NAR server date"
        }

        val pitEvidenceAt =
            manifest[
                "pit_evidence_at_epoch_millis"
            ]
                ?.toLongOrNull()
                ?: error(
                    "invalid NAR PIT evidence time"
                )

        require(
            pitEvidenceAt > 0L
        ) {
            "invalid NAR PIT evidence time"
        }

        val sourceTimestampMillis =
            Math.multiplyExact(
                sourceTimestamp,
                1000L
            )

        val expectedPitEvidenceAt =
            maxOf(
                downloadedAt,
                serverDate ?: 0L,
                sourceTimestampMillis
            )

        require(
            pitEvidenceAt ==
                expectedPitEvidenceAt
        ) {
            "NAR PIT evidence time is inconsistent"
        }

        val snapshotSha256 =
            manifest["snapshot_sha256"]
                ?: error(
                    "NAR snapshot SHA-256 missing"
                )

        require(
            sha256Regex.matches(
                snapshotSha256
            )
        ) {
            "invalid NAR snapshot SHA-256"
        }

        return VerifiedMetadata(
            date = manifestDate,
            sourceFileName =
                sourceFileName,
            sourceTimestampEpochSecond =
                sourceTimestamp,
            pitEvidenceAtEpochMillis =
                pitEvidenceAt,
            snapshotSha256 =
                snapshotSha256
        )
    }

    private fun readManifestTextStrict(
        directory: File
    ): String {
        val manifest =
            File(
                directory,
                "manifest.txt"
            )

        require(
            manifest.isFile
        ) {
            "NAR snapshot manifest missing"
        }

        require(
            manifest.canonicalFile.parentFile ==
                directory
        ) {
            "NAR snapshot manifest escaped directory"
        }

        val bytes =
            readLimitedBytes(
                file = manifest,
                maxBytes =
                    MAX_MANIFEST_BYTES
            )

        val decoder =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(
                    CodingErrorAction.REPORT
                )
                .onUnmappableCharacter(
                    CodingErrorAction.REPORT
                )

        return decoder.decode(
            ByteBuffer.wrap(bytes)
        ).toString()
    }

    private fun readLimitedBytes(
        file: File,
        maxBytes: Long
    ): ByteArray {
        require(
            maxBytes > 0L &&
                maxBytes <= Int.MAX_VALUE
        ) {
            "invalid read limit"
        }

        val output =
            ByteArrayOutputStream()

        val buffer =
            ByteArray(4096)

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

                require(
                    total <= maxBytes
                ) {
                    "NAR snapshot manifest too large"
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

            require(index > 0) {
                "invalid NAR snapshot manifest"
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
                "invalid NAR snapshot manifest key"
            }

            require(
                key !in result
            ) {
                "duplicate NAR snapshot manifest key"
            }

            result[key] = value
        }

        return result
    }
}
