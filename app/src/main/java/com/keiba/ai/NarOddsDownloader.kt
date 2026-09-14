package com.keiba.ai

import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
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

            validateRaceIdentity(
                html = decodeUtf8Html(
                    bytes
                ),
                babaCode = babaCode,
                raceDate = raceDate,
                raceNo = raceNo
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
            startsWithHtmlDocument(
                normalized
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


    internal fun validateRaceIdentity(
        html: String,
        babaCode: String,
        raceDate: LocalDate,
        raceNo: Int
    ) {
        require(
            html.isNotBlank()
        ) {
            "odds html is blank"
        }

        validateRaceListAnchor(
            html = html,
            babaCode = babaCode,
            raceDate = raceDate,
            raceNo = raceNo
        )

        val activeCourseVenue =
            validateActiveCourse(
                html
            )

        validateActiveRace(
            html = html,
            raceNo = raceNo
        )

        validateRaceHeader(
            html = html,
            raceDate = raceDate,
            raceNo = raceNo,
            activeCourseVenue =
                activeCourseVenue
        )
    }

    private fun decodeUtf8Html(
        bytes: ByteArray
    ): String {
        val decoder =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(
                    CodingErrorAction.REPORT
                )
                .onUnmappableCharacter(
                    CodingErrorAction.REPORT
                )

        return try {
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
    }

    private fun validateRaceListAnchor(
        html: String,
        babaCode: String,
        raceDate: LocalDate,
        raceNo: Int
    ) {
        val anchors =
            ANCHOR_REGEX
                .findAll(
                    html
                )
                .map {
                    it.groupValues[1] to
                        it.groupValues[2]
                }
                .filter {
                    (attrs, _) ->
                    elementId(
                        attrs
                    ) == "RaceList"
                }
                .toList()

        require(
            anchors.size == 1
        ) {
            "RaceList anchor must be unique"
        }

        val attrs =
            anchors.single().first

        val hrefRaw =
            attributeValue(
                attrs,
                "href"
            )

        require(
            !hrefRaw.isNullOrBlank()
        ) {
            "RaceList href is missing"
        }

        val href =
            decodeHtmlEntities(
                hrefRaw
            )

        val params =
            parseQueryParameters(
                href
            )

        val htmlBabaCode =
            params["k_babaCode"]

        val htmlRaceDate =
            params["k_raceDate"]

        val htmlRaceNo =
            params["k_raceNo"]

        require(
            htmlBabaCode != null &&
                htmlRaceDate != null &&
                htmlRaceNo != null
        ) {
            "RaceList query identity is incomplete"
        }

        require(
            htmlBabaCode ==
                babaCode
        ) {
            "RaceList babaCode mismatch"
        }

        val parsedDate =
            try {
                LocalDate.parse(
                    htmlRaceDate,
                    RACE_DATE_QUERY_FORMAT
                )
            } catch (
                e: Exception
            ) {
                throw IllegalArgumentException(
                    "RaceList raceDate is not parseable",
                    e
                )
            }

        require(
            parsedDate ==
                raceDate
        ) {
            "RaceList raceDate mismatch"
        }

        val parsedRaceNo =
            htmlRaceNo.toIntOrNull()
                ?: throw IllegalArgumentException(
                    "RaceList raceNo is not parseable"
                )

        require(
            parsedRaceNo ==
                raceNo
        ) {
            "RaceList raceNo mismatch"
        }
    }

    private fun validateActiveRace(
        html: String,
        raceNo: Int
    ) {
        val activeRaces =
            ANCHOR_REGEX
                .findAll(
                    html
                )
                .map {
                    it.groupValues[1] to
                        it.groupValues[2]
                }
                .filter {
                    (attrs, _) ->
                    val tokens =
                        classTokens(
                            attrs
                        )

                    "raceNum" in tokens &&
                        "active" in tokens
                }
                .toList()

        require(
            activeRaces.size == 1
        ) {
            "active race navigation must be unique"
        }

        val label =
            stripHtmlTags(
                activeRaces.single().second
            ).trim()

        val match =
            ACTIVE_RACE_LABEL_REGEX
                .matchEntire(
                    label
                )
                ?: throw IllegalArgumentException(
                    "active race label is not parseable"
                )

        val parsedRaceNo =
            match.groupValues[1]
                .toIntOrNull()
                ?: throw IllegalArgumentException(
                    "active race number is not parseable"
                )

        require(
            parsedRaceNo ==
                raceNo
        ) {
            "active race number mismatch"
        }
    }

    private fun validateActiveCourse(
        html: String
    ): String {
        val activeCourses =
            ANCHOR_REGEX
                .findAll(
                    html
                )
                .map {
                    it.groupValues[1] to
                        it.groupValues[2]
                }
                .filter {
                    (attrs, _) ->
                    val tokens =
                        classTokens(
                            attrs
                        )

                    "courseBtn" in tokens &&
                        "active" in tokens
                }
                .toList()

        require(
            activeCourses.size == 1
        ) {
            "active course navigation must be unique"
        }

        val venue =
            stripHtmlTags(
                activeCourses.single().second
            ).trim()

        require(
            venue.isNotEmpty()
        ) {
            "active course venue is blank"
        }

        return venue
    }

    private fun validateRaceHeader(
        html: String,
        raceDate: LocalDate,
        raceNo: Int,
        activeCourseVenue: String
    ) {
        val matches =
            RACE_HEADER_REGEX
                .findAll(
                    html
                )
                .toList()

        require(
            matches.size == 1
        ) {
            "race header must be unique"
        }

        val match =
            matches.single()

        val year =
            match.groupValues[1]
                .toInt()

        val month =
            match.groupValues[2]
                .toInt()

        val day =
            match.groupValues[3]
                .toInt()

        val venue =
            match.groupValues[4]
                .trim()

        val headerRaceNo =
            match.groupValues[5]
                .toIntOrNull()
                ?: throw IllegalArgumentException(
                    "race header raceNo is not parseable"
                )

        val startTimeText =
            match.groupValues[6]

        require(
            startTimeText.isNotBlank()
        ) {
            "race header start time is blank"
        }

        try {
            LocalTime.parse(
                startTimeText,
                RACE_START_TIME_FORMAT
            )
        } catch (
            e: Exception
        ) {
            throw IllegalArgumentException(
                "race header start time is not parseable",
                e
            )
        }

        val headerDate =
            try {
                LocalDate.of(
                    year,
                    month,
                    day
                )
            } catch (
                e: Exception
            ) {
                throw IllegalArgumentException(
                    "race header date is not parseable",
                    e
                )
            }

        require(
            headerDate ==
                raceDate
        ) {
            "race header date mismatch"
        }

        require(
            headerRaceNo ==
                raceNo
        ) {
            "race header raceNo mismatch"
        }

        require(
            stripAllUnicodeWhitespace(
                venue
            ) ==
                stripAllUnicodeWhitespace(
                    activeCourseVenue
                )
        ) {
            "race header venue mismatch"
        }
    }

    private fun parseQueryParameters(
        rawHref: String
    ): Map<String, String> {
        val queryIndex =
            rawHref.indexOf(
                '?'
            )

        require(
            queryIndex >= 0 &&
                queryIndex <
                    rawHref.length - 1
        ) {
            "RaceList href query is missing"
        }

        val query =
            rawHref.substring(
                queryIndex + 1
            )

        val params =
            linkedMapOf<String, String>()

        for (
            part in query.split(
                '&'
            )
        ) {
            if (part.isEmpty()) {
                continue
            }

            val eq =
                part.indexOf(
                    '='
                )

            require(
                eq > 0
            ) {
                "RaceList query parameter is malformed"
            }

            val key =
                URLDecoder.decode(
                    part.substring(
                        0,
                        eq
                    ),
                    StandardCharsets.UTF_8
                        .name()
                )

            val value =
                URLDecoder.decode(
                    part.substring(
                        eq + 1
                    ),
                    StandardCharsets.UTF_8
                        .name()
                )

            val previous =
                params.put(
                    key,
                    value
                )

            require(
                previous == null ||
                    previous ==
                        value
            ) {
                "RaceList query parameter is duplicated with conflict"
            }
        }

        return params
    }

    private fun attributeValue(
        attrs: String,
        name: String
    ): String? {
        val regex =
            Regex(
                """(?i)(?:^|\s+)${Regex.escape(name)}\s*=\s*(?:["']([^"']*)["']|([^\s>]+))"""
            )

        val matches =
            regex.findAll(
                attrs
            ).toList()

        require(
            matches.size <= 1
        ) {
            "duplicate HTML attribute: $name"
        }

        if (
            matches.isEmpty()
        ) {
            return null
        }

        val match =
            matches.single()

        return match.groupValues[1]
            .ifEmpty {
                match.groupValues[2]
            }
    }

    private fun elementId(
        attrs: String
    ): String? =
        attributeValue(
            attrs,
            "id"
        )

    private fun classTokens(
        attrs: String
    ): Set<String> {
        val raw =
            attributeValue(
                attrs,
                "class"
            )
                ?: return emptySet()

        return raw
            .split(
                Regex(
                    """\s+"""
                )
            )
            .filter {
                it.isNotEmpty()
            }
            .toSet()
    }

    private fun decodeHtmlEntities(
        input: String
    ): String {
        val output =
            StringBuilder(
                input.length
            )

        var index =
            0

        while (
            index < input.length
        ) {
            val ch =
                input[index]

            if (ch != '&') {
                output.append(
                    ch
                )
                index++
                continue
            }

            val semi =
                input.indexOf(
                    ';',
                    startIndex = index + 1
                )

            if (
                semi < 0 ||
                semi >
                    index + 10
            ) {
                output.append(
                    '&'
                )
                index++
                continue
            }

            val entity =
                input.substring(
                    index + 1,
                    semi
                )

            val decoded: String? =
                when {
                    entity == "amp" ->
                        "&"

                    entity == "lt" ->
                        "<"

                    entity == "gt" ->
                        ">"

                    entity == "quot" ->
                        "\""

                    entity == "apos" ->
                        "'"

                    entity.startsWith(
                        "#x"
                    ) ||
                        entity.startsWith(
                            "#X"
                        ) ->
                        entity
                            .substring(
                                2
                            )
                            .toIntOrNull(
                                16
                            )
                            ?.toChar()
                            ?.toString()

                    entity.startsWith(
                        "#"
                    ) ->
                        entity
                            .substring(
                                1
                            )
                            .toIntOrNull()
                            ?.toChar()
                            ?.toString()

                    else ->
                        null
                }

            if (
                decoded != null
            ) {
                output.append(
                    decoded
                )
                index =
                    semi + 1
            } else {
                output.append(
                    '&'
                )
                index++
            }
        }

        return output.toString()
    }

    private fun stripHtmlTags(
        input: String
    ): String =
        input.replace(
            Regex(
                """(?is)<[^>]*>"""
            ),
            ""
        )

    private fun stripAllUnicodeWhitespace(
        input: String
    ): String =
        buildString(
            input.length
        ) {
            for (
                ch in input
            ) {
                if (
                    !Character.isWhitespace(
                        ch
                    ) &&
                    !Character.isSpaceChar(
                        ch
                    ) &&
                    ch !=
                        '\uFEFF'
                ) {
                    append(
                        ch
                    )
                }
            }
        }

    private val ANCHOR_REGEX =
        Regex(
            """(?is)<a\b([^>]*)>(.*?)</a>"""
        )

    private val ACTIVE_RACE_LABEL_REGEX =
        Regex(
            """^\s*(\d+)\s*R\s*$"""
        )

    private val RACE_HEADER_REGEX =
        Regex(
            """(\d{4})年(\d{1,2})月(\d{1,2})日(?:（[^）]*）)?[\s\u00A0\u3000\u202F]*(.*?)[\s\u00A0\u3000\u202F]*第(\d+)競走[\s\u00A0\u3000\u202F]*(\d{1,2}:\d{2})発走"""
        )

    private val RACE_DATE_QUERY_FORMAT =
        DateTimeFormatter.ofPattern(
            "uuuu/MM/dd"
        )

    private val RACE_START_TIME_FORMAT =
        DateTimeFormatter.ofPattern(
            "H:mm"
        ).withResolverStyle(
            ResolverStyle.STRICT
        )


    private fun startsWithHtmlDocument(
        text: String
    ): Boolean {
        var index =
            0

        while (true) {
            while (
                index < text.length &&
                text[index].isWhitespace()
            ) {
                index++
            }

            if (
                text.startsWith(
                    "<!--",
                    startIndex = index
                )
            ) {
                val commentEnd =
                    text.indexOf(
                        "-->",
                        startIndex = index + 4
                    )

                if (commentEnd < 0) {
                    return false
                }

                index =
                    commentEnd + 3

                continue
            }

            return text.regionMatches(
                thisOffset = index,
                other = "<!doctype html",
                otherOffset = 0,
                length = "<!doctype html".length,
                ignoreCase = true
            ) ||
                text.regionMatches(
                    thisOffset = index,
                    other = "<html",
                    otherOffset = 0,
                    length = "<html".length,
                    ignoreCase = true
                )
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
