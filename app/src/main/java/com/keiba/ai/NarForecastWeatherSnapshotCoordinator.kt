package com.keiba.ai

import android.content.Context
import java.io.File

object NarForecastWeatherSnapshotCoordinator {

    data class CaptureResult(
        val status:
            NarForecastWeatherSnapshotStore.SaveStatus,
        val requestedLatitude: Double,
        val requestedLongitude: Double,
        val pitEvidenceAtEpochMillis: Long,
        val forecastSha256: String,
        val snapshotSha256: String,
        val snapshotDirectory: File
    )

    fun capture(
        context: Context,
        latitude: Double,
        longitude: Double
    ): CaptureResult {
        val downloaded =
            NarForecastWeatherDownloader
                .download(
                    latitude = latitude,
                    longitude = longitude
                )

        return captureDownloaded(
            context = context,
            data = downloaded
        )
    }

    internal fun captureDownloaded(
        context: Context,
        data:
            NarForecastWeatherDownloader.ForecastResponse
    ): CaptureResult =
        captureDownloadedToRoot(
            root = File(
                context.noBackupFilesDir,
                "nar-forecast-weather-snapshots"
            ),
            data = data
        )

    internal fun captureDownloadedToRoot(
        root: File,
        data:
            NarForecastWeatherDownloader.ForecastResponse
    ): CaptureResult {
        val saved =
            NarForecastWeatherSnapshotStore
                .saveToRoot(
                    root = root,
                    data = data
                )

        require(
            NarForecastWeatherSnapshotStore
                .verifySnapshot(
                    saved.directory
                )
        ) {
            "saved weather snapshot failed integrity verification"
        }

        return CaptureResult(
            status =
                saved.status,
            requestedLatitude =
                data.requestedLatitude,
            requestedLongitude =
                data.requestedLongitude,
            pitEvidenceAtEpochMillis =
                saved.pitEvidenceAtEpochMillis,
            forecastSha256 =
                saved.forecastSha256,
            snapshotSha256 =
                saved.snapshotSha256,
            snapshotDirectory =
                saved.directory.canonicalFile
        )
    }
}
