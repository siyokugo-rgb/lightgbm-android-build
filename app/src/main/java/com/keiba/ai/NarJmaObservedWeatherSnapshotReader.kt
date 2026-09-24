package com.keiba.ai

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Re-verifies a selected LIVE_CAPTURED_OBSERVED snapshot, parses
 * observed.csv, and returns an exact station row with PIT provenance.
 *
 * Does not perform network I/O.
 * Does not load venue→station mapping assets.
 * Does not invent PIT availability timestamps.
 */
object NarJmaObservedWeatherSnapshotReader {

    private const val ROOT_NAME =
        "nar-jma-observed-weather-snapshots"

    private val STATION_ID_PATTERN =
        Regex("""^\d+$""")

    data class SnapshotInput(
        val selection:
            NarJmaObservedWeatherSnapshotSelector.Selection,
        val stationId: String,
        val selectedRow: NarJmaObservedWeatherRow
    )

    fun read(
        context: Context,
        product: NarJmaObservedWeatherProduct,
        stationId: String,
        predictionAsOfEpochMillis: Long
    ): SnapshotInput? =
        readFromRoot(
            root = File(
                context.noBackupFilesDir,
                ROOT_NAME
            ),
            product = product,
            stationId = stationId,
            predictionAsOfEpochMillis =
                predictionAsOfEpochMillis
        )

    internal fun readFromRoot(
        root: File,
        product: NarJmaObservedWeatherProduct,
        stationId: String,
        predictionAsOfEpochMillis: Long
    ): SnapshotInput? {
        require(
            STATION_ID_PATTERN.matches(
                stationId.trim()
            )
        ) {
            "invalid station_id"
        }

        val selection =
            NarJmaObservedWeatherSnapshotSelector
                .selectLatestFromRoot(
                    root = root,
                    product = product,
                    predictionAsOfEpochMillis =
                        predictionAsOfEpochMillis
                )
                ?: return null

        return readSelected(
            selection = selection,
            stationId = stationId.trim()
        )
    }

    internal fun readSelectedForTest(
        selection:
            NarJmaObservedWeatherSnapshotSelector.Selection,
        stationId: String
    ): SnapshotInput? =
        readSelected(
            selection = selection,
            stationId = stationId.trim()
        )

    private fun readSelected(
        selection:
            NarJmaObservedWeatherSnapshotSelector.Selection,
        stationId: String
    ): SnapshotInput? {
        require(
            STATION_ID_PATTERN.matches(
                stationId
            )
        ) {
            "invalid station_id"
        }

        require(
            selection.pitEvidenceAtEpochMillis <=
                selection.predictionAsOfEpochMillis
        ) {
            "future observed weather snapshot selection rejected"
        }

        require(
            selection.ageMillis ==
                Math.subtractExact(
                    selection.predictionAsOfEpochMillis,
                    selection.pitEvidenceAtEpochMillis
                )
        ) {
            "invalid observed weather snapshot age"
        }

        val directory =
            selection.directory
                .canonicalFile

        require(
            directory ==
                selection.directory
        ) {
            "observed weather snapshot directory is not canonical"
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
            "observed weather selection directory identity mismatch"
        }

        require(
            directory.parentFile?.name ==
                selection.captureDateUtc
        ) {
            "observed weather selection day identity mismatch"
        }

        require(
            directory.parentFile
                ?.parentFile
                ?.name ==
                selection.product.productId
        ) {
            "observed weather selection product identity mismatch"
        }

        require(
            NarJmaObservedWeatherSnapshotStore
                .verifySnapshot(
                    directory
                )
        ) {
            "observed weather snapshot failed pre-read integrity verification"
        }

        val pinned =
            readPinnedManifest(
                directory = directory,
                selection = selection
            )

        require(
            pinned.rawSha256 ==
                selection.rawSha256
        ) {
            "observed weather selection raw SHA mismatch"
        }

        require(
            pinned.snapshotSha256 ==
                selection.snapshotSha256
        ) {
            "observed weather selection snapshot SHA mismatch"
        }

        require(
            pinned.sourceUrl ==
                selection.sourceUrl
        ) {
            "observed weather selection source URL mismatch"
        }

        require(
            pinned.productId ==
                selection.product.productId
        ) {
            "observed weather selection product mismatch"
        }

        require(
            pinned.pitEvidenceAtEpochMillis ==
                selection.pitEvidenceAtEpochMillis
        ) {
            "observed weather selection pit evidence mismatch"
        }

        require(
            pinned.downloadedAtEpochMillis ==
                selection.downloadedAtEpochMillis
        ) {
            "observed weather selection downloadedAt mismatch"
        }

        val rawBytes =
            readStrictChildBytes(
                directory = directory,
                name = "observed.csv",
                maxBytes =
                    NarJmaObservedWeatherDownloader
                        .MAX_RESPONSE_BYTES
            )

        require(
            sha256Hex(rawBytes) ==
                selection.rawSha256
        ) {
            "observed.csv bytes do not match selected snapshot"
        }

        require(
            rawBytes.size.toLong() ==
                selection.contentLength
        ) {
            "observed.csv length does not match selected snapshot"
        }

        require(
            NarJmaObservedWeatherSnapshotStore
                .verifySnapshot(
                    directory
                )
        ) {
            "observed weather snapshot changed during read"
        }

        val pinnedAfter =
            readPinnedManifest(
                directory = directory,
                selection = selection
            )

        require(pinned == pinnedAfter) {
            "observed weather snapshot manifest changed during read"
        }

        val payload =
            NarJmaObservedWeatherCsvParser.parse(
                selection.product,
                rawBytes
            )

        require(
            payload.product ==
                selection.product
        ) {
            "observed weather parsed product mismatch"
        }

        val selectedRow =
            NarJmaObservedWeatherStationSelector
                .select(
                    payload,
                    stationId
                )
                ?: return null

        require(
            selectedRow.productTimestampEpochMillis >
                0L
        ) {
            "invalid observed weather product timestamp"
        }

        require(
            selectedRow.productTimestampEpochMillis <=
                selection.pitEvidenceAtEpochMillis
        ) {
            "observed productTimestamp exceeds pitEvidence"
        }

        require(
            selectedRow.productTimestampEpochMillis <=
                selection.predictionAsOfEpochMillis
        ) {
            "future observed productTimestamp rejected"
        }

        return SnapshotInput(
            selection = selection,
            stationId = stationId,
            selectedRow = selectedRow
        )
    }

    private data class PinnedManifest(
        val productId: String,
        val sourceUrl: String,
        val downloadedAtEpochMillis: Long,
        val pitEvidenceAtEpochMillis: Long,
        val rawSha256: String,
        val snapshotSha256: String
    )

    private fun readPinnedManifest(
        directory: File,
        selection:
            NarJmaObservedWeatherSnapshotSelector.Selection
    ): PinnedManifest {
        val manifestFile =
            File(
                directory,
                "manifest.txt"
            )

        require(manifestFile.isFile) {
            "missing observed weather manifest"
        }

        require(
            manifestFile.canonicalFile
                .parentFile ==
                directory.canonicalFile
        ) {
            "observed weather manifest escaped directory"
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
                "invalid observed weather snapshot manifest"
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
                "duplicate observed weather manifest key"
            }

            fields[key] = value
        }

        val productId =
            fields["product_id"]
                ?: error(
                    "missing observed weather product_id"
                )

        val sourceUrl =
            fields["source_url"]
                ?: error(
                    "missing observed weather source_url"
                )

        val downloadedAt =
            fields[
                "downloaded_at_epoch_millis"
            ]
                ?.toLongOrNull()
                ?: error(
                    "invalid observed weather downloaded_at"
                )

        val pitEvidenceAt =
            fields[
                "pit_evidence_at_epoch_millis"
            ]
                ?.toLongOrNull()
                ?: error(
                    "invalid observed weather pit evidence"
                )

        val rawSha256 =
            fields["raw_sha256"]
                ?: error(
                    "missing observed weather raw_sha256"
                )

        val snapshotSha256 =
            fields["snapshot_sha256"]
                ?: error(
                    "missing observed weather snapshot_sha256"
                )

        require(
            productId ==
                selection.product.productId
        ) {
            "observed weather selection product mismatch"
        }

        require(
            sourceUrl ==
                selection.sourceUrl
        ) {
            "observed weather selection source URL mismatch"
        }

        require(
            downloadedAt ==
                selection.downloadedAtEpochMillis
        ) {
            "observed weather selection downloadedAt mismatch"
        }

        require(
            pitEvidenceAt ==
                selection.pitEvidenceAtEpochMillis
        ) {
            "observed weather selection pit evidence mismatch"
        }

        require(
            rawSha256 ==
                selection.rawSha256
        ) {
            "observed weather selection raw SHA mismatch"
        }

        require(
            snapshotSha256 ==
                selection.snapshotSha256
        ) {
            "observed weather selection snapshot SHA mismatch"
        }

        return PinnedManifest(
            productId = productId,
            sourceUrl = sourceUrl,
            downloadedAtEpochMillis =
                downloadedAt,
            pitEvidenceAtEpochMillis =
                pitEvidenceAt,
            rawSha256 = rawSha256,
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
            "missing observed weather snapshot file: $name"
        }

        require(
            file.canonicalFile.parentFile ==
                directory.canonicalFile
        ) {
            "observed weather snapshot file escaped directory: $name"
        }

        require(
            file.length() <= maxBytes
        ) {
            "observed weather snapshot file too large: $name"
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
                    "observed weather snapshot exceeded size limit: $name"
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
