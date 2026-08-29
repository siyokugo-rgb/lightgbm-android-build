package com.keiba.ai

import android.content.Context
import java.io.File

object NarDailySnapshotCoordinator {

    data class CaptureResult(
        val status: NarDailySnapshotStore.SaveStatus,
        val date: String,
        val sourceFileName: String,
        val sourceTimestampEpochSecond: Long,
        val pitEvidenceAtEpochMillis: Long,
        val snapshotSha256: String,
        val snapshotDirectory: File
    )

    fun capture(
        context: Context
    ): CaptureResult {
        val downloaded =
            NarDailyRaceDownloader.download()

        return captureDownloaded(
            context = context,
            data = downloaded
        )
    }

    internal fun captureDownloaded(
        context: Context,
        data: NarDailyRaceDownloader.DailyRaceData
    ): CaptureResult =
        captureDownloadedToRoot(
            root = File(
                context.noBackupFilesDir,
                "nar-daily-snapshots"
            ),
            data = data
        )

    internal fun captureDownloadedToRoot(
        root: File,
        data: NarDailyRaceDownloader.DailyRaceData
    ): CaptureResult {
        val sourceFileName =
            data.sourceFileName
                ?: error(
                    "source filename missing"
                )

        val sourceTimestamp =
            data.sourceTimestampEpochSecond
                ?: error(
                    "source timestamp missing"
                )

        val saved =
            NarDailySnapshotStore
                .saveToRoot(
                    root = root,
                    data = data
                )

        require(
            NarDailySnapshotStore
                .verifySnapshot(
                    saved.directory
                )
        ) {
            "saved NAR snapshot failed integrity verification"
        }

        return CaptureResult(
            status = saved.status,
            date = data.date,
            sourceFileName =
                sourceFileName,
            sourceTimestampEpochSecond =
                sourceTimestamp,
            pitEvidenceAtEpochMillis =
                saved.pitEvidenceAtEpochMillis,
            snapshotSha256 =
                saved.snapshotSha256,
            snapshotDirectory =
                saved.directory
                    .canonicalFile
        )
    }
}
