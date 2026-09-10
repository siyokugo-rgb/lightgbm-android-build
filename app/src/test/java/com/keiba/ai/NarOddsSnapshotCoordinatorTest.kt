package com.keiba.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.LocalDate

class NarOddsSnapshotCoordinatorTest {

    @Test
    fun capturesAndVerifiesDownloadedOdds() {
        val root =
            Files.createTempDirectory(
                "odds-coordinator"
            ).toFile()

        try {
            val data =
                sampleData()

            val result =
                NarOddsSnapshotCoordinator
                    .captureDownloadedToRoot(
                        root = root,
                        data = data
                    )

            assertEquals(
                NarOddsSnapshotStore
                    .SaveStatus.CREATED,
                result.status
            )

            assertEquals(
                data.babaCode,
                result.babaCode
            )

            assertEquals(
                data.raceDate,
                result.raceDate
            )

            assertEquals(
                data.raceNo,
                result.raceNo
            )

            assertEquals(
                data.observedAtEpochMillis,
                result.observedAtEpochMillis
            )

            assertTrue(
                result.rawResponseSha256
                    .matches(
                        Regex(
                            """^[0-9a-f]{64}$"""
                        )
                    )
            )

            assertTrue(
                result.snapshotSha256
                    .matches(
                        Regex(
                            """^[0-9a-f]{64}$"""
                        )
                    )
            )

            assertTrue(
                result.snapshotDirectory
                    .isDirectory
            )

            assertTrue(
                NarOddsSnapshotStore
                    .verifySnapshot(
                        result.snapshotDirectory
                    )
            )

            assertArrayEquals(
                data.responseBytes,
                result.snapshotDirectory
                    .resolve("odds.html")
                    .readBytes()
            )

            assertTrue(
                result.snapshotDirectory
                    .resolve("manifest.txt")
                    .isFile
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun repeatedSameOddsReturnsAlreadyPresent() {
        val root =
            Files.createTempDirectory(
                "odds-coordinator-repeat"
            ).toFile()

        try {
            val data =
                sampleData()

            val first =
                NarOddsSnapshotCoordinator
                    .captureDownloadedToRoot(
                        root = root,
                        data = data
                    )

            val second =
                NarOddsSnapshotCoordinator
                    .captureDownloadedToRoot(
                        root = root,
                        data = data
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
                first.snapshotDirectory,
                second.snapshotDirectory
            )

            assertEquals(
                first.rawResponseSha256,
                second.rawResponseSha256
            )

            assertEquals(
                first.snapshotSha256,
                second.snapshotSha256
            )

            assertEquals(
                first.observedAtEpochMillis,
                second.observedAtEpochMillis
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun tamperedExistingOddsIsRejected() {
        val root =
            Files.createTempDirectory(
                "odds-coordinator-tamper"
            ).toFile()

        try {
            val data =
                sampleData()

            val first =
                NarOddsSnapshotCoordinator
                    .captureDownloadedToRoot(
                        root = root,
                        data = data
                    )

            val raw =
                first.snapshotDirectory
                    .resolve("odds.html")

            raw.appendText(
                " ",
                Charsets.UTF_8
            )

            val corruptedBefore =
                raw.readBytes()

            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarOddsSnapshotCoordinator
                    .captureDownloadedToRoot(
                        root = root,
                        data = data
                    )
            }

            assertArrayEquals(
                corruptedBefore,
                raw.readBytes()
            )
        } finally {
            root.deleteRecursively()
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
