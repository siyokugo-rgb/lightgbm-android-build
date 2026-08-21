package com.keiba.ai

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val view = TextView(this).apply {
            text = "Starting test..."
            textSize = 18f
            gravity = Gravity.START
            setPadding(48, 48, 48, 48)
        }

        val scrollView = ScrollView(this).apply {
            addView(view)
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
                    "\n\nNAR: downloading..."
            }

            val narStart = SystemClock.elapsedRealtime()
            val narStatus = NarLocalParserTest.run(this@MainActivity)
            val narMs = SystemClock.elapsedRealtime() - narStart

            runOnUiThread {
                view.text =
                    lightGbmStatus +
                    "\n\nLightGBM elapsed=${lightMs}ms" +
                    "\n\n" + narStatus +
                    "\nNAR elapsed=${narMs}ms"
            }
        }.start()
    }
}
