package com.keiba.ai

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()

        val view = TextView(this).apply {
            text = "Starting test..."
            textSize = 18f
            gravity = Gravity.START
            setPadding(48, 48, 48, 48)
        }

        val scrollView = ScrollView(this).apply {
            isSaveEnabled = false
            addView(view)

            setOnApplyWindowInsetsListener { v, insets ->
                @Suppress("DEPRECATION")
                v.setPadding(
                    v.paddingLeft,
                    insets.systemWindowInsetTop,
                    v.paddingRight,
                    insets.systemWindowInsetBottom
                )
                insets
            }
        }

        setContentView(scrollView)

        Thread {
            val modelFile = File(filesDir, "lightgbm_test_model.txt")

            val lightStart = SystemClock.elapsedRealtime()

            val lightGbmStatus = try {
                val mode: String
                val result: String

                if (modelFile.exists()) {
                    mode = "LOAD_EXISTING"
                    result =
                        LightGbmNative.nativeLoadPredictTest(modelFile.absolutePath)
                } else {
                    mode = "TRAIN_AND_SAVE"
                    result =
                        LightGbmNative.nativeTrainSaveTest(modelFile.absolutePath)
                }

                LightGbmNative.nativeStatus() +
                    "\n\nmode=" + mode +
                    "\nmodel=" + modelFile.absolutePath +
                    "\n\n" + result
            } catch (t: Throwable) {
                "LightGBM FAIL\n${t.javaClass.simpleName}: ${t.message}"
            }

            val lightMs = SystemClock.elapsedRealtime() - lightStart

            runOnUiThread {
                view.text =
                    lightGbmStatus +
                    "\n\nLightGBM elapsed=${lightMs}ms" +
                    "\n\n保存済みNARデータを検証しています..."
            }

            val parityStart = SystemClock.elapsedRealtime()

            val parityStatus = try {
                NarModelParityTest.run(this@MainActivity)
            } catch (t: Throwable) {
                "NAR MODEL PARITY FAIL\n" +
                    "${t.javaClass.simpleName}: ${t.message}"
            }

            val parityMs =
                SystemClock.elapsedRealtime() - parityStart

            val transformParityStart =
                SystemClock.elapsedRealtime()

            val transformParityStatus = try {
                NarV1TransformParityTest.run(
                    this@MainActivity
                )
            } catch (t: Throwable) {
                "NAR V1 TRANSFORM PARITY FAIL\n" +
                    "${t.javaClass.simpleName}: ${t.message}"
            }

            val transformParityMs =
                SystemClock.elapsedRealtime() -
                    transformParityStart

            val sourceParityStart =
                SystemClock.elapsedRealtime()

            val sourceParityStatus = try {
                NarV1SourceParityTest.run(
                    this@MainActivity
                )
            } catch (t: Throwable) {
                "NAR V1 SOURCE PARITY FAIL\n" +
                    "${t.javaClass.simpleName}: ${t.message}"
            }

            val sourceParityMs =
                SystemClock.elapsedRealtime() -
                    sourceParityStart

            val dailyStart =
                SystemClock.elapsedRealtime()

            val dailyStatus =
                NarDailyRaceDownloadTest.run()

            val dailyMs =
                SystemClock.elapsedRealtime() -
                    dailyStart

            val narStart = SystemClock.elapsedRealtime()
            val narStatus = NarLocalParserTest.run(this@MainActivity)
            val narMs = SystemClock.elapsedRealtime() - narStart

            val lightGbmOk =
                lightGbmStatus.contains("LightGBM C API linked successfully") &&
                    !lightGbmStatus.contains("LightGBM FAIL") &&
                    (
                        lightGbmStatus.contains("LOAD/PREDICT OK") ||
                            lightGbmStatus.contains("TRAIN/SAVE OK")
                    )

            val narOk =
                narStatus.startsWith("LOCAL CSV PARSE OK") &&
                    narStatus.contains("status=PASS")

            val entries =
                Regex("(?m)^entries=(\\d+)$")
                    .find(narStatus)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()

            val outcomes =
                Regex("(?m)^outcomes=(\\d+)$")
                    .find(narStatus)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()

            val payouts =
                Regex("(?m)^payouts=(\\d+)$")
                    .find(narStatus)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()

            val narDataOk =
                narOk &&
                    entries == 12 &&
                    outcomes == 12 &&
                    payouts != null

            val parityOk =
                parityStatus.startsWith(
                    "NAR MODEL PARITY OK"
                )

            val transformParityOk =
                transformParityStatus.startsWith(
                    "NAR V1 TRANSFORM PARITY OK"
                )

            val sourceParityOk =
                sourceParityStatus.startsWith(
                    "NAR V1 SOURCE PARITY OK"
                )

            val dailyOk =
                dailyStatus.startsWith(
                    "NAR DAILY DOWNLOAD OK"
                )

            val overallOk =
                lightGbmOk &&
                    parityOk &&
                    transformParityOk &&
                    sourceParityOk &&
                    dailyOk &&
                    narDataOk

            val summary = buildString {
                append("=== 実機検証結果 ===")
                append("\n総合判定=")
                append(if (overallOk) "正常" else "要確認")

                append("\nLightGBM/JNI=")
                append(if (lightGbmOk) "正常" else "異常")

                append("\nBaseline V1実モデル=")
                append(if (parityOk) "正常" else "異常")

                append("\nV1 raw→68特徴変換=")
                append(
                    if (transformParityOk) {
                        "正常"
                    } else {
                        "異常"
                    }
                )

                append("\nV1 CSV→予測経路=")
                append(
                    if (sourceParityOk) {
                        "正常"
                    } else {
                        "異常"
                    }
                )

                append("\n当日NAR公式通信=")
                append(
                    if (dailyOk) {
                        "正常"
                    } else {
                        "異常"
                    }
                )

                append("\n保存済みNARデータ=")
                append(if (narDataOk) "正常" else "異常")

                append("\nこの検証でのNAR通信=あり")
                append("\n検証対象=大井 1998/08/06 1R")

                append("\n\n発走前エントリー=")
                append(entries?.let { "${it}頭" } ?: "取得失敗")

                append("\n発走後結果=")
                append(outcomes?.let { "${it}頭" } ?: "取得失敗")

                append("\n払戻レコード=")
                append(payouts?.toString() ?: "取得失敗")

                append("\n期待頭数=12頭")
                append("\n頭数一致=")
                append(
                    if (entries == 12 && outcomes == 12) {
                        "正常"
                    } else {
                        "要確認"
                    }
                )

                append("\n払戻検証=")
                append(
                    if (narOk && payouts != null) {
                        "正常"
                    } else {
                        "要確認"
                    }
                )

                append(
                    "\n\n※「正常」は、この検証対象について" +
                        "実データを解析し期待値との一致を確認した結果です。"
                )
            }

            runOnUiThread {
                view.text =
                    summary +
                    "\n\n--- 詳細ログ ---\n\n" +
                    lightGbmStatus +
                    "\n\nLightGBM elapsed=${lightMs}ms" +
                    "\n\n--- Baseline V1 parity ---\n\n" +
                    parityStatus +
                    "\nParity elapsed=${parityMs}ms" +
                    "\n\n--- V1 raw -> 68 transform parity ---\n\n" +
                    transformParityStatus +
                    "\nTransform parity elapsed=${transformParityMs}ms" +
                    "\n\n--- V1 source CSV -> prediction parity ---\n\n" +
                    sourceParityStatus +
                    "\nSource parity elapsed=${sourceParityMs}ms" +
                    "\n\n--- NAR daily live download ---\n\n" +
                    dailyStatus +
                    "\nDaily download elapsed=${dailyMs}ms" +
                    "\n\n--- Local NAR data ---\n\n" +
                    narStatus +
                    "\nLocal data elapsed=${narMs}ms"

                scrollView.post {
                    scrollView.fullScroll(View.FOCUS_UP)
                    scrollView.scrollTo(0, 0)
                }
            }
        }.start()
    }
}
