package com.keiba.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class NarPitSnapshotSelectorTest {

    @Test
    fun selectsLatestSnapshotNotAfterPredictionAsOf() {
        val root =
            Files.createTempDirectory(
                "nar-selector-latest"
            ).toFile()

        try {
            saveSnapshot(
                root = root,
                sourceTimestamp = 1_788_001_087L,
                pitEvidenceAt = 1_788_001_100_000L,
                marker = "A"
            )

            val expected =
                saveSnapshot(
                    root = root,
                    sourceTimestamp = 1_788_001_187L,
                    pitEvidenceAt = 1_788_001_200_000L,
                    marker = "B"
                )

            saveSnapshot(
                root = root,
                sourceTimestamp = 1_788_001_287L,
                pitEvidenceAt = 1_788_001_300_000L,
                marker = "C"
            )

            val selected =
                NarPitSnapshotSelector
                    .selectLatestFromRoot(
                        root = root,
                        date = "20260829",
                        predictionAsOfEpochMillis =
                            1_788_001_250_000L
                    )
                    ?: error(
                        "expected snapshot"
                    )

            assertEquals(
                expected.directory
                    .canonicalFile,
                selected.directory
            )

            assertEquals(
                1_788_001_200_000L,
                selected
                    .pitEvidenceAtEpochMillis
            )

            assertEquals(
                50_000L,
                selected.ageMillis
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun predictionAsOfBoundaryIsInclusive() {
        val root =
            Files.createTempDirectory(
                "nar-selector-boundary"
            ).toFile()

        try {
            val saved =
                saveSnapshot(
                    root = root,
                    sourceTimestamp =
                        1_788_001_087L,
                    pitEvidenceAt =
                        1_788_001_100_000L,
                    marker = "A"
                )

            val selected =
                NarPitSnapshotSelector
                    .selectLatestFromRoot(
                        root = root,
                        date = "20260829",
                        predictionAsOfEpochMillis =
                            1_788_001_100_000L
                    )
                    ?: error(
                        "expected boundary snapshot"
                    )

            assertEquals(
                saved.directory
                    .canonicalFile,
                selected.directory
            )

            assertEquals(
                0L,
                selected.ageMillis
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun futureSnapshotIsNeverSelected() {
        val root =
            Files.createTempDirectory(
                "nar-selector-future"
            ).toFile()

        try {
            saveSnapshot(
                root = root,
                sourceTimestamp =
                    1_788_001_087L,
                pitEvidenceAt =
                    1_788_001_200_000L,
                marker = "future"
            )

            val selected =
                NarPitSnapshotSelector
                    .selectLatestFromRoot(
                        root = root,
                        date = "20260829",
                        predictionAsOfEpochMillis =
                            1_788_001_199_999L
                    )

            assertNull(selected)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun missingRootReturnsNoSnapshot() {
        val parent =
            Files.createTempDirectory(
                "nar-selector-missing"
            ).toFile()

        val root =
            parent.resolve(
                "does-not-exist"
            )

        try {
            val selected =
                NarPitSnapshotSelector
                    .selectLatestFromRoot(
                        root = root,
                        date = "20260829",
                        predictionAsOfEpochMillis =
                            1_788_001_300_000L
                    )

            assertNull(selected)
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun corruptedPublishedSnapshotFailsClosedEvenIfFuture() {
        val root =
            Files.createTempDirectory(
                "nar-selector-corrupt"
            ).toFile()

        try {
            saveSnapshot(
                root = root,
                sourceTimestamp =
                    1_788_001_087L,
                pitEvidenceAt =
                    1_788_001_100_000L,
                marker = "eligible"
            )

            val future =
                saveSnapshot(
                    root = root,
                    sourceTimestamp =
                        1_788_001_287L,
                    pitEvidenceAt =
                        1_788_001_300_000L,
                    marker = "future"
                )

            future.directory
                .resolve(
                    "horselist.csv"
                )
                .appendText(
                    "tampered"
                )

            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarPitSnapshotSelector
                    .selectLatestFromRoot(
                        root = root,
                        date = "20260829",
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun staleStagingDirectoryIsIgnored() {
        val root =
            Files.createTempDirectory(
                "nar-selector-staging"
            ).toFile()

        try {
            val saved =
                saveSnapshot(
                    root = root,
                    sourceTimestamp =
                        1_788_001_087L,
                    pitEvidenceAt =
                        1_788_001_100_000L,
                    marker = "A"
                )

            val day =
                root.resolve(
                    "20260829"
                )

            assertTrue(
                day.resolve(
                    ".1788001087.tmp-" +
                        "123e4567-e89b-12d3-a456-426614174000"
                ).mkdir()
            )

            val selected =
                NarPitSnapshotSelector
                    .selectLatestFromRoot(
                        root = root,
                        date = "20260829",
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )
                    ?: error(
                        "expected snapshot"
                    )

            assertEquals(
                saved.directory
                    .canonicalFile,
                selected.directory
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun unexpectedDayDirectoryEntryFailsClosed() {
        val root =
            Files.createTempDirectory(
                "nar-selector-unexpected"
            ).toFile()

        try {
            saveSnapshot(
                root = root,
                sourceTimestamp =
                    1_788_001_087L,
                pitEvidenceAt =
                    1_788_001_100_000L,
                marker = "A"
            )

            assertTrue(
                root.resolve(
                    "20260829"
                ).resolve(
                    "unexpected"
                ).mkdir()
            )

            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarPitSnapshotSelector
                    .selectLatestFromRoot(
                        root = root,
                        date = "20260829",
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun directoryTimestampMismatchFailsClosed() {
        val root =
            Files.createTempDirectory(
                "nar-selector-dir-mismatch"
            ).toFile()

        try {
            val saved =
                saveSnapshot(
                    root = root,
                    sourceTimestamp =
                        1_788_001_087L,
                    pitEvidenceAt =
                        1_788_001_100_000L,
                    marker = "A"
                )

            val parent =
                saved.directory
                    .parentFile
                    ?: error(
                        "snapshot parent directory missing"
                    )

            val renamed =
                parent.resolve(
                    "1788001088"
                )

            assertTrue(
                saved.directory
                    .renameTo(
                        renamed
                    )
            )

            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarPitSnapshotSelector
                    .selectLatestFromRoot(
                        root = root,
                        date = "20260829",
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun invalidCalendarDateIsRejected() {
        val root =
            Files.createTempDirectory(
                "nar-selector-calendar-date"
            ).toFile()

        try {
            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarPitSnapshotSelector
                    .selectLatestFromRoot(
                        root = root,
                        date = "20260230",
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun sourceTimestampCalendarDateMismatchFailsClosed() {
        val root =
            Files.createTempDirectory(
                "nar-selector-source-date"
            ).toFile()

        try {
            saveSnapshot(
                root = root,
                sourceTimestamp =
                    1_788_015_900L,
                pitEvidenceAt =
                    1_788_016_000_000L,
                marker = "wrong-date"
            )

            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarPitSnapshotSelector
                    .selectLatestFromRoot(
                        root = root,
                        date = "20260829",
                        predictionAsOfEpochMillis =
                            1_788_016_100_000L
                    )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun invalidDateIsRejected() {
        val root =
            Files.createTempDirectory(
                "nar-selector-date"
            ).toFile()

        try {
            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarPitSnapshotSelector
                    .selectLatestFromRoot(
                        root = root,
                        date = "../20260829",
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun saveSnapshot(
        root: java.io.File,
        sourceTimestamp: Long,
        pitEvidenceAt: Long,
        marker: String
    ): NarDailySnapshotStore.SaveResult {
        val sourceTimestampMillis =
            Math.multiplyExact(
                sourceTimestamp,
                1000L
            )

        require(
            pitEvidenceAt >
                sourceTimestampMillis
        )

        return NarDailySnapshotStore
            .saveToRoot(
                root = root,
                data =
                    NarDailyRaceDownloader
                        .DailyRaceData(
                            date =
                                "20260829",
                            racelistCsv =
                                "競馬場,競走年月日,レース番号,距離\n" +
                                    "大井,20260829,1,1200-$marker\n",
                            horselistCsv =
                                "競馬場,競走年月日,レース番号,馬番,馬名\n" +
                                    "大井,20260829,1,1,テスト-$marker\n",
                            paybackCsv =
                                null,
                            sourceFileName =
                                "20260829_" +
                                    sourceTimestamp +
                                    "_race.zip",
                            sourceTimestampEpochSecond =
                                sourceTimestamp,
                            downloadedAtEpochMillis =
                                pitEvidenceAt - 1_000L,
                            serverDateEpochMillis =
                                pitEvidenceAt
                        )
            )
    }
}
