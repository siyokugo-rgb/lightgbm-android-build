package com.keiba.ai

import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.net.ssl.HttpsURLConnection

object NarOddsDownloader {

    private const val BASE_URL =
        "https://www.keiba.go.jp/KeibaWeb/TodayRaceInfo/OddsTanFuku"

    private const val ALLOWED_HOST =
        "www.keiba.go.jp"

    private const val EXPECTED_CONTENT_TYPE =
        "text/html"

    private const val ODDS_FLAG =
        "5"

    internal const val MAX_RESPONSE_BYTES =
        2L * 1024L * 1024L

    enum class BetType {
        WIN,
        PLACE
    }

    data class OddsResponse(
        val babaCode: String,
        val raceDate: LocalDate,
        val raceNo: Int,
        val requestUrl: String,
        val coveredBetTypes: Set<BetType>,
        val responseBytes: ByteArray,
        val downloadStartedAtEpochMillis: Long,
        val downloadCompletedAtEpochMillis: Long,
        val serverDateEpochMillis: Long?,
        val observedAtEpochMillis: Long
    )

    fun download(
        babaCode: String,
        raceDate: LocalDate,
        raceNo: Int
    ): OddsResponse {

        val requestUrl =
            buildRequestUrl(
                babaCode = babaCode,
                raceDate = raceDate,
                raceNo = raceNo
            )

        val url =
            URL(requestUrl)

        require(
            url.protocol == "https"
        ) {
            "odds URL must use HTTPS"
        }

        require(
            url.host.equals(
                ALLOWED_HOST,
                ignoreCase = true
            )
        ) {
            "unexpected odds host"
        }

        require(
            url.userInfo == null
        ) {
            "odds URL must not contain user info"
        }

        val connection =
            url.openConnection()
                as? HttpsURLConnection
                ?: error(
                    "odds connection is not HTTPS"
                )

        connection.requestMethod =
            "GET"

        connection.connectTimeout =
            15_000

        connection.readTimeout =
            30_000

        connection.instanceFollowRedirects =
            false

        connection.useCaches =
            false

        connection.setRequestProperty(
            "Accept",
            EXPECTED_CONTENT_TYPE
        )

        connection.setRequestProperty(
            "User-Agent",
            "KeibaAI-Android/0.1"
        )

        try {
            val downloadStartedAt =
                System.currentTimeMillis()

            val responseCode =
                connection.responseCode

            require(
                responseCode ==
                    HttpsURLConnection.HTTP_OK
            ) {
                if (
                    responseCode in 300..399
                ) {
                    "odds redirect rejected: HTTP $responseCode"
                } else {
                    "odds HTTP $responseCode"
                }
            }

            require(
                connection.url.protocol ==
                    "https" &&
                    connection.url.host.equals(
                        ALLOWED_HOST,
                        ignoreCase = true
                    ) &&
                    connection.url.userInfo == null
            ) {
                "odds connection changed origin"
            }

            validateContentType(
                connection.contentType
            )

            val contentLength =
                connection.contentLengthLong

            require(
                contentLength < 0L ||
                    contentLength <=
                        MAX_RESPONSE_BYTES
            ) {
                "odds response too large"
            }

            val serverDate =
                connection.date
                    .takeIf {
                        it > 0L
                    }

            val bytes =
                SizeLimitedInputStream(
                    connection.inputStream,
                    MAX_RESPONSE_BYTES,
                    "odds response"
                )
                    .buffered()
                    .use {
                        it.readBytes()
                    }

            val downloadCompletedAt =
                System.currentTimeMillis()

            validateResponseBytes(
                bytes
            )

            require(
                downloadCompletedAt >=
                    downloadStartedAt
            ) {
                "invalid odds download timestamps"
            }

            val observedAt =
                computeObservedAt(
                    downloadCompletedAtEpochMillis =
                        downloadCompletedAt,
                    serverDateEpochMillis =
                        serverDate
                )

            return OddsResponse(
                babaCode =
                    babaCode,
                raceDate =
                    raceDate,
                raceNo =
                    raceNo,
                requestUrl =
                    requestUrl,
                coveredBetTypes =
                    setOf(
                        BetType.WIN,
                        BetType.PLACE
                    ),
                responseBytes =
                    bytes,
                downloadStartedAtEpochMillis =
                    downloadStartedAt,
                downloadCompletedAtEpochMillis =
                    downloadCompletedAt,
                serverDateEpochMillis =
                    serverDate,
                observedAtEpochMillis =
                    observedAt
            )
        } finally {
            connection.disconnect()
        }
    }

    internal fun buildRequestUrl(
        babaCode: String,
        raceDate: LocalDate,
        raceNo: Int
    ): String {

        require(
            babaCode.matches(
                Regex("""^[0-9]{2}$""")
            )
        ) {
            "invalid NAR baba code"
        }

        require(
            raceNo in 1..99
        ) {
            "invalid NAR race number"
        }

        val dateText =
            raceDate.format(
                DateTimeFormatter
                    .ofPattern(
                        "uuuu/MM/dd"
                    )
            )

        val encodedDate =
            URLEncoder.encode(
                dateText,
                StandardCharsets.UTF_8
                    .name()
            )

        return BASE_URL +
            "?k_babaCode=$babaCode" +
            "&k_raceDate=$encodedDate" +
            "&k_raceNo=$raceNo" +
            "&odds_flg=$ODDS_FLAG"
    }

    internal fun validateContentTypeForTest(
        contentType: String?
    ) {
        validateContentType(
            contentType
        )
    }

    internal fun readLimitedForTest(
        bytes: ByteArray
    ): ByteArray =
        SizeLimitedInputStream(
            ByteArrayInputStream(
                bytes
            ),
            MAX_RESPONSE_BYTES,
            "test odds response"
        ).use {
            it.readBytes()
        }

    internal fun observedAtForTest(
        downloadCompletedAtEpochMillis: Long,
        serverDateEpochMillis: Long?
    ): Long =
        computeObservedAt(
            downloadCompletedAtEpochMillis =
                downloadCompletedAtEpochMillis,
            serverDateEpochMillis =
                serverDateEpochMillis
        )

    private fun validateContentType(
        contentType: String?
    ) {
        val parts =
            contentType
                ?.split(';')
                ?.map {
                    it.trim()
                }
                ?: emptyList()

        val mediaType =
            parts
                .firstOrNull()
                ?.lowercase()
                .orEmpty()

        require(
            mediaType ==
                EXPECTED_CONTENT_TYPE
        ) {
            "unexpected odds Content-Type"
        }

        val charset =
            parts
                .drop(1)
                .mapNotNull {
                    val pieces =
                        it.split(
                            '=',
                            limit = 2
                        )

                    if (
                        pieces.size == 2 &&
                        pieces[0]
                            .trim()
                            .equals(
                                "charset",
                                ignoreCase = true
                            )
                    ) {
                        pieces[1]
                            .trim()
                            .trim('"')
                    } else {
                        null
                    }
                }
                .firstOrNull()

        if (charset != null) {
            require(
                charset.equals(
                    "UTF-8",
                    ignoreCase = true
                )
            ) {
                "unexpected odds charset"
            }
        }
    }

    internal fun validateResponseBytes(
        bytes: ByteArray
    ) {
        require(
            bytes.isNotEmpty()
        ) {
            "empty odds response"
        }

        require(
            bytes.size.toLong() <=
                MAX_RESPONSE_BYTES
        ) {
            "odds response too large"
        }

        val decoder =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(
                    CodingErrorAction.REPORT
                )
                .onUnmappableCharacter(
                    CodingErrorAction.REPORT
                )

        val text =
            try {
                decoder.decode(
                    ByteBuffer.wrap(
                        bytes
                    )
                ).toString()
            } catch (
                e: CharacterCodingException
            ) {
                throw IllegalArgumentException(
                    "odds response is not valid UTF-8",
                    e
                )
            }

        val normalized =
            text
                .removePrefix(
                    "\uFEFF"
                )
                .trim()

        val lower =
            normalized.lowercase()

        require(
            lower.startsWith(
                "<!doctype html"
            ) ||
                lower.startsWith(
                    "<html"
                )
        ) {
            "odds response is not HTML"
        }

        require(
            text.contains(
                "地方競馬情報サイト"
            )
        ) {
            "odds response is not NAR page"
        }

        require(
            text.contains(
                "オッズ"
            )
        ) {
            "odds response is not odds page"
        }

        require(
            text.contains(
                "単勝・複勝"
            )
        ) {
            "odds response is not TanFuku page"
        }

        val forbiddenMarkers =
            listOf(
                "csrf-token",
                "xsrf-token",
                "access_token",
                "refresh_token",
                "session_token",
                "authorization:",
                "bearer ",
                "set-cookie:"
            )

        for (
            marker in forbiddenMarkers
        ) {
            require(
                !lower.contains(
                    marker
                )
            ) {
                "odds response contains possible secret material"
            }
        }
    }

    private fun computeObservedAt(
        downloadCompletedAtEpochMillis: Long,
        serverDateEpochMillis: Long?
    ): Long {

        require(
            downloadCompletedAtEpochMillis >
                0L
        ) {
            "invalid odds download completion time"
        }

        if (
            serverDateEpochMillis != null
        ) {
            require(
                serverDateEpochMillis >
                    0L
            ) {
                "invalid odds server date"
            }
        }

        return maxOf(
            downloadCompletedAtEpochMillis,
            serverDateEpochMillis ?: 0L
        )
    }

    private class SizeLimitedInputStream(
        input: InputStream,
        private val maxBytes: Long,
        private val label: String
    ) : FilterInputStream(
        input
    ) {

        private var count =
            0L

        override fun read(): Int {
            val value =
                super.read()

            if (value >= 0) {
                addBytes(
                    1L
                )
            }

            return value
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int
        ): Int {
            val read =
                super.read(
                    buffer,
                    offset,
                    length
                )

            if (read > 0) {
                addBytes(
                    read.toLong()
                )
            }

            return read
        }

        private fun addBytes(
            amount: Long
        ) {
            count =
                Math.addExact(
                    count,
                    amount
                )

            require(
                count <=
                    maxBytes
            ) {
                "$label exceeds size limit"
            }
        }
    }
}
