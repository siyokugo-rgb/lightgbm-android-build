package com.keiba.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest
import java.time.LocalDate

class NarOddsLiveE2ETest {

    @Test
    fun liveDownloadHistoricalTanFukuPage() {

        assumeTrue(
            "live NAR Odds test is opt-in only",
            System.getenv(
                "KEIBA_LIVE_ODDS_TEST"
            ) == "1"
        )

        val response =
            NarOddsDownloader
                .download(
                    babaCode = "36",
                    raceDate =
                        LocalDate.of(
                            2026,
                            9,
                            8
                        ),
                    raceNo = 3
                )

        assertEquals(
            "36",
            response.babaCode
        )

        assertEquals(
            LocalDate.of(
                2026,
                9,
                8
            ),
            response.raceDate
        )

        assertEquals(
            3,
            response.raceNo
        )

        assertEquals(
            setOf(
                NarOddsDownloader.BetType.WIN,
                NarOddsDownloader.BetType.PLACE
            ),
            response.coveredBetTypes
        )

        assertTrue(
            response.responseBytes.isNotEmpty()
        )

        assertTrue(
            response.responseBytes.size.toLong() <=
                NarOddsDownloader.MAX_RESPONSE_BYTES
        )

        assertTrue(
            response.downloadStartedAtEpochMillis >
                0L
        )

        assertTrue(
            response.downloadCompletedAtEpochMillis >=
                response.downloadStartedAtEpochMillis
        )

        val expectedObservedAt =
            maxOf(
                response.downloadCompletedAtEpochMillis,
                response.serverDateEpochMillis ?: 0L
            )

        assertEquals(
            expectedObservedAt,
            response.observedAtEpochMillis
        )

        val text =
            response.responseBytes
                .toString(
                    Charsets.UTF_8
                )

        assertTrue(
            text.contains(
                "地方競馬情報サイト"
            )
        )

        assertTrue(
            text.contains(
                "オッズ"
            )
        )

        assertTrue(
            text.contains(
                "単勝"
            )
        )

        assertTrue(
            text.contains(
                "複勝"
            )
        )

        val sha256 =
            MessageDigest
                .getInstance(
                    "SHA-256"
                )
                .digest(
                    response.responseBytes
                )
                .joinToString("") {
                    "%02x".format(it)
                }

        println(
            "NAR ODDS LIVE E2E PASS"
        )

        println(
            "request_url    = " +
                response.requestUrl
        )

        println(
            "started_at     = " +
                response.downloadStartedAtEpochMillis
        )

        println(
            "completed_at   = " +
                response.downloadCompletedAtEpochMillis
        )

        println(
            "server_date    = " +
                response.serverDateEpochMillis
        )

        println(
            "observed_at    = " +
                response.observedAtEpochMillis
        )

        println(
            "response_bytes = " +
                response.responseBytes.size
        )

        println(
            "sha256         = " +
                sha256
        )

        val snapshotRoot =
            Files.createTempDirectory(
                "nar-odds-live-snapshot"
            ).toFile()

        try {
            val captured =
                NarOddsSnapshotCoordinator
                    .captureDownloadedToRoot(
                        root = snapshotRoot,
                        data = response
                    )

            assertEquals(
                NarOddsSnapshotStore
                    .SaveStatus.CREATED,
                captured.status
            )

            assertEquals(
                response.babaCode,
                captured.babaCode
            )

            assertEquals(
                response.raceDate,
                captured.raceDate
            )

            assertEquals(
                response.raceNo,
                captured.raceNo
            )

            assertEquals(
                response.observedAtEpochMillis,
                captured.observedAtEpochMillis
            )

            assertEquals(
                sha256,
                captured.rawResponseSha256
            )

            assertTrue(
                captured.snapshotSha256
                    .matches(
                        Regex(
                            """^[0-9a-f]{64}$"""
                        )
                    )
            )

            assertTrue(
                NarOddsSnapshotStore
                    .verifySnapshot(
                        captured.snapshotDirectory
                    )
            )

            assertTrue(
                response.responseBytes
                    .contentEquals(
                        captured.snapshotDirectory
                            .resolve("odds.html")
                            .readBytes()
                    )
            )

            println(
                "NAR ODDS SNAPSHOT LIVE E2E PASS"
            )

            println(
                "snapshot_status = " +
                    captured.status
            )

            println(
                "snapshot_dir    = " +
                    captured.snapshotDirectory
                        .canonicalPath
            )

            println(
                "raw_sha256      = " +
                    captured.rawResponseSha256
            )

            println(
                "snapshot_sha256 = " +
                    captured.snapshotSha256
            )
        } finally {
            snapshotRoot.deleteRecursively()
        }
    }
}
