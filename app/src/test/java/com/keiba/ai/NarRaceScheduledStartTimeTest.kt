package com.keiba.ai

import com.keiba.ai.model.RaceKey
import com.keiba.ai.model.RaceRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class NarRaceScheduledStartTimeTest {

    private val tokyo =
        ZoneId.of("Asia/Tokyo")

    @Test
    fun convertsYyyymmddAndHhmmToUtcEpochSeconds() {
        val epoch =
            NarRaceScheduledStartTime
                .toEpochSeconds(
                    raceDateYyyymmdd =
                        19980806,
                    postTimeHhmm =
                        1545
                )

        val expected =
            LocalDateTime
                .of(
                    1998,
                    8,
                    6,
                    15,
                    45
                )
                .atZone(tokyo)
                .toInstant()
                .epochSecond

        assertEquals(expected, epoch)
        assertEquals(
            Instant.ofEpochSecond(epoch)
                .atZone(ZoneOffset.UTC)
                .toLocalDateTime()
                .toString(),
            "1998-08-06T06:45"
        )
    }

    @Test
    fun converts0930ViaNumericHhmmEncoding() {
        val epoch =
            NarRaceScheduledStartTime
                .toEpochSeconds(
                    raceDateYyyymmdd =
                        20260829,
                    postTimeHhmm =
                        930
                )

        val expected =
            LocalDateTime
                .of(
                    2026,
                    8,
                    29,
                    9,
                    30
                )
                .atZone(tokyo)
                .toInstant()
                .epochSecond

        assertEquals(expected, epoch)
    }

    @Test
    fun converts1200And2359() {
        assertEquals(
            LocalDateTime
                .of(2026, 8, 29, 12, 0)
                .atZone(tokyo)
                .toInstant()
                .epochSecond,
            NarRaceScheduledStartTime
                .toEpochSeconds(
                    20260829,
                    1200
                )
        )

        assertEquals(
            LocalDateTime
                .of(2026, 8, 29, 23, 59)
                .atZone(tokyo)
                .toInstant()
                .epochSecond,
            NarRaceScheduledStartTime
                .toEpochSeconds(
                    20260829,
                    2359
                )
        )
    }

    @Test
    fun leapDayPasses() {
        val epoch =
            NarRaceScheduledStartTime
                .toEpochSeconds(
                    20240229,
                    1020
                )

        assertEquals(
            LocalDateTime
                .of(2024, 2, 29, 10, 20)
                .atZone(tokyo)
                .toInstant()
                .epochSecond,
            epoch
        )
    }

    @Test
    fun isDeterministic() {
        val first =
            NarRaceScheduledStartTime
                .toEpochSeconds(
                    19980806,
                    1545
                )

        val second =
            NarRaceScheduledStartTime
                .toEpochSeconds(
                    19980806,
                    1545
                )

        assertEquals(first, second)
    }

    @Test
    fun parsePostTimeRawAcceptsFourDigitHhmm() {
        assertEquals(
            1050,
            NarRaceScheduledStartTime
                .parsePostTimeRaw(
                    "1050"
                )
        )
        assertEquals(
            930,
            NarRaceScheduledStartTime
                .parsePostTimeRaw(
                    "0930"
                )
        )
    }

    @Test
    fun rejectsInvalidDates() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarRaceScheduledStartTime
                .toEpochSeconds(
                    20230229,
                    1200
                )
        }

        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarRaceScheduledStartTime
                .toEpochSeconds(
                    20241301,
                    1200
                )
        }

        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarRaceScheduledStartTime
                .toEpochSeconds(
                    20240100,
                    1200
                )
        }
    }

    @Test
    fun rejectsInvalidPostTimes() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarRaceScheduledStartTime
                .toEpochSeconds(
                    20260829,
                    -1
                )
        }

        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarRaceScheduledStartTime
                .toEpochSeconds(
                    20260829,
                    2400
                )
        }

        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarRaceScheduledStartTime
                .toEpochSeconds(
                    20260829,
                    1260
                )
        }
    }

    @Test
    fun rejectsMalformedRawPostTime() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarRaceScheduledStartTime
                .parsePostTimeRaw(
                    "12:30"
                )
        }

        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarRaceScheduledStartTime
                .parsePostTimeRaw(
                    "930"
                )
        }

        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarRaceScheduledStartTime
                .parsePostTimeRaw(
                    "12a0"
                )
        }

        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarRaceScheduledStartTime
                .parsePostTimeRaw(
                    " 1050"
                )
        }

        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarRaceScheduledStartTime
                .parsePostTimeRaw(
                    ""
                )
        }
    }

    @Test
    fun missingPostTimeReturnsNullWithoutInventingMidnight() {
        val race =
            RaceRecord(
                key = RaceKey(
                    "大井",
                    20260829,
                    1
                ),
                postTime = null,
                distanceMeters = null,
                weather = null,
                trackCondition = null,
                declaredCount = null,
                raceName = null
            )

        assertNull(
            NarRaceScheduledStartTime
                .toEpochSecondsOrNull(
                    race
                )
        )
    }

    @Test
    fun raceRecordConversionUsesKeyDateAndPostTime() {
        val race =
            RaceRecord(
                key = RaceKey(
                    "大井",
                    19980806,
                    1
                ),
                postTime = 1545,
                distanceMeters = 1600,
                weather = "曇",
                trackCondition = "稍重",
                declaredCount = 2,
                raceName = "４才"
            )

        assertEquals(
            NarRaceScheduledStartTime
                .toEpochSeconds(
                    19980806,
                    1545
                ),
            NarRaceScheduledStartTime
                .toEpochSecondsOrNull(
                    race
                )
        )
    }

    @Test
    fun epochSecondsConnectsToForecastTargetSelector() {
        val raceStart =
            NarRaceScheduledStartTime
                .toEpochSeconds(
                    20260829,
                    1545
                )

        val hourFloor =
            raceStart -
                (raceStart % 3_600L)

        val payload =
            NarForecastWeatherPayload(
                providerLatitude = 35.6,
                providerLongitude = 139.75,
                utcOffsetSeconds = 0,
                hourly = listOf(
                    point(hourFloor - 3_600L),
                    point(hourFloor),
                    point(hourFloor + 3_600L)
                )
            )

        val selected =
            NarForecastWeatherTargetSelector
                .select(
                    payload = payload,
                    raceScheduledStartEpochSeconds =
                        raceStart
                )

        assertEquals(
            hourFloor,
            selected!!.targetEpochSeconds
        )
    }

    private fun point(
        targetEpochSeconds: Long
    ): NarForecastWeatherHourlyPoint =
        NarForecastWeatherHourlyPoint(
            targetEpochSeconds =
                targetEpochSeconds,
            temperature2mCelsius = 20.0,
            relativeHumidity2mPercent = 70.0,
            pressureMslHpa = 1008.0,
            surfacePressureHpa = 1007.0,
            precipitationMm = 0.0,
            weatherCode = 1,
            windSpeed10mMetersPerSecond = 3.0,
            windDirection10mDegrees = 180.0,
            windGusts10mMetersPerSecond = 5.0
        )
}
