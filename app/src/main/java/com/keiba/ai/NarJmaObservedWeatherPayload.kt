package com.keiba.ai

sealed interface NarJmaObservedWeatherRow {
    val stationId: String
    val prefecture: String
    val stationName: String
    val internationalStationId: String?
    val productTimestampEpochMillis: Long
}

data class NarJmaObservedWeatherPayload(
    val product: NarJmaObservedWeatherProduct,
    val rows: List<NarJmaObservedWeatherRow>
)

data class NarJmaObservedPrecipitationRow(
    override val stationId: String,
    override val prefecture: String,
    override val stationName: String,
    override val internationalStationId: String?,
    override val productTimestampEpochMillis: Long,
    val windowHours: Int,
    val currentValueMm: Double?,
    val currentValueQuality: NarJmaObservedLatestQuality?,
    val dailyMaxValueMm: Double?,
    val dailyMaxQuality: NarJmaObservedLatestQuality?,
    val dailyMaxOccurrenceHour: Int?,
    val dailyMaxOccurrenceMinute: Int?,
    val dailyMaxOccurrenceQuality: NarJmaObservedLatestQuality?
) : NarJmaObservedWeatherRow

enum class NarJmaObservedTemperatureExtremeType {
    MAX,
    MIN
}

data class NarJmaObservedTemperatureExtremeRow(
    override val stationId: String,
    override val prefecture: String,
    override val stationName: String,
    override val internationalStationId: String?,
    override val productTimestampEpochMillis: Long,
    val extremeType: NarJmaObservedTemperatureExtremeType,
    val dailyExtremeCelsius: Double?,
    val dailyExtremeQuality: NarJmaObservedLatestQuality?,
    val occurrenceHour: Int?,
    val occurrenceMinute: Int?,
    val occurrenceQuality: NarJmaObservedLatestQuality?
) : NarJmaObservedWeatherRow

enum class NarJmaObservedWindExtremeKind {
    MAX_WIND,
    MAX_GUST
}

data class NarJmaObservedWindExtremeRow(
    override val stationId: String,
    override val prefecture: String,
    override val stationName: String,
    override val internationalStationId: String?,
    override val productTimestampEpochMillis: Long,
    val kind: NarJmaObservedWindExtremeKind,
    val dailyMaxMetersPerSecond: Double?,
    val dailyMaxQuality: NarJmaObservedLatestQuality?,
    val directionRaw: String?,
    val directionQuality: NarJmaObservedLatestQuality?,
    val occurrenceHour: Int?,
    val occurrenceMinute: Int?,
    val occurrenceQuality: NarJmaObservedLatestQuality?
) : NarJmaObservedWeatherRow
