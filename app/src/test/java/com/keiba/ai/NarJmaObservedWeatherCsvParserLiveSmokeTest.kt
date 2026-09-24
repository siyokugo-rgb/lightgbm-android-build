package com.keiba.ai

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class NarJmaObservedWeatherCsvParserLiveSmokeTest {

    @Test
    fun parseLiveDocumentedProductsForDistinctSchemas() {
        assumeTrue(
            "observed CSV parser live smoke is opt-in only",
            System.getenv(
                "KEIBA_LIVE_OBSERVED_WEATHER_PARSER_SMOKE"
            ) == "1"
        )

        val cases =
            listOf(
                LiveCase(
                    product =
                        NarJmaObservedWeatherProduct
                            .PRE1H00_RCT,
                    stationId = "44166"
                ),
                LiveCase(
                    product =
                        NarJmaObservedWeatherProduct
                            .MXTEMSADEXT00_RCT,
                    stationId = "53041"
                ),
                LiveCase(
                    product =
                        NarJmaObservedWeatherProduct
                            .MNTEMSADEXT00_RCT,
                    stationId = "53041"
                ),
                LiveCase(
                    product =
                        NarJmaObservedWeatherProduct
                            .MXWSP00_RCT,
                    stationId = "44166"
                ),
                LiveCase(
                    product =
                        NarJmaObservedWeatherProduct
                            .GUST00_RCT,
                    stationId = "44166"
                )
            )

        for (case in cases) {
            val downloaded =
                NarJmaObservedWeatherDownloader
                    .download(
                        case.product
                    )

            val payload =
                NarJmaObservedWeatherCsvParser
                    .parse(
                        case.product,
                        downloaded.responseBytes
                    )

            assertTrue(
                payload.rows.isNotEmpty()
            )

            val selected =
                NarJmaObservedWeatherStationSelector
                    .select(
                        payload,
                        case.stationId
                    )

            assertNotNull(
                "missing station ${case.stationId} in ${case.product.productId}",
                selected
            )

            assertTrue(
                selected!!
                    .productTimestampEpochMillis >
                    0L
            )
        }
    }

    private data class LiveCase(
        val product: NarJmaObservedWeatherProduct,
        val stationId: String
    )
}
