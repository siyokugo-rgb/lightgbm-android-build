package com.keiba.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class NarForecastWeatherArchiveLiveTest {

    @Test
    fun captureToPersistentArchive() {

        assumeTrue(
            "persistent Weather archive test is opt-in only",
            System.getenv(
                "KEIBA_LIVE_WEATHER_ARCHIVE_TEST"
            ) == "1"
        )

        val rootText =
            System.getenv(
                "KEIBA_NAR_WEATHER_ARCHIVE_ROOT"
            )
                ?.trim()
                .orEmpty()

        require(
            rootText.isNotEmpty()
        ) {
            "KEIBA_NAR_WEATHER_ARCHIVE_ROOT is required"
        }

        val root =
            File(rootText)
                .canonicalFile

        require(
            root.isAbsolute
        ) {
            "Weather archive root must be absolute"
        }

        val userDir =
            requireNotNull(
                System.getProperty("user.dir")
            ) {
                "user.dir is unavailable"
            }

        val projectDir =
            File(userDir)
                .canonicalFile

        require(
            root != projectDir &&
                !root.path.startsWith(
                    projectDir.path +
                        File.separator,
                    ignoreCase = true
                )
        ) {
            "Weather archive root must be outside Git working tree"
        }

        require(
            root.exists() || root.mkdirs()
        ) {
            "could not create Weather archive root"
        }

        require(
            root.isDirectory
        ) {
            "Weather archive root is not a directory"
        }

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

        assertTrue(
            saved.snapshotDirectory
                .resolve("manifest.txt")
                .isFile
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

        println(
            "PERSISTENT WEATHER ARCHIVE PASS"
        )

        println(
            "status         = " +
                saved.status
        )

        println(
            "archive_root   = " +
                root.canonicalPath
        )

        println(
            "snapshot_dir   = " +
                saved.snapshotDirectory
                    .canonicalPath
        )

        println(
            "downloaded_at  = " +
                downloaded.downloadedAtEpochMillis
        )

        println(
            "server_date    = " +
                downloaded.serverDateEpochMillis
        )

        println(
            "pit_evidence   = " +
                saved.pitEvidenceAtEpochMillis
        )

        println(
            "forecast_sha   = " +
                saved.forecastSha256
        )

        println(
            "snapshot_sha   = " +
                saved.snapshotSha256
        )

        println(
            "response_bytes = " +
                downloaded.responseBytes.size
        )
    }

    @Test
    fun verifyRestoredArchive() {

        assumeTrue(
            "Weather restore verification is opt-in only",
            System.getenv(
                "KEIBA_WEATHER_RESTORE_VERIFY_TEST"
            ) == "1"
        )

        val rootText =
            System.getenv(
                "KEIBA_NAR_WEATHER_RESTORE_ROOT"
            )
                ?.trim()
                .orEmpty()

        require(
            rootText.isNotEmpty()
        ) {
            "KEIBA_NAR_WEATHER_RESTORE_ROOT is required"
        }

        val root =
            File(rootText)
                .canonicalFile

        require(
            root.isAbsolute
        ) {
            "Weather restore root must be absolute"
        }

        require(
            root.isDirectory
        ) {
            "Weather restore root is not a directory"
        }

        val snapshots =
            root.walkTopDown()
                .filter {
                    it.isDirectory &&
                        it.resolve("forecast.json").isFile &&
                        it.resolve("manifest.txt").isFile
                }
                .toList()

        assertTrue(
            "No restored Weather snapshots found",
            snapshots.isNotEmpty()
        )

        snapshots.forEach { snapshot ->
            assertTrue(
                "Restored Weather snapshot failed verification: " +
                    snapshot.canonicalPath,
                NarForecastWeatherSnapshotStore
                    .verifySnapshot(
                        snapshot
                    )
            )
        }

        println(
            "RESTORED WEATHER ARCHIVE VERIFY PASS"
        )

        println(
            "restore_root   = " +
                root.canonicalPath
        )

        println(
            "snapshot_count = " +
                snapshots.size
        )

        snapshots.forEach { snapshot ->
            println(
                "verified       = " +
                    snapshot.canonicalPath
            )
        }
    }
}
