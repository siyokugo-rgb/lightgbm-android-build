package com.keiba.ai

import android.content.Context
import com.keiba.ai.model.RaceKey
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.abs

object NarV1SourceParityTest {

    private const val FIXTURE_ASSET =
        "nar-v1/android-source-parity-fixture.json"

    private const val DICTIONARY_ASSET =
        "nar-v1/category-dictionaries.json"

    private const val FEATURE_ORDER_ASSET =
        "nar-v1/feature-order.json"

    private const val MODEL_ASSET =
        "nar-v1/model.txt"

    private const val PREDICTION_TOLERANCE =
        1e-10

    fun run(context: Context): String {

        val fixture =
            loadJson(
                context,
                FIXTURE_ASSET
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

        val raceId =
            fixture.getString("race_id")

        val raceParts =
            raceId.split("|")

        require(raceParts.size == 3) {
            "invalid race_id: $raceId"
        }

        val key =
            RaceKey(
                track = raceParts[0],
                date = raceParts[1]
                    .toIntOrNull()
                    ?: error(
                        "invalid race date: $raceId"
                    ),
                raceNumber = raceParts[2]
                    .toIntOrNull()
                    ?: error(
                        "invalid race number: $raceId"
                    )
            )

        val races =
            NarCsvParser.parseTable(
                fixture.getString(
                    "racelist_csv"
                )
            )

        val horses =
            NarCsvParser.parseTable(
                fixture.getString(
                    "horselist_csv"
                )
            )

        val builtEntries =
            NarV1RawFeatureBuilder.build(
                races,
                horses,
                key
            )

        val expectedEntries =
            fixture.getJSONArray(
                "entries"
            )

        require(
            builtEntries.size ==
                fixture.getInt(
                    "expected_entries"
                )
        ) {
            "built entry count mismatch: " +
                builtEntries.size
        }

        require(
            expectedEntries.length() ==
                builtEntries.size
        ) {
            "expected entry count mismatch"
        }

        val expectedById =
            mutableMapOf<String, JSONObject>()

        for (
            i in
            0 until expectedEntries.length()
        ) {
            val entry =
                expectedEntries.getJSONObject(i)

            val entryId =
                entry.getString(
                    "entry_id"
                )

            require(
                expectedById.put(
                    entryId,
                    entry
                ) == null
            ) {
                "duplicate expected entry: $entryId"
            }
        }

        val modelFile =
            File(
                context.filesDir,
                "nar_v1_source_test_model.txt"
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

        var rawValues = 0
        var rawMatches = 0

        var vectorValues = 0
        var vectorMatches = 0

        var predictionMatches = 0
        var maxPredictionDiff = 0.0

        for (built in builtEntries) {

            val expectedEntry =
                expectedById[built.entryId]
                    ?: return buildString {
                        append(
                            "NAR V1 SOURCE PARITY FAIL"
                        )
                        append(
                            "\nreason=unexpected_entry"
                        )
                        append("\nentry=")
                        append(built.entryId)
                    }

            val expectedRaw =
                expectedEntry.getJSONObject(
                    "raw_features"
                )

            require(
                expectedRaw.length() == 34
            ) {
                "${built.entryId}: " +
                    "expected raw count != 34"
            }

            require(
                built.rawFeatures.length() == 34
            ) {
                "${built.entryId}: " +
                    "actual raw count != 34"
            }

            val keyIterator =
                expectedRaw.keys()

            while (keyIterator.hasNext()) {

                val featureName =
                    keyIterator.next()

                rawValues++

                val expectedValue =
                    expectedRaw.getString(
                        featureName
                    )

                val actualValue =
                    if (
                        built.rawFeatures.has(
                            featureName
                        )
                    ) {
                        built.rawFeatures
                            .getString(
                                featureName
                            )
                    } else {
                        return buildString {
                            append(
                                "NAR V1 SOURCE PARITY FAIL"
                            )
                            append(
                                "\nreason=raw_feature_missing"
                            )
                            append("\nentry=")
                            append(built.entryId)
                            append("\nfeature=")
                            append(featureName)
                        }
                    }

                if (
                    actualValue !=
                    expectedValue
                ) {
                    return buildString {
                        append(
                            "NAR V1 SOURCE PARITY FAIL"
                        )

                        append("\nreason=raw_value")

                        append("\nentry=")
                        append(built.entryId)

                        append("\nfeature=")
                        append(featureName)

                        append("\nexpected=")
                        append(expectedValue)

                        append("\nactual=")
                        append(actualValue)
                    }
                }

                rawMatches++
            }

            val actualVector =
                NarV1Transformer.transform(
                    built.rawFeatures,
                    dictionaries,
                    featureOrder
                )

            val expectedVector =
                expectedEntry.getJSONArray(
                    "expected_feature_vector"
                )

            require(
                actualVector.size == 68
            )

            require(
                expectedVector.length() == 68
            )

            for (
                featureIndex
                in 0 until 68
            ) {
                vectorValues++

                val expectedValue =
                    expectedVector.getLong(
                        featureIndex
                    )

                val actualValue =
                    actualVector[
                        featureIndex
                    ]

                if (
                    actualValue !=
                    expectedValue
                ) {
                    return buildString {
                        append(
                            "NAR V1 SOURCE PARITY FAIL"
                        )

                        append(
                            "\nreason=transformed_feature"
                        )

                        append("\nentry=")
                        append(built.entryId)

                        append(
                            "\nfeature_index="
                        )
                        append(featureIndex)

                        append("\nexpected=")
                        append(expectedValue)

                        append("\nactual=")
                        append(actualValue)
                    }
                }

                vectorMatches++
            }

            val prediction =
                LightGbmNative.nativePredict(
                    modelFile.absolutePath,
                    DoubleArray(
                        actualVector.size
                    ) { index ->
                        actualVector[index]
                            .toDouble()
                    }
                )

            if (prediction < 0.0) {
                return buildString {
                    append(
                        "NAR V1 SOURCE PARITY FAIL"
                    )
                    append(
                        "\nreason=native_predict"
                    )
                    append("\nentry=")
                    append(built.entryId)
                    append("\ncode=")
                    append(prediction)
                }
            }

            val expectedPrediction =
                expectedEntry.getDouble(
                    "expected_prediction"
                )

            val diff =
                abs(
                    prediction -
                        expectedPrediction
                )

            if (
                diff >
                maxPredictionDiff
            ) {
                maxPredictionDiff =
                    diff
            }

            if (
                diff <=
                PREDICTION_TOLERANCE
            ) {
                predictionMatches++
            } else {
                return buildString {
                    append(
                        "NAR V1 SOURCE PARITY FAIL"
                    )

                    append(
                        "\nreason=prediction"
                    )

                    append("\nentry=")
                    append(built.entryId)

                    append("\nexpected=")
                    append(
                        String.format(
                            Locale.US,
                            "%.15f",
                            expectedPrediction
                        )
                    )

                    append("\nactual=")
                    append(
                        String.format(
                            Locale.US,
                            "%.15f",
                            prediction
                        )
                    )

                    append("\ndiff=")
                    append(
                        String.format(
                            Locale.US,
                            "%.3e",
                            diff
                        )
                    )
                }
            }
        }

        require(
            rawValues ==
                fixture.getInt(
                    "expected_raw_values"
                )
        ) {
            "raw value count mismatch: $rawValues"
        }

        return buildString {
            append(
                "NAR V1 SOURCE PARITY OK"
            )

            append("\nrace=")
            append(raceId)

            append("\nentries=")
            append(builtEntries.size)

            append("\nraw_values=")
            append(rawValues)

            append("\nraw_matches=")
            append(rawMatches)

            append("\nvector_values=")
            append(vectorValues)

            append("\nvector_matches=")
            append(vectorMatches)

            append("\npredictions=")
            append(builtEntries.size)

            append(
                "\nprediction_matches="
            )
            append(predictionMatches)

            append(
                "\nmax_prediction_diff="
            )
            append(
                String.format(
                    Locale.US,
                    "%.3e",
                    maxPredictionDiff
                )
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
