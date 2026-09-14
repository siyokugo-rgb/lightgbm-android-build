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
    fun leadingHtmlCommentBeforeDoctypeIsAccepted() {
        val body =
            """
            <!-- resources/views/layouts/app.blade.php -->
            <!DOCTYPE html>
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

        NarOddsDownloader
            .validateResponseBytes(
                body.toByteArray(
                    Charsets.UTF_8
                )
            )
    }

    @Test
    fun arbitraryPreambleBeforeDoctypeIsRejected() {
        val body =
            "unexpected preamble\n" +
                sampleHtml()

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
    fun unterminatedLeadingHtmlCommentIsRejected() {
        val body =
            "<!-- unterminated comment\n" +
                sampleHtml()

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



    @Test
    fun raceIdentityAcceptsCapturedHistoricalHtmlShape() {
        // Shape captured from keiba.go.jp OddsTanFuku HTML on 2026-09-08 門別3R.
        val html =
            identityHtml(
                raceListHref =
                    "../TodayRaceInfo/RaceList?k_babaCode=36&amp;k_raceDate=2026%2F09%2F08&amp;k_raceNo=3&amp;odds_flg=5",
                raceClass =
                    "cNaviBtn raceNum active",
                courseClass =
                    "cNaviBtn courseBtn active",
                headerDateText =
                    "2026年9月8日（火）",
                headerVenue =
                    "門　別",
                headerRaceNo =
                    3,
                courseVenue =
                    "門別"
            )

        NarOddsDownloader
            .validateRaceIdentity(
                html = html,
                babaCode = "36",
                raceDate =
                    LocalDate.of(
                        2026,
                        9,
                        8
                    ),
                raceNo = 3
            )
    }


    @Test
    fun raceIdentityAcceptsSyntheticFixtureHtml() {
        val html =
            javaClass
                .getResourceAsStream(
                    "/odds/race-identity-synthetic.html"
                )!!
                .bufferedReader(
                    Charsets.UTF_8
                )
                .use {
                    it.readText()
                }

        NarOddsDownloader
            .validateRaceIdentity(
                html = html,
                babaCode = "36",
                raceDate =
                    LocalDate.of(
                        2026,
                        9,
                        8
                    ),
                raceNo = 3
            )
    }

    @Test
    fun raceIdentityMatchingHtmlIsAccepted() {
        NarOddsDownloader
            .validateRaceIdentity(
                html = identityHtml(),
                babaCode = "36",
                raceDate =
                    LocalDate.of(
                        2026,
                        9,
                        8
                    ),
                raceNo = 3
            )
    }

    @Test
    fun raceIdentityAcceptsReorderedQueryAndClassTokens() {
        val html =
            identityHtml(
                raceListHref =
                    "../TodayRaceInfo/RaceList?odds_flg=5&amp;k_raceNo=3&amp;k_babaCode=36&amp;k_raceDate=2026%2F09%2F08",
                raceClass =
                    "active cNaviBtn raceNum",
                courseClass =
                    "active courseBtn cNaviBtn"
            )

        NarOddsDownloader
            .validateRaceIdentity(
                html = html,
                babaCode = "36",
                raceDate =
                    LocalDate.of(
                        2026,
                        9,
                        8
                    ),
                raceNo = 3
            )
    }

    @Test
    fun raceIdentityAcceptsHtmlEntitiesInHref() {
        val html =
            identityHtml(
                raceListHref =
                    "../TodayRaceInfo/RaceList?k_babaCode=36&amp;k_raceDate=2026%2F09%2F08&amp;k_raceNo=3"
            )

        NarOddsDownloader
            .validateRaceIdentity(
                html = html,
                babaCode = "36",
                raceDate =
                    LocalDate.of(
                        2026,
                        9,
                        8
                    ),
                raceNo = 3
            )
    }

    @Test
    fun raceIdentityAcceptsUnicodeWhitespaceInVenue() {
        val html =
            identityHtml(
                headerVenue =
                    "門\u3000別",
                courseVenue =
                    "門別"
            )

        NarOddsDownloader
            .validateRaceIdentity(
                html = html,
                babaCode = "36",
                raceDate =
                    LocalDate.of(
                        2026,
                        9,
                        8
                    ),
                raceNo = 3
            )
    }

    @Test
    fun raceListBabaCodeMismatchIsRejected() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarOddsDownloader
                .validateRaceIdentity(
                    html =
                        identityHtml(
                            raceListHref =
                                "../TodayRaceInfo/RaceList?k_babaCode=03&amp;k_raceDate=2026%2F09%2F08&amp;k_raceNo=3"
                        ),
                    babaCode = "36",
                    raceDate =
                        LocalDate.of(
                            2026,
                            9,
                            8
                        ),
                    raceNo = 3
                )
        }
    }

    @Test
    fun raceListRaceDateMismatchIsRejected() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarOddsDownloader
                .validateRaceIdentity(
                    html =
                        identityHtml(
                            raceListHref =
                                "../TodayRaceInfo/RaceList?k_babaCode=36&amp;k_raceDate=2026%2F09%2F09&amp;k_raceNo=3"
                        ),
                    babaCode = "36",
                    raceDate =
                        LocalDate.of(
                            2026,
                            9,
                            8
                        ),
                    raceNo = 3
                )
        }
    }

    @Test
    fun raceListRaceNoMismatchIsRejected() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarOddsDownloader
                .validateRaceIdentity(
                    html =
                        identityHtml(
                            raceListHref =
                                "../TodayRaceInfo/RaceList?k_babaCode=36&amp;k_raceDate=2026%2F09%2F08&amp;k_raceNo=4"
                        ),
                    babaCode = "36",
                    raceDate =
                        LocalDate.of(
                            2026,
                            9,
                            8
                        ),
                    raceNo = 3
                )
        }
    }

    @Test
    fun missingActiveRaceIsRejected() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarOddsDownloader
                .validateRaceIdentity(
                    html =
                        identityHtml(
                            includeActiveRace =
                                false
                        ),
                    babaCode = "36",
                    raceDate =
                        LocalDate.of(
                            2026,
                            9,
                            8
                        ),
                    raceNo = 3
                )
        }
    }

    @Test
    fun duplicateActiveRaceIsRejected() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarOddsDownloader
                .validateRaceIdentity(
                    html =
                        identityHtml(
                            duplicateActiveRace =
                                true
                        ),
                    babaCode = "36",
                    raceDate =
                        LocalDate.of(
                            2026,
                            9,
                            8
                        ),
                    raceNo = 3
                )
        }
    }

    @Test
    fun missingActiveCourseIsRejected() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarOddsDownloader
                .validateRaceIdentity(
                    html =
                        identityHtml(
                            includeActiveCourse =
                                false
                        ),
                    babaCode = "36",
                    raceDate =
                        LocalDate.of(
                            2026,
                            9,
                            8
                        ),
                    raceNo = 3
                )
        }
    }

    @Test
    fun duplicateActiveCourseIsRejected() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarOddsDownloader
                .validateRaceIdentity(
                    html =
                        identityHtml(
                            duplicateActiveCourse =
                                true
                        ),
                    babaCode = "36",
                    raceDate =
                        LocalDate.of(
                            2026,
                            9,
                            8
                        ),
                    raceNo = 3
                )
        }
    }

    @Test
    fun headerDateMismatchIsRejected() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarOddsDownloader
                .validateRaceIdentity(
                    html =
                        identityHtml(
                            headerDateText =
                                "2026年9月9日"
                        ),
                    babaCode = "36",
                    raceDate =
                        LocalDate.of(
                            2026,
                            9,
                            8
                        ),
                    raceNo = 3
                )
        }
    }

    @Test
    fun headerRaceNoMismatchIsRejected() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarOddsDownloader
                .validateRaceIdentity(
                    html =
                        identityHtml(
                            headerRaceNo =
                                4
                        ),
                    babaCode = "36",
                    raceDate =
                        LocalDate.of(
                            2026,
                            9,
                            8
                        ),
                    raceNo = 3
                )
        }
    }

    @Test
    fun headerVenueMismatchIsRejected() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarOddsDownloader
                .validateRaceIdentity(
                    html =
                        identityHtml(
                            headerVenue =
                                "大井",
                            courseVenue =
                                "門別"
                        ),
                    babaCode = "36",
                    raceDate =
                        LocalDate.of(
                            2026,
                            9,
                            8
                        ),
                    raceNo = 3
                )
        }
    }



    @Test
    fun dataIdSpoofIsRejected() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarOddsDownloader
                .validateRaceIdentity(
                    html =
                        identityHtml(
                            raceListExtraAttrs =
                                """data-id="RaceList"""",
                            omitRaceListId =
                                true
                        ),
                    babaCode = "36",
                    raceDate =
                        LocalDate.of(
                            2026,
                            9,
                            8
                        ),
                    raceNo = 3
                )
        }
    }

    @Test
    fun dataClassSpoofIsRejected() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarOddsDownloader
                .validateRaceIdentity(
                    html =
                        identityHtml(
                            activeRaceExtraAttrs =
                                """data-class="raceNum active"""",
                            omitActiveRaceClass =
                                true
                        ),
                    babaCode = "36",
                    raceDate =
                        LocalDate.of(
                            2026,
                            9,
                            8
                        ),
                    raceNo = 3
                )
        }
    }

    @Test
    fun dataHrefSpoofIsRejected() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarOddsDownloader
                .validateRaceIdentity(
                    html =
                        identityHtml(
                            raceListExtraAttrs =
                                """data-href="../TodayRaceInfo/RaceList?k_babaCode=36&amp;k_raceDate=2026%2F09%2F08&amp;k_raceNo=3"""",
                            omitRaceListHref =
                                true
                        ),
                    babaCode = "36",
                    raceDate =
                        LocalDate.of(
                            2026,
                            9,
                            8
                        ),
                    raceNo = 3
                )
        }
    }

    @Test
    fun venueWithIdeographicSpaceIsAccepted() {
        NarOddsDownloader
            .validateRaceIdentity(
                html =
                    identityHtml(
                        headerVenue =
                            "門　別",
                        courseVenue =
                            "門別"
                    ),
                babaCode = "36",
                raceDate =
                    LocalDate.of(
                        2026,
                        9,
                        8
                    ),
                raceNo = 3
            )
    }

    @Test
    fun venueWithNbspIsAccepted() {
        NarOddsDownloader
            .validateRaceIdentity(
                html =
                    identityHtml(
                        headerVenue =
                            "門 別",
                        courseVenue =
                            "門別"
                    ),
                babaCode = "36",
                raceDate =
                    LocalDate.of(
                        2026,
                        9,
                        8
                    ),
                raceNo = 3
            )
    }

    @Test
    fun venueWithNarrowNbspIsAccepted() {
        NarOddsDownloader
            .validateRaceIdentity(
                html =
                    identityHtml(
                        headerVenue =
                            "門 別",
                        courseVenue =
                            "門別"
                    ),
                babaCode = "36",
                raceDate =
                    LocalDate.of(
                        2026,
                        9,
                        8
                    ),
                raceNo = 3
            )
    }

    @Test
    fun headerStartTime2500IsRejected() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarOddsDownloader
                .validateRaceIdentity(
                    html =
                        identityHtml(
                            headerStartTime =
                                "25:00"
                        ),
                    babaCode = "36",
                    raceDate =
                        LocalDate.of(
                            2026,
                            9,
                            8
                        ),
                    raceNo = 3
                )
        }
    }

    @Test
    fun headerStartTime1299IsRejected() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarOddsDownloader
                .validateRaceIdentity(
                    html =
                        identityHtml(
                            headerStartTime =
                                "12:99"
                        ),
                    babaCode = "36",
                    raceDate =
                        LocalDate.of(
                            2026,
                            9,
                            8
                        ),
                    raceNo = 3
                )
        }
    }


    @Test
    fun colonPrefixedIdSpoofIsRejected() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarOddsDownloader
                .validateRaceIdentity(
                    html =
                        identityHtml(
                            raceListExtraAttrs =
                                """x:id="RaceList"""",
                            omitRaceListId =
                                true
                        ),
                    babaCode = "36",
                    raceDate =
                        LocalDate.of(
                            2026,
                            9,
                            8
                        ),
                    raceNo = 3
                )
        }
    }

    @Test
    fun dottedIdSpoofIsRejected() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarOddsDownloader
                .validateRaceIdentity(
                    html =
                        identityHtml(
                            raceListExtraAttrs =
                                """.id="RaceList"""",
                            omitRaceListId =
                                true
                        ),
                    babaCode = "36",
                    raceDate =
                        LocalDate.of(
                            2026,
                            9,
                            8
                        ),
                    raceNo = 3
                )
        }
    }

    @Test
    fun colonPrefixedClassSpoofIsRejected() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarOddsDownloader
                .validateRaceIdentity(
                    html =
                        identityHtml(
                            activeRaceExtraAttrs =
                                """x:class="raceNum active"""",
                            omitActiveRaceClass =
                                true
                        ),
                    babaCode = "36",
                    raceDate =
                        LocalDate.of(
                            2026,
                            9,
                            8
                        ),
                    raceNo = 3
                )
        }
    }

    @Test
    fun duplicateRaceListIdIsRejected() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarOddsDownloader
                .validateRaceIdentity(
                    html =
                        identityHtml(
                            raceListExtraAttrs =
                                """id="RaceList""""
                        ),
                    babaCode = "36",
                    raceDate =
                        LocalDate.of(
                            2026,
                            9,
                            8
                        ),
                    raceNo = 3
                )
        }
    }

    @Test
    fun duplicateRaceListHrefIsRejected() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarOddsDownloader
                .validateRaceIdentity(
                    html =
                        identityHtml(
                            raceListExtraAttrs =
                                """href="../TodayRaceInfo/RaceList?k_babaCode=36&amp;k_raceDate=2026%2F09%2F08&amp;k_raceNo=3""""
                        ),
                    babaCode = "36",
                    raceDate =
                        LocalDate.of(
                            2026,
                            9,
                            8
                        ),
                    raceNo = 3
                )
        }
    }

    @Test
    fun duplicateActiveRaceClassIsRejected() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarOddsDownloader
                .validateRaceIdentity(
                    html =
                        identityHtml(
                            activeRaceExtraAttrs =
                                """class="raceNum active""""
                        ),
                    babaCode = "36",
                    raceDate =
                        LocalDate.of(
                            2026,
                            9,
                            8
                        ),
                    raceNo = 3
                )
        }
    }

    @Test
    fun attributeOrderNewlineAndExtraSpacesAreAccepted() {
        val html =
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
              <a
                  href="../TodayRaceInfo/RaceList?k_raceNo=3&amp;k_babaCode=36&amp;k_raceDate=2026%2F09%2F08"
                  id="RaceList"
              >当日メニュー</a>
              <a   class="active   courseBtn   cNaviBtn">門別</a>
              <a
                class="raceNum
                active"
              >3R</a>
              <h4>2026年9月8日（火）　門別　第3競走　15:30発走</h4>
            </body>
            </html>
            """.trimIndent()

        NarOddsDownloader
            .validateRaceIdentity(
                html = html,
                babaCode = "36",
                raceDate =
                    LocalDate.of(
                        2026,
                        9,
                        8
                    ),
                raceNo = 3
            )
    }

    @Test
    fun headerStartTime0000IsAccepted() {
        NarOddsDownloader
            .validateRaceIdentity(
                html =
                    identityHtml(
                        headerStartTime =
                            "00:00"
                    ),
                babaCode = "36",
                raceDate =
                    LocalDate.of(
                        2026,
                        9,
                        8
                    ),
                raceNo = 3
            )
    }

    @Test
    fun headerStartTime905IsAccepted() {
        NarOddsDownloader
            .validateRaceIdentity(
                html =
                    identityHtml(
                        headerStartTime =
                            "9:05"
                    ),
                babaCode = "36",
                raceDate =
                    LocalDate.of(
                        2026,
                        9,
                        8
                    ),
                raceNo = 3
            )
    }

    @Test
    fun headerStartTime2359IsAccepted() {
        NarOddsDownloader
            .validateRaceIdentity(
                html =
                    identityHtml(
                        headerStartTime =
                            "23:59"
                    ),
                babaCode = "36",
                raceDate =
                    LocalDate.of(
                        2026,
                        9,
                        8
                    ),
                raceNo = 3
            )
    }

    @Test
    fun headerStartTime2400IsRejected() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarOddsDownloader
                .validateRaceIdentity(
                    html =
                        identityHtml(
                            headerStartTime =
                                "24:00"
                        ),
                    babaCode = "36",
                    raceDate =
                        LocalDate.of(
                            2026,
                            9,
                            8
                        ),
                    raceNo = 3
                )
        }
    }

    private fun identityHtml(
        raceListHref: String =
            "../TodayRaceInfo/RaceList?k_babaCode=36&amp;k_raceDate=2026%2F09%2F08&amp;k_raceNo=3&amp;odds_flg=5",
        raceClass: String =
            "cNaviBtn raceNum active",
        courseClass: String =
            "cNaviBtn courseBtn active",
        headerDateText: String =
            "2026年9月8日（火）",
        headerVenue: String =
            "門\u3000別",
        headerRaceNo: Int =
            3,
        headerStartTime: String =
            "15:30",
        courseVenue: String =
            "門別",
        raceListExtraAttrs: String =
            "",
        activeRaceExtraAttrs: String =
            "",
        includeActiveRace: Boolean =
            true,
        includeActiveCourse: Boolean =
            true,
        duplicateActiveRace: Boolean =
            false,
        duplicateActiveCourse: Boolean =
            false,
        omitRaceListId: Boolean =
            false,
        omitActiveRaceClass: Boolean =
            false,
        omitRaceListHref: Boolean =
            false
    ): String {
        val raceListIdAttr =
            if (
                omitRaceListId
            ) {
                ""
            } else {
                """id="RaceList""""
            }

        val raceListHrefAttr =
            if (
                omitRaceListHref
            ) {
                ""
            } else {
                """href="$raceListHref""""
            }

        val activeRaceClassAttr =
            if (
                omitActiveRaceClass
            ) {
                ""
            } else {
                """class="$raceClass""""
            }

        val activeRace =
            if (
                includeActiveRace
            ) {
                """
                <a $activeRaceClassAttr $activeRaceExtraAttrs>3R</a>
                """ +
                    if (
                        duplicateActiveRace
                    ) {
                        """
                        <a class="raceNum active">3R</a>
                        """
                    } else {
                        ""
                    }
            } else {
                """
                <a class="cNaviBtn raceNum">1R</a>
                """
            }

        val activeCourse =
            if (
                includeActiveCourse
            ) {
                """
                <a class="$courseClass">$courseVenue</a>
                """ +
                    if (
                        duplicateActiveCourse
                    ) {
                        """
                        <a class="courseBtn active">$courseVenue</a>
                        """
                    } else {
                        ""
                    }
            } else {
                """
                <a class="cNaviBtn courseBtn">大井</a>
                """
            }

        return """
        <!doctype html>
        <html lang="ja">
        <head>
          <meta charset="UTF-8">
          <title>オッズ｜地方競馬情報サイト</title>
        </head>
        <body>
          <h1>オッズ</h1>
          <div>単勝・複勝</div>
          <a $raceListIdAttr $raceListHrefAttr $raceListExtraAttrs>当日メニュー</a>
          $activeCourse
          $activeRace
          <h4>${headerDateText}　${headerVenue}　第${headerRaceNo}競走　${headerStartTime}発走</h4>
        </body>
        </html>
        """.trimIndent()
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
