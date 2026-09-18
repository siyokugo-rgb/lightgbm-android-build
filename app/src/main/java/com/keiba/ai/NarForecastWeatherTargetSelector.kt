package com.keiba.ai

/**
 * Maps a race scheduled start time to one hourly ForecastWeather point.
 *
 * This is NOT PIT availability selection. Snapshot eligibility remains
 * [NarForecastWeatherSnapshotSelector] / Reader (Step B).
 *
 * ## Provider hourly timestamp semantics (Open-Meteo Forecast API)
 *
 * Open-Meteo documents field meanings at each labeled hourly stamp.
 * These are provider facts, not a selection algorithm:
 *
 * - Instantaneous at `targetEpochSeconds`:
 *   temperature_2m, relative_humidity_2m, pressure_msl,
 *   surface_pressure, weather_code, wind_speed_10m,
 *   wind_direction_10m
 * - Preceding-hour aggregate ending at `targetEpochSeconds`:
 *   precipitation (sum), wind_gusts_10m (max)
 *
 * Source: https://open-meteo.com/en/docs
 * ("Most weather variables are given as an instantaneous value for
 * the indicated hour. Some variables like precipitation are calculated
 * from the preceding hour as an average or sum.")
 *
 * ## Project selection policy: FLOOR_TO_HOUR
 *
 * FLOOR_TO_HOUR is a **deterministic project approximation**, not an
 * Open-Meteo-mandated mapping rule. Open-Meteo labels instants /
 * preceding-hour aggregates; it does not prescribe floor vs nearest
 * vs exact for mid-hour race starts.
 *
 * Preconditions on `payload.hourly`:
 * - non-empty
 * - each `targetEpochSeconds` positive
 * - strictly increasing
 * - adjacent deltas exactly `3600` seconds (fail-closed)
 *
 * Selection:
 * 1. Require `raceScheduledStartEpochSeconds` in
 *    `[first.targetEpochSeconds, last.targetEpochSeconds]`.
 *    Outside that closed range → `null` (no silent clamp /
 *    no first/last substitute).
 * 2. Otherwise return the latest point with
 *    `targetEpochSeconds <= raceScheduledStartEpochSeconds`.
 *
 * Why this project chose floor (policy, not provider necessity):
 * - No interpolation
 * - No later labeled instant after the race second
 * - Deterministic UTC epoch arithmetic
 * - Exact 1-hour grid makes "preceding labeled hour" well-defined
 *
 * Precipitation / gust values on the selected point remain the
 * provider's preceding-hour aggregates ending at that stamp; they are
 * not reindexed here. Cumulative 1h/3h/6h/12h/24h derivation is out
 * of scope for Step C.
 */
object NarForecastWeatherTargetSelector {

    private const val HOUR_SECONDS =
        3_600L

    /**
     * @param payload typed ForecastWeather hourly series
     * @param raceScheduledStartEpochSeconds UTC epoch seconds
     * @return matching hourly point, or null when the race second is
     *   outside `[first, last]` labeled coverage
     */
    fun select(
        payload: NarForecastWeatherPayload,
        raceScheduledStartEpochSeconds: Long
    ): NarForecastWeatherHourlyPoint? {
        require(
            raceScheduledStartEpochSeconds > 0L
        ) {
            "invalid raceScheduledStartEpochSeconds"
        }

        val hourly =
            payload.hourly

        // Payload from NarForecastWeatherParser already guarantees
        // non-empty + strictly increasing positive times. Re-assert
        // those invariants plus exact 3600s spacing, so an arbitrarily
        // constructed Payload cannot silently mis-map under FLOOR_TO_HOUR.
        require(hourly.isNotEmpty()) {
            "forecast hourly points must not be empty"
        }

        var previous:
            Long? = null

        for (point in hourly) {
            val target =
                point.targetEpochSeconds

            require(target > 0L) {
                "forecast target timestamp must be positive"
            }

            val prior =
                previous

            if (prior != null) {
                require(target > prior) {
                    "forecast target timestamps must be strictly increasing"
                }

                val delta =
                    Math.subtractExact(
                        target,
                        prior
                    )

                require(
                    delta ==
                        HOUR_SECONDS
                ) {
                    "forecast target timestamps must be exactly hourly (3600s)"
                }
            }

            previous = target
        }

        val first =
            hourly.first()
                .targetEpochSeconds

        val last =
            hourly.last()
                .targetEpochSeconds

        if (
            raceScheduledStartEpochSeconds < first ||
            raceScheduledStartEpochSeconds > last
        ) {
            return null
        }

        // Largest index with targetEpochSeconds <= race start.
        var low = 0
        var high = hourly.lastIndex
        var floorIndex = 0

        while (low <= high) {
            val mid =
                (low + high) ushr 1

            val midTarget =
                hourly[mid]
                    .targetEpochSeconds

            if (
                midTarget <=
                    raceScheduledStartEpochSeconds
            ) {
                floorIndex = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }

        return hourly[floorIndex]
    }
}
