package com.keiba.ai

import java.time.LocalDate

/**
 * Typed LIVE_CAPTURED_OBSERVED station mapping loaded from the
 * packaged single Source of Truth asset.
 *
 * Feature eligibility remains NOT_YET_AUTHORIZED — this config
 * alone does not authorize production model features.
 */
data class NarJmaObservedLiveStationMapping(
    val version: Int,
    val status: String,
    val role: String,
    val acquisitionStrategy: String,
    val pitStatus: String,
    val featureEligibility: String,
    val mappingEffectiveFrom: LocalDate,
    val mappingScope: String,
    val venues: Map<String, NarJmaObservedLiveVenueMapping>
)

data class NarJmaObservedLiveVenueMapping(
    val venue: String,
    val venuePeriodFrom: LocalDate?,
    val venuePeriodTo: LocalDate?,
    val productMappings:
        Map<
            NarJmaObservedWeatherProduct.Family,
            NarJmaObservedLiveProductStationMapping
        >
)

data class NarJmaObservedLiveProductStationMapping(
    val family: NarJmaObservedWeatherProduct.Family,
    val stationId: String,
    val stationName: String,
    val stationEffectiveFrom: LocalDate?,
    val stationEffectiveTo: LocalDate?,
    val productIds: Set<String>,
    val automaticFallback: Boolean
)

data class NarJmaObservedLiveStationSelection(
    val venue: String,
    val product: NarJmaObservedWeatherProduct,
    val family: NarJmaObservedWeatherProduct.Family,
    val stationId: String,
    val stationName: String
)
