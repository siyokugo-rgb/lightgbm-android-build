package com.keiba.ai

import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

object NarV2Transformer {

    private val dateFormatter =
        DateTimeFormatter.BASIC_ISO_DATE

    private val weightRegex =
        Regex("""^([^0-9.+-]*)([0-9]+(?:\.[0-9]+)?)$""")

    private val timeRegex =
        Regex("""^(\d+):(\d{2})\.(\d)$""")

    private val goodTimeRegex =
        Regex("""^良(\d+):(\d{2})\.(\d)$""")

    private val numericPassthrough = listOf(
        "feature_race__距離",
        "feature_race__1着賞金(円)",
        "feature_race__2着賞金(円)",
        "feature_race__3着賞金(円)",
        "feature_race__4着賞金(円)",
        "feature_race__5着賞金(円)",
        "feature_entry__枠番",
        "feature_entry__馬番",
        "feature_entry__齢"
    )

    private val categoricalPassthrough = listOf(
        "feature_race__競馬場",
        "feature_race__競走種類名称",
        "feature_race__回り",
        "feature_race__条件",
        "feature_entry__性",
        "feature_entry__毛色",
        "feature_entry__騎手所属",
        "feature_entry__調教師所属"
    )

    private val record4Sources = listOf(
        "feature_entry__騎手成績",
        "feature_entry__全成績",
        "feature_entry__ダート左成績",
        "feature_entry__ダート右成績",
        "feature_entry__当競馬場成績",
        "feature_entry__うち当距離成績"
    )

    fun transform(
        raw: JSONObject,
        dictionaries: JSONObject,
        featureOrder: JSONObject
    ): LongArray {

        val values =
            linkedMapOf<String, Long>()

        for (name in numericPassthrough) {
            val text = rawString(raw, name)

            require(text.isNotEmpty()) {
                "blank numeric feature: $name"
            }

            values[name] =
                text.toLongOrNull()
                    ?: error(
                        "invalid integer: $name=$text"
                    )
        }

        val raceDate =
            parseDate(
                rawString(
                    raw,
                    "feature_race__競走年月日"
                )
            )

        values["race_month"] =
            raceDate.monthValue.toLong()

        values["race_day_of_year"] =
            raceDate.dayOfYear.toLong()

        values["race_weekday_mon0"] =
            (raceDate.dayOfWeek.value - 1).toLong()

        val birthDate =
            parseDate(
                rawString(
                    raw,
                    "feature_entry__生年月日"
                )
            )

        val ageDays =
            ChronoUnit.DAYS.between(
                birthDate,
                raceDate
            )

        require(ageDays >= 0) {
            "birth date is after race date"
        }

        values["age_days"] = ageDays

        val weight =
            parseWeight(
                rawString(
                    raw,
                    "feature_entry__負担重量"
                )
            )

        values["burden_weight_deci_kg"] =
            weight.first

        for (source in record4Sources) {
            val parsed =
                parseRecord4(
                    rawString(raw, source)
                )

            val prefix =
                source
                    .removePrefix(
                        "feature_entry__"
                    )
                    .removePrefix(
                        "feature_race__"
                    )

            values["${prefix}_first"] =
                parsed.first
            values["${prefix}_second"] =
                parsed.second
            values["${prefix}_third"] =
                parsed.third
            values["${prefix}_other"] =
                parsed.other
            values["${prefix}_total"] =
                parsed.first +
                    parsed.second +
                    parsed.third +
                    parsed.other
            values["${prefix}_missing"] =
                parsed.missing
        }

        val best =
            parseTime(
                rawString(
                    raw,
                    "feature_entry__最高タイム"
                ),
                timeRegex
            )

        values["best_time_deciseconds"] =
            best.first
        values["best_time_missing"] =
            best.second

        val bestGood =
            parseTime(
                rawString(
                    raw,
                    "feature_entry__最高タイム良馬場"
                ),
                goodTimeRegex
            )

        values[
            "best_time_good_deciseconds"
        ] = bestGood.first

        values[
            "best_time_good_missing"
        ] = bestGood.second

        val categorical =
            linkedMapOf<String, String>()

        for (name in categoricalPassthrough) {
            categorical[name] =
                rawString(raw, name)
        }

        val venue =
            rawString(
                raw,
                "feature_race__競馬場"
            )

        val surfaceRaw =
            rawString(
                raw,
                "feature_race__芝ダート区分"
            )

        categorical["surface_type"] =
            when {
                surfaceRaw.isNotEmpty() ->
                    surfaceRaw

                venue == "帯広ば" ->
                    "ばんえい"

                else ->
                    "__MISSING__"
            }

        categorical["burden_weight_mark"] =
            weight.second

        for ((name, value) in categorical) {
            values[name] =
                encodeCategory(
                    dictionaries,
                    name,
                    value
                )
        }

        require(
            featureOrder.getInt("version") == 2
        ) {
            "feature order version != 2"
        }

        val features =
            featureOrder
                .getJSONArray("features")

        require(features.length() == 64) {
            "feature order count != 64"
        }

        return LongArray(
            features.length()
        ) { index ->

            val spec =
                features.getJSONObject(index)

            require(
                spec.getInt("index") == index
            ) {
                "feature index mismatch: $index"
            }

            val name =
                spec.getString("name")

            values[name]
                ?: error(
                    "feature not produced: $name"
                )
        }
    }

    private fun rawString(
        raw: JSONObject,
        name: String
    ): String {

        if (
            !raw.has(name) ||
            raw.isNull(name)
        ) {
            return ""
        }

        return raw
            .getString(name)
            .trim()
    }

    private fun parseDate(
        raw: String
    ): LocalDate {

        require(
            raw.length == 8 &&
                raw.all { it.isDigit() }
        ) {
            "invalid YYYYMMDD: $raw"
        }

        return LocalDate.parse(
            raw,
            dateFormatter
        )
    }

    private fun parseWeight(
        raw: String
    ): Pair<Long, String> {

        val match =
            weightRegex.matchEntire(raw)
                ?: error(
                    "invalid burden weight: $raw"
                )

        var mark =
            match.groupValues[1]

        if (mark.isEmpty()) {
            mark = "NONE"
        }

        val value =
            BigDecimal(
                match.groupValues[2]
            )

        val scaled =
            value.multiply(
                BigDecimal.TEN
            )

        val integer =
            try {
                scaled.longValueExact()
            } catch (
                e: ArithmeticException
            ) {
                error(
                    "weight cannot be represented exactly: $raw"
                )
            }

        return integer to mark
    }

    private data class Record4(
        val first: Long,
        val second: Long,
        val third: Long,
        val other: Long,
        val missing: Long
    )

    private fun parseRecord4(
        raw: String
    ): Record4 {

        if (raw.isEmpty()) {
            return Record4(
                first = 0,
                second = 0,
                third = 0,
                other = 0,
                missing = 1
            )
        }

        val parts =
            raw.split("-")

        require(
            parts.size == 4 &&
                parts.all { part ->
                    part.isNotEmpty() &&
                        part.all {
                            char -> char.isDigit()
                        }
                }
        ) {
            "invalid record4: $raw"
        }

        return Record4(
            first = parts[0].toLong(),
            second = parts[1].toLong(),
            third = parts[2].toLong(),
            other = parts[3].toLong(),
            missing = 0
        )
    }

    private fun parseTime(
        raw: String,
        regex: Regex
    ): Pair<Long, Long> {

        if (raw.isEmpty()) {
            return 0L to 1L
        }

        val match =
            regex.matchEntire(raw)
                ?: error(
                    "invalid best time: $raw"
                )

        val minute =
            match.groupValues[1]
                .toLong()

        val second =
            match.groupValues[2]
                .toLong()

        val tenth =
            match.groupValues[3]
                .toLong()

        require(second < 60) {
            "invalid seconds: $raw"
        }

        return (
            (minute * 60 + second) * 10 +
                tenth
            ) to 0L
    }

    private fun encodeCategory(
        dictionaries: JSONObject,
        featureName: String,
        value: String
    ): Long {

        val missingId =
            dictionaries.getLong(
                "missing_id"
            )

        val unknownId =
            dictionaries.getLong(
                "unknown_id"
            )

        if (
            value.isEmpty() ||
            value == "__MISSING__"
        ) {
            return missingId
        }

        val mapping =
            dictionaries
                .getJSONObject("features")
                .getJSONObject(featureName)
                .getJSONObject("value_to_id")

        return if (mapping.has(value)) {
            mapping.getLong(value)
        } else {
            unknownId
        }
    }
}
