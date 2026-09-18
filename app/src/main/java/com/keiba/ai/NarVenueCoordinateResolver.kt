package com.keiba.ai

import com.keiba.ai.model.RaceKey
import java.time.DateTimeException
import java.time.LocalDate

/**
 * Resolves RaceKey track + race date to requested Weather coordinates.
 *
 * Pure / deterministic. Does not download forecasts, touch Android
 * Context, or invent venue aliases.
 *
 * Venue identity is an exact string match against config keys.
 * Unknown venue or uncovered date → `null` (no silent fallback).
 * Ambiguous multi-rule coverage → fail-closed (should already be
 * rejected at config parse time via overlap checks).
 */
object NarVenueCoordinateResolver {

    fun resolve(
        config: NarVenueCoordinateConfig,
        raceKey: RaceKey
    ): NarVenueCoordinate? =
        resolve(
            config = config,
            track = raceKey.track,
            raceDateYyyymmdd =
                raceKey.date
        )

    fun resolve(
        config: NarVenueCoordinateConfig,
        track: String,
        raceDateYyyymmdd: Int
    ): NarVenueCoordinate? {
        require(track.isNotEmpty()) {
            "invalid venue track"
        }

        val raceDate =
            parseRaceDate(
                raceDateYyyymmdd
            )

        val rules =
            config.rulesByVenue[track]
                ?: return null

        var match:
            NarVenueCoordinate? = null

        for (rule in rules) {
            if (!rule.covers(raceDate)) {
                continue
            }

            if (match != null) {
                error(
                    "ambiguous venue coordinate rules for track/date"
                )
            }

            match =
                rule.coordinate
        }

        return match
    }

    private fun parseRaceDate(
        raceDateYyyymmdd: Int
    ): LocalDate {
        require(
            raceDateYyyymmdd in
                1_000_0101..9_999_1231
        ) {
            "invalid race date"
        }

        val year =
            raceDateYyyymmdd / 10_000

        val month =
            (raceDateYyyymmdd / 100) %
                100

        val day =
            raceDateYyyymmdd % 100

        return try {
            LocalDate.of(
                year,
                month,
                day
            )
        } catch (error: DateTimeException) {
            throw IllegalArgumentException(
                "invalid race date",
                error
            )
        }
    }
}
