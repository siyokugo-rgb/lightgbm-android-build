package com.keiba.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class NarForecastWeatherSnapshotReaderTest {

    private val latitude =
        35.591339

    private val longitude =
        139.742608

    @Test
    fun readSelectedReturnsTypedPayload() {
        withRoot { root ->
            val saved =
                saveSnapshot(
                    root = root,
                    latitude = latitude,
                    longitude = longitude,
                    downloadedAt = 1_788_001_100_000L,
                    serverDate = 1_788_001_120_000L,
                    temperature = 29.0,
                    providerLatitude = 35.6,
                    providerLongitude = 139.75
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

            assertNotNull(selection)

            val input =
                NarForecastWeatherSnapshotReader
                    .readSelectedForTest(
                        selection!!
                    )

            assertEquals(
                saved.directory.canonicalFile,
                input.selection.directory
            )
            assertEquals(
                1,
                input.payload.hourly.size
            )
            assertEquals(
                29.0,
                input.payload.hourly[0]
                    .temperature2mCelsius,
                0.0
            )
            assertEquals(
                35.6,
                input.payload.providerLatitude,
                0.0
            )
            assertEquals(
                139.75,
                input.payload.providerLongitude,
                0.0
            )
        }
    }

    @Test
    fun readFromRootSelectsLatestEligiblePayload() {
        withRoot { root ->
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
                latitude = latitude,
                longitude = longitude,
                downloadedAt = 1_788_001_150_000L,
                serverDate = null,
                temperature = 21.5
            )

            val input =
                NarForecastWeatherSnapshotReader
                    .readFromRoot(
                        root = root,
                        latitude = latitude,
                        longitude = longitude,
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )

            assertNotNull(input)
            assertEquals(
                21.5,
                input!!.payload.hourly[0]
                    .temperature2mCelsius,
                0.0
            )
        }
    }

    @Test
    fun readFromRootReturnsNullWhenNoEligible() {
        withRoot { root ->
            saveSnapshot(
                root = root,
                latitude = latitude,
                longitude = longitude,
                downloadedAt = 1_788_001_300_000L,
                serverDate = null,
                temperature = 20.0
            )

            val input =
                NarForecastWeatherSnapshotReader
                    .readFromRoot(
                        root = root,
                        latitude = latitude,
                        longitude = longitude,
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )

            assertNull(input)
        }
    }

    @Test
    fun rejectsFutureSelection() {
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
                    .Selection(
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
                        captureDateUtc =
                            "20260829",
                        downloadedAtEpochMillis =
                            1_788_001_100_000L,
                        serverDateEpochMillis =
                            null,
                        pitEvidenceAtEpochMillis =
                            1_788_001_100_000L,
                        predictionAsOfEpochMillis =
                            1_788_001_050_000L,
                        ageMillis =
                            -50_000L,
                        forecastSha256 =
                            saved.forecastSha256,
                        snapshotSha256 =
                            saved.snapshotSha256,
                        directory =
                            saved.directory
                                .canonicalFile
                    )

            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarForecastWeatherSnapshotReader
                    .readSelectedForTest(
                        selection
                    )
            }
        }
    }

    @Test
    fun rejectsTamperedSnapshotSha() {
        withRoot { root ->
            val selection =
                requireSelection(root)

            val tampered =
                selection.copy(
                    snapshotSha256 =
                        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb" +
                            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                )

            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarForecastWeatherSnapshotReader
                    .readSelectedForTest(
                        tampered
                    )
            }
        }
    }

    @Test
    fun rejectsTamperedForecastSha() {
        withRoot { root ->
            val selection =
                requireSelection(root)

            val tampered =
                selection.copy(
                    forecastSha256 =
                        "cccccccccccccccccccccccccccccccc" +
                            "cccccccccccccccccccccccccccccccc"
                )

            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarForecastWeatherSnapshotReader
                    .readSelectedForTest(
                        tampered
                    )
            }
        }
    }

    @Test
    fun rejectsTamperedPitEvidence() {
        withRoot { root ->
            val selection =
                requireSelection(root)

            val tampered =
                selection.copy(
                    pitEvidenceAtEpochMillis =
                        selection.pitEvidenceAtEpochMillis -
                            1L,
                    ageMillis =
                        selection.predictionAsOfEpochMillis -
                            (
                                selection.pitEvidenceAtEpochMillis -
                                    1L
                                )
                )

            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarForecastWeatherSnapshotReader
                    .readSelectedForTest(
                        tampered
                    )
            }
        }
    }

    @Test
    fun rejectsBodyCorruptionAfterSelection() {
        withRoot { root ->
            val selection =
                requireSelection(root)

            File(
                selection.directory,
                "forecast.json"
            ).writeText(
                "CORRUPTED_AFTER_SELECTION"
            )

            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarForecastWeatherSnapshotReader
                    .readSelectedForTest(
                        selection
                    )
            }
        }
    }

    @Test
    fun rejectsTypedSemanticFailureAfterCoarseStoreShape() {
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

            val broken =
                """
                {
                  "latitude":35.6,
                  "longitude":139.75,
                  "utc_offset_seconds":0,
                  "hourly":{
                    "time":[1788404400,1788408000],
                    "temperature_2m":[20.0],
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
                    .toByteArray(
                        Charsets.UTF_8
                    )

            // Coarse Downloader token gate still passes.
            NarForecastWeatherDownloader
                .validateResponseBytes(
                    broken
                )

            val forecastSha =
                sha256Hex(broken)

            val manifestFile =
                File(
                    saved.directory,
                    "manifest.txt"
                )

            val originalManifest =
                manifestFile.readText(
                    Charsets.UTF_8
                )

            val fields =
                linkedMapOf<String, String>()

            for (line in originalManifest.split('\n')) {
                if (line.isEmpty()) {
                    continue
                }

                val index =
                    line.indexOf('=')

                fields[
                    line.substring(
                        0,
                        index
                    )
                ] =
                    line.substring(
                        index + 1
                    )
            }

            fields["forecast_sha256"] =
                forecastSha

            val snapshotSha =
                sha256Hex(
                    buildString {
                        append("format_version=")
                        append(fields["format_version"])
                        append('\n')
                        append("provider=")
                        append(fields["provider"])
                        append('\n')
                        append("capture_date_utc=")
                        append(fields["capture_date_utc"])
                        append('\n')
                        append("requested_latitude=")
                        append(fields["requested_latitude"])
                        append('\n')
                        append("requested_longitude=")
                        append(fields["requested_longitude"])
                        append('\n')
                        append("request_url=")
                        append(fields["request_url"])
                        append('\n')
                        append("downloaded_at_epoch_millis=")
                        append(
                            fields[
                                "downloaded_at_epoch_millis"
                            ]
                        )
                        append('\n')
                        append("server_date_epoch_millis=")
                        append(
                            fields[
                                "server_date_epoch_millis"
                            ]
                        )
                        append('\n')
                        append("pit_evidence_at_epoch_millis=")
                        append(
                            fields[
                                "pit_evidence_at_epoch_millis"
                            ]
                        )
                        append('\n')
                        append("forecast_sha256=")
                        append(forecastSha)
                        append('\n')
                    }.toByteArray(
                        Charsets.UTF_8
                    )
                )

            fields["snapshot_sha256"] =
                snapshotSha

            File(
                saved.directory,
                "forecast.json"
            ).writeBytes(
                broken
            )

            manifestFile.writeText(
                fields.entries.joinToString(
                    "\n",
                    postfix = "\n"
                ) {
                    "${it.key}=${it.value}"
                }
            )

            val renamed =
                File(
                    saved.directory.parentFile,
                    fields[
                        "downloaded_at_epoch_millis"
                    ] +
                        "-" +
                        snapshotSha
                )

            assertTrue(
                saved.directory.renameTo(
                    renamed
                )
            )

            assertTrue(
                NarForecastWeatherSnapshotStore
                    .verifySnapshot(
                        renamed
                    )
            )

            val selection =
                NarForecastWeatherSnapshotSelector
                    .Selection(
                        requestedLatitude =
                            latitude,
                        requestedLongitude =
                            longitude,
                        requestUrl =
                            fields.getValue(
                                "request_url"
                            ),
                        captureDateUtc =
                            fields.getValue(
                                "capture_date_utc"
                            ),
                        downloadedAtEpochMillis =
                            fields.getValue(
                                "downloaded_at_epoch_millis"
                            )
                                .toLong(),
                        serverDateEpochMillis =
                            null,
                        pitEvidenceAtEpochMillis =
                            fields.getValue(
                                "pit_evidence_at_epoch_millis"
                            )
                                .toLong(),
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L,
                        ageMillis =
                            1_788_001_200_000L -
                                fields.getValue(
                                    "pit_evidence_at_epoch_millis"
                                )
                                    .toLong(),
                        forecastSha256 =
                            forecastSha,
                        snapshotSha256 =
                            snapshotSha,
                        directory =
                            renamed.canonicalFile
                    )

            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarForecastWeatherSnapshotReader
                    .readSelectedForTest(
                        selection
                    )
            }
        }
    }

    @Test
    fun doesNotRequireProviderCoordinatesEqualRequested() {
        withRoot { root ->
            saveSnapshot(
                root = root,
                latitude = latitude,
                longitude = longitude,
                downloadedAt = 1_788_001_100_000L,
                serverDate = null,
                temperature = 18.0,
                providerLatitude = 35.55,
                providerLongitude = 139.70
            )

            val input =
                NarForecastWeatherSnapshotReader
                    .readFromRoot(
                        root = root,
                        latitude = latitude,
                        longitude = longitude,
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )

            assertNotNull(input)
            assertEquals(
                latitude,
                input!!.selection.requestedLatitude,
                0.0
            )
            assertEquals(
                35.55,
                input.payload.providerLatitude,
                0.0
            )
            assertTrue(
                input.payload.providerLatitude !=
                    input.selection.requestedLatitude
            )
        }
    }

    private fun requireSelection(
        root: File
    ): NarForecastWeatherSnapshotSelector.Selection {
        saveSnapshot(
            root = root,
            latitude = latitude,
            longitude = longitude,
            downloadedAt = 1_788_001_100_000L,
            serverDate = null,
            temperature = 20.0
        )

        return NarForecastWeatherSnapshotSelector
            .selectLatestFromRoot(
                root = root,
                latitude = latitude,
                longitude = longitude,
                predictionAsOfEpochMillis =
                    1_788_001_200_000L
            )
            ?: error("expected selection")
    }

    private fun withRoot(
        block: (File) -> Unit
    ) {
        val root =
            Files.createTempDirectory(
                "nar-weather-reader"
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
        temperature: Double,
        providerLatitude: Double = 35.6,
        providerLongitude: Double = 139.75
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
                            temperature =
                                temperature,
                            providerLatitude =
                                providerLatitude,
                            providerLongitude =
                                providerLongitude
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

    private fun sampleResponse(
        temperature: Double,
        providerLatitude: Double,
        providerLongitude: Double
    ): String =
        """
        {
          "latitude":$providerLatitude,
          "longitude":$providerLongitude,
          "utc_offset_seconds":0,
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

    private fun sha256Hex(
        bytes: ByteArray
    ): String {
        val digest =
            java.security.MessageDigest
                .getInstance(
                    "SHA-256"
                )
                .digest(bytes)

        return buildString(
            digest.size * 2
        ) {
            for (value in digest) {
                append(
                    String.format(
                        "%02x",
                        value
                    )
                )
            }
        }
    }
}
