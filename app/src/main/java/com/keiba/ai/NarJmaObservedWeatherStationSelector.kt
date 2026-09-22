package com.keiba.ai

/**
 * Exact station_id row selection only.
 * No alias, name match, nearest, or fallback.
 */
object NarJmaObservedWeatherStationSelector {

    private val STATION_ID_PATTERN =
        Regex("""^\d+$""")

    fun select(
        payload: NarJmaObservedWeatherPayload,
        stationId: String
    ): NarJmaObservedWeatherRow? {
        require(
            STATION_ID_PATTERN.matches(
                stationId.trim()
            )
        ) {
            "invalid station_id for selection"
        }

        val matches =
            payload.rows.filter {
                it.stationId == stationId.trim()
            }

        return when (matches.size) {
            0 -> null
            1 -> matches.first()
            else ->
                throw IllegalArgumentException(
                    "ambiguous station_id in observed CSV payload"
                )
        }
    }
}
