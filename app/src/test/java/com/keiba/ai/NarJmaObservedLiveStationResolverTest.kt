package com.keiba.ai

import com.keiba.ai.model.RaceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NarJmaObservedLiveStationResolverTest {

    private val mapping:
        NarJmaObservedLiveStationMapping by lazy {
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

        NarJmaObservedLiveStationMappingParser
            .parse(
                file.readBytes()
            )
    }

    @Test
    fun kawasakiProductSpecificStations() {
        val precip =
            NarJmaObservedLiveStationResolver
                .resolve(
                    mapping = mapping,
                    raceKey = RaceKey(
                        "川崎",
                        20260922,
                        1
                    ),
                    product =
                        NarJmaObservedWeatherProduct
                            .PRE1H00_RCT
                )

        val wind =
            NarJmaObservedLiveStationResolver
                .resolve(
                    mapping = mapping,
                    raceKey = RaceKey(
                        "川崎",
                        20260922,
                        1
                    ),
                    product =
                        NarJmaObservedWeatherProduct
                            .MXWSP00_RCT
                )

        assertEquals("46061", precip!!.stationId)
        assertEquals("44166", wind!!.stationId)
    }

    @Test
    fun nagoyaUses53041AfterVenuePeriod() {
        val selection =
            NarJmaObservedLiveStationResolver
                .resolve(
                    mapping = mapping,
                    raceKey = RaceKey(
                        "名古屋",
                        20260922,
                        1
                    ),
                    product =
                        NarJmaObservedWeatherProduct
                            .MXTEMSADEXT00_RCT
                )

        assertEquals("53041", selection!!.stationId)
        assertEquals("桑名", selection.stationName)
    }

    @Test
    fun beforeMappingEffectiveReturnsNull() {
        assertNull(
            NarJmaObservedLiveStationResolver
                .resolve(
                    mapping = mapping,
                    raceKey = RaceKey(
                        "大井",
                        20260921,
                        1
                    ),
                    product =
                        NarJmaObservedWeatherProduct
                            .PRE1H00_RCT
                )
        )
    }

    @Test
    fun unknownVenueReturnsNull() {
        assertNull(
            NarJmaObservedLiveStationResolver
                .resolve(
                    mapping = mapping,
                    raceKey = RaceKey(
                        "東京",
                        20260922,
                        1
                    ),
                    product =
                        NarJmaObservedWeatherProduct
                            .PRE1H00_RCT
                )
        )
    }

    @Test
    fun precipitationProductsShareSameMapping() {
        val products =
            listOf(
                NarJmaObservedWeatherProduct.PRE1H00_RCT,
                NarJmaObservedWeatherProduct.PRE3H00_RCT,
                NarJmaObservedWeatherProduct.PRE6H00_RCT,
                NarJmaObservedWeatherProduct.PRE12H00_RCT,
                NarJmaObservedWeatherProduct.PRE24H00_RCT
            )

        val stations =
            products.map {
                NarJmaObservedLiveStationResolver
                    .resolve(
                        mapping = mapping,
                        raceKey = RaceKey(
                            "大井",
                            20260922,
                            1
                        ),
                        product = it
                    )!!.stationId
            }

        assertTrue(
            stations.all {
                it == stations.first()
            }
        )
    }

    @Test
    fun noAutomaticFallbackEnabled() {
        for (venue in mapping.venues.values) {
            for (pm in venue.productMappings.values) {
                assertEquals(
                    false,
                    pm.automaticFallback
                )
            }
        }
    }
}
