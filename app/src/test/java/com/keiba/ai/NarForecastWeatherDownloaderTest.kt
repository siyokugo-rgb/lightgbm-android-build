package com.keiba.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL

class NarForecastWeatherDownloaderTest {

    @Test
    fun requestIsPinnedToExpectedContract() {
        val request =
            NarForecastWeatherDownloader
                .buildRequestUrl(
                    latitude = 35.591339,
                    longitude = 139.742608
                )

        val url = URL(request)

        assertEquals(
            "https",
            url.protocol
        )

        assertEquals(
            "api.open-meteo.com",
            url.host
        )

        assertTrue(
            url.query.contains(
                "forecast_hours=72"
            )
        )

        assertTrue(
            url.query.contains(
                "timeformat=unixtime"
            )
        )

        assertTrue(
            url.query.contains(
                "timezone=GMT"
            )
        )

        assertTrue(
            url.query.contains(
                "wind_speed_unit=ms"
            )
        )

        assertTrue(
            url.query.contains(
                "cell_selection=land"
            )
        )
    }

    @Test
    fun invalidCoordinatesAreRejected() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarForecastWeatherDownloader
                .buildRequestUrl(
                    latitude = 91.0,
                    longitude = 139.0
                )
        }

        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarForecastWeatherDownloader
                .buildRequestUrl(
                    latitude = 35.0,
                    longitude = 181.0
                )
        }

        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarForecastWeatherDownloader
                .buildRequestUrl(
                    latitude = Double.NaN,
                    longitude = 139.0
                )
        }
    }

    @Test
    fun expectedJsonContentTypeIsAccepted() {
        NarForecastWeatherDownloader
            .validateContentTypeForTest(
                "application/json; charset=utf-8"
            )
    }

    @Test
    fun nonJsonContentTypeIsRejected() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarForecastWeatherDownloader
                .validateContentTypeForTest(
                    "text/html"
                )
        }
    }

    @Test
    fun expectedForecastBodyIsAccepted() {
        NarForecastWeatherDownloader
            .validateResponseBytes(
                sampleResponse()
                    .toByteArray(
                        Charsets.UTF_8
                    )
            )
    }

    @Test
    fun missingHourlyUnitsAreRejectedOnDownloadGate() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarForecastWeatherDownloader
                .validateResponseBytes(
                    sampleResponseWithoutUnits()
                        .toByteArray(
                            Charsets.UTF_8
                        )
                )
        }
    }

    @Test
    fun duplicateKeysAreRejectedOnDownloadGate() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarForecastWeatherDownloader
                .validateResponseBytes(
                    sampleResponse()
                        .replace(
                            """"latitude":35.6,""",
                            """"latitude":35.6,"latitude":35.7,"""
                        )
                        .toByteArray(
                            Charsets.UTF_8
                        )
                )
        }
    }

    @Test
    fun wrongHourlyUnitIsRejectedOnDownloadGate() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarForecastWeatherDownloader
                .validateResponseBytes(
                    sampleResponse()
                        .replace(
                            """"wind_speed_10m":"m/s"""",
                            """"wind_speed_10m":"km/h""""
                        )
                        .toByteArray(
                            Charsets.UTF_8
                        )
                )
        }
    }

    @Test
    fun nonUtcForecastBodyIsRejected() {
        val body =
            sampleResponse()
                .replace(
                    "\"utc_offset_seconds\":0",
                    "\"utc_offset_seconds\":32400"
                )

        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarForecastWeatherDownloader
                .validateResponseBytes(
                    body.toByteArray(
                        Charsets.UTF_8
                    )
                )
        }
    }

    @Test
    fun malformedForecastBodyIsRejected() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarForecastWeatherDownloader
                .validateResponseBytes(
                    """{"hourly":{}}"""
                        .toByteArray(
                            Charsets.UTF_8
                        )
                )
        }
    }

    @Test
    fun oversizedResponseIsRejected() {
        val bytes =
            ByteArray(
                (
                    NarForecastWeatherDownloader
                        .MAX_RESPONSE_BYTES +
                        1L
                    ).toInt()
            )

        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarForecastWeatherDownloader
                .readLimitedForTest(
                    bytes
                )
        }
    }

    private fun sampleResponseWithoutUnits(): String =
        """
        {
          "latitude":35.6,
          "longitude":139.75,
          "utc_offset_seconds":0,
          "hourly":{
            "time":[1788404400],
            "temperature_2m":[29.0],
            "relative_humidity_2m":[70],
            "pressure_msl":[1008.0],
            "surface_pressure":[1007.0],
            "precipitation":[0.0],
            "weather_code":[1],
            "wind_speed_10m":[3.0],
            "wind_direction_10m":[180],
            "wind_gusts_10m":[5.0]
          }
        }
        """.trimIndent()

    private fun sampleResponse(): String =
        """
        {
          "latitude":35.6,
          "longitude":139.75,
          "utc_offset_seconds":0,
          "hourly_units":{
            "time":"unixtime",
            "temperature_2m":"°C",
            "relative_humidity_2m":"%",
            "pressure_msl":"hPa",
            "surface_pressure":"hPa",
            "precipitation":"mm",
            "weather_code":"wmo code",
            "wind_speed_10m":"m/s",
            "wind_direction_10m":"°",
            "wind_gusts_10m":"m/s"
          },
          "hourly":{
            "time":[1788404400],
            "temperature_2m":[29.0],
            "relative_humidity_2m":[70],
            "pressure_msl":[1008.0],
            "surface_pressure":[1007.0],
            "precipitation":[0.0],
            "weather_code":[1],
            "wind_speed_10m":[3.0],
            "wind_direction_10m":[180],
            "wind_gusts_10m":[5.0]
          }
        }
        """.trimIndent()
}
