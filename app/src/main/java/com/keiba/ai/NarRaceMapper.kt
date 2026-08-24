package com.keiba.ai

import com.keiba.ai.model.BetType
import com.keiba.ai.model.HorseEntry
import com.keiba.ai.model.HorseOutcome
import com.keiba.ai.model.HorseOutcomeStatus
import com.keiba.ai.model.HorseNonStart
import com.keiba.ai.model.HorseNonStartStatus
import com.keiba.ai.model.NarRaceBundle
import com.keiba.ai.model.Payout
import com.keiba.ai.model.RaceKey
import com.keiba.ai.model.RaceRecord

object NarRaceMapper {

    fun map(
        races: NarCsvParser.CsvTable,
        horses: NarCsvParser.CsvTable,
        paybacks: NarCsvParser.CsvTable,
        key: RaceKey
    ): NarRaceBundle {

        val raceRow = races.rows.firstOrNull { matches(races, it, key) }
            ?: error("Race not found: $key")

        val horseRows = horses.rows
            .filter { matches(horses, it, key) }
            .sortedBy {
                intValue(horses, it, "馬番") ?: Int.MAX_VALUE
            }

        require(horseRows.isNotEmpty()) {
            "Horses not found: $key"
        }

        val paybackRow =
            paybacks.rows.firstOrNull { matches(paybacks, it, key) }

        val race = RaceRecord(
            key = key,
            postTime = intValue(races, raceRow, "発走時刻"),
            distanceMeters = intValue(races, raceRow, "距離"),
            weather = textValue(races, raceRow, "天候"),
            trackCondition = textValue(races, raceRow, "馬場"),
            declaredCount = intValue(races, raceRow, "頭数"),
            raceName = textValue(races, raceRow, "レース名")
        )

        val horseEntries = horseRows.map { row ->

            val horseNumber =
                intValue(horses, row, "馬番")
                    ?: error("Horse number missing: $key")

            val horseName =
                textValue(horses, row, "馬名")
                    ?: error("Horse name missing: $key / $horseNumber")

            HorseEntry(
                key = key,
                horseNumber = horseNumber,
                horseName = horseName,
                jockey = textValue(horses, row, "騎手名"),
                assignedWeightKg =
                    doubleValue(horses, row, "負担重量"),
                bodyWeightKg =
                    intValue(horses, row, "馬体重")
            )
        }

        val horseOutcomes = horseRows.mapNotNull { row ->
            val finishRaw =
                NarCsvParser.value(horses, row, "着順").trim()

            val finishPosition =
                finishRaw.toIntOrNull()?.takeIf { it > 0 }

            val specialResult =
                NarCsvParser.value(horses, row, "着差").trim()

            val hasSpecialResult =
                specialResult == "出走取消" ||
                    specialResult == "競走除外" ||
                    specialResult == "競走中止" ||
                    specialResult == "失格"

            if (finishPosition != null && hasSpecialResult) {
                error(
                    "Conflicting horse outcome: " +
                        "$key / finish=$finishRaw / result=$specialResult"
                )
            }

            val status = when {
                finishPosition != null ->
                    HorseOutcomeStatus.FINISHED

                specialResult == "競走中止" ->
                    HorseOutcomeStatus.DID_NOT_FINISH

                specialResult == "失格" ->
                    HorseOutcomeStatus.DISQUALIFIED

                finishRaw.isEmpty() && specialResult.isEmpty() ->
                    return@mapNotNull null

                specialResult == "出走取消" ||
                    specialResult == "競走除外" ->
                    return@mapNotNull null

                else ->
                    error(
                        "Unsupported horse outcome: " +
                            "$key / finish=$finishRaw / result=$specialResult"
                    )
            }

            val horseNumber =
                intValue(horses, row, "馬番")
                    ?: error("Horse number missing: $key")

            HorseOutcome(
                key = key,
                horseNumber = horseNumber,
                status = status,
                finishPosition =
                    if (status == HorseOutcomeStatus.FINISHED) {
                        finishPosition
                    } else {
                        null
                    }
            )
        }

        val nonStarts = horseRows.mapNotNull { row ->
            val specialResult =
                NarCsvParser.value(horses, row, "着差").trim()

            val status = when (specialResult) {
                "出走取消" -> HorseNonStartStatus.SCRATCHED
                "競走除外" -> HorseNonStartStatus.EXCLUDED
                else -> return@mapNotNull null
            }

            val horseNumber =
                intValue(horses, row, "馬番")
                    ?: error("Horse number missing: $key")

            HorseNonStart(
                key = key,
                horseNumber = horseNumber,
                status = status
            )
        }

        val payouts =
            if (paybackRow == null) {
                emptyList()
            } else {
                mapPayouts(paybacks, paybackRow, key)
            }

        return NarRaceBundle(
            race = race,
            entries = horseEntries,
            outcomes = horseOutcomes,
            nonStarts = nonStarts,
            payouts = payouts
        )
    }

    private fun mapPayouts(
        table: NarCsvParser.CsvTable,
        row: List<String>,
        key: RaceKey
    ): List<Payout> {

        val result = mutableListOf<Payout>()

        addPayout(
            result,
            key,
            BetType.WIN,
            intValue(table, row, "単勝払戻金（円）"),
            intValue(table, row, "単勝組番")
        )

        for (i in 1..3) {
            addPayout(
                result,
                key,
                BetType.PLACE,
                intValue(table, row, "複勝払戻金${i}（円）"),
                intValue(table, row, "複勝組番$i")
            )
        }

        addPayout(
            result,
            key,
            BetType.BRACKET_QUINELLA,
            intValue(table, row, "枠複払戻金（円）"),
            intValue(table, row, "枠複組番1"),
            intValue(table, row, "枠複組番2")
        )

        addPayout(
            result,
            key,
            BetType.BRACKET_EXACTA,
            intValue(table, row, "枠単払戻金（円）"),
            intValue(table, row, "枠単組番1"),
            intValue(table, row, "枠単組番2")
        )

        addPayout(
            result,
            key,
            BetType.QUINELLA,
            intValue(table, row, "馬複払戻金（円）"),
            intValue(table, row, "馬複組番1"),
            intValue(table, row, "馬複組番2")
        )

        addPayout(
            result,
            key,
            BetType.EXACTA,
            intValue(table, row, "馬単払戻金（円）"),
            intValue(table, row, "馬単組番1"),
            intValue(table, row, "馬単組番2")
        )

        for (i in 1..3) {
            addPayout(
                result,
                key,
                BetType.WIDE,
                intValue(table, row, "ワイド払戻金${i}（円）"),
                intValue(table, row, "ワイド組番${i}馬番1"),
                intValue(table, row, "ワイド組番${i}馬番2")
            )
        }

        addPayout(
            result,
            key,
            BetType.TRIO,
            intValue(table, row, "３連複払戻金（円）"),
            intValue(table, row, "３連複組番馬番1"),
            intValue(table, row, "３連複組番馬番2"),
            intValue(table, row, "３連複組番馬番3")
        )

        addPayout(
            result,
            key,
            BetType.TRIFECTA,
            intValue(table, row, "３連単払戻金（円）"),
            intValue(table, row, "３連単組番馬番1"),
            intValue(table, row, "３連単組番馬番2"),
            intValue(table, row, "３連単組番馬番3")
        )

        return result
    }

    private fun addPayout(
        out: MutableList<Payout>,
        key: RaceKey,
        betType: BetType,
        amount: Int?,
        vararg combination: Int?
    ) {
        if (amount == null || amount <= 0) return
        if (combination.isEmpty()) return
        if (combination.any { it == null }) return

        out += Payout(
            key = key,
            betType = betType,
            combination = combination.map { it!! },
            amountYen = amount
        )
    }

    private fun matches(
        table: NarCsvParser.CsvTable,
        row: List<String>,
        key: RaceKey
    ): Boolean {
        return NarCsvParser.value(table, row, "競馬場") == key.track &&
            intValue(table, row, "競走年月日") == key.date &&
            intValue(table, row, "レース番号") == key.raceNumber
    }

    private fun textValue(
        table: NarCsvParser.CsvTable,
        row: List<String>,
        column: String
    ): String? {
        return NarCsvParser.value(table, row, column)
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    private fun intValue(
        table: NarCsvParser.CsvTable,
        row: List<String>,
        column: String
    ): Int? {
        val raw = NarCsvParser.value(table, row, column).trim()
        if (raw.isEmpty()) return null

        val digits = raw.filter { it.isDigit() }

        return digits
            .takeIf { it.isNotEmpty() }
            ?.toIntOrNull()
    }

    private fun doubleValue(
        table: NarCsvParser.CsvTable,
        row: List<String>,
        column: String
    ): Double? {
        val raw = NarCsvParser.value(table, row, column).trim()
        if (raw.isEmpty()) return null

        val cleaned = raw.filter {
            it.isDigit() || it == '.' || it == '-'
        }

        return cleaned.toDoubleOrNull()
    }
}
