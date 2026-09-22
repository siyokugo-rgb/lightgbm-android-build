package com.keiba.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class NarJmaObservedWeatherSnapshotStoreTest {

    @Test
    fun savesAndVerifiesRawObservedCsvSnapshot() {
        val root =
            Files.createTempDirectory(
                "nar-obs-snapshot"
            ).toFile()

        try {
            val saved =
                NarJmaObservedWeatherSnapshotStore
                    .saveToRoot(
                        root,
                        sampleData()
                    )

            assertEquals(
                NarJmaObservedWeatherSnapshotStore
                    .SaveStatus.CREATED,
                saved.status
            )

            assertEquals(
                "pre1h00_rct",
                saved.productId
            )

            assertTrue(
                saved.directory
                    .resolve(
                        "observed.csv"
                    )
                    .isFile
            )

            assertTrue(
                saved.directory
                    .resolve(
                        "manifest.txt"
                    )
                    .isFile
            )

            assertArrayEquals(
                sampleCsvBytes(),
                saved.directory
                    .resolve(
                        "observed.csv"
                    )
                    .readBytes()
            )

            assertEquals(
                "20260829",
                saved.directory
                    .parentFile
                    ?.name
            )

            assertEquals(
                "pre1h00_rct",
                saved.directory
                    .parentFile
                    ?.parentFile
                    ?.name
            )

            assertEquals(
                1_788_001_120_000L,
                saved.pitEvidenceAtEpochMillis
            )

            assertTrue(
                NarJmaObservedWeatherSnapshotStore
                    .verifySnapshot(
                        saved.directory
                    )
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun sameCaptureIsIdempotent() {
        val root =
            Files.createTempDirectory(
                "nar-obs-idempotent"
            ).toFile()

        try {
            val first =
                NarJmaObservedWeatherSnapshotStore
                    .saveToRoot(
                        root,
                        sampleData()
                    )

            val second =
                NarJmaObservedWeatherSnapshotStore
                    .saveToRoot(
                        root,
                        sampleData()
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
                first.directory.canonicalFile,
                second.directory.canonicalFile
            )

            assertEquals(
                first.snapshotSha256,
                second.snapshotSha256
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun revisionCreatesSeparateSnapshot() {
        val root =
            Files.createTempDirectory(
                "nar-obs-revision"
            ).toFile()

        try {
            val first =
                NarJmaObservedWeatherSnapshotStore
                    .saveToRoot(
                        root,
                        sampleData()
                    )

            val revised =
                sampleData()
                    .copy(
                        responseBytes =
                            (
                                "観測所番号,地点,現在値(mm),現在値の品質情報\n" +
                                    "44166,羽田,1,8\n"
                            ).toByteArray(
                                Charsets.UTF_8
                            ),
                        downloadedAtEpochMillis =
                            1_788_001_100_001L
                    )

            val second =
                NarJmaObservedWeatherSnapshotStore
                    .saveToRoot(
                        root,
                        revised
                    )

            assertNotEquals(
                first.directory.canonicalFile,
                second.directory.canonicalFile
            )

            assertNotEquals(
                first.rawSha256,
                second.rawSha256
            )

            assertTrue(
                NarJmaObservedWeatherSnapshotStore
                    .verifySnapshot(
                        first.directory
                    )
            )

            assertTrue(
                NarJmaObservedWeatherSnapshotStore
                    .verifySnapshot(
                        second.directory
                    )
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rawShaMismatchIsDetected() {
        val root =
            Files.createTempDirectory(
                "nar-obs-tamper-raw"
            ).toFile()

        try {
            val saved =
                NarJmaObservedWeatherSnapshotStore
                    .saveToRoot(
                        root,
                        sampleData()
                    )

            saved.directory
                .resolve(
                    "observed.csv"
                )
                .writeBytes(
                    (
                        "観測所番号,地点,現在値(mm),現在値の品質情報\n" +
                            "44166,羽田,9,8\n"
                    ).toByteArray(
                        Charsets.UTF_8
                    )
                )

            assertFalse(
                NarJmaObservedWeatherSnapshotStore
                    .verifySnapshot(
                        saved.directory
                    )
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun snapshotShaMismatchIsDetected() {
        val root =
            Files.createTempDirectory(
                "nar-obs-tamper-manifest"
            ).toFile()

        try {
            val saved =
                NarJmaObservedWeatherSnapshotStore
                    .saveToRoot(
                        root,
                        sampleData()
                    )

            val manifest =
                saved.directory
                    .resolve(
                        "manifest.txt"
                    )

            val text =
                manifest.readText(
                    Charsets.UTF_8
                )

            manifest.writeText(
                text.replace(
                    Regex(
                        "snapshot_sha256=[0-9a-f]+"
                    ),
                    "snapshot_sha256=" +
                        "0".repeat(64)
                ),
                Charsets.UTF_8
            )

            assertFalse(
                NarJmaObservedWeatherSnapshotStore
                    .verifySnapshot(
                        saved.directory
                    )
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun unexpectedFileIsRejected() {
        val root =
            Files.createTempDirectory(
                "nar-obs-extra"
            ).toFile()

        try {
            val saved =
                NarJmaObservedWeatherSnapshotStore
                    .saveToRoot(
                        root,
                        sampleData()
                    )

            saved.directory
                .resolve(
                    "extra.txt"
                )
                .writeText(
                    "nope",
                    Charsets.UTF_8
                )

            assertFalse(
                NarJmaObservedWeatherSnapshotStore
                    .verifySnapshot(
                        saved.directory
                    )
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun missingRawFileFailsVerification() {
        val root =
            Files.createTempDirectory(
                "nar-obs-missing"
            ).toFile()

        try {
            val saved =
                NarJmaObservedWeatherSnapshotStore
                    .saveToRoot(
                        root,
                        sampleData()
                    )

            saved.directory
                .resolve(
                    "observed.csv"
                )
                .delete()

            assertFalse(
                NarJmaObservedWeatherSnapshotStore
                    .verifySnapshot(
                        saved.directory
                    )
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun duplicateManifestKeyFailsVerification() {
        val root =
            Files.createTempDirectory(
                "nar-obs-dup-key"
            ).toFile()

        try {
            val saved =
                NarJmaObservedWeatherSnapshotStore
                    .saveToRoot(
                        root,
                        sampleData()
                    )

            val manifest =
                saved.directory
                    .resolve(
                        "manifest.txt"
                    )

            manifest.appendText(
                "product_id=pre1h00_rct\n"
            )

            assertFalse(
                NarJmaObservedWeatherSnapshotStore
                    .verifySnapshot(
                        saved.directory
                    )
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun unknownManifestKeyFailsVerification() {
        val root =
            Files.createTempDirectory(
                "nar-obs-unknown-key"
            ).toFile()

        try {
            val saved =
                NarJmaObservedWeatherSnapshotStore
                    .saveToRoot(
                        root,
                        sampleData()
                    )

            val manifest =
                saved.directory
                    .resolve(
                        "manifest.txt"
                    )

            manifest.appendText(
                "station_id=44166\n"
            )

            assertFalse(
                NarJmaObservedWeatherSnapshotStore
                    .verifySnapshot(
                        saved.directory
                    )
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun mismatchedSourceUrlIsRejectedOnSave() {
        val root =
            Files.createTempDirectory(
                "nar-obs-url"
            ).toFile()

        try {
            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarJmaObservedWeatherSnapshotStore
                    .saveToRoot(
                        root,
                        sampleData()
                            .copy(
                                requestUrl =
                                    "https://www.data.jma.go.jp/stats/data/mdrr/pre_rct/alltable/pre3h00_rct.csv"
                            )
                    )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun invalidProductIdIsRejectedOnSave() {
        val root =
            Files.createTempDirectory(
                "nar-obs-bad-product"
            ).toFile()

        try {
            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarJmaObservedWeatherSnapshotStore
                    .saveToRoot(
                        root,
                        sampleData()
                            .copy(
                                productId =
                                    "../escape"
                            )
                    )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun invalidDownloadedAtIsRejected() {
        val root =
            Files.createTempDirectory(
                "nar-obs-time"
            ).toFile()

        try {
            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarJmaObservedWeatherSnapshotStore
                    .saveToRoot(
                        root,
                        sampleData()
                            .copy(
                                downloadedAtEpochMillis =
                                    0L
                            )
                    )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun missingServerDateUsesDownloadAsPitEvidence() {
        val root =
            Files.createTempDirectory(
                "nar-obs-no-server"
            ).toFile()

        try {
            val saved =
                NarJmaObservedWeatherSnapshotStore
                    .saveToRoot(
                        root,
                        sampleData()
                            .copy(
                                serverDateEpochMillis =
                                    null
                            )
                    )

            assertEquals(
                1_788_001_100_000L,
                saved.pitEvidenceAtEpochMillis
            )

            assertTrue(
                NarJmaObservedWeatherSnapshotStore
                    .verifySnapshot(
                        saved.directory
                    )
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun directoryNameMismatchFailsVerification() {
        val root =
            Files.createTempDirectory(
                "nar-obs-dirname"
            ).toFile()

        try {
            val saved =
                NarJmaObservedWeatherSnapshotStore
                    .saveToRoot(
                        root,
                        sampleData()
                    )

            val parent =
                saved.directory.parentFile
                    ?: error("missing parent")

            val renamed =
                File(
                    parent,
                    "0-" +
                        saved.snapshotSha256
                )

            assertTrue(
                saved.directory.renameTo(
                    renamed
                )
            )

            assertFalse(
                NarJmaObservedWeatherSnapshotStore
                    .verifySnapshot(
                        renamed
                    )
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun conflictingSameIdentityIsRejected() {
        val root =
            Files.createTempDirectory(
                "nar-obs-conflict"
            ).toFile()

        try {
            val first =
                NarJmaObservedWeatherSnapshotStore
                    .saveToRoot(
                        root,
                        sampleData()
                    )

            // Corrupt raw while keeping directory name/identity
            // so a second save of the original payload fails closed.
            first.directory
                .resolve(
                    "observed.csv"
                )
                .writeBytes(
                    (
                        "観測所番号,地点,現在値(mm),現在値の品質情報\n" +
                            "44166,羽田,3,8\n"
                    ).toByteArray(
                        Charsets.UTF_8
                    )
                )

            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarJmaObservedWeatherSnapshotStore
                    .saveToRoot(
                        root,
                        sampleData()
                    )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun symlinkRawFileFailsVerification() {
        val root =
            Files.createTempDirectory(
                "nar-obs-symlink"
            ).toFile()

        try {
            val saved =
                NarJmaObservedWeatherSnapshotStore
                    .saveToRoot(
                        root,
                        sampleData()
                    )

            val raw =
                saved.directory
                    .resolve(
                        "observed.csv"
                    )

            val outside =
                File(
                    root,
                    "outside.csv"
                )

            outside.writeBytes(
                sampleCsvBytes()
            )

            assertTrue(
                raw.delete()
            )

            Files.createSymbolicLink(
                raw.toPath(),
                outside.toPath()
            )

            assertFalse(
                NarJmaObservedWeatherSnapshotStore
                    .verifySnapshot(
                        saved.directory
                    )
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun oversizedManifestFailsVerification() {
        val root =
            Files.createTempDirectory(
                "nar-obs-big-manifest"
            ).toFile()

        try {
            val saved =
                NarJmaObservedWeatherSnapshotStore
                    .saveToRoot(
                        root,
                        sampleData()
                    )

            val manifest =
                saved.directory
                    .resolve(
                        "manifest.txt"
                    )

            // Exceed MAX_MANIFEST_BYTES (32 KiB) while keeping a parseable shape.
            val padding =
                "x".repeat(
                    40 * 1024
                )

            manifest.writeText(
                manifest.readText(
                    Charsets.UTF_8
                ) +
                    "padding=$padding\n",
                Charsets.UTF_8
            )

            assertFalse(
                NarJmaObservedWeatherSnapshotStore
                    .verifySnapshot(
                        saved.directory
                    )
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun pitEvidenceMismatchFailsVerification() {
        val root =
            Files.createTempDirectory(
                "nar-obs-pit"
            ).toFile()

        try {
            val saved =
                NarJmaObservedWeatherSnapshotStore
                    .saveToRoot(
                        root,
                        sampleData()
                    )

            val manifest =
                saved.directory
                    .resolve(
                        "manifest.txt"
                    )

            val text =
                manifest.readText(
                    Charsets.UTF_8
                )

            manifest.writeText(
                text.replace(
                    "pit_evidence_at_epoch_millis=1788001120000",
                    "pit_evidence_at_epoch_millis=1"
                ),
                Charsets.UTF_8
            )

            assertFalse(
                NarJmaObservedWeatherSnapshotStore
                    .verifySnapshot(
                        saved.directory
                    )
            )
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
                    sampleCsvBytes(),
                contentType =
                    "text/csv",
                downloadedAtEpochMillis =
                    1_788_001_100_000L,
                serverDateEpochMillis =
                    1_788_001_120_000L,
                lastModifiedEpochMillis =
                    1_788_001_050_000L
            )
    }

    private fun sampleCsvBytes(): ByteArray =
        (
            "観測所番号,地点,現在値(mm),現在値の品質情報\n" +
                "44166,羽田,0,8\n"
        ).toByteArray(
            Charsets.UTF_8
        )
}
