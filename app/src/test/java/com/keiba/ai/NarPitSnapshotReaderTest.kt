package com.keiba.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class NarPitSnapshotReaderTest {

    @Test
    fun readsLatestEligibleSnapshotThroughPitSelector() {
        val root =
            Files.createTempDirectory(
                "nar-reader-latest"
            ).toFile()

        try {
            saveSnapshot(
                root = root,
                sourceTimestamp =
                    1_788_001_087L,
                pitEvidenceAt =
                    1_788_001_120_000L,
                marker = "A"
            )

            val expected =
                saveSnapshot(
                    root = root,
                    sourceTimestamp =
                        1_788_001_187L,
                    pitEvidenceAt =
                        1_788_001_220_000L,
                    marker = "B"
                )

            saveSnapshot(
                root = root,
                sourceTimestamp =
                    1_788_001_287L,
                pitEvidenceAt =
                    1_788_001_320_000L,
                marker = "C"
            )

            val input =
                NarPitSnapshotReader
                    .readFromRoot(
                        root = root,
                        date = "20260829",
                        predictionAsOfEpochMillis =
                            1_788_001_250_000L
                    )
                    ?: error(
                        "expected reader input"
                    )

            assertEquals(
                expected.directory
                    .canonicalFile,
                input.selection
                    .directory
            )

            assertEquals(
                30_000L,
                input.selection
                    .ageMillis
            )

            assertEquals(
                1,
                input.races.size
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun productionFeaturesAreTrainServingCompatibleOnly() {
        val root =
            Files.createTempDirectory(
                "nar-reader-features"
            ).toFile()

        try {
            saveSnapshot(
                root = root,
                sourceTimestamp =
                    1_788_001_087L,
                pitEvidenceAt =
                    1_788_001_120_000L,
                marker = "A"
            )

            val entry =
                NarPitSnapshotReader
                    .readFromRoot(
                        root = root,
                        date = "20260829",
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )
                    ?.races
                    ?.single()
                    ?.entries
                    ?.single()
                    ?: error(
                        "expected entry"
                    )

            val features =
                entry.productionRawFeatures

            assertEquals(
                7,
                features.size
            )

            assertEquals(
                "大井",
                features[
                    "feature_race__競馬場"
                ]
            )

            assertEquals(
                "20260829",
                features[
                    "feature_race__競走年月日"
                ]
            )

            assertEquals(
                "鹿毛",
                features[
                    "feature_entry__毛色"
                ]
            )

            assertEquals(
                "20200101",
                features[
                    "feature_entry__生年月日"
                ]
            )

            assertFalse(
                features.keys.any {
                    "距離" in it ||
                        "騎手" in it ||
                        "負担重量" in it ||
                        "馬体重" in it ||
                        "着順" in it
                }
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun horseEntryKeepsPreRaceDomainDataButNotDeferredBodyWeight() {
        val root =
            Files.createTempDirectory(
                "nar-reader-entry"
            ).toFile()

        try {
            saveSnapshot(
                root = root,
                sourceTimestamp =
                    1_788_001_087L,
                pitEvidenceAt =
                    1_788_001_120_000L,
                marker = "A"
            )

            val entry =
                NarPitSnapshotReader
                    .readFromRoot(
                        root = root,
                        date = "20260829",
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )
                    ?.races
                    ?.single()
                    ?.entries
                    ?.single()
                    ?.horseEntry
                    ?: error(
                        "expected horse entry"
                    )

            assertEquals(
                1,
                entry.horseNumber
            )

            assertEquals(
                "テスト馬-A",
                entry.horseName
            )

            assertEquals(
                "テスト騎手",
                entry.jockey
            )

            assertEquals(
                55.0,
                entry.assignedWeightKg
                    ?: error(
                        "expected assigned weight"
                    ),
                0.0
            )

            /*
             * The snapshot contains 馬体重=480, but the Contract still
             * classifies it as DEFERRED_DYNAMIC. It must not enter
             * HorseEntry yet.
             */
            assertNull(
                entry.bodyWeightKg
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun resultColumnsCanExistButNeverLeakIntoReaderOutput() {
        val root =
            Files.createTempDirectory(
                "nar-reader-result-guard"
            ).toFile()

        try {
            saveSnapshot(
                root = root,
                sourceTimestamp =
                    1_788_001_087L,
                pitEvidenceAt =
                    1_788_001_120_000L,
                marker = "A"
            )

            val input =
                NarPitSnapshotReader
                    .readFromRoot(
                        root = root,
                        date = "20260829",
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )
                    ?: error(
                        "expected input"
                    )

            val entry =
                input.races
                    .single()
                    .entries
                    .single()

            assertFalse(
                entry.productionRawFeatures
                    .keys
                    .any {
                        "着順" in it ||
                            "上がり" in it
                    }
            )

            assertTrue(
                NarPreRaceFeatureContract
                    .featureDecision(
                        source =
                            NarPreRaceFeatureContract
                                .Source.HORSELIST,
                        column = "着順",
                        context =
                            NarPreRaceFeatureContract
                                .DataContext.LIVE_PIT_SNAPSHOT
                    ) ==
                    NarPreRaceFeatureContract
                        .FeatureDecision.FORBIDDEN_RESULT
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun futureOnlySnapshotReturnsNull() {
        val root =
            Files.createTempDirectory(
                "nar-reader-future"
            ).toFile()

        try {
            saveSnapshot(
                root = root,
                sourceTimestamp =
                    1_788_001_087L,
                pitEvidenceAt =
                    1_788_001_200_000L,
                marker = "A"
            )

            assertNull(
                NarPitSnapshotReader
                    .readFromRoot(
                        root = root,
                        date = "20260829",
                        predictionAsOfEpochMillis =
                            1_788_001_199_999L
                    )
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun selectedSnapshotIdentityIsPinned() {
        val root =
            Files.createTempDirectory(
                "nar-reader-identity"
            ).toFile()

        try {
            val saved =
                saveSnapshot(
                    root = root,
                    sourceTimestamp =
                        1_788_001_087L,
                    pitEvidenceAt =
                        1_788_001_120_000L,
                    marker = "A"
                )

            val selection =
                NarPitSnapshotSelector
                    .selectLatestFromRoot(
                        root = root,
                        date = "20260829",
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )
                    ?: error(
                        "expected selection"
                    )

            assertEquals(
                saved.directory
                    .canonicalFile,
                selection.directory
            )

            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarPitSnapshotReader
                    .readSelectedForTest(
                        selection.copy(
                            snapshotSha256 =
                                "0".repeat(64)
                        )
                    )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun duplicateHeaderNameIsRejected() {
        val root =
            Files.createTempDirectory(
                "nar-reader-header-duplicate"
            ).toFile()

        try {
            val header =
                horseHeader()
                    .trimEnd(
                        '\n'
                    ) +
                    ",余分,余分\n"

            val row =
                horseRow(
                    number = 1,
                    marker = "A"
                )
                    .trimEnd(
                        '\n'
                    ) +
                    ",x\n"

            saveCustomSnapshot(
                root = root,
                horselist =
                    header + row
            )

            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarPitSnapshotReader
                    .readFromRoot(
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
    fun missingHorseNameIsRejected() {
        val root =
            Files.createTempDirectory(
                "nar-reader-name"
            ).toFile()

        try {
            saveCustomSnapshot(
                root = root,
                horselist =
                    "競馬場,競走年月日,レース番号,馬番,毛色,生年月日,父馬名,母馬名,母父馬名,騎手名,負担重量\n" +
                        "大井,20260829,1,1,鹿毛,20200101,父,母,母父,騎手,55\n"
            )

            assertThrows(
                IllegalStateException::class.java
            ) {
                NarPitSnapshotReader
                    .readFromRoot(
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
    fun duplicateHorseNumberIsRejected() {
        val root =
            Files.createTempDirectory(
                "nar-reader-duplicate"
            ).toFile()

        try {
            saveCustomSnapshot(
                root = root,
                horselist =
                    horseHeader() +
                        horseRow(
                            number = 1,
                            marker = "A"
                        ) +
                        horseRow(
                            number = 1,
                            marker = "B"
                        )
            )

            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarPitSnapshotReader
                    .readFromRoot(
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
    fun malformedFieldCountIsRejected() {
        val root =
            Files.createTempDirectory(
                "nar-reader-field-count"
            ).toFile()

        try {
            saveCustomSnapshot(
                root = root,
                horselist =
                    horseHeader() +
                        "大井,20260829,1,1,テスト馬,鹿毛,20200101,父,母\n"
            )

            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarPitSnapshotReader
                    .readFromRoot(
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
    fun invalidBurdenWeightIsRejected() {
        val root =
            Files.createTempDirectory(
                "nar-reader-burden"
            ).toFile()

        try {
            saveCustomSnapshot(
                root = root,
                horselist =
                    horseHeader() +
                        "大井,20260829,1,1,テスト馬,鹿毛,20200101,父,母,母父,騎手,55kg,480,1\n"
            )

            assertThrows(
                IllegalStateException::class.java
            ) {
                NarPitSnapshotReader
                    .readFromRoot(
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

    private fun saveSnapshot(
        root: java.io.File,
        sourceTimestamp: Long,
        pitEvidenceAt: Long,
        marker: String
    ): NarDailySnapshotStore.SaveResult =
        NarDailySnapshotStore
            .saveToRoot(
                root = root,
                data =
                    sampleData(
                        sourceTimestamp =
                            sourceTimestamp,
                        pitEvidenceAt =
                            pitEvidenceAt,
                        marker =
                            marker
                    )
            )

    private fun saveCustomSnapshot(
        root: java.io.File,
        horselist: String
    ): NarDailySnapshotStore.SaveResult =
        NarDailySnapshotStore
            .saveToRoot(
                root = root,
                data =
                    sampleData(
                        sourceTimestamp =
                            1_788_001_087L,
                        pitEvidenceAt =
                            1_788_001_120_000L,
                        marker = "custom"
                    )
                        .copy(
                            horselistCsv =
                                horselist
                        )
            )

    private fun sampleData(
        sourceTimestamp: Long,
        pitEvidenceAt: Long,
        marker: String
    ): NarDailyRaceDownloader.DailyRaceData =
        NarDailyRaceDownloader
            .DailyRaceData(
                date =
                    "20260829",
                racelistCsv =
                    "競馬場,競走年月日,レース番号,距離,上がり3F\n" +
                        "大井,20260829,1,1200,36.1\n",
                horselistCsv =
                    horseHeader() +
                        horseRow(
                            number = 1,
                            marker = marker
                        ),
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

    private fun horseHeader(): String =
        "競馬場,競走年月日,レース番号,馬番,馬名,毛色,生年月日," +
            "父馬名,母馬名,母父馬名,騎手名,負担重量,馬体重,着順\n"

    private fun horseRow(
        number: Int,
        marker: String
    ): String =
        "大井,20260829,1,$number,テスト馬-$marker,鹿毛,20200101," +
            "父,母,母父,テスト騎手,☆55,480,1\n"
}
