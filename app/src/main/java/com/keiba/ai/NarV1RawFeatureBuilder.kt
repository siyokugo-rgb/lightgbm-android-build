package com.keiba.ai

import com.keiba.ai.model.RaceKey
import org.json.JSONObject

object NarV1RawFeatureBuilder {

    data class RawEntry(
        val entryId: String,
        val horseNumber: Int,
        val rawFeatures: JSONObject
    )

    private val raceColumns = listOf(
        "競馬場",
        "競走年月日",
        "競走種類名称",
        "芝ダート区分",
        "回り",
        "距離",
        "条件",
        "1着賞金(円)",
        "2着賞金(円)",
        "3着賞金(円)",
        "4着賞金(円)",
        "5着賞金(円)"
    )

    private val horseColumns = listOf(
        "枠番",
        "馬番",
        "性",
        "齢",
        "毛色",
        "生年月日",
        "父馬名",
        "母馬名",
        "母父馬名",
        "騎手名",
        "騎手所属",
        "負担重量",
        "騎手成績",
        "調教師",
        "調教師所属",
        "全成績",
        "ダート左成績",
        "ダート右成績",
        "当競馬場成績",
        "うち当距離成績",
        "最高タイム",
        "最高タイム良馬場"
    )

    private val keyColumns = listOf(
        "競馬場",
        "競走年月日",
        "レース番号"
    )

    fun build(
        races: NarCsvParser.CsvTable,
        horses: NarCsvParser.CsvTable,
        key: RaceKey
    ): List<RawEntry> {

        requireColumns(
            races,
            "racelist",
            (keyColumns + raceColumns).distinct()
        )

        requireColumns(
            horses,
            "horselist",
            (keyColumns + horseColumns).distinct()
        )

        val raceRows =
            races.rows.filter {
                matches(races, it, key)
            }

        require(raceRows.size == 1) {
            "racelist expected 1 row for $key, " +
                "got ${raceRows.size}"
        }

        val raceRow = raceRows.single()

        val horseRows =
            horses.rows.filter {
                matches(horses, it, key)
            }

        require(horseRows.isNotEmpty()) {
            "horselist has no rows for $key"
        }

        val result =
            horseRows.map { horseRow ->

                val horseNumber =
                    NarCsvParser.value(
                        horses,
                        horseRow,
                        "馬番"
                    )
                        .trim()
                        .toIntOrNull()
                        ?.takeIf { it > 0 }
                        ?: error(
                            "invalid horse number for $key"
                        )

                val raw =
                    JSONObject()

                for (column in raceColumns) {
                    raw.put(
                        "feature_race__$column",
                        NarCsvParser.value(
                            races,
                            raceRow,
                            column
                        )
                    )
                }

                for (column in horseColumns) {
                    raw.put(
                        "feature_entry__$column",
                        NarCsvParser.value(
                            horses,
                            horseRow,
                            column
                        )
                    )
                }

                require(raw.length() == 34) {
                    "raw feature count != 34: " +
                        raw.length()
                }

                val dateText =
                    key.date
                        .toString()
                        .padStart(8, '0')

                RawEntry(
                    entryId =
                        "${key.track}|" +
                            "$dateText|" +
                            "${key.raceNumber}|" +
                            horseNumber,
                    horseNumber =
                        horseNumber,
                    rawFeatures =
                        raw
                )
            }
                .sortedBy {
                    it.horseNumber
                }

        require(
            result
                .map { it.horseNumber }
                .distinct()
                .size == result.size
        ) {
            "duplicate horse number for $key"
        }

        return result
    }

    private fun matches(
        table: NarCsvParser.CsvTable,
        row: List<String>,
        key: RaceKey
    ): Boolean {

        val track =
            NarCsvParser.value(
                table,
                row,
                "競馬場"
            )

        val date =
            NarCsvParser.value(
                table,
                row,
                "競走年月日"
            )
                .trim()
                .toIntOrNull()

        val raceNumber =
            NarCsvParser.value(
                table,
                row,
                "レース番号"
            )
                .trim()
                .toIntOrNull()

        return (
            track == key.track &&
                date == key.date &&
                raceNumber == key.raceNumber
            )
    }

    private fun requireColumns(
        table: NarCsvParser.CsvTable,
        sourceName: String,
        columns: List<String>
    ) {
        val missing =
            columns.filterNot {
                table.header.containsKey(it)
            }

        require(missing.isEmpty()) {
            "$sourceName missing columns: " +
                missing.joinToString(",")
        }
    }
}
