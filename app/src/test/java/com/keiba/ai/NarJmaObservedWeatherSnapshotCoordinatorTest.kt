package com.keiba.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class NarJmaObservedWeatherSnapshotCoordinatorTest {

    @Test
    fun capturesAndVerifiesDownloadedCsv() {
        val root =
            Files.createTempDirectory(
                "obs-coordinator"
            ).toFile()

        try {
            val result =
                NarJmaObservedWeatherSnapshotCoordinator
                    .captureDownloadedToRoot(
                        root = root,
                        data = sampleData()
                    )

            assertEquals(
                NarJmaObservedWeatherSnapshotStore
                    .SaveStatus.CREATED,
                result.status
            )

            assertEquals(
                "pre1h00_rct",
                result.productId
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
                NarJmaObservedWeatherSnapshotStore
                    .verifySnapshot(
                        result.snapshotDirectory
                    )
            )

            assertTrue(
                result.snapshotDirectory
                    .resolve(
                        "observed.csv"
                    )
                    .isFile
            )

            assertFalse(
                result.snapshotDirectory
                    .resolve(
                        "forecast.json"
                    )
                    .exists()
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun repeatedSameCaptureReturnsAlreadyPresent() {
        val root =
            Files.createTempDirectory(
                "obs-coordinator-repeat"
            ).toFile()

        try {
            val first =
                NarJmaObservedWeatherSnapshotCoordinator
                    .captureDownloadedToRoot(
                        root = root,
                        data = sampleData()
                    )

            val second =
                NarJmaObservedWeatherSnapshotCoordinator
                    .captureDownloadedToRoot(
                        root = root,
                        data = sampleData()
                    )

            assertEquals(
                NarJmaObservedWeatherSnapshotStore
                    .SaveStatus.CREATED,
                first.status
            )

            assertEquals(
                NarJmaObservedWeatherSnapshotStore
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
    fun tamperedExistingCaptureIsRejected() {
        val root =
            Files.createTempDirectory(
                "obs-coordinator-tamper"
            ).toFile()

        try {
            val first =
                NarJmaObservedWeatherSnapshotCoordinator
                    .captureDownloadedToRoot(
                        root = root,
                        data = sampleData()
                    )

            first.snapshotDirectory
                .resolve(
                    "observed.csv"
                )
                .appendText(
                    "tampered"
                )

            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarJmaObservedWeatherSnapshotCoordinator
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
        NarJmaObservedWeatherDownloader.ObservedCsvResponse {
        val product =
            NarJmaObservedWeatherProduct
                .PRE1H00_RCT

        return NarJmaObservedWeatherDownloader
            .ObservedCsvResponse(
                productId =
                    product.productId,
                requestUrl =
                    product.canonicalUrl(),
                responseBytes =
                    (
                        "観測所番号,地点,現在値(mm),現在値の品質情報\n" +
                            "44166,羽田,0,8\n"
                    ).toByteArray(
                        Charsets.UTF_8
                    ),
                contentType =
                    "text/csv",
                downloadedAtEpochMillis =
                    1_788_001_100_000L,
                serverDateEpochMillis =
                    1_788_001_120_000L,
                lastModifiedEpochMillis =
                    null
            )
    }
}
