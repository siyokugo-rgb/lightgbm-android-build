package com.keiba.ai

object LightGbmNative {
    init {
        System.loadLibrary("keiba_lgbm")
    }

    external fun nativeStatus(): String
    external fun nativeTrainPredictTest(): String
}
