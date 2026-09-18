package com.keiba.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class NarForecastWeatherParserTest {

    @Test
    fun parsesSingleHourPayload() {
        val raw =
            sampleResponse().toByteArray(
                StandardCharsets.UTF_8
            )
        val copy =
            raw.copyOf()

        val payload =
            NarForecastWeatherParser.parse(raw)

        assertEquals(35.6, payload.providerLatitude, 0.0)
        assertEquals(139.75, payload.providerLongitude, 0.0)
        assertEquals(0, payload.utcOffsetSeconds)
        assertEquals(1, payload.hourly.size)

        val point =
            payload.hourly[0]

        assertEquals(1_788_404_400L, point.targetEpochSeconds)
        assertEquals(29.0, point.temperature2mCelsius, 0.0)
        assertEquals(70.0, point.relativeHumidity2mPercent, 0.0)
        assertEquals(1008.0, point.pressureMslHpa, 0.0)
        assertEquals(1007.0, point.surfacePressureHpa, 0.0)
        assertEquals(0.0, point.precipitationMm, 0.0)
        assertEquals(1, point.weatherCode)
        assertEquals(3.0, point.windSpeed10mMetersPerSecond, 0.0)
        assertEquals(180.0, point.windDirection10mDegrees, 0.0)
        assertEquals(5.0, point.windGusts10mMetersPerSecond, 0.0)

        assertTrue(raw.contentEquals(copy))
    }

    @Test
    fun parsesMultipleHoursPreservingOrder() {
        val body =
            """
            {
              "latitude":35.6,
              "longitude":139.75,
              "utc_offset_seconds":0,
              "generationtime_ms":0.12,
              "timezone":"GMT",
              "hourly_units":{"time":"unixtime","temperature_2m":"°C","relative_humidity_2m":"%","pressure_msl":"hPa","surface_pressure":"hPa","precipitation":"mm","weather_code":"wmo code","wind_speed_10m":"m/s","wind_direction_10m":"°","wind_gusts_10m":"m/s"},
              "hourly":{
                "time":[1788404400,1788408000],
                "temperature_2m":[29.0,28.5],
                "relative_humidity_2m":[70,71.5],
                "pressure_msl":[1008.0,1007.5],
                "surface_pressure":[1007.0,1006.5],
                "precipitation":[0.0,0.2],
                "weather_code":[1,2],
                "wind_speed_10m":[3.0,3.5],
                "wind_direction_10m":[180,181.25],
                "wind_gusts_10m":[5.0,6.0]
              }
            }
            """.trimIndent()

        val payload =
            NarForecastWeatherParser.parse(
                body.toByteArray(StandardCharsets.UTF_8)
            )

        assertEquals(2, payload.hourly.size)
        assertEquals(
            1_788_404_400L,
            payload.hourly[0].targetEpochSeconds
        )
        assertEquals(
            1_788_408_000L,
            payload.hourly[1].targetEpochSeconds
        )
        assertEquals(
            71.5,
            payload.hourly[1].relativeHumidity2mPercent,
            0.0
        )
        assertEquals(
            181.25,
            payload.hourly[1].windDirection10mDegrees,
            0.0
        )
    }

    @Test
    fun hourlyListIsIndependentCopy() {
        val payload =
            NarForecastWeatherParser.parse(
                sampleResponse().toByteArray(
                    StandardCharsets.UTF_8
                )
            )

        assertThrows(
            UnsupportedOperationException::class.java
        ) {
            (payload.hourly as MutableList)
                .add(payload.hourly[0])
        }
    }

    @Test
    fun rejectsMalformedJson() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarForecastWeatherParser.parse(
                "{".toByteArray(StandardCharsets.UTF_8)
            )
        }
    }

    @Test
    fun rejectsInvalidUtf8() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarForecastWeatherParser.parse(
                byteArrayOf(
                    '{'.code.toByte(),
                    0x80.toByte()
                )
            )
        }
    }

    @Test
    fun rejectsMissingHourly() {
        assertRejects(
            sampleResponse().replace(
                """"hourly":{""",
                """"hourly_missing":{"""
            )
        )
    }

    @Test
    fun rejectsMissingTime() {
        assertRejects(
            sampleResponse().replace(
                """"time":[1788404400],""",
                ""
            )
        )
    }

    @Test
    fun rejectsMissingWeatherField() {
        assertRejects(
            sampleResponse().replace(
                """"precipitation":[0.0],""",
                ""
            )
        )
    }

    @Test
    fun rejectsNonArrayHourlyField() {
        assertRejects(
            sampleResponse().replace(
                """"temperature_2m":[29.0]""",
                """"temperature_2m":29.0"""
            )
        )
    }

    @Test
    fun rejectsArrayLengthMismatch() {
        assertRejects(
            sampleResponse().replace(
                """"temperature_2m":[29.0]""",
                """"temperature_2m":[29.0,30.0]"""
            )
        )
    }

    @Test
    fun rejectsEmptyArrays() {
        assertRejects(
            """
            {
              "latitude":35.6,
              "longitude":139.75,
              "utc_offset_seconds":0,
              "hourly":{
                "time":[],
                "temperature_2m":[],
                "relative_humidity_2m":[],
                "pressure_msl":[],
                "surface_pressure":[],
                "precipitation":[],
                "weather_code":[],
                "wind_speed_10m":[],
                "wind_direction_10m":[],
                "wind_gusts_10m":[]
              }
            }
            """.trimIndent()
        )
    }

    @Test
    fun rejectsNullElement() {
        assertRejects(
            sampleResponse().replace(
                """"precipitation":[0.0]""",
                """"precipitation":[null]"""
            )
        )
    }

    @Test
    fun rejectsWrongNumericType() {
        assertRejects(
            sampleResponse().replace(
                """"temperature_2m":[29.0]""",
                """"temperature_2m":["warm"]"""
            )
        )
    }

    @Test
    fun rejectsDuplicateTimestamp() {
        assertRejects(
            multiHourBody(
                times = "[1788404400,1788404400]"
            )
        )
    }

    @Test
    fun rejectsDescendingTimestamp() {
        assertRejects(
            multiHourBody(
                times = "[1788408000,1788404400]"
            )
        )
    }

    @Test
    fun rejectsFractionalTimestamp() {
        assertRejects(
            sampleResponse().replace(
                """"time":[1788404400]""",
                """"time":[1788404400.5]"""
            )
        )
    }

    @Test
    fun rejectsNonFiniteTemperatureAsStringNaN() {
        assertRejects(
            sampleResponse().replace(
                """"temperature_2m":[29.0]""",
                """"temperature_2m":["NaN"]"""
            )
        )
    }

    @Test
    fun rejectsNonZeroUtcOffset() {
        assertRejects(
            sampleResponse().replace(
                """"utc_offset_seconds":0""",
                """"utc_offset_seconds":32400"""
            )
        )
    }

    @Test
    fun rejectsInvalidLatitude() {
        assertRejects(
            sampleResponse().replace(
                """"latitude":35.6""",
                """"latitude":91.0"""
            )
        )
    }

    @Test
    fun rejectsInvalidLongitude() {
        assertRejects(
            sampleResponse().replace(
                """"longitude":139.75""",
                """"longitude":181.0"""
            )
        )
    }

    @Test
    fun rejectsHumidityOutOfRange() {
        assertRejects(
            sampleResponse().replace(
                """"relative_humidity_2m":[70]""",
                """"relative_humidity_2m":[101]"""
            )
        )
    }

    @Test
    fun rejectsNegativePrecipitation() {
        assertRejects(
            sampleResponse().replace(
                """"precipitation":[0.0]""",
                """"precipitation":[-0.1]"""
            )
        )
    }

    @Test
    fun rejectsNonPositivePressure() {
        assertRejects(
            sampleResponse().replace(
                """"pressure_msl":[1008.0]""",
                """"pressure_msl":[0.0]"""
            )
        )
    }

    @Test
    fun rejectsNegativeWindSpeed() {
        assertRejects(
            sampleResponse().replace(
                """"wind_speed_10m":[3.0]""",
                """"wind_speed_10m":[-1.0]"""
            )
        )
    }

    @Test
    fun rejectsNegativeWindGust() {
        assertRejects(
            sampleResponse().replace(
                """"wind_gusts_10m":[5.0]""",
                """"wind_gusts_10m":[-0.5]"""
            )
        )
    }

    @Test
    fun rejectsNonIntegerWeatherCode() {
        assertRejects(
            sampleResponse().replace(
                """"weather_code":[1]""",
                """"weather_code":[1.5]"""
            )
        )
    }

    @Test
    fun rejectsOversizedInput() {
        val bytes =
            ByteArray(
                (
                    NarForecastWeatherDownloader
                        .MAX_RESPONSE_BYTES + 1L
                    ).toInt()
            )

        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarForecastWeatherParser.parse(bytes)
        }
    }

    @Test
    fun allowsHarmlessExtraMetadata() {
        val body =
            sampleResponse().replace(
                """"utc_offset_seconds":0,""",
                """"utc_offset_seconds":0,"elevation":3.0,"generationtime_ms":1.2,"""
            )

        val payload =
            NarForecastWeatherParser.parse(
                body.toByteArray(StandardCharsets.UTF_8)
            )

        assertEquals(1, payload.hourly.size)
    }

    private fun assertRejects(body: String) {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarForecastWeatherParser.parse(
                body.toByteArray(StandardCharsets.UTF_8)
            )
        }
    }

    @Test
    fun rejectsRootDuplicateKey() {
        assertRejects(
            """
            {
              "latitude":1.0,
              "latitude":2.0,
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
        )
    }

    @Test
    fun rejectsHourlyDuplicateKey() {
        assertRejects(
            sampleResponse().replace(
                """"temperature_2m":[29.0],""",
                """"temperature_2m":[29.0],"temperature_2m":[28.0],"""
            )
        )
    }

    @Test
    fun rejectsHourlyUnitsDuplicateKey() {
        assertRejects(
            sampleResponseWithUnits().replace(
                """"temperature_2m":"°C",""",
                """"temperature_2m":"°C","temperature_2m":"°F","""
            )
        )
    }

    @Test
    fun rejectsNestedObjectDuplicateKey() {
        assertRejects(
            """
            {
              "latitude":35.6,
              "longitude":139.75,
              "utc_offset_seconds":0,
              "meta":{"a":1,"a":2},
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
        )
    }

    @Test
    fun rejectsDuplicateKeyInsideArrayObject() {
        assertRejects(
            """
            {
              "latitude":35.6,
              "longitude":139.75,
              "utc_offset_seconds":0,
              "notes":[{"k":1,"k":2}],
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
        )
    }

    @Test
    fun allowsSameKeyNameInDifferentObjectScopes() {
        val payload =
            NarForecastWeatherParser.parse(
                """
                {
                  "latitude":35.6,
                  "longitude":139.75,
                  "utc_offset_seconds":0,
                  "meta":{"time":"label"},
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
                    .toByteArray(
                        StandardCharsets.UTF_8
                    )
            )

        assertEquals(1, payload.hourly.size)
    }

    @Test
    fun doesNotFalsePositiveOnBracesInsideStrings() {
        val payload =
            NarForecastWeatherParser.parse(
                """
                {
                  "latitude":35.6,
                  "longitude":139.75,
                  "utc_offset_seconds":0,
                  "note":"not { \"latitude\": 1, \"latitude\": 2 }",
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
                    .toByteArray(
                        StandardCharsets.UTF_8
                    )
            )

        assertEquals(1, payload.hourly.size)
    }

    @Test
    fun doesNotFalsePositiveOnEscapedQuotesInStrings() {
        val payload =
            NarForecastWeatherParser.parse(
                """
                {
                  "latitude":35.6,
                  "longitude":139.75,
                  "utc_offset_seconds":0,
                  "note":"say \"hello\", then continue",
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
                    .toByteArray(
                        StandardCharsets.UTF_8
                    )
            )

        assertEquals(1, payload.hourly.size)
    }

    @Test
    fun rejectsUnicodeEscapeSemanticDuplicateKey() {
        assertRejects(
            """
            {
              "latitude":1.0,
              "latit\u0075de":2.0,
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
        )
    }

    @Test
    fun acceptsExactHourlyUnitsWhenPresent() {
        val payload =
            NarForecastWeatherParser.parse(
                sampleResponseWithUnits()
                    .toByteArray(
                        StandardCharsets.UTF_8
                    )
            )

        assertEquals(1, payload.hourly.size)
    }

    @Test
    fun acceptsLegacyPayloadMissingHourlyUnits() {
        val payload =
            NarForecastWeatherParser.parse(
                sampleResponse().toByteArray(
                    StandardCharsets.UTF_8
                )
            )

        assertEquals(1, payload.hourly.size)
    }

    @Test
    fun rejectsWrongTemperatureUnit() {
        assertRejects(
            sampleResponseWithUnits().replace(
                """"temperature_2m":"°C"""",
                """"temperature_2m":"°F""""
            )
        )
    }

    @Test
    fun rejectsWrongWindUnit() {
        assertRejects(
            sampleResponseWithUnits().replace(
                """"wind_speed_10m":"m/s"""",
                """"wind_speed_10m":"km/h""""
            )
        )
    }

    @Test
    fun rejectsWrongPrecipitationUnit() {
        assertRejects(
            sampleResponseWithUnits().replace(
                """"precipitation":"mm"""",
                """"precipitation":"inch""""
            )
        )
    }

    @Test
    fun rejectsWrongTimeUnit() {
        assertRejects(
            sampleResponseWithUnits().replace(
                """"time":"unixtime"""",
                """"time":"iso8601""""
            )
        )
    }

    @Test
    fun rejectsNullHourlyUnit() {
        assertRejects(
            sampleResponseWithUnits().replace(
                """"weather_code":"wmo code"""",
                """"weather_code":null"""
            )
        )
    }

    @Test
    fun rejectsNonStringHourlyUnit() {
        assertRejects(
            sampleResponseWithUnits().replace(
                """"pressure_msl":"hPa"""",
                """"pressure_msl":1"""
            )
        )
    }

    @Test
    fun rejectsMissingRequiredHourlyUnitField() {
        assertRejects(
            sampleResponseWithUnits().replace(
                """"weather_code":"wmo code",""",
                ""
            )
        )
    }

    private fun multiHourBody(
        times: String
    ): String =
        """
        {
          "latitude":35.6,
          "longitude":139.75,
          "utc_offset_seconds":0,
          "hourly":{
            "time":$times,
            "temperature_2m":[29.0,28.0],
            "relative_humidity_2m":[70,71],
            "pressure_msl":[1008.0,1007.0],
            "surface_pressure":[1007.0,1006.0],
            "precipitation":[0.0,0.1],
            "weather_code":[1,2],
            "wind_speed_10m":[3.0,3.1],
            "wind_direction_10m":[180,190],
            "wind_gusts_10m":[5.0,5.1]
          }
        }
        """.trimIndent()

    private fun sampleResponseWithUnits(): String =
        """
        {
          "latitude":35.6,
          "longitude":139.75,
          "utc_offset_seconds":0,
          "hourly_units":${NarForecastWeatherHourlyUnits.expectedObjectJson()},
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
