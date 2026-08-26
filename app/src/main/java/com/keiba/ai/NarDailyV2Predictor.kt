package com.keiba.ai

import android.content.Context
import com.keiba.ai.model.RaceKey
import org.json.JSONObject
import java.io.File

object NarDailyV2Predictor {

    private const val DICTIONARY_ASSET =
        "nar-v1/category-dictionaries.json"

    private const val FEATURE_ORDER_ASSET =
        "nar-v2/feature-order.json"

    private const val MODEL_ASSET =
        "nar-v2/model.txt"

    data class EntryPrediction(
        val horseNumber: Int,
        val horseName: String,
        val prediction: Double
    )

    data class RacePrediction(
        val key: RaceKey,
        val entries: List<EntryPrediction>
    )

    data class DailyPrediction(
        val date: String,
        val races: List<RacePrediction>
    )

    fun predict(
        context: Context
    ): DailyPrediction {

        val daily =
            NarDailyRaceDownloader.download()

        val races =
            NarCsvParser.parseTable(
                daily.racelistCsv
            )

        val horses =
            NarCsvParser.parseTable(
                daily.horselistCsv
            )

        val dictionaries =
            loadJson(
                context,
                DICTIONARY_ASSET
            )

        val featureOrder =
            loadJson(
                context,
                FEATURE_ORDER_ASSET
            )

        val modelFile =
            File(
                context.filesDir,
                "nar_v2_daily_model.txt"
            )

        context.assets
            .open(MODEL_ASSET)
            .use { input ->
                modelFile
                    .outputStream()
                    .use { output ->
                        input.copyTo(output)
                    }
            }

        val raceKeys =
            races.rows
                .map { row ->
                    RaceKey(
                        track =
                            NarCsvParser.value(
                                races,
                                row,
                                "競馬場"
                            ),
                        date =
                            NarCsvParser.value(
                                races,
                                row,
                                "競走年月日"
                            )
                                .trim()
                                .toIntOrNull()
                                ?: error(
                                    "invalid race date"
                                ),
                        raceNumber =
                            NarCsvParser.value(
                                races,
                                row,
                                "レース番号"
                            )
                                .trim()
                                .toIntOrNull()
                                ?: error(
                                    "invalid race number"
                                )
                    )
                }
                .distinct()

        require(raceKeys.isNotEmpty()) {
            "no races in daily data"
        }

        val predictedRaces =
            raceKeys.map { key ->

                require(
                    key.date.toString()
                        .padStart(8, '0') ==
                        daily.date
                ) {
                    "daily date mismatch: $key"
                }

                val rawEntries =
                    NarV1RawFeatureBuilder.build(
                        races,
                        horses,
                        key
                    )

                val horseNames =
                    horseNameMap(
                        horses,
                        key
                    )

                val predictions =
                    rawEntries.map { entry ->

                        val vector =
                            NarV2Transformer.transform(
                                entry.rawFeatures,
                                dictionaries,
                                featureOrder
                            )

                        require(
                            vector.size == 64
                        ) {
                            "feature count != 64: " +
                                entry.entryId
                        }

                        val prediction =
                            LightGbmNative.nativePredict(
                                modelFile.absolutePath,
                                DoubleArray(
                                    vector.size
                                ) { index ->
                                    vector[index]
                                        .toDouble()
                                }
                            )

                        require(
                            prediction >= 0.0 &&
                                prediction <= 1.0
                        ) {
                            "invalid prediction " +
                                "${entry.entryId}: " +
                                prediction
                        }

                        EntryPrediction(
                            horseNumber =
                                entry.horseNumber,
                            horseName =
                                horseNames[
                                    entry.horseNumber
                                ] ?: "",
                            prediction =
                                prediction
                        )
                    }
                    .sortedByDescending {
                        it.prediction
                    }

                RacePrediction(
                    key = key,
                    entries = predictions
                )
            }

        return DailyPrediction(
            date = daily.date,
            races = predictedRaces
        )
    }

    private fun horseNameMap(
        horses: NarCsvParser.CsvTable,
        key: RaceKey
    ): Map<Int, String> {

        require(
            horses.header.containsKey(
                "馬名"
            )
        ) {
            "horselist missing column: 馬名"
        }

        return horses.rows
            .filter { row ->

                val track =
                    NarCsvParser.value(
                        horses,
                        row,
                        "競馬場"
                    )

                val date =
                    NarCsvParser.value(
                        horses,
                        row,
                        "競走年月日"
                    )
                        .trim()
                        .toIntOrNull()

                val raceNumber =
                    NarCsvParser.value(
                        horses,
                        row,
                        "レース番号"
                    )
                        .trim()
                        .toIntOrNull()

                track == key.track &&
                    date == key.date &&
                    raceNumber ==
                        key.raceNumber
            }
            .associate { row ->

                val horseNumber =
                    NarCsvParser.value(
                        horses,
                        row,
                        "馬番"
                    )
                        .trim()
                        .toIntOrNull()
                        ?: error(
                            "invalid horse number"
                        )

                horseNumber to
                    NarCsvParser.value(
                        horses,
                        row,
                        "馬名"
                    )
            }
    }

    private fun loadJson(
        context: Context,
        asset: String
    ): JSONObject {

        val text =
            context.assets
                .open(asset)
                .bufferedReader()
                .use {
                    it.readText()
                }

        return JSONObject(text)
    }
}
