package com.keiba.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files

class NarForecastWeatherLiveE2ETest {

    @Test
    fun liveDownloadSaveAndVerify() {

        assumeTrue(
            "live Weather test is opt-in only",
            System.getenv("KEIBA_LIVE_WEATHER_TEST") == "1"
        )

        val root =
            Files.createTempDirectory(
                "nar-weather-live-e2e"
            ).toFile()

        try {
            val downloaded =
                NarForecastWeatherDownloader
                    .download(
                        latitude = 35.591339,
                        longitude = 139.742608
                    )

            val saved =
                NarForecastWeatherSnapshotCoordinator
                    .captureDownloadedToRoot(
                        root = root,
                        data = downloaded
                    )

            assertEquals(
                NarForecastWeatherSnapshotStore
                    .SaveStatus.CREATED,
                saved.status
            )

            assertTrue(
                NarForecastWeatherSnapshotStore
                    .verifySnapshot(
                        saved.snapshotDirectory
                    )
            )

            assertArrayEquals(
                downloaded.responseBytes,
                saved.snapshotDirectory
                    .resolve("forecast.json")
                    .readBytes()
            )

            val expectedPit =
                maxOf(
                    downloaded.downloadedAtEpochMillis,
                    downloaded.serverDateEpochMillis ?: 0L
                )

            assertEquals(
                expectedPit,
                saved.pitEvidenceAtEpochMillis
            )

            assertTrue(
                saved.snapshotDirectory
                    .resolve("manifest.txt")
                    .isFile
            )

            println("LIVE WEATHER E2E PASS")
            println(
                "downloaded_at = " +
                    downloaded.downloadedAtEpochMillis
            )
            println(
                "server_date   = " +
                    downloaded.serverDateEpochMillis
            )
            println(
                "pit_evidence  = " +
                    saved.pitEvidenceAtEpochMillis
            )
            println(
                "forecast_sha  = " +
                    saved.forecastSha256
            )
            println(
                "snapshot_sha  = " +
                    saved.snapshotSha256
            )
            println(
                "response_bytes = " +
                    downloaded.responseBytes.size
            )

            val firstDirectory =
                saved.snapshotDirectory
                    .canonicalFile

            val firstForecastBefore =
                firstDirectory
                    .resolve("forecast.json")
                    .readBytes()

            val firstManifestBefore =
                firstDirectory
                    .resolve("manifest.txt")
                    .readBytes()

            Thread.sleep(2_000L)

            val downloaded2 =
                NarForecastWeatherDownloader
                    .download(
                        latitude = 35.591339,
                        longitude = 139.742608
                    )

            val saved2 =
                NarForecastWeatherSnapshotCoordinator
                    .captureDownloadedToRoot(
                        root = root,
                        data = downloaded2
                    )

            assertEquals(
                NarForecastWeatherSnapshotStore
                    .SaveStatus.CREATED,
                saved2.status
            )

            assertTrue(
                downloaded2.downloadedAtEpochMillis >
                    downloaded.downloadedAtEpochMillis
            )

            assertTrue(
                firstDirectory !=
                    saved2.snapshotDirectory
                        .canonicalFile
            )

            assertTrue(
                NarForecastWeatherSnapshotStore
                    .verifySnapshot(
                        firstDirectory
                    )
            )

            assertTrue(
                NarForecastWeatherSnapshotStore
                    .verifySnapshot(
                        saved2.snapshotDirectory
                    )
            )

            assertArrayEquals(
                firstForecastBefore,
                firstDirectory
                    .resolve("forecast.json")
                    .readBytes()
            )

            assertArrayEquals(
                firstManifestBefore,
                firstDirectory
                    .resolve("manifest.txt")
                    .readBytes()
            )

            assertArrayEquals(
                downloaded2.responseBytes,
                saved2.snapshotDirectory
                    .resolve("forecast.json")
                    .readBytes()
            )

            val expectedPit2 =
                maxOf(
                    downloaded2.downloadedAtEpochMillis,
                    downloaded2.serverDateEpochMillis ?: 0L
                )

            assertEquals(
                expectedPit2,
                saved2.pitEvidenceAtEpochMillis
            )

            val snapshots =
                root.walkTopDown()
                    .filter {
                        it.isDirectory &&
                            it.resolve("forecast.json").isFile &&
                            it.resolve("manifest.txt").isFile
                    }
                    .toList()

            assertEquals(
                2,
                snapshots.size
            )

            println("SECOND CAPTURE PASS")
            println(
                "second_downloaded_at = " +
                    downloaded2.downloadedAtEpochMillis
            )
            println(
                "second_server_date   = " +
                    downloaded2.serverDateEpochMillis
            )
            println(
                "second_pit_evidence  = " +
                    saved2.pitEvidenceAtEpochMillis
            )
            println(
                "second_forecast_sha  = " +
                    saved2.forecastSha256
            )
            println(
                "second_snapshot_sha  = " +
                    saved2.snapshotSha256
            )
            println(
                "raw_content_same     = " +
                    downloaded.responseBytes
                        .contentEquals(
                            downloaded2.responseBytes
                        )
            )
            println(
                "snapshot_count       = " +
                    snapshots.size
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
