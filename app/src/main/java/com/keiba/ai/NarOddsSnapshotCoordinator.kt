package com.keiba.ai

import android.content.Context
import java.io.File
import java.time.LocalDate

object NarOddsSnapshotCoordinator {

    data class CaptureResult(
        val status:
            NarOddsSnapshotStore.SaveStatus,
        val babaCode: String,
        val raceDate: LocalDate,
        val raceNo: Int,
        val observedAtEpochMillis: Long,
        val rawResponseSha256: String,
        val snapshotSha256: String,
        val snapshotDirectory: File
    )

    fun capture(
        context: Context,
        babaCode: String,
        raceDate: LocalDate,
        raceNo: Int
    ): CaptureResult {
        val downloaded =
            NarOddsDownloader
                .download(
                    babaCode = babaCode,
                    raceDate = raceDate,
                    raceNo = raceNo
                )

        return captureDownloaded(
            context = context,
            data = downloaded
        )
    }

    internal fun captureDownloaded(
        context: Context,
        data:
            NarOddsDownloader.OddsResponse
    ): CaptureResult =
        captureDownloadedToRoot(
            root = File(
                context.noBackupFilesDir,
                "nar-odds-snapshots"
            ),
            data = data
        )

    internal fun captureDownloadedToRoot(
        root: File,
        data:
            NarOddsDownloader.OddsResponse
    ): CaptureResult {
        val saved =
            NarOddsSnapshotStore
                .saveToRoot(
                    root = root,
                    data = data
                )

        require(
            NarOddsSnapshotStore
                .verifySnapshot(
                    saved.directory
                )
        ) {
            "saved odds snapshot failed integrity verification"
        }

        return CaptureResult(
            status =
                saved.status,
            babaCode =
                data.babaCode,
            raceDate =
                data.raceDate,
            raceNo =
                data.raceNo,
            observedAtEpochMillis =
                saved.observedAtEpochMillis,
            rawResponseSha256 =
                saved.rawResponseSha256,
            snapshotSha256 =
                saved.snapshotSha256,
            snapshotDirectory =
                saved.directory.canonicalFile
        )
    }
}
