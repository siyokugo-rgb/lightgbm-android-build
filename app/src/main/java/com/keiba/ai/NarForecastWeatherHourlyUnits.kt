package com.keiba.ai

import org.json.JSONException
import org.json.JSONObject

/**
 * R4 ForecastWeather `hourly_units` contract.
 *
 * Exact strings were confirmed against a live Open-Meteo response using
 * [NarForecastWeatherDownloader.buildRequestUrl] parameters
 * (`timeformat=unixtime`, `timezone=GMT`, `temperature_unit=celsius`,
 * `wind_speed_unit=ms`, `precipitation_unit=mm`) on 2026-09-18:
 *
 * ```
 * time=unixtime
 * temperature_2m=°C
 * relative_humidity_2m=%
 * pressure_msl=hPa
 * surface_pressure=hPa
 * precipitation=mm
 * weather_code=wmo code
 * wind_speed_10m=m/s
 * wind_direction_10m=°
 * wind_gusts_10m=m/s
 * ```
 *
 * ## Compatibility
 *
 * - Parser / Reader (legacy snapshots): missing `hourly_units` is
 *   allowed because R4 request_url already pins units; when the object
 *   is present it must match exactly.
 * - Downloader / new capture: `hourly_units` is required and must match
 *   exactly so ambiguous unit responses are never archived.
 */
internal object NarForecastWeatherHourlyUnits {

    val EXPECTED:
        Map<String, String> =
        linkedMapOf(
            "time" to "unixtime",
            "temperature_2m" to "°C",
            "relative_humidity_2m" to "%",
            "pressure_msl" to "hPa",
            "surface_pressure" to "hPa",
            "precipitation" to "mm",
            "weather_code" to "wmo code",
            "wind_speed_10m" to "m/s",
            "wind_direction_10m" to "°",
            "wind_gusts_10m" to "m/s"
        )

    /**
     * Compact JSON object body for fixtures / new-capture samples.
     */
    fun expectedObjectJson(): String =
        EXPECTED.entries.joinToString(
            prefix = "{",
            postfix = "}",
            separator = ","
        ) { (key, value) ->
            "\"$key\":\"$value\""
        }

    /**
     * Legacy-tolerant: absent `hourly_units` → no-op.
     * Present → exact required fields.
     */
    fun validateIfPresent(
        root: JSONObject
    ) {
        if (
            !root.has("hourly_units") ||
            root.isNull("hourly_units")
        ) {
            return
        }

        validateExact(
            requireObject(
                root,
                "hourly_units"
            )
        )
    }

    /**
     * New-capture gate: `hourly_units` must exist and match exactly.
     */
    fun requirePresentAndExact(
        root: JSONObject
    ) {
        require(!root.isNull("hourly_units")) {
            "weather response missing field: hourly_units"
        }

        validateExact(
            requireObject(
                root,
                "hourly_units"
            )
        )
    }

    private fun validateExact(
        units: JSONObject
    ) {
        for ((field, expected) in EXPECTED) {
            require(!units.isNull(field)) {
                "weather hourly_units missing field: $field"
            }

            val actual =
                try {
                    units.get(field)
                } catch (error: JSONException) {
                    throw IllegalArgumentException(
                        "weather hourly_units missing field: $field",
                        error
                    )
                }

            require(actual is String) {
                "weather hourly_units field is not a string: $field"
            }

            require(actual == expected) {
                "weather hourly_units mismatch: $field"
            }
        }
    }

    private fun requireObject(
        parent: JSONObject,
        key: String
    ): JSONObject {
        val value =
            try {
                parent.get(key)
            } catch (error: JSONException) {
                throw IllegalArgumentException(
                    "weather response missing field: $key",
                    error
                )
            }

        require(value is JSONObject) {
            "weather field is not an object: $key"
        }

        return value
    }
}
