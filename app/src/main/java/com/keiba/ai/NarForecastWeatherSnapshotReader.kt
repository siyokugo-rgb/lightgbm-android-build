package com.keiba.ai

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Re-verifies a selected ForecastWeather snapshot and returns a
 * typed immutable payload via [NarForecastWeatherParser].
 *
 * Does not perform network I/O.
 * Does not invent PIT availability timestamps.
 * Does not select target forecast hours.
 */
object NarForecastWeatherSnapshotReader {

    private const val ROOT_NAME =
        "nar-forecast-weather-snapshots"

    data class SnapshotInput(
        val selection:
            NarForecastWeatherSnapshotSelector.Selection,
        val payload: NarForecastWeatherPayload
    )

    fun read(
        context: Context,
        latitude: Double,
        longitude: Double,
        predictionAsOfEpochMillis: Long
    ): SnapshotInput? =
        readFromRoot(
            root = File(
                context.noBackupFilesDir,
                ROOT_NAME
            ),
            latitude = latitude,
            longitude = longitude,
            predictionAsOfEpochMillis =
                predictionAsOfEpochMillis
        )

    internal fun readFromRoot(
        root: File,
        latitude: Double,
        longitude: Double,
        predictionAsOfEpochMillis: Long
    ): SnapshotInput? {
        val selection =
            NarForecastWeatherSnapshotSelector
                .selectLatestFromRoot(
                    root = root,
                    latitude = latitude,
                    longitude = longitude,
                    predictionAsOfEpochMillis =
                        predictionAsOfEpochMillis
                )
                ?: return null

        return readSelected(selection)
    }

    internal fun readSelectedForTest(
        selection:
            NarForecastWeatherSnapshotSelector.Selection
    ): SnapshotInput =
        readSelected(selection)

    private fun readSelected(
        selection:
            NarForecastWeatherSnapshotSelector.Selection
    ): SnapshotInput {
        require(
            selection.pitEvidenceAtEpochMillis <=
                selection.predictionAsOfEpochMillis
        ) {
            "future weather snapshot selection rejected"
        }

        require(
            selection.ageMillis ==
                Math.subtractExact(
                    selection.predictionAsOfEpochMillis,
                    selection.pitEvidenceAtEpochMillis
                )
        ) {
            "invalid weather snapshot age"
        }

        val directory =
            selection.directory
                .canonicalFile

        require(
            directory ==
                selection.directory
        ) {
            "weather snapshot directory is not canonical"
        }

        val expectedName =
            selection.downloadedAtEpochMillis
                .toString() +
                "-" +
                selection.snapshotSha256

        require(
            directory.name ==
                expectedName
        ) {
            "weather selection directory identity mismatch"
        }

        require(
            NarForecastWeatherSnapshotStore
                .verifySnapshot(
                    directory
                )
        ) {
            "weather snapshot failed pre-read integrity verification"
        }

        val pinned =
            readPinnedManifest(
                directory = directory,
                selection = selection
            )

        require(
            pinned.forecastSha256 ==
                selection.forecastSha256
        ) {
            "weather selection forecast SHA mismatch"
        }

        require(
            pinned.snapshotSha256 ==
                selection.snapshotSha256
        ) {
            "weather selection snapshot SHA mismatch"
        }

        require(
            pinned.requestUrl ==
                selection.requestUrl
        ) {
            "weather selection request URL mismatch"
        }

        require(
            pinned.pitEvidenceAtEpochMillis ==
                selection.pitEvidenceAtEpochMillis
        ) {
            "weather selection pit evidence mismatch"
        }

        require(
            pinned.downloadedAtEpochMillis ==
                selection.downloadedAtEpochMillis
        ) {
            "weather selection downloadedAt mismatch"
        }

        val forecastBytes =
            readStrictChildBytes(
                directory = directory,
                name = "forecast.json",
                maxBytes =
                    NarForecastWeatherDownloader
                        .MAX_RESPONSE_BYTES
            )

        require(
            sha256Hex(forecastBytes) ==
                selection.forecastSha256
        ) {
            "forecast bytes do not match selected snapshot"
        }

        require(
            NarForecastWeatherSnapshotStore
                .verifySnapshot(
                    directory
                )
        ) {
            "weather snapshot changed during read"
        }

        val pinnedAfter =
            readPinnedManifest(
                directory = directory,
                selection = selection
            )

        require(pinned == pinnedAfter) {
            "weather snapshot manifest changed during read"
        }

        val payload =
            NarForecastWeatherParser.parse(
                forecastBytes
            )

        return SnapshotInput(
            selection = selection,
            payload = payload
        )
    }

    private data class PinnedManifest(
        val downloadedAtEpochMillis: Long,
        val pitEvidenceAtEpochMillis: Long,
        val requestUrl: String,
        val forecastSha256: String,
        val snapshotSha256: String
    )

    private fun readPinnedManifest(
        directory: File,
        selection:
            NarForecastWeatherSnapshotSelector.Selection
    ): PinnedManifest {
        val manifestFile =
            File(
                directory,
                "manifest.txt"
            )

        require(manifestFile.isFile) {
            "missing weather manifest"
        }

        val text =
            manifestFile.readText(
                Charsets.UTF_8
            )

        val fields =
            linkedMapOf<String, String>()

        for (line in text.split('\n')) {
            if (line.isEmpty()) {
                continue
            }

            val index =
                line.indexOf('=')

            require(index > 0) {
                "invalid weather snapshot manifest"
            }

            val key =
                line.substring(
                    0,
                    index
                )

            val value =
                line.substring(
                    index + 1
                )

            require(key !in fields) {
                "duplicate weather manifest key"
            }

            fields[key] = value
        }

        val downloadedAt =
            fields[
                "downloaded_at_epoch_millis"
            ]
                ?.toLongOrNull()
                ?: error(
                    "invalid weather downloaded_at"
                )

        val pitEvidenceAt =
            fields[
                "pit_evidence_at_epoch_millis"
            ]
                ?.toLongOrNull()
                ?: error(
                    "invalid weather pit evidence"
                )

        val requestUrl =
            fields["request_url"]
                ?: error(
                    "missing weather request_url"
                )

        val forecastSha256 =
            fields["forecast_sha256"]
                ?: error(
                    "missing weather forecast_sha256"
                )

        val snapshotSha256 =
            fields["snapshot_sha256"]
                ?: error(
                    "missing weather snapshot_sha256"
                )

        require(
            downloadedAt ==
                selection.downloadedAtEpochMillis
        ) {
            "weather selection downloadedAt mismatch"
        }

        require(
            pitEvidenceAt ==
                selection.pitEvidenceAtEpochMillis
        ) {
            "weather selection pit evidence mismatch"
        }

        require(
            requestUrl ==
                selection.requestUrl
        ) {
            "weather selection request URL mismatch"
        }

        require(
            forecastSha256 ==
                selection.forecastSha256
        ) {
            "weather selection forecast SHA mismatch"
        }

        require(
            snapshotSha256 ==
                selection.snapshotSha256
        ) {
            "weather selection snapshot SHA mismatch"
        }

        return PinnedManifest(
            downloadedAtEpochMillis =
                downloadedAt,
            pitEvidenceAtEpochMillis =
                pitEvidenceAt,
            requestUrl = requestUrl,
            forecastSha256 =
                forecastSha256,
            snapshotSha256 =
                snapshotSha256
        )
    }

    private fun readStrictChildBytes(
        directory: File,
        name: String,
        maxBytes: Long
    ): ByteArray {
        val file =
            File(
                directory,
                name
            )

        require(file.isFile) {
            "missing weather snapshot file: $name"
        }

        require(
            file.canonicalFile.parentFile ==
                directory.canonicalFile
        ) {
            "weather snapshot file escaped directory: $name"
        }

        require(
            file.length() <= maxBytes
        ) {
            "weather snapshot file too large: $name"
        }

        val output =
            ByteArrayOutputStream()

        val buffer =
            ByteArray(8192)

        var total = 0L

        FileInputStream(file).use { input ->
            while (true) {
                val count =
                    input.read(buffer)

                if (count < 0) {
                    break
                }

                total =
                    Math.addExact(
                        total,
                        count.toLong()
                    )

                require(total <= maxBytes) {
                    "weather snapshot exceeded size limit: $name"
                }

                output.write(
                    buffer,
                    0,
                    count
                )
            }
        }

        return output.toByteArray()
    }

    private fun sha256Hex(
        bytes: ByteArray
    ): String {
        val digest =
            MessageDigest
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
