#include <jni.h>
#include <string>
#include <sstream>
#include <iomanip>
#include <LightGBM/c_api.h>

extern "C"
JNIEXPORT jstring JNICALL
Java_com_keiba_ai_LightGbmNative_nativeStatus(
        JNIEnv* env,
        jobject /* thiz */) {

    const char* error = LGBM_GetLastError();

    std::string result = "LightGBM C API linked successfully";

    if (error != nullptr && error[0] != '\0') {
        result += " / last_error: ";
        result += error;
    }

    return env->NewStringUTF(result.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_keiba_ai_LightGbmNative_nativeTrainPredictTest(
        JNIEnv* env,
        jobject /* thiz */) {

    DatasetHandle dataset = nullptr;
    BoosterHandle booster = nullptr;

    auto cleanup = [&]() {
        if (booster != nullptr) {
            LGBM_BoosterFree(booster);
            booster = nullptr;
        }
        if (dataset != nullptr) {
            LGBM_DatasetFree(dataset);
            dataset = nullptr;
        }
    };

    auto fail = [&](const char* step) -> jstring {
        std::string result = "TRAIN/PREDICT FAIL\nstep=";
        result += step;
        result += "\nerror=";
        const char* error = LGBM_GetLastError();
        result += (error != nullptr ? error : "unknown");
        cleanup();
        return env->NewStringUTF(result.c_str());
    };

    const double train_data[16] = {
        0.0, 1.0, 2.0, 3.0,
        4.0, 5.0, 6.0, 7.0,
        8.0, 9.0, 10.0, 11.0,
        12.0, 13.0, 14.0, 15.0
    };

    const float labels[16] = {
        0.0f, 0.0f, 0.0f, 0.0f,
        0.0f, 0.0f, 0.0f, 0.0f,
        1.0f, 1.0f, 1.0f, 1.0f,
        1.0f, 1.0f, 1.0f, 1.0f
    };

    const char* dataset_params =
        "max_bin=16 min_data_in_bin=1 min_data_in_leaf=1 feature_pre_filter=false";

    if (LGBM_DatasetCreateFromMat(
            train_data,
            C_API_DTYPE_FLOAT64,
            16,
            1,
            1,
            dataset_params,
            nullptr,
            &dataset) != 0) {
        return fail("DatasetCreateFromMat");
    }

    if (LGBM_DatasetSetField(
            dataset,
            "label",
            labels,
            16,
            C_API_DTYPE_FLOAT32) != 0) {
        return fail("DatasetSetField(label)");
    }

    const char* booster_params =
        "objective=binary metric=binary_logloss learning_rate=0.3 "
        "num_leaves=4 min_data_in_leaf=1 min_data_in_bin=1 "
        "feature_pre_filter=false verbosity=-1 num_threads=1 force_col_wise=true";

    if (LGBM_BoosterCreate(dataset, booster_params, &booster) != 0) {
        return fail("BoosterCreate");
    }

    int trained_iterations = 0;
    for (int i = 0; i < 25; ++i) {
        int is_finished = 0;
        if (LGBM_BoosterUpdateOneIter(booster, &is_finished) != 0) {
            return fail("BoosterUpdateOneIter");
        }
        ++trained_iterations;
        if (is_finished != 0) {
            break;
        }
    }

    const double low_data[1] = {1.0};
    const double high_data[1] = {14.0};

    int64_t out_len = 0;
    double low_pred[1] = {0.0};
    if (LGBM_BoosterPredictForMat(
            booster,
            low_data,
            C_API_DTYPE_FLOAT64,
            1,
            1,
            1,
            C_API_PREDICT_NORMAL,
            0,
            -1,
            "",
            &out_len,
            low_pred) != 0) {
        return fail("Predict(low)");
    }
    if (out_len != 1) {
        cleanup();
        return env->NewStringUTF("TRAIN/PREDICT FAIL\nstep=Predict(low)\nerror=unexpected output length");
    }

    out_len = 0;
    double high_pred[1] = {0.0};
    if (LGBM_BoosterPredictForMat(
            booster,
            high_data,
            C_API_DTYPE_FLOAT64,
            1,
            1,
            1,
            C_API_PREDICT_NORMAL,
            0,
            -1,
            "",
            &out_len,
            high_pred) != 0) {
        return fail("Predict(high)");
    }
    if (out_len != 1) {
        cleanup();
        return env->NewStringUTF("TRAIN/PREDICT FAIL\nstep=Predict(high)\nerror=unexpected output length");
    }

    const bool separated = high_pred[0] > low_pred[0];

    std::ostringstream result;
    result << std::fixed << std::setprecision(6)
           << "TRAIN/PREDICT OK"
           << "\niterations=" << trained_iterations
           << "\nlow(1.0)=" << low_pred[0]
           << "\nhigh(14.0)=" << high_pred[0]
           << "\nseparation=" << (separated ? "PASS" : "FAIL");

    cleanup();
    const std::string text = result.str();
    return env->NewStringUTF(text.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_keiba_ai_LightGbmNative_nativeTrainSaveTest(
        JNIEnv* env,
        jobject /* thiz */,
        jstring model_path_j) {

    if (model_path_j == nullptr) {
        return env->NewStringUTF("TRAIN/SAVE FAIL\nstep=Path\nerror=null path");
    }

    const char* model_path = env->GetStringUTFChars(model_path_j, nullptr);
    if (model_path == nullptr) {
        return env->NewStringUTF("TRAIN/SAVE FAIL\nstep=Path\nerror=GetStringUTFChars failed");
    }

    DatasetHandle dataset = nullptr;
    BoosterHandle booster = nullptr;

    auto cleanup = [&]() {
        if (booster != nullptr) {
            LGBM_BoosterFree(booster);
            booster = nullptr;
        }
        if (dataset != nullptr) {
            LGBM_DatasetFree(dataset);
            dataset = nullptr;
        }
    };

    auto fail = [&](const char* step) -> jstring {
        std::string result = "TRAIN/SAVE FAIL\nstep=";
        result += step;
        result += "\nerror=";
        const char* error = LGBM_GetLastError();
        result += (error != nullptr ? error : "unknown");
        cleanup();
        env->ReleaseStringUTFChars(model_path_j, model_path);
        return env->NewStringUTF(result.c_str());
    };

    const double train_data[16] = {
        0.0, 1.0, 2.0, 3.0,
        4.0, 5.0, 6.0, 7.0,
        8.0, 9.0, 10.0, 11.0,
        12.0, 13.0, 14.0, 15.0
    };

    const float labels[16] = {
        0.0f, 0.0f, 0.0f, 0.0f,
        0.0f, 0.0f, 0.0f, 0.0f,
        1.0f, 1.0f, 1.0f, 1.0f,
        1.0f, 1.0f, 1.0f, 1.0f
    };

    const char* dataset_params =
        "max_bin=16 min_data_in_bin=1 min_data_in_leaf=1 feature_pre_filter=false";

    if (LGBM_DatasetCreateFromMat(
            train_data,
            C_API_DTYPE_FLOAT64,
            16,
            1,
            1,
            dataset_params,
            nullptr,
            &dataset) != 0) {
        return fail("DatasetCreateFromMat");
    }

    if (LGBM_DatasetSetField(
            dataset,
            "label",
            labels,
            16,
            C_API_DTYPE_FLOAT32) != 0) {
        return fail("DatasetSetField(label)");
    }

    const char* booster_params =
        "objective=binary metric=binary_logloss learning_rate=0.3 "
        "num_leaves=4 min_data_in_leaf=1 min_data_in_bin=1 "
        "feature_pre_filter=false verbosity=-1 num_threads=1 force_col_wise=true";

    if (LGBM_BoosterCreate(dataset, booster_params, &booster) != 0) {
        return fail("BoosterCreate");
    }

    int trained_iterations = 0;
    for (int i = 0; i < 25; ++i) {
        int is_finished = 0;
        if (LGBM_BoosterUpdateOneIter(booster, &is_finished) != 0) {
            return fail("BoosterUpdateOneIter");
        }
        ++trained_iterations;
        if (is_finished != 0) {
            break;
        }
    }

    const double low_data[1] = {1.0};
    const double high_data[1] = {14.0};

    int64_t out_len = 0;
    double low_pred[1] = {0.0};
    if (LGBM_BoosterPredictForMat(
            booster, low_data, C_API_DTYPE_FLOAT64,
            1, 1, 1, C_API_PREDICT_NORMAL,
            0, -1, "", &out_len, low_pred) != 0) {
        return fail("Predict(low)");
    }
    if (out_len != 1) {
        cleanup();
        env->ReleaseStringUTFChars(model_path_j, model_path);
        return env->NewStringUTF("TRAIN/SAVE FAIL\nstep=Predict(low)\nerror=unexpected output length");
    }

    out_len = 0;
    double high_pred[1] = {0.0};
    if (LGBM_BoosterPredictForMat(
            booster, high_data, C_API_DTYPE_FLOAT64,
            1, 1, 1, C_API_PREDICT_NORMAL,
            0, -1, "", &out_len, high_pred) != 0) {
        return fail("Predict(high)");
    }
    if (out_len != 1) {
        cleanup();
        env->ReleaseStringUTFChars(model_path_j, model_path);
        return env->NewStringUTF("TRAIN/SAVE FAIL\nstep=Predict(high)\nerror=unexpected output length");
    }

    if (LGBM_BoosterSaveModel(
            booster,
            0,
            -1,
            0,
            model_path) != 0) {
        return fail("BoosterSaveModel");
    }

    const std::string path_copy(model_path);
    cleanup();
    env->ReleaseStringUTFChars(model_path_j, model_path);

    std::ostringstream result;
    result << std::fixed << std::setprecision(6)
           << "TRAIN/SAVE OK"
           << "\niterations=" << trained_iterations
           << "\nlow(1.0)=" << low_pred[0]
           << "\nhigh(14.0)=" << high_pred[0]
           << "\npath=" << path_copy;

    const std::string text = result.str();
    return env->NewStringUTF(text.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_keiba_ai_LightGbmNative_nativeLoadPredictTest(
        JNIEnv* env,
        jobject /* thiz */,
        jstring model_path_j) {

    if (model_path_j == nullptr) {
        return env->NewStringUTF("LOAD/PREDICT FAIL\nstep=Path\nerror=null path");
    }

    const char* model_path = env->GetStringUTFChars(model_path_j, nullptr);
    if (model_path == nullptr) {
        return env->NewStringUTF("LOAD/PREDICT FAIL\nstep=Path\nerror=GetStringUTFChars failed");
    }

    BoosterHandle booster = nullptr;

    auto cleanup = [&]() {
        if (booster != nullptr) {
            LGBM_BoosterFree(booster);
            booster = nullptr;
        }
    };

    auto fail = [&](const char* step) -> jstring {
        std::string result = "LOAD/PREDICT FAIL\nstep=";
        result += step;
        result += "\nerror=";
        const char* error = LGBM_GetLastError();
        result += (error != nullptr ? error : "unknown");
        cleanup();
        env->ReleaseStringUTFChars(model_path_j, model_path);
        return env->NewStringUTF(result.c_str());
    };

    int loaded_iterations = 0;
    if (LGBM_BoosterCreateFromModelfile(
            model_path,
            &loaded_iterations,
            &booster) != 0) {
        return fail("BoosterCreateFromModelfile");
    }

    const double low_data[1] = {1.0};
    const double high_data[1] = {14.0};

    int64_t out_len = 0;
    double low_pred[1] = {0.0};
    if (LGBM_BoosterPredictForMat(
            booster, low_data, C_API_DTYPE_FLOAT64,
            1, 1, 1, C_API_PREDICT_NORMAL,
            0, -1, "", &out_len, low_pred) != 0) {
        return fail("Predict(low)");
    }
    if (out_len != 1) {
        cleanup();
        env->ReleaseStringUTFChars(model_path_j, model_path);
        return env->NewStringUTF("LOAD/PREDICT FAIL\nstep=Predict(low)\nerror=unexpected output length");
    }

    out_len = 0;
    double high_pred[1] = {0.0};
    if (LGBM_BoosterPredictForMat(
            booster, high_data, C_API_DTYPE_FLOAT64,
            1, 1, 1, C_API_PREDICT_NORMAL,
            0, -1, "", &out_len, high_pred) != 0) {
        return fail("Predict(high)");
    }
    if (out_len != 1) {
        cleanup();
        env->ReleaseStringUTFChars(model_path_j, model_path);
        return env->NewStringUTF("LOAD/PREDICT FAIL\nstep=Predict(high)\nerror=unexpected output length");
    }

    const bool separated = high_pred[0] > low_pred[0];
    const std::string path_copy(model_path);

    cleanup();
    env->ReleaseStringUTFChars(model_path_j, model_path);

    std::ostringstream result;
    result << std::fixed << std::setprecision(6)
           << "LOAD/PREDICT OK"
           << "\nloaded_iterations=" << loaded_iterations
           << "\nlow(1.0)=" << low_pred[0]
           << "\nhigh(14.0)=" << high_pred[0]
           << "\nseparation=" << (separated ? "PASS" : "FAIL")
           << "\npath=" << path_copy;

    const std::string text = result.str();
    return env->NewStringUTF(text.c_str());
}

extern "C"
JNIEXPORT jdouble JNICALL
Java_com_keiba_ai_LightGbmNative_nativePredict(
        JNIEnv* env,
        jobject /* thiz */,
        jstring model_path_j,
        jdoubleArray features_j) {

    if (model_path_j == nullptr || features_j == nullptr) {
        return -1.0;
    }

    const jsize feature_count =
        env->GetArrayLength(features_j);

    if (feature_count <= 0) {
        return -2.0;
    }

    const char* model_path =
        env->GetStringUTFChars(
            model_path_j,
            nullptr
        );

    if (model_path == nullptr) {
        return -3.0;
    }

    jdouble* features =
        env->GetDoubleArrayElements(
            features_j,
            nullptr
        );

    if (features == nullptr) {
        env->ReleaseStringUTFChars(
            model_path_j,
            model_path
        );
        return -4.0;
    }

    BoosterHandle booster = nullptr;
    int loaded_iterations = 0;

    const int load_rc =
        LGBM_BoosterCreateFromModelfile(
            model_path,
            &loaded_iterations,
            &booster
        );

    if (load_rc != 0) {
        env->ReleaseDoubleArrayElements(
            features_j,
            features,
            JNI_ABORT
        );

        env->ReleaseStringUTFChars(
            model_path_j,
            model_path
        );

        return -5.0;
    }

    int model_feature_count = 0;

    const int feature_count_rc =
        LGBM_BoosterGetNumFeature(
            booster,
            &model_feature_count
        );

    if (feature_count_rc != 0) {
        LGBM_BoosterFree(booster);

        env->ReleaseDoubleArrayElements(
            features_j,
            features,
            JNI_ABORT
        );

        env->ReleaseStringUTFChars(
            model_path_j,
            model_path
        );

        return -7.0;
    }

    if (
        model_feature_count !=
            static_cast<int>(feature_count)
    ) {
        LGBM_BoosterFree(booster);

        env->ReleaseDoubleArrayElements(
            features_j,
            features,
            JNI_ABORT
        );

        env->ReleaseStringUTFChars(
            model_path_j,
            model_path
        );

        return -2.0;
    }

    int64_t out_len = 0;
    double prediction[1] = {0.0};

    const int predict_rc =
        LGBM_BoosterPredictForMat(
            booster,
            features,
            C_API_DTYPE_FLOAT64,
            1,
            feature_count,
            1,
            C_API_PREDICT_NORMAL,
            0,
            -1,
            "",
            &out_len,
            prediction
        );

    LGBM_BoosterFree(booster);

    env->ReleaseDoubleArrayElements(
        features_j,
        features,
        JNI_ABORT
    );

    env->ReleaseStringUTFChars(
        model_path_j,
        model_path
    );

    if (
        predict_rc != 0
        || out_len != 1
    ) {
        return -6.0;
    }

    return prediction[0];
}
