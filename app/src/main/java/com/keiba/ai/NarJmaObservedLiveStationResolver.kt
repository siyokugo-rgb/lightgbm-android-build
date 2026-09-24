package com.keiba.ai

import com.keiba.ai.model.RaceKey
import java.time.DateTimeException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/**
 * Pure venue/product → station_id resolver for LIVE mapping.
 *
 * No automatic fallback. Prospective only
 * (`mapping_effective_from` and station/venue periods).
 */
object NarJmaObservedLiveStationResolver {

    private val basicDateFormatter =
        DateTimeFormatter.BASIC_ISO_DATE
            .withResolverStyle(
                ResolverStyle.STRICT
            )

    fun resolve(
        mapping: NarJmaObservedLiveStationMapping,
        raceKey: RaceKey,
        product: NarJmaObservedWeatherProduct
    ): NarJmaObservedLiveStationSelection? {
        val raceDate =
            parseRaceDate(
                raceKey.date
            )
                ?: return null

        if (
            raceDate.isBefore(
                mapping.mappingEffectiveFrom
            )
        ) {
            return null
        }

        val venueMapping =
            mapping.venues[raceKey.track]
                ?: return null

        if (
            !covers(
                raceDate,
                venueMapping.venuePeriodFrom,
                venueMapping.venuePeriodTo
            )
        ) {
            return null
        }

        val productMapping =
            venueMapping.productMappings[
                product.family
            ]
                ?: return null

        if (
            product.productId !in
                productMapping.productIds
        ) {
            return null
        }

        if (productMapping.automaticFallback) {
            return null
        }

        if (
            !covers(
                raceDate,
                productMapping.stationEffectiveFrom,
                productMapping.stationEffectiveTo
            )
        ) {
            return null
        }

        return NarJmaObservedLiveStationSelection(
            venue = raceKey.track,
            product = product,
            family = product.family,
            stationId =
                productMapping.stationId,
            stationName =
                productMapping.stationName
        )
    }

    fun familyFor(
        product: NarJmaObservedWeatherProduct
    ): NarJmaObservedWeatherProduct.Family =
        product.family

    private fun parseRaceDate(
        yyyymmdd: Int
    ): LocalDate? {
        if (yyyymmdd !in 10000101..99991231) {
            return null
        }

        val text =
            yyyymmdd.toString()

        if (text.length != 8) {
            return null
        }

        return try {
            LocalDate.parse(
                text,
                basicDateFormatter
            )
        } catch (_: DateTimeException) {
            null
        }
    }

    private fun covers(
        date: LocalDate,
        from: LocalDate?,
        to: LocalDate?
    ): Boolean {
        if (
            from != null &&
            date.isBefore(from)
        ) {
            return false
        }

        if (
            to != null &&
            date.isAfter(to)
        ) {
            return false
        }

        return true
    }
}
