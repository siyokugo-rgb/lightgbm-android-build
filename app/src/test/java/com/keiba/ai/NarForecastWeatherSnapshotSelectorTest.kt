package com.keiba.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

class NarForecastWeatherSnapshotSelectorTest {

    private val latitude =
        35.591339

    private val longitude =
        139.742608

    private val otherLatitude =
        34.766583

    private val otherLongitude =
        135.445194

    @Test
    fun selectsSingleEligibleSnapshot() {
        withRoot { root ->
            val saved =
                saveSnapshot(
                    root = root,
                    latitude = latitude,
                    longitude = longitude,
                    downloadedAt = 1_788_001_100_000L,
                    serverDate = 1_788_001_050_000L,
                    temperature = 29.0
                )

            val predictionAsOf =
                1_788_001_200_000L

            val selection =
                NarForecastWeatherSnapshotSelector
                    .selectLatestFromRoot(
                        root = root,
                        latitude = latitude,
                        longitude = longitude,
                        predictionAsOfEpochMillis =
                            predictionAsOf
                    )

            assertNotNull(selection)
            assertEquals(
                saved.directory.canonicalFile,
                selection!!.directory
            )
            assertEquals(
                saved.pitEvidenceAtEpochMillis,
                selection.pitEvidenceAtEpochMillis
            )
            assertEquals(
                predictionAsOf -
                    saved.pitEvidenceAtEpochMillis,
                selection.ageMillis
            )
        }
    }

    @Test
    fun selectsLatestPitEvidenceAmongMatches() {
        withRoot { root ->
            saveSnapshot(
                root = root,
                latitude = latitude,
                longitude = longitude,
                downloadedAt = 1_788_001_100_000L,
                serverDate = 1_788_001_100_000L,
                temperature = 20.0
            )

            val newer =
                saveSnapshot(
                    root = root,
                    latitude = latitude,
                    longitude = longitude,
                    downloadedAt = 1_788_001_150_000L,
                    serverDate = 1_788_001_160_000L,
                    temperature = 21.0
                )

            val selection =
                NarForecastWeatherSnapshotSelector
                    .selectLatestFromRoot(
                        root = root,
                        latitude = latitude,
                        longitude = longitude,
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )

            assertEquals(
                newer.directory.canonicalFile,
                selection!!.directory
            )
            assertEquals(
                1_788_001_160_000L,
                selection.pitEvidenceAtEpochMillis
            )
        }
    }

    @Test
    fun samePitEvidencePrefersLaterDownloadedAt() {
        withRoot { root ->
            saveSnapshot(
                root = root,
                latitude = latitude,
                longitude = longitude,
                downloadedAt = 1_788_001_100_000L,
                serverDate = 1_788_001_180_000L,
                temperature = 20.0
            )

            val laterDownload =
                saveSnapshot(
                    root = root,
                    latitude = latitude,
                    longitude = longitude,
                    downloadedAt = 1_788_001_170_000L,
                    serverDate = 1_788_001_180_000L,
                    temperature = 21.0
                )

            val selection =
                NarForecastWeatherSnapshotSelector
                    .selectLatestFromRoot(
                        root = root,
                        latitude = latitude,
                        longitude = longitude,
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )

            assertEquals(
                laterDownload.directory.canonicalFile,
                selection!!.directory
            )
            assertEquals(
                1_788_001_170_000L,
                selection.downloadedAtEpochMillis
            )
        }
    }

    @Test
    fun ignoresDifferentCoordinates() {
        withRoot { root ->
            val matching =
                saveSnapshot(
                    root = root,
                    latitude = latitude,
                    longitude = longitude,
                    downloadedAt = 1_788_001_100_000L,
                    serverDate = null,
                    temperature = 20.0
                )

            saveSnapshot(
                root = root,
                latitude = otherLatitude,
                longitude = otherLongitude,
                downloadedAt = 1_788_001_190_000L,
                serverDate = null,
                temperature = 30.0
            )

            val selection =
                NarForecastWeatherSnapshotSelector
                    .selectLatestFromRoot(
                        root = root,
                        latitude = latitude,
                        longitude = longitude,
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )

            assertEquals(
                matching.directory.canonicalFile,
                selection!!.directory
            )
        }
    }

    @Test
    fun selectsLatestEligibleAcrossDays() {
        withRoot { root ->
            saveSnapshot(
                root = root,
                latitude = latitude,
                longitude = longitude,
                downloadedAt = 1_788_001_100_000L,
                serverDate = null,
                temperature = 20.0
            )

            val nextDay =
                saveSnapshot(
                    root = root,
                    latitude = latitude,
                    longitude = longitude,
                    downloadedAt = 1_788_100_000_000L,
                    serverDate = null,
                    temperature = 22.0
                )

            val selection =
                NarForecastWeatherSnapshotSelector
                    .selectLatestFromRoot(
                        root = root,
                        latitude = latitude,
                        longitude = longitude,
                        predictionAsOfEpochMillis =
                            1_788_200_000_000L
                    )

            assertEquals(
                nextDay.directory.canonicalFile,
                selection!!.directory
            )
        }
    }

    @Test
    fun missingRootReturnsNull() {
        val missing =
            File(
                Files.createTempDirectory(
                    "weather-missing-parent"
                ).toFile(),
                "does-not-exist"
            )

        val selection =
            NarForecastWeatherSnapshotSelector
                .selectLatestFromRoot(
                    root = missing,
                    latitude = latitude,
                    longitude = longitude,
                    predictionAsOfEpochMillis =
                        1_788_001_200_000L
                )

        assertNull(selection)
    }

    @Test
    fun noEligibleSnapshotReturnsNull() {
        withRoot { root ->
            saveSnapshot(
                root = root,
                latitude = latitude,
                longitude = longitude,
                downloadedAt = 1_788_001_300_000L,
                serverDate = null,
                temperature = 20.0
            )

            val selection =
                NarForecastWeatherSnapshotSelector
                    .selectLatestFromRoot(
                        root = root,
                        latitude = latitude,
                        longitude = longitude,
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )

            assertNull(selection)
        }
    }

    @Test
    fun ageMillisUsesPitEvidence() {
        withRoot { root ->
            saveSnapshot(
                root = root,
                latitude = latitude,
                longitude = longitude,
                downloadedAt = 1_788_001_100_000L,
                serverDate = 1_788_001_150_000L,
                temperature = 20.0
            )

            val predictionAsOf =
                1_788_001_200_000L

            val selection =
                NarForecastWeatherSnapshotSelector
                    .selectLatestFromRoot(
                        root = root,
                        latitude = latitude,
                        longitude = longitude,
                        predictionAsOfEpochMillis =
                            predictionAsOf
                    )

            assertEquals(
                50_000L,
                selection!!.ageMillis
            )
        }
    }

    @Test
    fun nullServerDateUsesDownloadedAtAsPitEvidence() {
        withRoot { root ->
            val saved =
                saveSnapshot(
                    root = root,
                    latitude = latitude,
                    longitude = longitude,
                    downloadedAt = 1_788_001_100_000L,
                    serverDate = null,
                    temperature = 20.0
                )

            val selection =
                NarForecastWeatherSnapshotSelector
                    .selectLatestFromRoot(
                        root = root,
                        latitude = latitude,
                        longitude = longitude,
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )

            assertNull(
                selection!!.serverDateEpochMillis
            )
            assertEquals(
                saved.downloadedAtEpochMillis(),
                selection.pitEvidenceAtEpochMillis
            )
        }
    }

    @Test
    fun laterServerDateBecomesPitEvidence() {
        withRoot { root ->
            saveSnapshot(
                root = root,
                latitude = latitude,
                longitude = longitude,
                downloadedAt = 1_788_001_100_000L,
                serverDate = 1_788_001_175_000L,
                temperature = 20.0
            )

            val selection =
                NarForecastWeatherSnapshotSelector
                    .selectLatestFromRoot(
                        root = root,
                        latitude = latitude,
                        longitude = longitude,
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )

            assertEquals(
                1_788_001_175_000L,
                selection!!.pitEvidenceAtEpochMillis
            )
            assertEquals(
                1_788_001_175_000L,
                selection.serverDateEpochMillis
            )
        }
    }

    @Test
    fun futureDownloadedSnapshotBodyIsNotOpened() {
        withRoot { root ->
            val past =
                saveSnapshot(
                    root = root,
                    latitude = latitude,
                    longitude = longitude,
                    downloadedAt = 1_788_001_100_000L,
                    serverDate = null,
                    temperature = 20.0
                )

            val future =
                saveSnapshot(
                    root = root,
                    latitude = latitude,
                    longitude = longitude,
                    downloadedAt = 1_788_001_300_000L,
                    serverDate = null,
                    temperature = 99.0
                )

            File(
                future.directory,
                "forecast.json"
            ).writeText(
                "CORRUPTED_FUTURE_BODY"
            )

            assertTrue(
                File(
                    future.directory,
                    "forecast.json"
                ).readText()
                    .startsWith("CORRUPTED")
            )

            val selection =
                NarForecastWeatherSnapshotSelector
                    .selectLatestFromRoot(
                        root = root,
                        latitude = latitude,
                        longitude = longitude,
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )

            assertEquals(
                past.directory.canonicalFile,
                selection!!.directory
            )
        }
    }

    @Test
    fun futurePitEvidenceFromServerDateDoesNotOpenForecastBody() {
        withRoot { root ->
            val past =
                saveSnapshot(
                    root = root,
                    latitude = latitude,
                    longitude = longitude,
                    downloadedAt = 1_788_001_100_000L,
                    serverDate = null,
                    temperature = 20.0
                )

            val futurePit =
                saveSnapshot(
                    root = root,
                    latitude = latitude,
                    longitude = longitude,
                    downloadedAt = 1_788_001_150_000L,
                    serverDate = 1_788_001_300_000L,
                    temperature = 99.0
                )

            File(
                futurePit.directory,
                "forecast.json"
            ).writeText(
                "CORRUPTED_SERVER_DATE_FUTURE"
            )

            val selection =
                NarForecastWeatherSnapshotSelector
                    .selectLatestFromRoot(
                        root = root,
                        latitude = latitude,
                        longitude = longitude,
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )

            assertEquals(
                past.directory.canonicalFile,
                selection!!.directory
            )
        }
    }

    @Test
    fun rejectsInvalidLatitude() {
        withRoot { root ->
            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarForecastWeatherSnapshotSelector
                    .selectLatestFromRoot(
                        root = root,
                        latitude = 91.0,
                        longitude = longitude,
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )
            }
        }
    }

    @Test
    fun rejectsNonPositivePredictionAsOf() {
        withRoot { root ->
            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarForecastWeatherSnapshotSelector
                    .selectLatestFromRoot(
                        root = root,
                        latitude = latitude,
                        longitude = longitude,
                        predictionAsOfEpochMillis =
                            0L
                    )
            }
        }
    }

    @Test
    fun rejectsMalformedRootEntry() {
        withRoot { root ->
            File(root, "not-a-day.txt")
                .writeText("x")

            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarForecastWeatherSnapshotSelector
                    .selectLatestFromRoot(
                        root = root,
                        latitude = latitude,
                        longitude = longitude,
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )
            }
        }
    }

    @Test
    fun rejectsMalformedSnapshotDirectoryName() {
        withRoot { root ->
            val day =
                File(root, "20260829")

            require(day.mkdirs())

            File(day, "bad-name")
                .mkdir()

            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarForecastWeatherSnapshotSelector
                    .selectLatestFromRoot(
                        root = root,
                        latitude = latitude,
                        longitude = longitude,
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )
            }
        }
    }

    @Test
    fun skipsValidStagingDirectory() {
        withRoot { root ->
            val saved =
                saveSnapshot(
                    root = root,
                    latitude = latitude,
                    longitude = longitude,
                    downloadedAt = 1_788_001_100_000L,
                    serverDate = null,
                    temperature = 20.0
                )

            val staging =
                File(
                    saved.directory.parentFile,
                    ".1788001100000.tmp-" +
                        UUID.randomUUID().toString()
                )

            require(staging.mkdir())

            val selection =
                NarForecastWeatherSnapshotSelector
                    .selectLatestFromRoot(
                        root = root,
                        latitude = latitude,
                        longitude = longitude,
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )

            assertEquals(
                saved.directory.canonicalFile,
                selection!!.directory
            )
        }
    }

    @Test
    fun rejectsDuplicateManifestKey() {
        withRoot { root ->
            val saved =
                saveSnapshot(
                    root = root,
                    latitude = latitude,
                    longitude = longitude,
                    downloadedAt = 1_788_001_100_000L,
                    serverDate = null,
                    temperature = 20.0
                )

            val manifest =
                File(
                    saved.directory,
                    "manifest.txt"
                )

            manifest.appendText(
                "provider=open-meteo\n"
            )

            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarForecastWeatherSnapshotSelector
                    .selectLatestFromRoot(
                        root = root,
                        latitude = latitude,
                        longitude = longitude,
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )
            }
        }
    }

    @Test
    fun rejectsEligibleSnapshotIntegrityCorruption() {
        withRoot { root ->
            val saved =
                saveSnapshot(
                    root = root,
                    latitude = latitude,
                    longitude = longitude,
                    downloadedAt = 1_788_001_100_000L,
                    serverDate = null,
                    temperature = 20.0
                )

            File(
                saved.directory,
                "forecast.json"
            ).writeText(
                sampleResponse(21.0)
            )

            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarForecastWeatherSnapshotSelector
                    .selectLatestFromRoot(
                        root = root,
                        latitude = latitude,
                        longitude = longitude,
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )
            }
        }
    }

    @Test
    fun rejectsAmbiguousEqualRankSnapshots() {
        withRoot { root ->
            val first =
                saveSnapshot(
                    root = root,
                    latitude = latitude,
                    longitude = longitude,
                    downloadedAt = 1_788_001_100_000L,
                    serverDate = null,
                    temperature = 20.0
                )

            val second =
                saveSnapshot(
                    root = root,
                    latitude = latitude,
                    longitude = longitude,
                    downloadedAt = 1_788_001_100_000L,
                    serverDate = null,
                    temperature = 21.0
                )

            assertTrue(
                first.directory.canonicalFile !=
                    second.directory.canonicalFile
            )

            assertThrows(
                IllegalStateException::class.java
            ) {
                NarForecastWeatherSnapshotSelector
                    .selectLatestFromRoot(
                        root = root,
                        latitude = latitude,
                        longitude = longitude,
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )
            }
        }
    }

    @Test
    fun futureDayDirectoryIsSkippedWithoutFailingPastSelection() {
        withRoot { root ->
            val past =
                saveSnapshot(
                    root = root,
                    latitude = latitude,
                    longitude = longitude,
                    downloadedAt = 1_788_001_100_000L,
                    serverDate = null,
                    temperature = 20.0
                )

            val futureDay =
                File(root, "20990101")

            require(futureDay.mkdirs())

            val futureSnapshot =
                File(
                    futureDay,
                    "9999999999999-" +
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                )

            require(futureSnapshot.mkdir())

            File(
                futureSnapshot,
                "forecast.json"
            ).writeText(
                "SHOULD_NOT_BE_OPENED"
            )

            val selection =
                NarForecastWeatherSnapshotSelector
                    .selectLatestFromRoot(
                        root = root,
                        latitude = latitude,
                        longitude = longitude,
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )

            assertEquals(
                past.directory.canonicalFile,
                selection!!.directory
            )
        }
    }

    private fun withRoot(
        block: (File) -> Unit
    ) {
        val root =
            Files.createTempDirectory(
                "nar-weather-selector"
            ).toFile()

        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun saveSnapshot(
        root: File,
        latitude: Double,
        longitude: Double,
        downloadedAt: Long,
        serverDate: Long?,
        temperature: Double
    ): NarForecastWeatherSnapshotStore.SaveResult {
        val data =
            NarForecastWeatherDownloader
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
                        sampleResponse(
                            temperature
                        )
                            .toByteArray(
                                Charsets.UTF_8
                            ),
                    downloadedAtEpochMillis =
                        downloadedAt,
                    serverDateEpochMillis =
                        serverDate
                )

        return NarForecastWeatherSnapshotStore
            .saveToRoot(
                root = root,
                data = data
            )
    }

    private fun NarForecastWeatherSnapshotStore.SaveResult
        .downloadedAtEpochMillis(): Long =
        directory.name
            .substringBefore('-')
            .toLong()

    private fun sampleResponse(
        temperature: Double
    ): String =
        """
        {
          "latitude":35.6,
          "longitude":139.75,
          "utc_offset_seconds":0,
          "hourly_units":{
            "time":"unixtime",
            "temperature_2m":"°C",
            "relative_humidity_2m":"%",
            "pressure_msl":"hPa",
            "surface_pressure":"hPa",
            "precipitation":"mm",
            "weather_code":"wmo code",
            "wind_speed_10m":"m/s",
            "wind_direction_10m":"°",
            "wind_gusts_10m":"m/s"
          },
          "hourly":{
            "time":[1788404400],
            "temperature_2m":[$temperature],
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
