package com.keiba.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class NarForecastWeatherTargetSelectorTest {

    @Test
    fun floorToHour_exactHourBoundary() {
        val payload =
            payload(
                1_000L,
                4_600L,
                8_200L
            )

        val selected =
            NarForecastWeatherTargetSelector
                .select(
                    payload = payload,
                    raceScheduledStartEpochSeconds =
                        4_600L
                )

        assertNotNull(selected)
        assertEquals(
            4_600L,
            selected!!.targetEpochSeconds
        )
        assertEquals(
            21.0,
            selected.temperature2mCelsius,
            0.0
        )
    }

    @Test
    fun floorToHour_midHourUsesPrecedingLabeledInstant() {
        val payload =
            payload(
                1_000L,
                4_600L,
                8_200L
            )

        val selected =
            NarForecastWeatherTargetSelector
                .select(
                    payload = payload,
                    raceScheduledStartEpochSeconds =
                        4_600L + 1_800L
                )

        assertEquals(
            4_600L,
            selected!!.targetEpochSeconds
        )
    }

    @Test
    fun floorToHour_oneSecondBeforeNextHourStillFloors() {
        val payload =
            payload(
                3_600L,
                7_200L
            )

        val selected =
            NarForecastWeatherTargetSelector
                .select(
                    payload = payload,
                    raceScheduledStartEpochSeconds =
                        7_199L
                )

        assertEquals(
            3_600L,
            selected!!.targetEpochSeconds
        )
    }

    @Test
    fun floorToHour_selectsAmongMultipleHours() {
        val payload =
            payload(
                10_000L,
                13_600L,
                17_200L,
                20_800L
            )

        val selected =
            NarForecastWeatherTargetSelector
                .select(
                    payload = payload,
                    raceScheduledStartEpochSeconds =
                        17_250L
                )

        assertEquals(
            17_200L,
            selected!!.targetEpochSeconds
        )
        assertEquals(
            22.0,
            selected.temperature2mCelsius,
            0.0
        )
    }

    @Test
    fun floorToHour_firstCoverageBoundaryInclusive() {
        val payload =
            payload(
                5_000L,
                8_600L
            )

        val selected =
            NarForecastWeatherTargetSelector
                .select(
                    payload = payload,
                    raceScheduledStartEpochSeconds =
                        5_000L
                )

        assertEquals(
            5_000L,
            selected!!.targetEpochSeconds
        )
    }

    @Test
    fun floorToHour_lastCoverageBoundaryInclusive() {
        val payload =
            payload(
                5_000L,
                8_600L
            )

        val selected =
            NarForecastWeatherTargetSelector
                .select(
                    payload = payload,
                    raceScheduledStartEpochSeconds =
                        8_600L
                )

        assertEquals(
            8_600L,
            selected!!.targetEpochSeconds
        )
    }

    @Test
    fun floorToHour_beforeCoverageReturnsNull() {
        val payload =
            payload(
                5_000L,
                8_600L
            )

        val selected =
            NarForecastWeatherTargetSelector
                .select(
                    payload = payload,
                    raceScheduledStartEpochSeconds =
                        4_999L
                )

        assertNull(selected)
    }

    @Test
    fun floorToHour_afterCoverageReturnsNullNoClamp() {
        val payload =
            payload(
                5_000L,
                8_600L
            )

        val selected =
            NarForecastWeatherTargetSelector
                .select(
                    payload = payload,
                    raceScheduledStartEpochSeconds =
                        8_601L
                )

        assertNull(selected)
    }

    @Test
    fun rejectsNonPositiveRaceScheduledStart() {
        val payload =
            payload(5_000L)

        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarForecastWeatherTargetSelector
                .select(
                    payload = payload,
                    raceScheduledStartEpochSeconds =
                        0L
                )
        }
    }

    @Test
    fun isDeterministicAndClockIndependent() {
        val payload =
            payload(
                1_000L,
                4_600L,
                8_200L
            )

        val first =
            NarForecastWeatherTargetSelector
                .select(
                    payload = payload,
                    raceScheduledStartEpochSeconds =
                        5_000L
                )

        val second =
            NarForecastWeatherTargetSelector
                .select(
                    payload = payload,
                    raceScheduledStartEpochSeconds =
                        5_000L
                )

        assertEquals(
            first!!.targetEpochSeconds,
            second!!.targetEpochSeconds
        )
        assertSame(
            first,
            second
        )
    }

    @Test
    fun rejectsDescendingTimestampsOnArbitraryPayload() {
        val broken =
            NarForecastWeatherPayload(
                providerLatitude = 35.6,
                providerLongitude = 139.75,
                utcOffsetSeconds = 0,
                hourly = listOf(
                    point(8_200L, 20.0),
                    point(4_600L, 21.0)
                )
            )

        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarForecastWeatherTargetSelector
                .select(
                    payload = broken,
                    raceScheduledStartEpochSeconds =
                        8_200L
                )
        }
    }

    @Test
    fun futureTargetRelativeToPredictionAsOfIsAllowed() {
        // Step C must not conflate forecast target time with
        // predictionAsOf / pitEvidence. A race start after
        // "now" that still falls inside labeled coverage is valid.
        val payload =
            payload(
                1_700_000_000L,
                1_700_003_600L,
                1_700_007_200L
            )

        val selected =
            NarForecastWeatherTargetSelector
                .select(
                    payload = payload,
                    raceScheduledStartEpochSeconds =
                        1_700_003_600L + 60L
                )

        assertEquals(
            1_700_003_600L,
            selected!!.targetEpochSeconds
        )
    }

    private fun payload(
        vararg times: Long
    ): NarForecastWeatherPayload {
        require(times.isNotEmpty())

        val points =
            times.mapIndexed { index, time ->
                point(
                    targetEpochSeconds = time,
                    temperature =
                        20.0 + index
                )
            }

        return NarForecastWeatherPayload(
            providerLatitude = 35.6,
            providerLongitude = 139.75,
            utcOffsetSeconds = 0,
            hourly = points
        )
    }

    private fun point(
        targetEpochSeconds: Long,
        temperature: Double
    ): NarForecastWeatherHourlyPoint =
        NarForecastWeatherHourlyPoint(
            targetEpochSeconds =
                targetEpochSeconds,
            temperature2mCelsius =
                temperature,
            relativeHumidity2mPercent =
                70.0,
            pressureMslHpa =
                1008.0,
            surfacePressureHpa =
                1007.0,
            precipitationMm =
                0.0,
            weatherCode = 1,
            windSpeed10mMetersPerSecond =
                3.0,
            windDirection10mDegrees =
                180.0,
            windGusts10mMetersPerSecond =
                5.0
        )
}
