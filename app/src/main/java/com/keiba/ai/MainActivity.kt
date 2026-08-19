package com.keiba.ai

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val status = try {
            LightGbmNative.nativeStatus()
        } catch (t: Throwable) {
            "LightGBM load failed\n${t.javaClass.simpleName}: ${t.message}"
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
