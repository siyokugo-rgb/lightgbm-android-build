package com.keiba.ai

import android.content.Context
import java.io.File

/**
 * Minimal download → save → verify coordinator for documented
 * JMA public observed CSV products.
 *
 * No scheduler / polling. Station mapping is not loaded here;
 * raw captures are product-wide.
 */
object NarJmaObservedWeatherSnapshotCoordinator {

    data class CaptureResult(
        val status:
            NarJmaObservedWeatherSnapshotStore.SaveStatus,
        val productId: String,
        val pitEvidenceAtEpochMillis: Long,
        val rawSha256: String,
        val snapshotSha256: String,
        val snapshotDirectory: File
    )

    fun capture(
        context: Context,
        product: NarJmaObservedWeatherProduct
    ): CaptureResult {
        val downloaded =
            NarJmaObservedWeatherDownloader
                .download(
                    product = product
                )

        return captureDownloaded(
            context = context,
            data = downloaded
        )
    }

    internal fun captureDownloaded(
        context: Context,
        data:
            NarJmaObservedWeatherDownloader.ObservedCsvResponse
    ): CaptureResult =
        captureDownloadedToRoot(
            root = File(
                context.noBackupFilesDir,
                "nar-jma-observed-weather-snapshots"
            ),
            data = data
        )

    internal fun captureDownloadedToRoot(
        root: File,
        data:
            NarJmaObservedWeatherDownloader.ObservedCsvResponse
    ): CaptureResult {
        val saved =
            NarJmaObservedWeatherSnapshotStore
                .saveToRoot(
                    root = root,
                    data = data
                )

        require(
            NarJmaObservedWeatherSnapshotStore
                .verifySnapshot(
                    saved.directory
                )
        ) {
            "saved observed weather snapshot failed integrity verification"
        }

        return CaptureResult(
            status =
                saved.status,
            productId =
                saved.productId,
            pitEvidenceAtEpochMillis =
                saved.pitEvidenceAtEpochMillis,
            rawSha256 =
                saved.rawSha256,
            snapshotSha256 =
                saved.snapshotSha256,
            snapshotDirectory =
                saved.directory.canonicalFile
        )
    }
}
