package com.keiba.ai

object LightGbmNative {
    init {
        System.loadLibrary("keiba_lgbm")
    }

    external fun nativeStatus(): String
    external fun nativeTrainPredictTest(): String
    external fun nativeTrainSaveTest(modelPath: String): String
    external fun nativeLoadPredictTest(modelPath: String): String
}
