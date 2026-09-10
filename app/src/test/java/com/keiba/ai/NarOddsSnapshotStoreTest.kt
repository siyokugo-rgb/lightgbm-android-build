package com.keiba.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.time.LocalDate

class NarOddsSnapshotStoreTest {

    @Test
    fun savesAndVerifiesRawOddsSnapshot() {
        val root =
            Files.createTempDirectory(
                "nar-odds-snapshot"
            ).toFile()

        try {
            val data =
                sampleData()

            val saved =
                NarOddsSnapshotStore
                    .saveToRoot(
                        root = root,
                        data = data
                    )

            assertEquals(
                NarOddsSnapshotStore
                    .SaveStatus.CREATED,
                saved.status
            )

            assertArrayEquals(
                data.responseBytes,
                saved.directory
                    .resolve("odds.html")
                    .readBytes()
            )

            assertTrue(
                saved.directory
                    .resolve("manifest.txt")
                    .isFile
            )

            assertEquals(
                "03",
                saved.directory
                    .parentFile
                    ?.name
            )

            assertEquals(
                "36",
                saved.directory
                    .parentFile
                    ?.parentFile
                    ?.name
            )

            assertEquals(
                "20260908",
                saved.directory
                    .parentFile
                    ?.parentFile
                    ?.parentFile
                    ?.name
            )

            assertEquals(
                data.observedAtEpochMillis,
                saved.observedAtEpochMillis
            )

            assertTrue(
                NarOddsSnapshotStore
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
                "nar-odds-idempotent"
            ).toFile()

        try {
            val data =
                sampleData()

            val first =
                NarOddsSnapshotStore
                    .saveToRoot(
                        root,
                        data
                    )

            val rawBefore =
                first.directory
                    .resolve("odds.html")
                    .readBytes()

            val manifestBefore =
                first.directory
                    .resolve("manifest.txt")
                    .readBytes()

            val second =
                NarOddsSnapshotStore
                    .saveToRoot(
                        root,
                        data
                    )

            assertEquals(
                NarOddsSnapshotStore
                    .SaveStatus.CREATED,
                first.status
            )

            assertEquals(
                NarOddsSnapshotStore
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

            assertArrayEquals(
                rawBefore,
                first.directory
                    .resolve("odds.html")
                    .readBytes()
            )

            assertArrayEquals(
                manifestBefore,
                first.directory
                    .resolve("manifest.txt")
                    .readBytes()
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun sameRawAtDifferentCaptureTimeIsPreservedSeparately() {
        val root =
            Files.createTempDirectory(
                "nar-odds-append-only"
            ).toFile()

        try {
            val firstData =
                sampleData()

            val secondData =
                firstData.copy(
                    downloadStartedAtEpochMillis =
                        firstData
                            .downloadStartedAtEpochMillis +
                            60_000L,
                    downloadCompletedAtEpochMillis =
                        firstData
                            .downloadCompletedAtEpochMillis +
                            60_000L,
                    serverDateEpochMillis =
                        requireNotNull(
                            firstData
                                .serverDateEpochMillis
                        ) + 60_000L,
                    observedAtEpochMillis =
                        firstData
                            .observedAtEpochMillis +
                            60_000L
                )

            val first =
                NarOddsSnapshotStore
                    .saveToRoot(
                        root,
                        firstData
                    )

            val second =
                NarOddsSnapshotStore
                    .saveToRoot(
                        root,
                        secondData
                    )

            assertNotEquals(
                first.directory.canonicalFile,
                second.directory.canonicalFile
            )

            assertNotEquals(
                first.snapshotSha256,
                second.snapshotSha256
            )

            assertEquals(
                first.rawResponseSha256,
                second.rawResponseSha256
            )

            assertTrue(
                NarOddsSnapshotStore
                    .verifySnapshot(
                        first.directory
                    )
            )

            assertTrue(
                NarOddsSnapshotStore
                    .verifySnapshot(
                        second.directory
                    )
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rawOddsTamperingIsDetected() {
        val root =
            Files.createTempDirectory(
                "nar-odds-raw-tamper"
            ).toFile()

        try {
            val saved =
                NarOddsSnapshotStore
                    .saveToRoot(
                        root,
                        sampleData()
                    )

            saved.directory
                .resolve("odds.html")
                .appendText(
                    " ",
                    Charsets.UTF_8
                )

            assertFalse(
                NarOddsSnapshotStore
                    .verifySnapshot(
                        saved.directory
                    )
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun manifestTamperingIsDetected() {
        val root =
            Files.createTempDirectory(
                "nar-odds-manifest-tamper"
            ).toFile()

        try {
            val saved =
                NarOddsSnapshotStore
                    .saveToRoot(
                        root,
                        sampleData()
                    )

            val manifest =
                saved.directory
                    .resolve("manifest.txt")

            val oldText =
                "observed_at_epoch_millis=" +
                    saved.observedAtEpochMillis

            val newText =
                "observed_at_epoch_millis=" +
                    (
                        saved.observedAtEpochMillis +
                            1L
                    )

            val text =
                manifest.readText(
                    Charsets.UTF_8
                )

            assertTrue(
                text.contains(
                    oldText
                )
            )

            manifest.writeText(
                text.replace(
                    oldText,
                    newText
                ),
                Charsets.UTF_8
            )

            assertFalse(
                NarOddsSnapshotStore
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
                "nar-odds-extra-file"
            ).toFile()

        try {
            val saved =
                NarOddsSnapshotStore
                    .saveToRoot(
                        root,
                        sampleData()
                    )

            saved.directory
                .resolve("unexpected.txt")
                .writeText(
                    "unexpected",
                    Charsets.UTF_8
                )

            assertFalse(
                NarOddsSnapshotStore
                    .verifySnapshot(
                        saved.directory
                    )
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun mismatchedRequestProvenanceIsRejected() {
        val root =
            Files.createTempDirectory(
                "nar-odds-request"
            ).toFile()

        try {
            val bad =
                sampleData()
                    .copy(
                        requestUrl =
                            NarOddsDownloader
                                .buildRequestUrl(
                                    babaCode = "36",
                                    raceDate =
                                        LocalDate.of(
                                            2026,
                                            9,
                                            8
                                        ),
                                    raceNo = 4
                                )
                    )

            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarOddsSnapshotStore
                    .saveToRoot(
                        root,
                        bad
                    )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun mismatchedObservedAtIsRejected() {
        val root =
            Files.createTempDirectory(
                "nar-odds-observed-at"
            ).toFile()

        try {
            val bad =
                sampleData()
                    .copy(
                        observedAtEpochMillis =
                            sampleData()
                                .observedAtEpochMillis -
                                1L
                    )

            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarOddsSnapshotStore
                    .saveToRoot(
                        root,
                        bad
                    )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun unexpectedCoveredBetTypesAreRejected() {
        val root =
            Files.createTempDirectory(
                "nar-odds-bet-types"
            ).toFile()

        try {
            val bad =
                sampleData()
                    .copy(
                        coveredBetTypes =
                            setOf(
                                NarOddsDownloader
                                    .BetType.WIN
                            )
                    )

            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarOddsSnapshotStore
                    .saveToRoot(
                        root,
                        bad
                    )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun missingServerDateUsesCompletionAsObservedAt() {
        val root =
            Files.createTempDirectory(
                "nar-odds-no-server-date"
            ).toFile()

        try {
            val source =
                sampleData()

            val data =
                source.copy(
                    serverDateEpochMillis =
                        null,
                    observedAtEpochMillis =
                        source
                            .downloadCompletedAtEpochMillis
                )

            val saved =
                NarOddsSnapshotStore
                    .saveToRoot(
                        root,
                        data
                    )

            assertEquals(
                data.downloadCompletedAtEpochMillis,
                saved.observedAtEpochMillis
            )

            assertTrue(
                NarOddsSnapshotStore
                    .verifySnapshot(
                        saved.directory
                    )
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun invalidDownloadTimestampOrderIsRejected() {
        val root =
            Files.createTempDirectory(
                "nar-odds-time-order"
            ).toFile()

        try {
            val source =
                sampleData()

            val bad =
                source.copy(
                    downloadStartedAtEpochMillis =
                        source
                            .downloadCompletedAtEpochMillis +
                            1L
                )

            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarOddsSnapshotStore
                    .saveToRoot(
                        root,
                        bad
                    )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun existingIntermediateFileIsRejected() {
        val root =
            Files.createTempDirectory(
                "nar-odds-intermediate-file"
            ).toFile()

        try {
            root.resolve(
                "20260908"
            ).writeText(
                "not a directory",
                Charsets.UTF_8
            )

            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarOddsSnapshotStore
                    .saveToRoot(
                        root,
                        sampleData()
                    )
            }

            assertFalse(
                root.resolve(
                    "20260908"
                )
                    .resolve("36")
                    .exists()
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun corruptedExistingSnapshotIsNotOverwritten() {
        val root =
            Files.createTempDirectory(
                "nar-odds-existing-corrupt"
            ).toFile()

        try {
            val data =
                sampleData()

            val saved =
                NarOddsSnapshotStore
                    .saveToRoot(
                        root,
                        data
                    )

            val raw =
                saved.directory
                    .resolve("odds.html")

            val manifest =
                saved.directory
                    .resolve("manifest.txt")

            raw.appendText(
                " ",
                Charsets.UTF_8
            )

            val corruptedRawBefore =
                raw.readBytes()

            val manifestBefore =
                manifest.readBytes()

            assertFalse(
                NarOddsSnapshotStore
                    .verifySnapshot(
                        saved.directory
                    )
            )

            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarOddsSnapshotStore
                    .saveToRoot(
                        root,
                        data
                    )
            }

            assertArrayEquals(
                corruptedRawBefore,
                raw.readBytes()
            )

            assertArrayEquals(
                manifestBefore,
                manifest.readBytes()
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun symbolicLinkIntermediateEscapeIsRejected() {
        val root =
            Files.createTempDirectory(
                "nar-odds-symlink-root"
            ).toFile()

        val outside =
            Files.createTempDirectory(
                "nar-odds-symlink-outside"
            ).toFile()

        val linkPath =
            root.resolve(
                "20260908"
            ).toPath()

        try {
            val linkCreated =
                try {
                    Files.createSymbolicLink(
                        linkPath,
                        outside.toPath()
                    )

                    true
                } catch (_: UnsupportedOperationException) {
                    false
                } catch (_: java.io.IOException) {
                    false
                } catch (_: SecurityException) {
                    false
                }

            assumeTrue(
                "symbolic-link creation unavailable on this platform",
                linkCreated
            )

            assertTrue(
                Files.isSymbolicLink(
                    linkPath
                )
            )

            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarOddsSnapshotStore
                    .saveToRoot(
                        root,
                        sampleData()
                    )
            }

            assertFalse(
                outside.resolve(
                    "36"
                ).exists()
            )
        } finally {
            try {
                Files.deleteIfExists(
                    linkPath
                )
            } catch (_: Exception) {
                // Best-effort test cleanup.
            }

            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    private fun sampleData():
        NarOddsDownloader.OddsResponse {
        val raceDate =
            LocalDate.of(
                2026,
                9,
                8
            )

        val babaCode =
            "36"

        val raceNo =
            3

        val startedAt =
            1_788_840_000_000L

        val completedAt =
            1_788_840_001_000L

        val serverDate =
            1_788_840_002_000L

        return NarOddsDownloader
            .OddsResponse(
                babaCode =
                    babaCode,
                raceDate =
                    raceDate,
                raceNo =
                    raceNo,
                requestUrl =
                    NarOddsDownloader
                        .buildRequestUrl(
                            babaCode = babaCode,
                            raceDate = raceDate,
                            raceNo = raceNo
                        ),
                coveredBetTypes =
                    setOf(
                        NarOddsDownloader
                            .BetType.WIN,
                        NarOddsDownloader
                            .BetType.PLACE
                    ),
                responseBytes =
                    sampleHtml()
                        .toByteArray(
                            Charsets.UTF_8
                        ),
                downloadStartedAtEpochMillis =
                    startedAt,
                downloadCompletedAtEpochMillis =
                    completedAt,
                serverDateEpochMillis =
                    serverDate,
                observedAtEpochMillis =
                    serverDate
            )
    }

    private fun sampleHtml(): String =
        """
        <!doctype html>
        <html lang="ja">
        <head>
          <meta charset="UTF-8">
          <title>オッズ｜地方競馬情報サイト</title>
        </head>
        <body>
          <h1>オッズ</h1>
          <div>単勝・複勝</div>
        </body>
        </html>
        """.trimIndent()
}
