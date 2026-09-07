package com.keiba.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class NarForecastWeatherSnapshotStoreTest {

    @Test
    fun savesAndVerifiesRawForecastSnapshot() {
        val root =
            Files.createTempDirectory(
                "nar-weather-snapshot"
            ).toFile()

        try {
            val saved =
                NarForecastWeatherSnapshotStore
                    .saveToRoot(
                        root,
                        sampleData()
                    )

            assertEquals(
                NarForecastWeatherSnapshotStore
                    .SaveStatus.CREATED,
                saved.status
            )

            assertTrue(
                saved.directory
                    .resolve(
                        "forecast.json"
                    )
                    .isFile
            )

            assertTrue(
                saved.directory
                    .resolve(
                        "manifest.txt"
                    )
                    .isFile
            )

            assertArrayEquals(
                sampleData().responseBytes,
                saved.directory
                    .resolve(
                        "forecast.json"
                    )
                    .readBytes()
            )

            assertEquals(
                "20260829",
                saved.directory
                    .parentFile
                    ?.name
            )

            assertEquals(
                1_788_001_120_000L,
                saved.pitEvidenceAtEpochMillis
            )

            assertTrue(
                NarForecastWeatherSnapshotStore
                    .verifySnapshot(
                        saved.directory
                    )
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun sameCaptureIsIdempotent() {
        val root =
            Files.createTempDirectory(
                "nar-weather-idempotent"
            ).toFile()

        try {
            val first =
                NarForecastWeatherSnapshotStore
                    .saveToRoot(
                        root,
                        sampleData()
                    )

            val second =
                NarForecastWeatherSnapshotStore
                    .saveToRoot(
                        root,
                        sampleData()
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
                first.directory.canonicalFile,
                second.directory.canonicalFile
            )

            assertEquals(
                first.snapshotSha256,
                second.snapshotSha256
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun sameMillisDifferentForecastIsPreservedSeparately() {
        val root =
            Files.createTempDirectory(
                "nar-weather-same-time"
            ).toFile()

        try {
            val first =
                NarForecastWeatherSnapshotStore
                    .saveToRoot(
                        root,
                        sampleData()
                    )

            val changed =
                sampleData()
                    .copy(
                        responseBytes =
                            sampleResponse()
                                .replace(
                                    "\"temperature_2m\":[29.0]",
                                    "\"temperature_2m\":[30.0]"
                                )
                                .toByteArray(
                                    Charsets.UTF_8
                                )
                    )

            val second =
                NarForecastWeatherSnapshotStore
                    .saveToRoot(
                        root,
                        changed
                    )

            assertNotEquals(
                first.directory.canonicalFile,
                second.directory.canonicalFile
            )

            assertNotEquals(
                first.forecastSha256,
                second.forecastSha256
            )

            assertTrue(
                NarForecastWeatherSnapshotStore
                    .verifySnapshot(
                        first.directory
                    )
            )

            assertTrue(
                NarForecastWeatherSnapshotStore
                    .verifySnapshot(
                        second.directory
                    )
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rawForecastTamperingIsDetected() {
        val root =
            Files.createTempDirectory(
                "nar-weather-tamper"
            ).toFile()

        try {
            val saved =
                NarForecastWeatherSnapshotStore
                    .saveToRoot(
                        root,
                        sampleData()
                    )

            val forecast =
                saved.directory
                    .resolve(
                        "forecast.json"
                    )

            forecast.writeText(
                sampleResponse()
                    .replace(
                        "\"temperature_2m\":[29.0]",
                        "\"temperature_2m\":[31.0]"
                    ),
                Charsets.UTF_8
            )

            assertFalse(
                NarForecastWeatherSnapshotStore
                    .verifySnapshot(
                        saved.directory
                    )
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun unexpectedFileIsRejected() {
        val root =
            Files.createTempDirectory(
                "nar-weather-extra-file"
            ).toFile()

        try {
            val saved =
                NarForecastWeatherSnapshotStore
                    .saveToRoot(
                        root,
                        sampleData()
                    )

            saved.directory
                .resolve(
                    "observed-weather.json"
                )
                .writeText(
                    "{}",
                    Charsets.UTF_8
                )

            assertFalse(
                NarForecastWeatherSnapshotStore
                    .verifySnapshot(
                        saved.directory
                    )
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun mismatchedRequestProvenanceIsRejected() {
        val root =
            Files.createTempDirectory(
                "nar-weather-request"
            ).toFile()

        try {
            val bad =
                sampleData()
                    .copy(
                        requestUrl =
                            NarForecastWeatherDownloader
                                .buildRequestUrl(
                                    35.0,
                                    139.0
                                )
                    )

            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarForecastWeatherSnapshotStore
                    .saveToRoot(
                        root,
                        bad
                    )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun invalidDownloadedAtIsRejected() {
        val root =
            Files.createTempDirectory(
                "nar-weather-time"
            ).toFile()

        try {
            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarForecastWeatherSnapshotStore
                    .saveToRoot(
                        root,
                        sampleData()
                            .copy(
                                downloadedAtEpochMillis =
                                    0L
                            )
                    )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun malformedForecastIsRejected() {
        val root =
            Files.createTempDirectory(
                "nar-weather-malformed"
            ).toFile()

        try {
            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarForecastWeatherSnapshotStore
                    .saveToRoot(
                        root,
                        sampleData()
                            .copy(
                                responseBytes =
                                    """{"hourly":{}}"""
                                        .toByteArray(
                                            Charsets.UTF_8
                                        )
                            )
                    )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun missingServerDateUsesDownloadCompletionAsPitEvidence() {
        val root =
            Files.createTempDirectory(
                "nar-weather-no-server-date"
            ).toFile()

        try {
            val saved =
                NarForecastWeatherSnapshotStore
                    .saveToRoot(
                        root,
                        sampleData()
                            .copy(
                                serverDateEpochMillis =
                                    null
                            )
                    )

            assertEquals(
                1_788_001_100_000L,
                saved.pitEvidenceAtEpochMillis
            )

            assertTrue(
                NarForecastWeatherSnapshotStore
                    .verifySnapshot(
                        saved.directory
                    )
            )
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
