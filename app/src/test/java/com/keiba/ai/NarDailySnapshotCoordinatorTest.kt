package com.keiba.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class NarDailySnapshotCoordinatorTest {

    @Test
    fun capturesAndVerifiesDownloadedSnapshot() {
        val root =
            Files.createTempDirectory(
                "nar-coordinator-test"
            ).toFile()

        try {
            val result =
                NarDailySnapshotCoordinator
                    .captureDownloadedToRoot(
                        root = root,
                        data = sampleData()
                    )

            assertEquals(
                NarDailySnapshotStore
                    .SaveStatus.CREATED,
                result.status
            )

            assertEquals(
                "20260829",
                result.date
            )

            assertEquals(
                "20260829_1788001087_race.zip",
                result.sourceFileName
            )

            assertEquals(
                1_788_001_087L,
                result.sourceTimestampEpochSecond
            )

            assertEquals(
                1_788_001_120_000L,
                result.pitEvidenceAtEpochMillis
            )

            assertTrue(
                result.snapshotDirectory
                    .isDirectory
            )

            assertTrue(
                NarDailySnapshotStore
                    .verifySnapshot(
                        result.snapshotDirectory
                    )
            )

            assertFalse(
                result.snapshotDirectory
                    .resolve(
                        "payback.csv"
                    )
                    .exists()
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun repeatedSameSourceReturnsAlreadyPresent() {
        val root =
            Files.createTempDirectory(
                "nar-coordinator-repeat"
            ).toFile()

        try {
            val first =
                NarDailySnapshotCoordinator
                    .captureDownloadedToRoot(
                        root = root,
                        data = sampleData()
                    )

            val second =
                NarDailySnapshotCoordinator
                    .captureDownloadedToRoot(
                        root = root,
                        data = sampleData()
                            .copy(
                                downloadedAtEpochMillis =
                                    1_788_001_500_000L
                            )
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
                first.pitEvidenceAtEpochMillis,
                second.pitEvidenceAtEpochMillis
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun tamperedExistingSnapshotIsRejected() {
        val root =
            Files.createTempDirectory(
                "nar-coordinator-tamper"
            ).toFile()

        try {
            val first =
                NarDailySnapshotCoordinator
                    .captureDownloadedToRoot(
                        root = root,
                        data = sampleData()
                    )

            first.snapshotDirectory
                .resolve(
                    "horselist.csv"
                )
                .appendText(
                    "tampered"
                )

            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarDailySnapshotCoordinator
                    .captureDownloadedToRoot(
                        root = root,
                        data = sampleData()
                    )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun missingProvenanceIsRejectedBeforeUse() {
        val root =
            Files.createTempDirectory(
                "nar-coordinator-provenance"
            ).toFile()

        try {
            assertThrows(
                IllegalStateException::class.java
            ) {
                NarDailySnapshotCoordinator
                    .captureDownloadedToRoot(
                        root = root,
                        data = sampleData()
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
                "結果,払戻\n1,100\n",
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
