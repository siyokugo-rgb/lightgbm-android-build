package com.keiba.ai

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.DateTimeException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/**
 * Parsed [config/nar-v3-venue-coordinates.json] content.
 *
 * Source of Truth remains the JSON file in the repository.
 * This type is an immutable interpretation of config bytes — it does
 * not embed coordinates by hand and does not invent venue aliases.
 *
 * Android runtime asset wiring is intentionally out of scope for
 * Step E; callers supply already-loaded config bytes/text.
 */
data class NarVenueCoordinateConfig(
    val version: Int,
    val coordinateSystem: String,
    val rulesByVenue:
        Map<String, List<NarVenueCoordinateRule>>
) {
    init {
        require(version == 1) {
            "unsupported venue coordinate config version"
        }

        require(
            coordinateSystem ==
                "WGS84"
        ) {
            "unsupported venue coordinate system"
        }

        require(rulesByVenue.isNotEmpty()) {
            "venue coordinate config has no venues"
        }
    }
}

/**
 * One date-bounded coordinate rule.
 *
 * [fromInclusive] / [toInclusive] use ISO-8601 calendar dates.
 * `null` means unbounded on that side. Both bounds are inclusive.
 */
data class NarVenueCoordinateRule(
    val fromInclusive: LocalDate?,
    val toInclusive: LocalDate?,
    val coordinate: NarVenueCoordinate
) {
    init {
        val from =
            fromInclusive

        val to =
            toInclusive

        if (from != null && to != null) {
            require(!from.isAfter(to)) {
                "venue coordinate rule from > to"
            }
        }
    }

    fun covers(
        date: LocalDate
    ): Boolean {
        val from =
            fromInclusive

        if (
            from != null &&
            date.isBefore(from)
        ) {
            return false
        }

        val to =
            toInclusive

        if (
            to != null &&
            date.isAfter(to)
        ) {
            return false
        }

        return true
    }
}

/**
 * Pure JSON → [NarVenueCoordinateConfig] parser.
 *
 * Uses Android platform `org.json` (same family as Forecast parser).
 * No filesystem access.
 */
object NarVenueCoordinateConfigParser {

    internal const val MAX_CONFIG_BYTES =
        256L * 1024L

    private val isoDateFormatter =
        DateTimeFormatter.ISO_LOCAL_DATE
            .withResolverStyle(
                ResolverStyle.STRICT
            )

    fun parse(
        rawBytes: ByteArray
    ): NarVenueCoordinateConfig {
        require(
            rawBytes.size.toLong() <=
                MAX_CONFIG_BYTES
        ) {
            "venue coordinate config too large"
        }

        require(rawBytes.isNotEmpty()) {
            "empty venue coordinate config"
        }

        val text =
            decodeUtf8Strict(rawBytes)

        return parse(text)
    }

    fun parse(
        text: String
    ): NarVenueCoordinateConfig {
        val root =
            try {
                JSONObject(text)
            } catch (error: JSONException) {
                throw IllegalArgumentException(
                    "venue coordinate config is not valid JSON",
                    error
                )
            }

        val version =
            requireIntegralInt(
                root,
                "version"
            )

        require(version == 1) {
            "unsupported venue coordinate config version"
        }

        require(!root.isNull("coordinate_system")) {
            "venue coordinate config missing field: coordinate_system"
        }

        val coordinateSystem =
            try {
                root.getString(
                    "coordinate_system"
                )
            } catch (error: JSONException) {
                throw IllegalArgumentException(
                    "venue coordinate_system is not a string",
                    error
                )
            }

        require(
            coordinateSystem ==
                "WGS84"
        ) {
            "unsupported venue coordinate system"
        }

        require(!root.isNull("venues")) {
            "venue coordinate config missing field: venues"
        }

        val venuesObject =
            try {
                root.getJSONObject(
                    "venues"
                )
            } catch (error: JSONException) {
                throw IllegalArgumentException(
                    "venues is not an object",
                    error
                )
            }

        val venueNames =
            venuesObject.keys()
                .asSequence()
                .toList()

        require(venueNames.isNotEmpty()) {
            "venue coordinate config has no venues"
        }

        val rulesByVenue =
            linkedMapOf<String, List<NarVenueCoordinateRule>>()

        for (venueName in venueNames) {
            require(venueName.isNotEmpty()) {
                "empty venue name"
            }

            require(!venuesObject.isNull(venueName)) {
                "venue rules must not be null: $venueName"
            }

            val rulesArray =
                try {
                    venuesObject.getJSONArray(
                        venueName
                    )
                } catch (error: JSONException) {
                    throw IllegalArgumentException(
                        "venue rules must be an array: $venueName",
                        error
                    )
                }

            require(rulesArray.length() > 0) {
                "venue rules must not be empty: $venueName"
            }

            val rules =
                ArrayList<NarVenueCoordinateRule>(
                    rulesArray.length()
                )

            for (index in 0 until rulesArray.length()) {
                require(!rulesArray.isNull(index)) {
                    "venue rule must not be null: $venueName"
                }

                val ruleObject =
                    try {
                        rulesArray.getJSONObject(
                            index
                        )
                    } catch (error: JSONException) {
                        throw IllegalArgumentException(
                            "venue rule must be an object: $venueName",
                            error
                        )
                    }

                rules.add(
                    parseRule(
                        venueName =
                            venueName,
                        ruleObject =
                            ruleObject
                    )
                )
            }

            requireNoOverlaps(
                venueName = venueName,
                rules = rules
            )

            rulesByVenue[venueName] =
                rules.toList()
        }

        return NarVenueCoordinateConfig(
            version = version,
            coordinateSystem =
                coordinateSystem,
            rulesByVenue =
                rulesByVenue.toMap()
        )
    }

    private fun parseRule(
        venueName: String,
        ruleObject: JSONObject
    ): NarVenueCoordinateRule {
        val from =
            optionalIsoDate(
                ruleObject,
                "from"
            )

        val to =
            optionalIsoDate(
                ruleObject,
                "to"
            )

        if (from != null && to != null) {
            require(!from.isAfter(to)) {
                "venue coordinate rule from > to: $venueName"
            }
        }

        val latitude =
            requireFiniteDouble(
                ruleObject,
                "latitude"
            )

        require(latitude in -90.0..90.0) {
            "invalid venue latitude: $venueName"
        }

        val longitude =
            requireFiniteDouble(
                ruleObject,
                "longitude"
            )

        require(longitude in -180.0..180.0) {
            "invalid venue longitude: $venueName"
        }

        return NarVenueCoordinateRule(
            fromInclusive = from,
            toInclusive = to,
            coordinate =
                NarVenueCoordinate(
                    latitude = latitude,
                    longitude = longitude
                )
        )
    }

    private fun requireNoOverlaps(
        venueName: String,
        rules: List<NarVenueCoordinateRule>
    ) {
        for (i in rules.indices) {
            for (j in i + 1 until rules.size) {
                if (
                    rangesOverlap(
                        rules[i],
                        rules[j]
                    )
                ) {
                    throw IllegalArgumentException(
                        "overlapping venue coordinate rules: $venueName"
                    )
                }
            }
        }
    }

    private fun rangesOverlap(
        left: NarVenueCoordinateRule,
        right: NarVenueCoordinateRule
    ): Boolean {
        val leftFrom =
            left.fromInclusive
                ?: LocalDate.MIN

        val leftTo =
            left.toInclusive
                ?: LocalDate.MAX

        val rightFrom =
            right.fromInclusive
                ?: LocalDate.MIN

        val rightTo =
            right.toInclusive
                ?: LocalDate.MAX

        return !leftFrom.isAfter(rightTo) &&
            !rightFrom.isAfter(leftTo)
    }

    private fun optionalIsoDate(
        parent: JSONObject,
        key: String
    ): LocalDate? {
        if (!parent.has(key) || parent.isNull(key)) {
            return null
        }

        val raw =
            try {
                parent.get(key)
            } catch (error: JSONException) {
                throw IllegalArgumentException(
                    "invalid venue date field: $key",
                    error
                )
            }

        require(raw is String) {
            "venue date field must be string or null: $key"
        }

        return try {
            LocalDate.parse(
                raw,
                isoDateFormatter
            )
        } catch (error: DateTimeException) {
            throw IllegalArgumentException(
                "malformed venue date field: $key",
                error
            )
        }
    }

    private fun requireFiniteDouble(
        parent: JSONObject,
        key: String
    ): Double {
        require(!parent.isNull(key)) {
            "venue coordinate config missing field: $key"
        }

        val value =
            try {
                parent.get(key)
            } catch (error: JSONException) {
                throw IllegalArgumentException(
                    "venue coordinate config missing field: $key",
                    error
                )
            }

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
                        "venue coordinate value is not numeric: $key"
                    )
            }

        require(number.isFinite()) {
            "venue coordinate value is not finite: $key"
        }

        return number
    }

    private fun requireIntegralInt(
        parent: JSONObject,
        key: String
    ): Int {
        require(!parent.isNull(key)) {
            "venue coordinate config missing field: $key"
        }

        val value =
            try {
                parent.get(key)
            } catch (error: JSONException) {
                throw IllegalArgumentException(
                    "venue coordinate config missing field: $key",
                    error
                )
            }

        when (value) {
            is Int ->
                return value

            is Long -> {
                require(
                    value in
                        Int.MIN_VALUE.toLong()..
                            Int.MAX_VALUE.toLong()
                ) {
                    "venue integer out of Int range: $key"
                }

                return value.toInt()
            }

            is Double -> {
                require(value.isFinite()) {
                    "venue value is not finite: $key"
                }

                require(value % 1.0 == 0.0) {
                    "venue value is not an integer: $key"
                }

                return value.toInt()
            }

            is Number -> {
                val asDouble =
                    value.toDouble()

                require(asDouble.isFinite()) {
                    "venue value is not finite: $key"
                }

                require(asDouble % 1.0 == 0.0) {
                    "venue value is not an integer: $key"
                }

                return value.toInt()
            }

            else ->
                throw IllegalArgumentException(
                    "venue value is not numeric: $key"
                )
        }
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
                "venue coordinate config is not valid UTF-8",
                error
            )
        }
    }
}
