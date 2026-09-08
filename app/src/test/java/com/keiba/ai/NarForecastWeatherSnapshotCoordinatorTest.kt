package com.keiba.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class NarForecastWeatherSnapshotCoordinatorTest {

    @Test
    fun capturesAndVerifiesDownloadedForecast() {
        val root =
            Files.createTempDirectory(
                "weather-coordinator"
            ).toFile()

        try {
            val result =
                NarForecastWeatherSnapshotCoordinator
                    .captureDownloadedToRoot(
                        root = root,
                        data = sampleData()
                    )

            assertEquals(
                NarForecastWeatherSnapshotStore
                    .SaveStatus.CREATED,
                result.status
            )

            assertEquals(
                35.591339,
                result.requestedLatitude,
                0.0
            )

            assertEquals(
                139.742608,
                result.requestedLongitude,
                0.0
            )

            assertEquals(
                1_788_001_120_000L,
                result.pitEvidenceAtEpochMillis
            )

            assertTrue(
                result.snapshotDirectory
                    .isDirectory
            )

            assertTrue(
                NarForecastWeatherSnapshotStore
                    .verifySnapshot(
                        result.snapshotDirectory
                    )
            )

            assertTrue(
                result.snapshotDirectory
                    .resolve(
                        "forecast.json"
                    )
                    .isFile
            )

            assertFalse(
                result.snapshotDirectory
                    .resolve(
                        "observed-weather.json"
                    )
                    .exists()
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun repeatedSameForecastReturnsAlreadyPresent() {
        val root =
            Files.createTempDirectory(
                "weather-coordinator-repeat"
            ).toFile()

        try {
            val first =
                NarForecastWeatherSnapshotCoordinator
                    .captureDownloadedToRoot(
                        root = root,
                        data = sampleData()
                    )

            val second =
                NarForecastWeatherSnapshotCoordinator
                    .captureDownloadedToRoot(
                        root = root,
                        data = sampleData()
                    )

            assertEquals(
                NarForecastWeatherSnapshotStore
                    .SaveStatus.CREATED,
                first.status
            )

            assertEquals(
                NarForecastWeatherSnapshotStore
                    .SaveStatus.ALREADY_PRESENT,
                second.status
            )

            assertEquals(
                first.snapshotSha256,
                second.snapshotSha256
            )

            assertEquals(
                first.snapshotDirectory,
                second.snapshotDirectory
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun tamperedExistingForecastIsRejected() {
        val root =
            Files.createTempDirectory(
                "weather-coordinator-tamper"
            ).toFile()

        try {
            val first =
                NarForecastWeatherSnapshotCoordinator
                    .captureDownloadedToRoot(
                        root = root,
                        data = sampleData()
                    )

            first.snapshotDirectory
                .resolve(
                    "forecast.json"
                )
                .appendText(
                    "tampered"
                )

            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarForecastWeatherSnapshotCoordinator
                    .captureDownloadedToRoot(
                        root = root,
                        data = sampleData()
                    )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun sampleData():
        NarForecastWeatherDownloader.ForecastResponse {
        val latitude =
            35.591339

        val longitude =
            139.742608

        return NarForecastWeatherDownloader
            .ForecastResponse(
                requestedLatitude =
                    latitude,
                requestedLongitude =
                    longitude,
                requestUrl =
                    NarForecastWeatherDownloader
                        .buildRequestUrl(
                            latitude,
                            longitude
                        ),
                responseBytes =
                    sampleResponse()
                        .toByteArray(
                            Charsets.UTF_8
                        ),
                downloadedAtEpochMillis =
                    1_788_001_100_000L,
                serverDateEpochMillis =
                    1_788_001_120_000L
            )
    }

    private fun sampleResponse(): String =
        """
        {
          "latitude":35.6,
          "longitude":139.75,
          "utc_offset_seconds":0,
          "hourly":{
            "time":[1788404400],
            "temperature_2m":[29.0],
            "relative_humidity_2m":[70],
            "pressure_msl":[1008.0],
            "surface_pressure":[1007.0],
            "precipitation":[0.0],
            "weather_code":[1],
            "wind_speed_10m":[3.0],
            "wind_direction_10m":[180],
            "wind_gusts_10m":[5.0]
          }
        }
        """.trimIndent()
}
