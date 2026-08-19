plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.keiba.ai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.keiba.ai"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
}
