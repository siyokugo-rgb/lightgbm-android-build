package com.keiba.ai

import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneId

object NarJmaObservedWeatherCsvParser {

    private val JST =
        ZoneId.of("Asia/Tokyo")

    private val STATION_ID_PATTERN =
        Regex("""^\d+$""")

    fun parse(
        product: NarJmaObservedWeatherProduct,
        rawBytes: ByteArray
    ): NarJmaObservedWeatherPayload {
        // Do not mutate archived raw bytes.
        val table =
            NarJmaObservedWeatherCsvLexical
                .parseTable(
                    NarJmaObservedWeatherCsvLexical
                        .decodeShiftJisStrict(
                            rawBytes
                        )
                )

        val header =
            table.first()

        NarJmaObservedWeatherCsvSchema
            .validateHeader(
                product,
                header
            )

        val rows =
            mutableListOf<NarJmaObservedWeatherRow>()

        val seenStationIds =
            linkedSetOf<String>()

        for (cells in table.drop(1)) {
            val row =
                parseDataRow(
                    product,
                    cells
                )

            if (!seenStationIds.add(row.stationId)) {
                throw IllegalArgumentException(
                    "duplicate station_id in observed CSV"
                )
            }

            rows += row
        }

        return NarJmaObservedWeatherPayload(
            product = product,
            rows = rows.toList()
        )
    }

    private fun parseDataRow(
        product: NarJmaObservedWeatherProduct,
        cells: List<String>
    ): NarJmaObservedWeatherRow {
        val stationId =
            parseStationId(
                cells[0]
            )

        val prefecture =
            requireNonBlankField(
                cells[1],
                "prefecture"
            )

        val stationName =
            requireNonBlankField(
                cells[2],
                "station name"
            )

        val internationalId =
            cells[3]
                .trim()
                .ifEmpty { null }

        val productTimestamp =
            parseProductTimestamp(
                year = cells[4],
                month = cells[5],
                day = cells[6],
                hour = cells[7],
                minute = cells[8]
            )

        return when (product.family) {
            NarJmaObservedWeatherProduct.Family.PRECIPITATION -> {
                val window =
                    product.precipitationWindowHours
                        ?: error(
                            "missing precipitation window"
                        )

                NarJmaObservedPrecipitationRow(
                    stationId = stationId,
                    prefecture = prefecture,
                    stationName = stationName,
                    internationalStationId =
                        internationalId,
                    productTimestampEpochMillis =
                        productTimestamp,
                    windowHours = window,
                    currentValueMm =
                        parseOptionalNonNegativeDouble(
                            cells[9]
                        ),
                    currentValueQuality =
                        NarJmaObservedLatestQuality
                            .parseOptionalCell(
                                cells[10]
                            ),
                    dailyMaxValueMm =
                        parseOptionalNonNegativeDouble(
                            cells[11]
                        ),
                    dailyMaxQuality =
                        NarJmaObservedLatestQuality
                            .parseOptionalCell(
                                cells[12]
                            ),
                    dailyMaxOccurrenceHour =
                        parseOptionalHour(
                            cells[13]
                        ),
                    dailyMaxOccurrenceMinute =
                        parseOptionalMinute(
                            cells[14]
                        ),
                    dailyMaxOccurrenceQuality =
                        NarJmaObservedLatestQuality
                            .parseOptionalCell(
                                cells[15]
                            )
                )
            }

            NarJmaObservedWeatherProduct.Family.TEMPERATURE_EXTREME -> {
                val extremeType =
                    when (product) {
                        NarJmaObservedWeatherProduct.MXTEMSADEXT00_RCT ->
                            NarJmaObservedTemperatureExtremeType.MAX

                        NarJmaObservedWeatherProduct.MNTEMSADEXT00_RCT ->
                            NarJmaObservedTemperatureExtremeType.MIN

                        else ->
                            error(
                                "unexpected temperature product"
                            )
                    }

                NarJmaObservedTemperatureExtremeRow(
                    stationId = stationId,
                    prefecture = prefecture,
                    stationName = stationName,
                    internationalStationId =
                        internationalId,
                    productTimestampEpochMillis =
                        productTimestamp,
                    extremeType = extremeType,
                    dailyExtremeCelsius =
                        parseOptionalFiniteDouble(
                            cells[9]
                        ),
                    dailyExtremeQuality =
                        NarJmaObservedLatestQuality
                            .parseOptionalCell(
                                cells[10]
                            ),
                    occurrenceHour =
                        parseOptionalHour(
                            cells[11]
                        ),
                    occurrenceMinute =
                        parseOptionalMinute(
                            cells[12]
                        ),
                    occurrenceQuality =
                        NarJmaObservedLatestQuality
                            .parseOptionalCell(
                                cells[13]
                            )
                )
            }

            NarJmaObservedWeatherProduct.Family.WIND_MAX,
            NarJmaObservedWeatherProduct.Family.GUST_MAX -> {
                val kind =
                    when (product) {
                        NarJmaObservedWeatherProduct.MXWSP00_RCT ->
                            NarJmaObservedWindExtremeKind.MAX_WIND

                        NarJmaObservedWeatherProduct.GUST00_RCT ->
                            NarJmaObservedWindExtremeKind.MAX_GUST

                        else ->
                            error(
                                "unexpected wind product"
                            )
                    }

                NarJmaObservedWindExtremeRow(
                    stationId = stationId,
                    prefecture = prefecture,
                    stationName = stationName,
                    internationalStationId =
                        internationalId,
                    productTimestampEpochMillis =
                        productTimestamp,
                    kind = kind,
                    dailyMaxMetersPerSecond =
                        parseOptionalNonNegativeDouble(
                            cells[9]
                        ),
                    dailyMaxQuality =
                        NarJmaObservedLatestQuality
                            .parseOptionalCell(
                                cells[10]
                            ),
                    directionRaw =
                        cells[11]
                            .trim()
                            .ifEmpty { null },
                    directionQuality =
                        NarJmaObservedLatestQuality
                            .parseOptionalCell(
                                cells[12]
                            ),
                    occurrenceHour =
                        parseOptionalHour(
                            cells[13]
                        ),
                    occurrenceMinute =
                        parseOptionalMinute(
                            cells[14]
                        ),
                    occurrenceQuality =
                        NarJmaObservedLatestQuality
                            .parseOptionalCell(
                                cells[15]
                            )
                )
            }
        }
    }

    internal fun parseStationIdForTest(
        cell: String
    ): String =
        parseStationId(cell)

    internal fun parseProductTimestampForTest(
        year: String,
        month: String,
        day: String,
        hour: String,
        minute: String
    ): Long =
        parseProductTimestamp(
            year,
            month,
            day,
            hour,
            minute
        )

    private fun parseStationId(
        cell: String
    ): String {
        val trimmed =
            cell.trim()

        require(trimmed.isNotEmpty()) {
            "blank station_id"
        }

        require(
            STATION_ID_PATTERN.matches(
                trimmed
            )
        ) {
            "invalid station_id"
        }

        return trimmed
    }

    private fun requireNonBlankField(
        cell: String,
        label: String
    ): String {
        val trimmed =
            cell.trim()

        require(trimmed.isNotEmpty()) {
            "blank $label"
        }

        return trimmed
    }

    private fun parseProductTimestamp(
        year: String,
        month: String,
        day: String,
        hour: String,
        minute: String
    ): Long {
        val y =
            parseRequiredInt(
                year,
                "year"
            )

        val m =
            parseRequiredInt(
                month,
                "month"
            )

        val d =
            parseRequiredInt(
                day,
                "day"
            )

        val h =
            parseRequiredInt(
                hour,
                "hour"
            )

        val min =
            parseRequiredInt(
                minute,
                "minute"
            )

        require(h in 0..23) {
            "invalid hour"
        }

        require(min in 0..59) {
            "invalid minute"
        }

        val dateTime =
            try {
                LocalDateTime.of(
                    y,
                    m,
                    d,
                    h,
                    min
                )
            } catch (_: DateTimeException) {
                throw IllegalArgumentException(
                    "invalid product timestamp"
                )
            }

        return dateTime
            .atZone(JST)
            .toInstant()
            .toEpochMilli()
    }

    private fun parseRequiredInt(
        cell: String,
        label: String
    ): Int {
        val trimmed =
            cell.trim()

        require(trimmed.isNotEmpty()) {
            "blank $label"
        }

        return trimmed.toIntOrNull()
            ?: throw IllegalArgumentException(
                "invalid $label"
            )
    }

    private fun parseOptionalHour(
        cell: String
    ): Int? {
        val trimmed =
            cell.trim()

        if (trimmed.isEmpty()) {
            return null
        }

        val value =
            trimmed.toIntOrNull()
                ?: throw IllegalArgumentException(
                    "invalid hour cell"
                )

        require(value in 0..23) {
            "invalid hour cell"
        }

        return value
    }

    private fun parseOptionalMinute(
        cell: String
    ): Int? {
        val trimmed =
            cell.trim()

        if (trimmed.isEmpty()) {
            return null
        }

        val value =
            trimmed.toIntOrNull()
                ?: throw IllegalArgumentException(
                    "invalid minute cell"
                )

        require(value in 0..59) {
            "invalid minute cell"
        }

        return value
    }

    private fun parseOptionalFiniteDouble(
        cell: String
    ): Double? {
        val trimmed =
            cell.trim()

        if (trimmed.isEmpty()) {
            return null
        }

        val value =
            trimmed.toDoubleOrNull()
                ?: throw IllegalArgumentException(
                    "invalid numeric cell"
                )

        require(value.isFinite()) {
            "non-finite numeric cell"
        }

        return value
    }

    private fun parseOptionalNonNegativeDouble(
        cell: String
    ): Double? {
        val value =
            parseOptionalFiniteDouble(
                cell
            )
                ?: return null

        require(value >= 0.0) {
            "negative numeric cell"
        }

        return value
    }
}
