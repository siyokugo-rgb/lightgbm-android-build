package com.keiba.ai

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.abs

object NarModelParityTest {

    private const val MODEL_ASSET =
        "nar-v1/model.txt"

    private const val FIXTURE_ASSET =
        "nar-v1/android-parity-fixture.json"

    private const val TOLERANCE = 1e-10

    fun run(context: Context): String {
        val modelFile =
            File(
                context.filesDir,
                "nar_v1_win_baseline_model.txt"
            )

        context.assets
            .open(MODEL_ASSET)
            .use { input ->
                modelFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

        val fixtureText =
            context.assets
                .open(FIXTURE_ASSET)
                .bufferedReader()
                .use { it.readText() }

        val fixture =
            JSONObject(fixtureText)

        val featureCount =
            fixture.getInt("feature_count")

        if (featureCount != 68) {
            return "NAR MODEL PARITY FAIL" +
                "\nreason=feature_count" +
                "\nactual=$featureCount"
        }

        val raceId =
            fixture.getString("race_id")

        val entries =
            fixture.getJSONArray("entries")

        var maxDiff = 0.0
        var passed = 0

        val detail = StringBuilder()

        for (i in 0 until entries.length()) {
            val entry =
                entries.getJSONObject(i)

            val entryId =
                entry.getString("entry_id")

            val expected =
                entry.getDouble(
                    "expected_prediction"
                )

            val vectorJson =
                entry.getJSONArray(
                    "feature_vector"
                )

            if (vectorJson.length() != featureCount) {
                return "NAR MODEL PARITY FAIL" +
                    "\nreason=vector_size" +
                    "\nentry=$entryId"
            }

            val features =
                DoubleArray(featureCount) { index ->
                    vectorJson.getDouble(index)
                }

            val actual =
                LightGbmNative.nativePredict(
                    modelFile.absolutePath,
                    features
                )

            if (actual < 0.0) {
                return "NAR MODEL PARITY FAIL" +
                    "\nreason=native_predict" +
                    "\nentry=$entryId" +
                    "\ncode=$actual"
            }

            val diff =
                abs(actual - expected)

            if (diff > maxDiff) {
                maxDiff = diff
            }

            val ok =
                diff <= TOLERANCE

            if (ok) {
                passed++
            }

            detail.append("\n")
            detail.append(entryId)

            detail.append(" expected=")
            detail.append(
                String.format(
                    Locale.US,
                    "%.15f",
                    expected
                )
            )

            detail.append(" actual=")
            detail.append(
                String.format(
                    Locale.US,
                    "%.15f",
                    actual
                )
            )

            detail.append(" diff=")
            detail.append(
                String.format(
                    Locale.US,
                    "%.3e",
                    diff
                )
            )

            detail.append(
                if (ok) " PASS"
                else " FAIL"
            )
        }

        val allOk =
            passed == entries.length()

        return buildString {
            append(
                if (allOk) {
                    "NAR MODEL PARITY OK"
                } else {
                    "NAR MODEL PARITY FAIL"
                }
            )

            append("\nrace=")
            append(raceId)

            append("\nfeatures=")
            append(featureCount)

            append("\nentries=")
            append(entries.length())

            append("\npassed=")
            append(passed)

            append("\ntolerance=")
            append(TOLERANCE)

            append("\nmax_diff=")
            append(
                String.format(
                    Locale.US,
                    "%.3e",
                    maxDiff
                )
            )

            append("\n")
            append(detail)
        }
    }
}
