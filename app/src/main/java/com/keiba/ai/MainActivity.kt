package com.keiba.ai

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import java.io.File

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val modelFile = File(filesDir, "lightgbm_test_model.txt")

        val status = try {
            val mode: String
            val result: String

            if (modelFile.exists()) {
                mode = "LOAD_EXISTING"
                result = LightGbmNative.nativeLoadPredictTest(modelFile.absolutePath)
            } else {
                mode = "TRAIN_AND_SAVE"
                result = LightGbmNative.nativeTrainSaveTest(modelFile.absolutePath)
            }

            LightGbmNative.nativeStatus() +
                "\n\nmode=" + mode +
                "\nmodel=" + modelFile.absolutePath +
                "\n\n" + result
        } catch (t: Throwable) {
            "LightGBM model test failed\n${t.javaClass.simpleName}: ${t.message}"
        }

        val view = TextView(this).apply {
            text = status
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }

        setContentView(view)
    }
}
