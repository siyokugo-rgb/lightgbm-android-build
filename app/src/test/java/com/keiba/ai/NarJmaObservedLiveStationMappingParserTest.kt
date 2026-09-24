package com.keiba.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class NarJmaObservedLiveStationMappingParserTest {

    private fun repoBytes(): ByteArray {
        val candidates =
            listOf(
                java.io.File(
                    "config/nar-jma-observed-live-station-mapping.json"
                ),
                java.io.File(
                    "../config/nar-jma-observed-live-station-mapping.json"
                )
            )

        val file =
            candidates.firstOrNull {
                it.isFile
            }
                ?: error(
                    "missing config/nar-jma-observed-live-station-mapping.json"
                )

        return file.readBytes()
    }

    @Test
    fun parsesRepoSourceOfTruth() {
        val mapping =
            NarJmaObservedLiveStationMappingParser
                .parse(
                    repoBytes()
                )

        assertEquals(1, mapping.version)
        assertEquals(
            "LIVE_CAPTURE_MAPPING",
            mapping.status
        )
        assertEquals(
            "LIVE_CAPTURED_OBSERVED",
            mapping.role
        )
        assertEquals(
            "PUBLIC_ONLY_WITH_DEFERRED_GAPS",
            mapping.acquisitionStrategy
        )
        assertEquals(
            "CANDIDATE",
            mapping.pitStatus
        )
        assertEquals(
            "NOT_YET_AUTHORIZED",
            mapping.featureEligibility
        )
        assertEquals(
            15,
            mapping.venues.size
        )
        assertEquals(
            "46061",
            mapping.venues["川崎"]!!
                .productMappings[
                    NarJmaObservedWeatherProduct
                        .Family.PRECIPITATION
                ]!!
                .stationId
        )
        assertEquals(
            "44166",
            mapping.venues["川崎"]!!
                .productMappings[
                    NarJmaObservedWeatherProduct
                        .Family.WIND_MAX
                ]!!
                .stationId
        )
    }

    @Test
    fun wrongVersionIsRejected() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarJmaObservedLiveStationMappingParser
                .parse(
                    mutate(
                        """"version": 1""",
                        """"version": 2"""
                    )
                )
        }
    }

    @Test
    fun wrongStatusIsRejected() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarJmaObservedLiveStationMappingParser
                .parse(
                    mutate(
                        """"status": "LIVE_CAPTURE_MAPPING"""",
                        """"status": "DRAFT""""
                    )
                )
        }
    }

    @Test
    fun wrongRoleIsRejected() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarJmaObservedLiveStationMappingParser
                .parse(
                    mutate(
                        """"role": "LIVE_CAPTURED_OBSERVED"""",
                        """"role": "HISTORICAL_FINAL_OBSERVED""""
                    )
                )
        }
    }

    @Test
    fun wrongStrategyIsRejected() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarJmaObservedLiveStationMappingParser
                .parse(
                    mutate(
                        """"acquisition_strategy": "PUBLIC_ONLY_WITH_DEFERRED_GAPS"""",
                        """"acquisition_strategy": "JMBSC_PRIMARY""""
                    )
                )
        }
    }

    @Test
    fun wrongFeatureEligibilityIsRejected() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarJmaObservedLiveStationMappingParser
                .parse(
                    mutate(
                        """"feature_eligibility": "NOT_YET_AUTHORIZED"""",
                        """"feature_eligibility": "AUTHORIZED""""
                    )
                )
        }
    }

    @Test
    fun fallbackTrueIsRejected() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarJmaObservedLiveStationMappingParser
                .parse(
                    mutate(
                        """"automatic_fallback": false""",
                        """"automatic_fallback": true""",
                        replaceAll = false
                    )
                )
        }
    }

    @Test
    fun unknownProductIdIsRejected() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarJmaObservedLiveStationMappingParser
                .parse(
                    mutate(
                        """"pre1h00_rct"""",
                        """"not_a_product""""
                    )
                )
        }
    }

    @Test
    fun malformedStationIdIsRejected() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarJmaObservedLiveStationMappingParser
                .parse(
                    mutate(
                        """"station_id": "20432"""",
                        """"station_id": "ABC""""
                    )
                )
        }
    }

    private fun mutate(
        from: String,
        to: String,
        replaceAll: Boolean = true
    ): ByteArray {
        val text =
            String(
                repoBytes(),
                StandardCharsets.UTF_8
            )

        val mutated =
            if (replaceAll) {
                text.replace(from, to)
            } else {
                text.replaceFirst(from, to)
            }

        assertTrue(mutated != text)
        return mutated.toByteArray(
            StandardCharsets.UTF_8
        )
    }
}
