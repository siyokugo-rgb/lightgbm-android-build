package com.keiba.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Opt-in live smoke for one documented precipitation CSV product.
 *
 * Enable with KEIBA_LIVE_OBSERVED_WEATHER_SMOKE=1
 * Polling is forbidden; run at most once per session.
 */
class NarJmaObservedWeatherLiveSmokeTest {

    @Test
    fun downloadSaveVerifyOnePrecipitationProduct() {
        assumeTrue(
            "observed weather live smoke is opt-in only",
            System.getenv(
                "KEIBA_LIVE_OBSERVED_WEATHER_SMOKE"
            ) == "1"
        )

        val root =
            Files.createTempDirectory(
                "nar-obs-live-smoke"
            ).toFile()

        try {
            val product =
                NarJmaObservedWeatherProduct
                    .PRE1H00_RCT

            val downloaded =
                NarJmaObservedWeatherDownloader
                    .download(
                        product
                    )

            assertEquals(
                product.productId,
                downloaded.productId
            )

            assertEquals(
                product.canonicalUrl(),
                downloaded.requestUrl
            )

            assertEquals(
                "text/csv",
                downloaded.contentType
            )

            assertTrue(
                downloaded.responseBytes
                    .isNotEmpty()
            )

            assertTrue(
                downloaded.responseBytes
                    .size
                    .toLong() <=
                    NarJmaObservedWeatherDownloader
                        .MAX_RESPONSE_BYTES
            )

            assertTrue(
                downloaded.downloadedAtEpochMillis >
                    0L
            )

            val saved =
                NarJmaObservedWeatherSnapshotCoordinator
                    .captureDownloadedToRoot(
                        root = root,
                        data = downloaded
                    )

            assertEquals(
                NarJmaObservedWeatherSnapshotStore
                    .SaveStatus.CREATED,
                saved.status
            )

            assertTrue(
                NarJmaObservedWeatherSnapshotStore
                    .verifySnapshot(
                        saved.snapshotDirectory
                    )
            )

            assertTrue(
                saved.pitEvidenceAtEpochMillis >=
                    downloaded.downloadedAtEpochMillis
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
