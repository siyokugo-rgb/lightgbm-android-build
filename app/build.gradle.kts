plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val venueCoordinateConfigSource =
    rootProject.file("config/nar-v3-venue-coordinates.json")

val generatedVenueCoordinateAssetsDir =
    layout.buildDirectory.dir(
        "generated/assets/venueCoordinates"
    )

val copyVenueCoordinateConfig by tasks.registering(Copy::class) {
    description =
        "Package single-file venue coordinate Source of Truth into generated assets"
    from(venueCoordinateConfigSource)
    into(generatedVenueCoordinateAssetsDir)
    rename { "nar-v3-venue-coordinates.json" }
    onlyIf {
        venueCoordinateConfigSource.isFile
    }
    doFirst {
        require(venueCoordinateConfigSource.isFile) {
            "missing Source of Truth: config/nar-v3-venue-coordinates.json"
        }
    }
}

android {
    namespace = "com.keiba.ai"
    compileSdk = 35
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "com.keiba.ai"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"

        testInstrumentationRunner =
            "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
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

    sourceSets {
        getByName("main") {
            assets.srcDir(
                generatedVenueCoordinateAssetsDir
            )
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../android-native/CMakeLists.txt")
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(copyVenueCoordinateConfig)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Android framework org.json is stubbed in local JVM unit tests.
    // Production code continues to use the platform org.json; this jar is
    // test-classpath only so typed ForecastWeather parser tests can run.
    testImplementation("org.json:json:20240303")

    // AndroidX Test — androidTest only (pinned stable from Google Maven).
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
