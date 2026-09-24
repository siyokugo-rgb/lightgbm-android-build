package com.keiba.ai

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
 * Parses and validates
 * `config/nar-jma-observed-live-station-mapping.json`.
 */
object NarJmaObservedLiveStationMappingParser {

    internal const val MAX_CONFIG_BYTES =
        256L * 1024L

    private val EXPECTED_VENUES =
        listOf(
            "帯広ば",
            "園田",
            "名古屋",
            "佐賀",
            "高知",
            "大井",
            "金沢",
            "門別",
            "笠松",
            "水沢",
            "川崎",
            "盛岡",
            "船橋",
            "浦和",
            "姫路"
        )

    private val FAMILY_KEYS =
        mapOf(
            "precipitation" to
                NarJmaObservedWeatherProduct.Family
                    .PRECIPITATION,
            "temperature_extreme" to
                NarJmaObservedWeatherProduct.Family
                    .TEMPERATURE_EXTREME,
            "wind_max" to
                NarJmaObservedWeatherProduct.Family
                    .WIND_MAX,
            "gust_max" to
                NarJmaObservedWeatherProduct.Family
                    .GUST_MAX
        )

    private val isoDateFormatter =
        DateTimeFormatter.ISO_LOCAL_DATE
            .withResolverStyle(
                ResolverStyle.STRICT
            )

    private val stationIdPattern =
        Regex("""^\d+$""")

    fun parse(
        rawBytes: ByteArray
    ): NarJmaObservedLiveStationMapping {
        require(
            rawBytes.isNotEmpty()
        ) {
            "empty observed live station mapping"
        }

        require(
            rawBytes.size.toLong() <=
                MAX_CONFIG_BYTES
        ) {
            "observed live station mapping too large"
        }

        val text =
            decodeUtf8Strict(rawBytes)

        val root =
            try {
                JSONObject(text)
            } catch (error: JSONException) {
                throw IllegalArgumentException(
                    "observed live station mapping is not valid JSON",
                    error
                )
            }

        val version =
            root.getIntRequired(
                "version"
            )

        require(version == 1) {
            "unsupported observed live station mapping version"
        }

        val status =
            root.getStringRequired(
                "status"
            )

        require(
            status ==
                "LIVE_CAPTURE_MAPPING"
        ) {
            "unexpected observed live station mapping status"
        }

        val role =
            root.getStringRequired(
                "role"
            )

        require(
            role ==
                "LIVE_CAPTURED_OBSERVED"
        ) {
            "unexpected observed live station mapping role"
        }

        val strategy =
            root.getStringRequired(
                "acquisition_strategy"
            )

        require(
            strategy ==
                "PUBLIC_ONLY_WITH_DEFERRED_GAPS"
        ) {
            "unexpected observed acquisition_strategy"
        }

        val pitStatus =
            root.getStringRequired(
                "pit_status"
            )

        require(
            pitStatus ==
                "CANDIDATE"
        ) {
            "unexpected observed pit_status"
        }

        val featureEligibility =
            root.getStringRequired(
                "feature_eligibility"
            )

        require(
            featureEligibility ==
                "NOT_YET_AUTHORIZED"
        ) {
            "unexpected observed feature_eligibility"
        }

        val mappingScope =
            root.getStringRequired(
                "mapping_scope"
            )

        require(
            mappingScope ==
                "prospective_live_capture_only"
        ) {
            "unexpected observed mapping_scope"
        }

        val mappingEffectiveFrom =
            parseRequiredDate(
                root.getStringRequired(
                    "mapping_effective_from"
                ),
                "mapping_effective_from"
            )

        val selectionPolicy =
            root.getJSONObjectRequired(
                "selection_policy"
            )

        require(
            !selectionPolicy.getBoolean(
                "automatic_fallback"
            )
        ) {
            "observed automatic_fallback must be false"
        }

        require(
            !selectionPolicy.getBoolean(
                "multi_station_fusion"
            )
        ) {
            "observed multi_station_fusion must be false"
        }

        val venuesObject =
            root.getJSONObjectRequired(
                "venues"
            )

        val venueNames =
            venuesObject.keys()
                .asSequence()
                .toList()

        require(
            venueNames.toSet() ==
                EXPECTED_VENUES.toSet() &&
                venueNames.size == 15
        ) {
            "observed live station mapping venues must be exact 15"
        }

        val venues =
            linkedMapOf<
                String,
                NarJmaObservedLiveVenueMapping
            >()

        for (venue in EXPECTED_VENUES) {
            val venueObject =
                venuesObject
                    .getJSONObjectRequired(
                        venue
                    )

            venues[venue] =
                parseVenue(
                    venue,
                    venueObject
                )
        }

        return NarJmaObservedLiveStationMapping(
            version = version,
            status = status,
            role = role,
            acquisitionStrategy =
                strategy,
            pitStatus = pitStatus,
            featureEligibility =
                featureEligibility,
            mappingEffectiveFrom =
                mappingEffectiveFrom,
            mappingScope =
                mappingScope,
            venues = venues
        )
    }

    private fun parseVenue(
        venue: String,
        venueObject: JSONObject
    ): NarJmaObservedLiveVenueMapping {
        val period =
            venueObject
                .getJSONObjectRequired(
                    "venue_period"
                )

        val venueFrom =
            parseOptionalDate(
                period.optStringNullable(
                    "from"
                ),
                "venue_period.from"
            )

        val venueTo =
            parseOptionalDate(
                period.optStringNullable(
                    "to"
                ),
                "venue_period.to"
            )

        requireDateOrder(
            venueFrom,
            venueTo,
            "venue_period"
        )

        val productMappingsObject =
            venueObject
                .getJSONObjectRequired(
                    "product_mappings"
                )

        val familyKeys =
            productMappingsObject.keys()
                .asSequence()
                .toSet()

        require(
            familyKeys ==
                FAMILY_KEYS.keys
        ) {
            "observed product_mappings families mismatch for $venue"
        }

        val productMappings =
            linkedMapOf<
                NarJmaObservedWeatherProduct.Family,
                NarJmaObservedLiveProductStationMapping
            >()

        for ((key, family) in FAMILY_KEYS) {
            productMappings[family] =
                parseProductMapping(
                    family,
                    productMappingsObject
                        .getJSONObjectRequired(
                            key
                        )
                )
        }

        return NarJmaObservedLiveVenueMapping(
            venue = venue,
            venuePeriodFrom =
                venueFrom,
            venuePeriodTo =
                venueTo,
            productMappings =
                productMappings
        )
    }

    private fun parseProductMapping(
        family: NarJmaObservedWeatherProduct.Family,
        mapping: JSONObject
    ): NarJmaObservedLiveProductStationMapping {
        require(
            mapping.getStringRequired(
                "status"
            ) == "SELECTED"
        ) {
            "observed product mapping status must be SELECTED"
        }

        val stationId =
            mapping.getStringRequired(
                "station_id"
            )

        require(
            stationIdPattern.matches(
                stationId
            )
        ) {
            "invalid observed station_id"
        }

        val stationName =
            mapping.getStringRequired(
                "station_name"
            )

        require(
            stationName.isNotBlank()
        ) {
            "blank observed station_name"
        }

        val stationFrom =
            parseOptionalDate(
                mapping.optStringNullable(
                    "station_effective_from"
                ),
                "station_effective_from"
            )

        val stationTo =
            parseOptionalDate(
                mapping.optStringNullable(
                    "station_effective_to"
                ),
                "station_effective_to"
            )

        requireDateOrder(
            stationFrom,
            stationTo,
            "station period"
        )

        require(
            !mapping.getBoolean(
                "automatic_fallback"
            )
        ) {
            "observed automatic_fallback must be false"
        }

        val productIdsArray =
            mapping.getJSONArray(
                "product_ids"
            )

        require(
            productIdsArray.length() > 0
        ) {
            "observed product_ids empty"
        }

        val productIds =
            linkedSetOf<String>()

        for (index in 0 until productIdsArray.length()) {
            val productId =
                productIdsArray
                    .getString(index)
                    .trim()

            require(
                productId.isNotEmpty()
            ) {
                "blank observed product_id"
            }

            require(
                NarJmaObservedWeatherProduct
                    .isKnownProductId(
                        productId
                    )
            ) {
                "unknown observed product_id: $productId"
            }

            val product =
                NarJmaObservedWeatherProduct
                    .fromProductId(
                        productId
                    )

            require(
                product.family ==
                    family
            ) {
                "observed product_id family mismatch: $productId"
            }

            require(
                productIds.add(
                    productId
                )
            ) {
                "duplicate observed product_id"
            }
        }

        return NarJmaObservedLiveProductStationMapping(
            family = family,
            stationId = stationId,
            stationName = stationName,
            stationEffectiveFrom =
                stationFrom,
            stationEffectiveTo =
                stationTo,
            productIds = productIds,
            automaticFallback = false
        )
    }

    private fun parseRequiredDate(
        text: String,
        label: String
    ): LocalDate =
        try {
            LocalDate.parse(
                text,
                isoDateFormatter
            )
        } catch (_: DateTimeException) {
            throw IllegalArgumentException(
                "invalid $label"
            )
        }

    private fun parseOptionalDate(
        text: String?,
        label: String
    ): LocalDate? {
        if (text == null || text.isEmpty()) {
            return null
        }

        return parseRequiredDate(
            text,
            label
        )
    }

    private fun requireDateOrder(
        from: LocalDate?,
        to: LocalDate?,
        label: String
    ) {
        if (from != null && to != null) {
            require(!from.isAfter(to)) {
                "$label from > to"
            }
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
                "observed live station mapping is not valid UTF-8",
                error
            )
        }
    }

    private fun JSONObject.getIntRequired(
        key: String
    ): Int {
        if (!has(key) || isNull(key)) {
            throw IllegalArgumentException(
                "missing observed mapping field: $key"
            )
        }

        return try {
            getInt(key)
        } catch (error: JSONException) {
            throw IllegalArgumentException(
                "invalid observed mapping field: $key",
                error
            )
        }
    }

    private fun JSONObject.getStringRequired(
        key: String
    ): String {
        if (!has(key) || isNull(key)) {
            throw IllegalArgumentException(
                "missing observed mapping field: $key"
            )
        }

        val value =
            try {
                getString(key).trim()
            } catch (error: JSONException) {
                throw IllegalArgumentException(
                    "invalid observed mapping field: $key",
                    error
                )
            }

        require(value.isNotEmpty()) {
            "blank observed mapping field: $key"
        }

        return value
    }

    private fun JSONObject.getJSONObjectRequired(
        key: String
    ): JSONObject {
        if (!has(key) || isNull(key)) {
            throw IllegalArgumentException(
                "missing observed mapping field: $key"
            )
        }

        return try {
            getJSONObject(key)
        } catch (error: JSONException) {
            throw IllegalArgumentException(
                "invalid observed mapping field: $key",
                error
            )
        }
    }

    private fun JSONObject.optStringNullable(
        key: String
    ): String? {
        if (!has(key) || isNull(key)) {
            return null
        }

        return getString(key).trim()
    }
}
