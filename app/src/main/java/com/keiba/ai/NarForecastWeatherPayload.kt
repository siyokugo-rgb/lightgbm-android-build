package com.keiba.ai

/**
 * Typed immutable interpretation of an Open-Meteo ForecastWeather
 * raw JSON body.
 *
 * This is not PIT availability evidence. Target times below are
 * forecast target timestamps, not downloaded_at / server_date /
 * pit_evidence_at / issued_at.
 *
 * Units are fixed by the R4 request contract
 * ([NarForecastWeatherDownloader.buildRequestUrl]):
 * temperature=celsius, wind=ms, precipitation=mm, time=unixtime,
 * timezone/offset=GMT (utc_offset_seconds=0).
 * Response `hourly_units` strings are not strict-validated in this
 * Step because R4 archive fixtures do not pin those strings.
 */
data class NarForecastWeatherPayload(
    val providerLatitude: Double,
    val providerLongitude: Double,
    val utcOffsetSeconds: Int,
    val hourly: List<NarForecastWeatherHourlyPoint>
) {
    init {
        require(hourly.isNotEmpty()) {
            "forecast hourly points must not be empty"
        }
    }
}

/**
 * One hourly forecast target. Property names encode units.
 */
data class NarForecastWeatherHourlyPoint(
    val targetEpochSeconds: Long,
    val temperature2mCelsius: Double,
    val relativeHumidity2mPercent: Double,
    val pressureMslHpa: Double,
    val surfacePressureHpa: Double,
    val precipitationMm: Double,
    val weatherCode: Int,
    val windSpeed10mMetersPerSecond: Double,
    val windDirection10mDegrees: Double,
    val windGusts10mMetersPerSecond: Double
)
