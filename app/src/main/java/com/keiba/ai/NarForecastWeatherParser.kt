package com.keiba.ai

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Collections

/**
 * Pure ForecastWeather raw JSON → typed immutable payload.
 *
 * Does not perform network I/O, file I/O, current-time reads,
 * prediction_as_of selection, or archive selection.
 *
 * Does not invent forecast issuance/vintage timestamps.
 * Does not mutate or re-canonicalize raw evidence bytes.
 *
 * Structural / token checks in
 * [NarForecastWeatherDownloader.validateResponseBytes] remain a
 * coarse download gate; this parser owns typed meaning validation.
 */
object NarForecastWeatherParser {

    private val HOURLY_NUMERIC_FIELDS =
        listOf(
            "temperature_2m",
            "relative_humidity_2m",
            "pressure_msl",
            "surface_pressure",
            "precipitation",
            "weather_code",
            "wind_speed_10m",
            "wind_direction_10m",
            "wind_gusts_10m"
        )

    /**
     * Security bound against pathological hourly arrays that still
     * fit under [NarForecastWeatherDownloader.MAX_RESPONSE_BYTES].
     * Not a physical Open-Meteo forecast-length claim.
     */
    internal const val MAX_HOURLY_POINTS =
        10_000

    fun parse(
        rawBytes: ByteArray
    ): NarForecastWeatherPayload {
        require(
            rawBytes.size.toLong() <=
                NarForecastWeatherDownloader
                    .MAX_RESPONSE_BYTES
        ) {
            "weather response too large"
        }

        require(rawBytes.isNotEmpty()) {
            "empty weather response"
        }

        val text =
            decodeUtf8Strict(rawBytes)

        val root =
            try {
                JSONObject(text)
            } catch (error: JSONException) {
                throw IllegalArgumentException(
                    "weather response is not valid JSON",
                    error
                )
            }

        val latitude =
            requireFiniteDouble(
                root,
                "latitude"
            )

        require(latitude in -90.0..90.0) {
            "invalid provider latitude"
        }

        val longitude =
            requireFiniteDouble(
                root,
                "longitude"
            )

        require(longitude in -180.0..180.0) {
            "invalid provider longitude"
        }

        val utcOffsetSeconds =
            requireIntegralInt(
                root,
                "utc_offset_seconds"
            )

        require(utcOffsetSeconds == 0) {
            "weather response UTC offset is not zero"
        }

        val hourlyObject =
            requireObject(
                root,
                "hourly"
            )

        val timeArray =
            requireArray(
                hourlyObject,
                "time"
            )

        val fieldArrays =
            HOURLY_NUMERIC_FIELDS.associateWith {
                requireArray(
                    hourlyObject,
                    it
                )
            }

        val length =
            timeArray.length()

        require(length > 0) {
            "forecast hourly arrays must not be empty"
        }

        require(length <= MAX_HOURLY_POINTS) {
            "forecast hourly array too large"
        }

        for ((name, array) in fieldArrays) {
            require(array.length() == length) {
                "hourly array length mismatch: $name"
            }
        }

        val points =
            ArrayList<NarForecastWeatherHourlyPoint>(
                length
            )

        var previousTarget:
            Long? = null

        for (index in 0 until length) {
            val targetEpochSeconds =
                requireIntegralLongElement(
                    timeArray,
                    index,
                    "time"
                )

            require(targetEpochSeconds > 0L) {
                "forecast target timestamp must be positive"
            }

            val previous =
                previousTarget

            if (previous != null) {
                require(
                    targetEpochSeconds > previous
                ) {
                    "forecast target timestamps must be strictly increasing"
                }
            }

            previousTarget =
                targetEpochSeconds

            val temperature =
                requireFiniteDoubleElement(
                    fieldArrays.getValue(
                        "temperature_2m"
                    ),
                    index,
                    "temperature_2m"
                )

            val humidity =
                requireFiniteDoubleElement(
                    fieldArrays.getValue(
                        "relative_humidity_2m"
                    ),
                    index,
                    "relative_humidity_2m"
                )

            require(humidity in 0.0..100.0) {
                "relative humidity out of range"
            }

            val pressureMsl =
                requireFiniteDoubleElement(
                    fieldArrays.getValue(
                        "pressure_msl"
                    ),
                    index,
                    "pressure_msl"
                )

            require(pressureMsl > 0.0) {
                "pressure_msl must be positive"
            }

            val surfacePressure =
                requireFiniteDoubleElement(
                    fieldArrays.getValue(
                        "surface_pressure"
                    ),
                    index,
                    "surface_pressure"
                )

            require(surfacePressure > 0.0) {
                "surface_pressure must be positive"
            }

            val precipitation =
                requireFiniteDoubleElement(
                    fieldArrays.getValue(
                        "precipitation"
                    ),
                    index,
                    "precipitation"
                )

            require(precipitation >= 0.0) {
                "precipitation must be non-negative"
            }

            val weatherCode =
                requireIntegralIntElement(
                    fieldArrays.getValue(
                        "weather_code"
                    ),
                    index,
                    "weather_code"
                )

            val windSpeed =
                requireFiniteDoubleElement(
                    fieldArrays.getValue(
                        "wind_speed_10m"
                    ),
                    index,
                    "wind_speed_10m"
                )

            require(windSpeed >= 0.0) {
                "wind_speed_10m must be non-negative"
            }

            val windDirection =
                requireFiniteDoubleElement(
                    fieldArrays.getValue(
                        "wind_direction_10m"
                    ),
                    index,
                    "wind_direction_10m"
                )

            require(
                windDirection in 0.0..360.0
            ) {
                "wind_direction_10m out of range"
            }

            val windGusts =
                requireFiniteDoubleElement(
                    fieldArrays.getValue(
                        "wind_gusts_10m"
                    ),
                    index,
                    "wind_gusts_10m"
                )

            require(windGusts >= 0.0) {
                "wind_gusts_10m must be non-negative"
            }

            points.add(
                NarForecastWeatherHourlyPoint(
                    targetEpochSeconds =
                        targetEpochSeconds,
                    temperature2mCelsius =
                        temperature,
                    relativeHumidity2mPercent =
                        humidity,
                    pressureMslHpa =
                        pressureMsl,
                    surfacePressureHpa =
                        surfacePressure,
                    precipitationMm =
                        precipitation,
                    weatherCode =
                        weatherCode,
                    windSpeed10mMetersPerSecond =
                        windSpeed,
                    windDirection10mDegrees =
                        windDirection,
                    windGusts10mMetersPerSecond =
                        windGusts
                )
            )
        }

        return NarForecastWeatherPayload(
            providerLatitude = latitude,
            providerLongitude = longitude,
            utcOffsetSeconds = utcOffsetSeconds,
            hourly = Collections.unmodifiableList(points)
        )
    }

    private fun decodeUtf8Strict(
        bytes: ByteArray
    ): String {
        val decoder =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(
                    CodingErrorAction.REPORT
                )
                .onUnmappableCharacter(
                    CodingErrorAction.REPORT
                )

        return try {
            decoder.decode(
                ByteBuffer.wrap(bytes)
            ).toString()
        } catch (error: java.nio.charset.CharacterCodingException) {
            throw IllegalArgumentException(
                "weather response is not valid UTF-8",
                error
            )
        }
    }

    private fun requireObject(
        parent: JSONObject,
        key: String
    ): JSONObject {
        require(!parent.isNull(key)) {
            "weather response missing field: $key"
        }

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

    private fun requireArray(
        parent: JSONObject,
        key: String
    ): JSONArray {
        require(!parent.isNull(key)) {
            "weather response missing field: $key"
        }

        val value =
            try {
                parent.get(key)
            } catch (error: JSONException) {
                throw IllegalArgumentException(
                    "weather response missing field: $key",
                    error
                )
            }

        require(value is JSONArray) {
            "weather field is not an array: $key"
        }

        return value
    }

    private fun requireFiniteDouble(
        parent: JSONObject,
        key: String
    ): Double {
        require(!parent.isNull(key)) {
            "weather response missing field: $key"
        }

        val value =
            try {
                parent.get(key)
            } catch (error: JSONException) {
                throw IllegalArgumentException(
                    "weather response missing field: $key",
                    error
                )
            }

        return requireFiniteNumber(
            value,
            key
        )
    }

    private fun requireIntegralInt(
        parent: JSONObject,
        key: String
    ): Int {
        require(!parent.isNull(key)) {
            "weather response missing field: $key"
        }

        val value =
            try {
                parent.get(key)
            } catch (error: JSONException) {
                throw IllegalArgumentException(
                    "weather response missing field: $key",
                    error
                )
            }

        val asLong =
            requireIntegralNumber(
                value,
                key
            )

        require(
            asLong in
                Int.MIN_VALUE.toLong()..
                    Int.MAX_VALUE.toLong()
        ) {
            "weather integer out of Int range: $key"
        }

        return asLong.toInt()
    }

    private fun requireFiniteDoubleElement(
        array: JSONArray,
        index: Int,
        label: String
    ): Double {
        require(!array.isNull(index)) {
            "weather array contains null: $label"
        }

        val value =
            try {
                array.get(index)
            } catch (error: JSONException) {
                throw IllegalArgumentException(
                    "weather array element missing: $label",
                    error
                )
            }

        return requireFiniteNumber(
            value,
            label
        )
    }

    private fun requireIntegralLongElement(
        array: JSONArray,
        index: Int,
        label: String
    ): Long {
        require(!array.isNull(index)) {
            "weather array contains null: $label"
        }

        val value =
            try {
                array.get(index)
            } catch (error: JSONException) {
                throw IllegalArgumentException(
                    "weather array element missing: $label",
                    error
                )
            }

        return requireIntegralNumber(
            value,
            label
        )
    }

    private fun requireIntegralIntElement(
        array: JSONArray,
        index: Int,
        label: String
    ): Int {
        val asLong =
            requireIntegralLongElement(
                array,
                index,
                label
            )

        require(
            asLong in
                Int.MIN_VALUE.toLong()..
                    Int.MAX_VALUE.toLong()
        ) {
            "weather integer out of Int range: $label"
        }

        return asLong.toInt()
    }

    private fun requireFiniteNumber(
        value: Any?,
        label: String
    ): Double {
        val number =
            when (value) {
                is Int ->
                    value.toDouble()

                is Long ->
                    value.toDouble()

                is Float ->
                    value.toDouble()

                is Double ->
                    value

                is Number ->
                    value.toDouble()

                else ->
                    throw IllegalArgumentException(
                        "weather value is not numeric: $label"
                    )
            }

        require(number.isFinite()) {
            "weather value is not finite: $label"
        }

        return number
    }

    private fun requireIntegralNumber(
        value: Any?,
        label: String
    ): Long {
        when (value) {
            is Int ->
                return value.toLong()

            is Long ->
                return value

            is Float -> {
                require(value.isFinite()) {
                    "weather value is not finite: $label"
                }

                require(
                    value % 1.0f == 0.0f
                ) {
                    "weather value is not an integer: $label"
                }

                return value.toLong()
            }

            is Double -> {
                require(value.isFinite()) {
                    "weather value is not finite: $label"
                }

                require(
                    value % 1.0 == 0.0
                ) {
                    "weather value is not an integer: $label"
                }

                require(
                    value in
                        Long.MIN_VALUE.toDouble()..
                            Long.MAX_VALUE.toDouble()
                ) {
                    "weather integer overflow: $label"
                }

                return value.toLong()
            }

            is Number -> {
                val asDouble =
                    value.toDouble()

                require(asDouble.isFinite()) {
                    "weather value is not finite: $label"
                }

                require(
                    asDouble % 1.0 == 0.0
                ) {
                    "weather value is not an integer: $label"
                }

                return value.toLong()
            }

            else ->
                throw IllegalArgumentException(
                    "weather value is not numeric: $label"
                )
        }
    }
}
