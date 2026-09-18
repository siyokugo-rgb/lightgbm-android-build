package com.keiba.ai

/**
 * Maps a race scheduled start time to one hourly ForecastWeather point.
 *
 * This is NOT PIT availability selection. Snapshot eligibility remains
 * [NarForecastWeatherSnapshotSelector] / Reader (Step B).
 *
 * ## Provider hourly timestamp semantics (Open-Meteo Forecast API)
 *
 * Open-Meteo documents that most hourly variables are an
 * **instantaneous value for the indicated hour**, while some are
 * preceding-hour aggregates. For the fields this project requests:
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
 * ## Selection rule: FLOOR_TO_HOUR
 *
 * Given a parsed payload whose hourly times are strictly increasing
 * UTC epoch seconds:
 *
 * 1. Require `raceScheduledStartEpochSeconds` in
 *    `[first.targetEpochSeconds, last.targetEpochSeconds]`.
 *    Outside that closed range → `null` (no silent clamp /
 *    no first/last substitute).
 * 2. Otherwise return the latest point with
 *    `targetEpochSeconds <= raceScheduledStartEpochSeconds`.
 *
 * Rationale:
 * - Provider labels **instants**, not invented forward hour bins.
 * - Floor uses the most recent labeled forecast instant at or before
 *   race start without interpolation and without selecting a later
 *   labeled instant after the race second.
 * - Coverage is exactly the labeled series span; mid-hour race times
 *   resolve deterministically to the preceding labeled hour.
 *
 * Precipitation / gust values on the selected point remain the
 * provider's preceding-hour aggregates ending at that stamp; they are
 * not reindexed here. Cumulative 1h/3h/6h/12h/24h derivation is out
 * of scope for Step C.
 */
object NarForecastWeatherTargetSelector {

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
        // only the invariants this selector's binary search depends on,
        // so an arbitrarily constructed Payload cannot silently mis-map.
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
