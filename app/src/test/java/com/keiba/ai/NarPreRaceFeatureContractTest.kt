package com.keiba.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NarPreRaceFeatureContractTest {

    @Test
    fun raceKeyCanAlsoBeHistoricalFeature() {
        val table =
            NarCsvParser.parseTable(
                "競馬場,競走年月日,レース番号\n" +
                    "大井,20260829,1\n"
            )

        val row =
            table.rows.single()

        assertTrue(
            NarPreRaceFeatureContract
                .isKeyColumn(
                    NarPreRaceFeatureContract
                        .Source.RACELIST,
                    "競馬場"
                )
        )

        assertEquals(
            "大井",
            NarPreRaceFeatureContract
                .readFeatureValue(
                    NarPreRaceFeatureContract
                        .DataContext.HISTORICAL_MONTHLY,
                    NarPreRaceFeatureContract
                        .Source.RACELIST,
                    table,
                    row,
                    "競馬場"
                )
        )
    }

    @Test
    fun raceNumberIsKeyOnly() {
        val policy =
            NarPreRaceFeatureContract
                .policy(
                    NarPreRaceFeatureContract
                        .Source.RACELIST,
                    "レース番号"
                )

        assertTrue(policy.isKey)

        assertEquals(
            NarPreRaceFeatureContract
                .FeatureDecision.NOT_FEATURE,
            policy.historicalMonthly
        )

        assertEquals(
            NarPreRaceFeatureContract
                .FeatureDecision.NOT_FEATURE,
            policy.livePitSnapshot
        )
    }

    @Test
    fun horseNumberIsHistoricalPendingButLiveAllowedAndStillKey() {
        val policy =
            NarPreRaceFeatureContract
                .policy(
                    NarPreRaceFeatureContract
                        .Source.HORSELIST,
                    "馬番"
                )

        assertTrue(policy.isKey)

        assertEquals(
            NarPreRaceFeatureContract
                .FeatureDecision.PENDING_PIT,
            policy.historicalMonthly
        )

        assertEquals(
            NarPreRaceFeatureContract
                .FeatureDecision.ALLOWED,
            policy.livePitSnapshot
        )
    }

    @Test
    fun raceProgramFieldsAreHistoricalPendingButLiveAllowed() {
        for (column in listOf(
            "距離",
            "条件",
            "頭数"
        )) {
            assertEquals(
                NarPreRaceFeatureContract
                    .FeatureDecision.PENDING_PIT,
                NarPreRaceFeatureContract
                    .featureDecision(
                        NarPreRaceFeatureContract
                            .Source.RACELIST,
                        column,
                        NarPreRaceFeatureContract
                            .DataContext.HISTORICAL_MONTHLY
                    )
            )

            assertEquals(
                NarPreRaceFeatureContract
                    .FeatureDecision.ALLOWED,
                NarPreRaceFeatureContract
                    .featureDecision(
                        NarPreRaceFeatureContract
                            .Source.RACELIST,
                        column,
                        NarPreRaceFeatureContract
                            .DataContext.LIVE_PIT_SNAPSHOT
                    )
            )
        }
    }

    @Test
    fun horseIntrinsicFieldsAreAllowedHistorically() {
        val table =
            NarCsvParser.parseTable(
                "競馬場,競走年月日,レース番号,馬番,毛色,生年月日,父馬名\n" +
                    "大井,20260829,1,1,鹿毛,20200101,父テスト\n"
            )

        val row =
            table.rows.single()

        assertEquals(
            "鹿毛",
            NarPreRaceFeatureContract
                .readFeatureValue(
                    NarPreRaceFeatureContract
                        .DataContext.HISTORICAL_MONTHLY,
                    NarPreRaceFeatureContract
                        .Source.HORSELIST,
                    table,
                    row,
                    "毛色"
                )
        )

        assertEquals(
            "父テスト",
            NarPreRaceFeatureContract
                .readFeatureValue(
                    NarPreRaceFeatureContract
                        .DataContext.HISTORICAL_MONTHLY,
                    NarPreRaceFeatureContract
                        .Source.HORSELIST,
                    table,
                    row,
                    "父馬名"
                )
        )
    }

    @Test
    fun sexAndAgeRequireHistoricalSemanticNormalization() {
        for (column in listOf(
            "性",
            "齢"
        )) {
            assertEquals(
                NarPreRaceFeatureContract
                    .FeatureDecision.DEFERRED_SEMANTIC,
                NarPreRaceFeatureContract
                    .featureDecision(
                        NarPreRaceFeatureContract
                            .Source.HORSELIST,
                        column,
                        NarPreRaceFeatureContract
                            .DataContext.HISTORICAL_MONTHLY
                    )
            )

            assertEquals(
                NarPreRaceFeatureContract
                    .FeatureDecision.ALLOWED,
                NarPreRaceFeatureContract
                    .featureDecision(
                        NarPreRaceFeatureContract
                            .Source.HORSELIST,
                        column,
                        NarPreRaceFeatureContract
                            .DataContext.LIVE_PIT_SNAPSHOT
                    )
            )
        }
    }

    @Test
    fun connectionsAndStatsAreHistoricalPendingButLiveAllowed() {
        for (column in listOf(
            "騎手名",
            "負担重量",
            "調教師",
            "全成績"
        )) {
            assertEquals(
                NarPreRaceFeatureContract
                    .FeatureDecision.PENDING_PIT,
                NarPreRaceFeatureContract
                    .featureDecision(
                        NarPreRaceFeatureContract
                            .Source.HORSELIST,
                        column,
                        NarPreRaceFeatureContract
                            .DataContext.HISTORICAL_MONTHLY
                    )
            )

            assertEquals(
                NarPreRaceFeatureContract
                    .FeatureDecision.ALLOWED,
                NarPreRaceFeatureContract
                    .featureDecision(
                        NarPreRaceFeatureContract
                            .Source.HORSELIST,
                        column,
                        NarPreRaceFeatureContract
                            .DataContext.LIVE_PIT_SNAPSHOT
                    )
            )
        }
    }

    @Test
    fun productionFeatureRequiresTrainServingCompatibility() {
        assertTrue(
            NarPreRaceFeatureContract
                .isTrainServingCompatibleFeature(
                    source =
                        NarPreRaceFeatureContract
                            .Source.RACELIST,
                    column = "競馬場",
                    trainingContext =
                        NarPreRaceFeatureContract
                            .DataContext.HISTORICAL_MONTHLY,
                    servingContext =
                        NarPreRaceFeatureContract
                            .DataContext.LIVE_PIT_SNAPSHOT
                )
        )

        assertTrue(
            !NarPreRaceFeatureContract
                .isTrainServingCompatibleFeature(
                    source =
                        NarPreRaceFeatureContract
                            .Source.RACELIST,
                    column = "距離",
                    trainingContext =
                        NarPreRaceFeatureContract
                            .DataContext.HISTORICAL_MONTHLY,
                    servingContext =
                        NarPreRaceFeatureContract
                            .DataContext.LIVE_PIT_SNAPSHOT
                )
        )

        assertTrue(
            !NarPreRaceFeatureContract
                .isTrainServingCompatibleFeature(
                    source =
                        NarPreRaceFeatureContract
                            .Source.HORSELIST,
                    column = "性",
                    trainingContext =
                        NarPreRaceFeatureContract
                            .DataContext.HISTORICAL_MONTHLY,
                    servingContext =
                        NarPreRaceFeatureContract
                            .DataContext.LIVE_PIT_SNAPSHOT
                )
        )
    }

    @Test
    fun actualRacelistResultColumnIsRejected() {
        val table =
            NarCsvParser.parseTable(
                "競馬場,競走年月日,レース番号,上がり3F\n" +
                    "大井,20260829,1,36.1\n"
            )

        val row =
            table.rows.single()

        val error =
            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarPreRaceFeatureContract
                    .readFeatureValue(
                        NarPreRaceFeatureContract
                            .DataContext.LIVE_PIT_SNAPSHOT,
                        NarPreRaceFeatureContract
                            .Source.RACELIST,
                        table,
                        row,
                        "上がり3F"
                    )
            }

        assertTrue(
            error.message
                .orEmpty()
                .contains(
                    "FORBIDDEN_RESULT"
                )
        )
    }

    @Test
    fun horselistResultColumnIsRejected() {
        val table =
            NarCsvParser.parseTable(
                "競馬場,競走年月日,レース番号,馬番,着順\n" +
                    "大井,20260829,1,1,1\n"
            )

        val row =
            table.rows.single()

        val error =
            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarPreRaceFeatureContract
                    .readFeatureValue(
                        NarPreRaceFeatureContract
                            .DataContext.LIVE_PIT_SNAPSHOT,
                        NarPreRaceFeatureContract
                            .Source.HORSELIST,
                        table,
                        row,
                        "着順"
                    )
            }

        assertTrue(
            error.message
                .orEmpty()
                .contains(
                    "FORBIDDEN_RESULT"
                )
        )
    }

    @Test
    fun dynamicDeferredColumnIsRejectedInBothContexts() {
        val table =
            NarCsvParser.parseTable(
                "競馬場,競走年月日,レース番号,天候\n" +
                    "大井,20260829,1,晴\n"
            )

        val row =
            table.rows.single()

        for (context in
            NarPreRaceFeatureContract
                .DataContext.values()
        ) {
            val error =
                assertThrows(
                    IllegalArgumentException::class.java
                ) {
                    NarPreRaceFeatureContract
                        .readFeatureValue(
                            context,
                            NarPreRaceFeatureContract
                                .Source.RACELIST,
                            table,
                            row,
                            "天候"
                        )
                }

            assertTrue(
                error.message
                    .orEmpty()
                    .contains(
                        "DEFERRED_DYNAMIC"
                    )
            )
        }
    }

    @Test
    fun postTimeRemainsDeferredAudit() {
        val table =
            NarCsvParser.parseTable(
                "競馬場,競走年月日,レース番号,発走時刻\n" +
                    "大井,20260829,1,1020\n"
            )

        val row =
            table.rows.single()

        val error =
            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarPreRaceFeatureContract
                    .readFeatureValue(
                        NarPreRaceFeatureContract
                            .DataContext.LIVE_PIT_SNAPSHOT,
                        NarPreRaceFeatureContract
                            .Source.RACELIST,
                        table,
                        row,
                        "発走時刻"
                    )
            }

        assertTrue(
            error.message
                .orEmpty()
                .contains(
                    "DEFERRED_AUDIT"
                )
        )
    }

    @Test
    fun unknownColumnFailsClosed() {
        val table =
            NarCsvParser.parseTable(
                "競馬場,競走年月日,レース番号,未知列\n" +
                    "大井,20260829,1,x\n"
            )

        val row =
            table.rows.single()

        val error =
            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarPreRaceFeatureContract
                    .readFeatureValue(
                        NarPreRaceFeatureContract
                            .DataContext.LIVE_PIT_SNAPSHOT,
                        NarPreRaceFeatureContract
                            .Source.RACELIST,
                        table,
                        row,
                        "未知列"
                    )
            }

        assertTrue(
            error.message
                .orEmpty()
                .contains(
                    "DENIED_UNKNOWN"
                )
        )
    }

    @Test
    fun paybackIsAlwaysRejected() {
        val table =
            NarCsvParser.parseTable(
                "単勝\n100\n"
            )

        val row =
            table.rows.single()

        for (context in
            NarPreRaceFeatureContract
                .DataContext.values()
        ) {
            val error =
                assertThrows(
                    IllegalArgumentException::class.java
                ) {
                    NarPreRaceFeatureContract
                        .readFeatureValue(
                            context,
                            NarPreRaceFeatureContract
                                .Source.PAYBACK,
                            table,
                            row,
                            "単勝"
                        )
                }

            assertTrue(
                error.message
                    .orEmpty()
                    .contains(
                        "FORBIDDEN_RESULT"
                    )
            )
        }
    }

    @Test
    fun missingAllowedColumnDoesNotSilentlyReturnBlank() {
        val table =
            NarCsvParser.parseTable(
                "競馬場,競走年月日,レース番号\n" +
                    "大井,20260829,1\n"
            )

        val row =
            table.rows.single()

        assertThrows(
            IllegalStateException::class.java
        ) {
            NarPreRaceFeatureContract
                .readFeatureValue(
                    NarPreRaceFeatureContract
                        .DataContext.LIVE_PIT_SNAPSHOT,
                    NarPreRaceFeatureContract
                        .Source.RACELIST,
                    table,
                    row,
                    "頭数"
                )
        }
    }

    @Test
    fun allowedColumnListsAreContextSpecific() {
        val historicalRace =
            NarPreRaceFeatureContract
                .allowedFeatureColumns(
                    NarPreRaceFeatureContract
                        .Source.RACELIST,
                    NarPreRaceFeatureContract
                        .DataContext.HISTORICAL_MONTHLY
                )

        val liveRace =
            NarPreRaceFeatureContract
                .allowedFeatureColumns(
                    NarPreRaceFeatureContract
                        .Source.RACELIST,
                    NarPreRaceFeatureContract
                        .DataContext.LIVE_PIT_SNAPSHOT
                )

        val historicalHorse =
            NarPreRaceFeatureContract
                .allowedFeatureColumns(
                    NarPreRaceFeatureContract
                        .Source.HORSELIST,
                    NarPreRaceFeatureContract
                        .DataContext.HISTORICAL_MONTHLY
                )

        val liveHorse =
            NarPreRaceFeatureContract
                .allowedFeatureColumns(
                    NarPreRaceFeatureContract
                        .Source.HORSELIST,
                    NarPreRaceFeatureContract
                        .DataContext.LIVE_PIT_SNAPSHOT
                )

        assertTrue("競馬場" in historicalRace)
        assertTrue("距離" !in historicalRace)
        assertTrue("距離" in liveRace)
        assertTrue("毛色" in historicalHorse)
        assertTrue("性" !in historicalHorse)
        assertTrue("齢" !in historicalHorse)
        assertTrue("性" in liveHorse)
        assertTrue("齢" in liveHorse)
        assertTrue("馬番" !in historicalHorse)
        assertTrue("馬番" in liveHorse)
        assertTrue("騎手名" !in historicalHorse)
        assertTrue("騎手名" in liveHorse)
    }
}
