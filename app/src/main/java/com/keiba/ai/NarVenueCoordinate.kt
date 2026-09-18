package com.keiba.ai

/**
 * Requested venue coordinates for ForecastWeather capture / PIT
 * snapshot selection (WGS84).
 *
 * These are **requested** coordinates from the venue config, not
 * Open-Meteo response grid coordinates.
 */
data class NarVenueCoordinate(
    val latitude: Double,
    val longitude: Double
) {
    init {
        require(
            latitude.isFinite() &&
                latitude in -90.0..90.0
        ) {
            "invalid latitude"
        }

        require(
            longitude.isFinite() &&
                longitude in -180.0..180.0
        ) {
            "invalid longitude"
        }
    }
}
