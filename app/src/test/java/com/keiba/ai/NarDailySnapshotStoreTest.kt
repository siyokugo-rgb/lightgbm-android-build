package com.keiba.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class NarDailySnapshotStoreTest {

    @Test
    fun savesAndVerifiesSnapshot() {
        val root =
            Files.createTempDirectory(
                "nar-snapshot-test"
            ).toFile()

        try {
            val result =
                NarDailySnapshotStore
                    .saveToRoot(
                        root,
                        sampleData()
                    )

            assertEquals(
                NarDailySnapshotStore
                    .SaveStatus.CREATED,
                result.status
            )

            assertTrue(
                result.directory
                    .isDirectory
            )

            assertTrue(
                result.directory
                    .resolve(
                        "racelist.csv"
                    )
                    .isFile
            )

            assertTrue(
                result.directory
                    .resolve(
                        "horselist.csv"
                    )
                    .isFile
            )

            assertTrue(
                result.directory
                    .resolve(
                        "manifest.txt"
                    )
                    .isFile
            )

            assertFalse(
                result.directory
                    .resolve(
                        "payback.csv"
                    )
                    .exists()
            )

            assertTrue(
                NarDailySnapshotStore
                    .verifySnapshot(
                        result.directory
                    )
            )

            assertEquals(
                1_788_001_120_000L,
                result
                    .pitEvidenceAtEpochMillis
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun paybackIsNeverPersisted() {
        val root =
            Files.createTempDirectory(
                "nar-snapshot-no-payback"
            ).toFile()

        try {
            val data =
                sampleData()
                    .copy(
                        paybackCsv =
                            "result,secret\n1,999\n"
                    )

            val saved =
                NarDailySnapshotStore
                    .saveToRoot(
                        root,
                        data
                    )

            assertFalse(
                saved.directory
                    .resolve(
                        "payback.csv"
                    )
                    .exists()
            )

            assertTrue(
                NarDailySnapshotStore
                    .verifySnapshot(
                        saved.directory
                    )
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun sameSourceAndContentIsIdempotent() {
        val root =
            Files.createTempDirectory(
                "nar-snapshot-idempotent"
            ).toFile()

        try {
            val first =
                NarDailySnapshotStore
                    .saveToRoot(
                        root,
                        sampleData()
                    )

            val laterDownload =
                sampleData()
                    .copy(
                        downloadedAtEpochMillis =
                            1_788_001_999_000L
                    )

            val second =
                NarDailySnapshotStore
                    .saveToRoot(
                        root,
                        laterDownload
                    )

            assertEquals(
                NarDailySnapshotStore
                    .SaveStatus.CREATED,
                first.status
            )

            assertEquals(
                NarDailySnapshotStore
                    .SaveStatus.ALREADY_PRESENT,
                second.status
            )

            assertEquals(
                first.snapshotSha256,
                second.snapshotSha256
            )

            assertEquals(
                first
                    .pitEvidenceAtEpochMillis,
                second
                    .pitEvidenceAtEpochMillis
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun conflictingSameSourceIsRejected() {
        val root =
            Files.createTempDirectory(
                "nar-snapshot-conflict"
            ).toFile()

        try {
            NarDailySnapshotStore
                .saveToRoot(
                    root,
                    sampleData()
                )

            val conflicting =
                sampleData()
                    .copy(
                        horselistCsv =
                            "changed"
                    )

            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarDailySnapshotStore
                    .saveToRoot(
                        root,
                        conflicting
                    )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun tamperingIsDetected() {
        val root =
            Files.createTempDirectory(
                "nar-snapshot-tamper"
            ).toFile()

        try {
            val saved =
                NarDailySnapshotStore
                    .saveToRoot(
                        root,
                        sampleData()
                    )

            assertTrue(
                NarDailySnapshotStore
                    .verifySnapshot(
                        saved.directory
                    )
            )

            saved.directory
                .resolve(
                    "horselist.csv"
                )
                .appendText(
                    "tampered"
                )

            assertFalse(
                NarDailySnapshotStore
                    .verifySnapshot(
                        saved.directory
                    )
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun resultFileInjectionIsDetected() {
        val root =
            Files.createTempDirectory(
                "nar-snapshot-result-file"
            ).toFile()

        try {
            val saved =
                NarDailySnapshotStore
                    .saveToRoot(
                        root,
                        sampleData()
                    )

            saved.directory
                .resolve(
                    "payback.csv"
                )
                .writeText(
                    "result"
                )

            assertFalse(
                NarDailySnapshotStore
                    .verifySnapshot(
                        saved.directory
                    )
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun missingProvenanceIsRejected() {
        val root =
            Files.createTempDirectory(
                "nar-snapshot-provenance"
            ).toFile()

        try {
            assertThrows(
                IllegalStateException::class.java
            ) {
                NarDailySnapshotStore
                    .saveToRoot(
                        root,
                        sampleData()
                            .copy(
                                sourceFileName =
                                    null
                            )
                    )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun filenameTimestampMismatchIsRejected() {
        val root =
            Files.createTempDirectory(
                "nar-snapshot-source"
            ).toFile()

        try {
            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarDailySnapshotStore
                    .saveToRoot(
                        root,
                        sampleData()
                            .copy(
                                sourceTimestampEpochSecond =
                                    1_788_001_088L
                            )
                    )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun sampleData():
        NarDailyRaceDownloader.DailyRaceData =
        NarDailyRaceDownloader.DailyRaceData(
            date =
                "20260829",
            racelistCsv =
                "競馬場,競走年月日,レース番号\n" +
                    "大井,20260829,1\n",
            horselistCsv =
                "競馬場,競走年月日,レース番号,馬番\n" +
                    "大井,20260829,1,1\n",
            paybackCsv =
                null,
            sourceFileName =
                "20260829_1788001087_race.zip",
            sourceTimestampEpochSecond =
                1_788_001_087L,
            downloadedAtEpochMillis =
                1_788_001_100_000L,
            serverDateEpochMillis =
                1_788_001_120_000L
        )
}
