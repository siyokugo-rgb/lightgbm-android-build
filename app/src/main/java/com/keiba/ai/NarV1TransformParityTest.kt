package com.keiba.ai

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.abs

object NarV1TransformParityTest {

    private const val FIXTURE_ASSET =
        "nar-v1/android-transform-parity-fixture.json"

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

        require(
            fixture.getInt(
                "raw_feature_count"
            ) == 34
        ) {
            "raw feature count != 34"
        }

        require(
            fixture.getInt(
                "model_feature_count"
            ) == 68
        ) {
            "model feature count != 68"
        }

        val modelFile =
            File(
                context.filesDir,
                "nar_v1_transform_test_model.txt"
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

        val entries =
            fixture.getJSONArray(
                "entries"
            )

        var vectorValues = 0
        var vectorMatches = 0
        var predictionMatches = 0
        var maxPredictionDiff = 0.0

        for (i in 0 until entries.length()) {

            val entry =
                entries.getJSONObject(i)

            val entryId =
                entry.getString(
                    "entry_id"
                )

            val raw =
                entry.getJSONObject(
                    "raw_features"
                )

            val expected =
                entry.getJSONArray(
                    "expected_feature_vector"
                )

            val actual =
                NarV1Transformer.transform(
                    raw,
                    dictionaries,
                    featureOrder
                )

            require(actual.size == 68) {
                "$entryId: actual feature count != 68"
            }

            require(expected.length() == 68) {
                "$entryId: expected feature count != 68"
            }

            for (
                featureIndex
                in 0 until 68
            ) {
                vectorValues++

                val expectedValue =
                    expected.getLong(
                        featureIndex
                    )

                val actualValue =
                    actual[featureIndex]

                if (
                    actualValue !=
                    expectedValue
                ) {
                    return buildString {
                        append(
                            "NAR V1 TRANSFORM PARITY FAIL"
                        )

                        append("\nentry=")
                        append(entryId)

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
                        actual.size
                    ) { index ->
                        actual[index]
                            .toDouble()
                    }
                )

            if (prediction < 0.0) {
                return buildString {
                    append(
                        "NAR V1 TRANSFORM PARITY FAIL"
                    )
                    append("\nentry=")
                    append(entryId)
                    append(
                        "\nreason=native_predict"
                    )
                    append("\ncode=")
                    append(prediction)
                }
            }

            val expectedPrediction =
                entry.getDouble(
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
                        "NAR V1 TRANSFORM PARITY FAIL"
                    )

                    append("\nentry=")
                    append(entryId)

                    append(
                        "\nreason=prediction"
                    )

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

        return buildString {
            append(
                "NAR V1 TRANSFORM PARITY OK"
            )

            append("\nrace=")
            append(
                fixture.getString(
                    "race_id"
                )
            )

            append("\nentries=")
            append(entries.length())

            append("\nraw_features=34")
            append("\nmodel_features=68")

            append("\nvector_values=")
            append(vectorValues)

            append("\nvector_matches=")
            append(vectorMatches)

            append("\npredictions=")
            append(entries.length())

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
