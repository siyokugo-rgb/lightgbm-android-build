package com.keiba.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL
import java.time.LocalDate

class NarOddsDownloaderTest {

    @Test
    fun requestIsPinnedToExpectedContract() {
        val request =
            NarOddsDownloader
                .buildRequestUrl(
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
            "https://www.keiba.go.jp/KeibaWeb/TodayRaceInfo/OddsTanFuku" +
                "?k_babaCode=36" +
                "&k_raceDate=2026%2F09%2F08" +
                "&k_raceNo=3" +
                "&odds_flg=5",
            request
        )

        val url =
            URL(request)

        assertEquals(
            "https",
            url.protocol
        )

        assertEquals(
            "www.keiba.go.jp",
            url.host
        )

        assertEquals(
            null,
            url.userInfo
        )
    }

    @Test
    fun invalidRaceIdentityIsRejected() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarOddsDownloader
                .buildRequestUrl(
                    babaCode = "3",
                    raceDate =
                        LocalDate.of(
                            2026,
                            9,
                            8
                        ),
                    raceNo = 3
                )
        }

        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarOddsDownloader
                .buildRequestUrl(
                    babaCode = "AB",
                    raceDate =
                        LocalDate.of(
                            2026,
                            9,
                            8
                        ),
                    raceNo = 3
                )
        }

        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarOddsDownloader
                .buildRequestUrl(
                    babaCode = "36",
                    raceDate =
                        LocalDate.of(
                            2026,
                            9,
                            8
                        ),
                    raceNo = 0
                )
        }

        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarOddsDownloader
                .buildRequestUrl(
                    babaCode = "36",
                    raceDate =
                        LocalDate.of(
                            2026,
                            9,
                            8
                        ),
                    raceNo = 100
                )
        }
    }

    @Test
    fun expectedHtmlContentTypeIsAccepted() {
        NarOddsDownloader
            .validateContentTypeForTest(
                "text/html; charset=UTF-8"
            )

        NarOddsDownloader
            .validateContentTypeForTest(
                "text/html"
            )
    }

    @Test
    fun unexpectedContentTypeOrCharsetIsRejected() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarOddsDownloader
                .validateContentTypeForTest(
                    "application/json"
                )
        }

        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarOddsDownloader
                .validateContentTypeForTest(
                    "text/html; charset=Shift_JIS"
                )
        }
    }

    @Test
    fun expectedNarOddsHtmlIsAccepted() {
        NarOddsDownloader
            .validateResponseBytes(
                sampleHtml()
                    .toByteArray(
                        Charsets.UTF_8
                    )
            )
    }

    @Test
    fun utf8BomNarOddsHtmlIsAccepted() {
        val body =
            "\uFEFF\n" +
                sampleHtml()

        NarOddsDownloader
            .validateResponseBytes(
                body.toByteArray(
                    Charsets.UTF_8
                )
            )
    }

    @Test
    fun genericNarOddsPageWithoutTanFukuIsRejected() {
        val body =
            """
            <!doctype html>
            <html lang="ja">
            <head>
              <meta charset="UTF-8">
              <title>オッズ｜地方競馬情報サイト</title>
            </head>
            <body>
              <h1>オッズ</h1>
              <div>馬連複</div>
            </body>
            </html>
            """.trimIndent()

        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarOddsDownloader
                .validateResponseBytes(
                    body.toByteArray(
                        Charsets.UTF_8
                    )
                )
        }
    }

    @Test
    fun malformedUtf8IsRejected() {
        val malformed =
            byteArrayOf(
                0xC3.toByte(),
                0x28
            )

        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarOddsDownloader
                .validateResponseBytes(
                    malformed
                )
        }
    }

    @Test
    fun nonHtmlBodyIsRejected() {
        val body =
            """
            {
              "title":"地方競馬情報サイト",
              "page":"オッズ"
            }
            """.trimIndent()

        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarOddsDownloader
                .validateResponseBytes(
                    body.toByteArray(
                        Charsets.UTF_8
                    )
                )
        }
    }

    @Test
    fun nonNarOrNonOddsHtmlIsRejected() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarOddsDownloader
                .validateResponseBytes(
                    """
                    <!doctype html>
                    <html>
                    <head>
                    <title>Example</title>
                    </head>
                    <body>
                    オッズ
                    </body>
                    </html>
                    """.trimIndent()
                        .toByteArray(
                            Charsets.UTF_8
                        )
                )
        }

        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarOddsDownloader
                .validateResponseBytes(
                    """
                    <!doctype html>
                    <html>
                    <head>
                    <title>地方競馬情報サイト</title>
                    </head>
                    <body>
                    出馬表
                    </body>
                    </html>
                    """.trimIndent()
                        .toByteArray(
                            Charsets.UTF_8
                        )
                )
        }
    }

    @Test
    fun possibleSecretMaterialIsRejected() {
        val body =
            sampleHtml()
                .replace(
                    "</body>",
                    """
                    <meta name="csrf-token" content="secret">
                    </body>
                    """.trimIndent()
                )

        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarOddsDownloader
                .validateResponseBytes(
                    body.toByteArray(
                        Charsets.UTF_8
                    )
                )
        }
    }

    @Test
    fun observedAtUsesLatestAvailableEvidence() {
        assertEquals(
            2_000L,
            NarOddsDownloader
                .observedAtForTest(
                    downloadCompletedAtEpochMillis =
                        2_000L,
                    serverDateEpochMillis =
                        1_000L
                )
        )

        assertEquals(
            3_000L,
            NarOddsDownloader
                .observedAtForTest(
                    downloadCompletedAtEpochMillis =
                        2_000L,
                    serverDateEpochMillis =
                        3_000L
                )
        )

        assertEquals(
            2_000L,
            NarOddsDownloader
                .observedAtForTest(
                    downloadCompletedAtEpochMillis =
                        2_000L,
                    serverDateEpochMillis =
                        null
                )
        )
    }

    @Test
    fun invalidObservedAtEvidenceIsRejected() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarOddsDownloader
                .observedAtForTest(
                    downloadCompletedAtEpochMillis =
                        0L,
                    serverDateEpochMillis =
                        null
                )
        }

        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarOddsDownloader
                .observedAtForTest(
                    downloadCompletedAtEpochMillis =
                        1_000L,
                    serverDateEpochMillis =
                        0L
                )
        }
    }

    @Test
    fun oversizedResponseIsRejected() {
        val bytes =
            ByteArray(
                (
                    NarOddsDownloader
                        .MAX_RESPONSE_BYTES +
                        1L
                    ).toInt()
            )

        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarOddsDownloader
                .readLimitedForTest(
                    bytes
                )
        }
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
