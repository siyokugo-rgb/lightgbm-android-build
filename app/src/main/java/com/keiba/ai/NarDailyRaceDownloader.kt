package com.keiba.ai

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.net.URL
import java.util.zip.ZipInputStream
import javax.net.ssl.HttpsURLConnection

object NarDailyRaceDownloader {

    private const val DAILY_URL =
        "https://www.keiba.go.jp/KeibaWeb/DataDownload/" +
            "RaceDataDownload?type=daily"

    private const val ALLOWED_HOST = "www.keiba.go.jp"
    private const val EXPECTED_CONTENT_TYPE = "application/zip"

    internal const val MAX_RESPONSE_BYTES = 16L * 1024L * 1024L
    internal const val MAX_ZIP_ENTRIES = 8
    internal const val MAX_ENTRY_BYTES = 8L * 1024L * 1024L
    internal const val MAX_TOTAL_UNCOMPRESSED_BYTES = 16L * 1024L * 1024L

    private val expectedEntryRegex =
        Regex("""^(\d{8})_(racelist|horselist|payback)\.csv$""")

    data class DailyRaceData(
        val date: String,
        val racelistCsv: String,
        val horselistCsv: String,
        val paybackCsv: String?
    )

    fun download(): DailyRaceData {
        val url = URL(DAILY_URL)

        require(url.protocol == "https") {
            "NAR daily URL must use HTTPS"
        }

        require(url.host.equals(ALLOWED_HOST, ignoreCase = true)) {
            "unexpected NAR host"
        }

        val connection =
            url.openConnection() as? HttpsURLConnection
                ?: error("NAR daily connection is not HTTPS")

        connection.requestMethod = "GET"
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = false
        connection.useCaches = false

        connection.setRequestProperty(
            "Accept",
            EXPECTED_CONTENT_TYPE
        )

        connection.setRequestProperty(
            "User-Agent",
            "KeibaAI-Android/0.1"
        )

        try {
            val responseCode = connection.responseCode

            require(responseCode == HttpsURLConnection.HTTP_OK) {
                if (responseCode in 300..399) {
                    "NAR daily redirect rejected: HTTP $responseCode"
                } else {
                    "NAR daily HTTP $responseCode"
                }
            }

            require(
                connection.url.protocol == "https" &&
                    connection.url.host.equals(
                        ALLOWED_HOST,
                        ignoreCase = true
                    )
            ) {
                "NAR daily connection changed origin"
            }

            val mediaType =
                connection.contentType
                    ?.substringBefore(';')
                    ?.trim()
                    ?.lowercase()
                    .orEmpty()

            require(mediaType == EXPECTED_CONTENT_TYPE) {
                "unexpected Content-Type"
            }

            val contentLength = connection.contentLengthLong

            require(
                contentLength < 0L ||
                    contentLength <= MAX_RESPONSE_BYTES
            ) {
                "NAR daily response too large"
            }

            val limitedInput =
                SizeLimitedInputStream(
                    connection.inputStream,
                    MAX_RESPONSE_BYTES,
                    "NAR daily compressed response"
                )

            return limitedInput
                .buffered()
                .use(::parseZip)

        } finally {
            connection.disconnect()
        }
    }

    internal fun parseZipForTest(
        zipBytes: ByteArray
    ): DailyRaceData {
        val limitedInput =
            SizeLimitedInputStream(
                ByteArrayInputStream(zipBytes),
                MAX_RESPONSE_BYTES,
                "test compressed response"
            )

        return limitedInput
            .buffered()
            .use(::parseZip)
    }

    private fun parseZip(
        input: InputStream
    ): DailyRaceData {
        var racelist: Pair<String, String>? = null
        var horselist: Pair<String, String>? = null
        var payback: Pair<String, String>? = null

        var entryCount = 0
        var totalUncompressedBytes = 0L

        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++

                require(entryCount <= MAX_ZIP_ENTRIES) {
                    "too many ZIP entries"
                }

                require(!entry.isDirectory) {
                    "directory ZIP entry rejected"
                }

                val rawName = entry.name

                require(
                    rawName.isNotEmpty() &&
                        '/' !in rawName &&
                        '\\' !in rawName
                ) {
                    "unsafe ZIP entry name"
                }

                val match = expectedEntryRegex.matchEntire(rawName)

                require(match != null) {
                    "unexpected ZIP entry: ${safeForMessage(rawName)}"
                }

                if (entry.size >= 0L) {
                    require(entry.size <= MAX_ENTRY_BYTES) {
                        "ZIP entry too large"
                    }
                }

                val date = match.groupValues[1]
                val kind = match.groupValues[2]

                val result =
                    readCurrentEntry(
                        zip = zip,
                        totalUncompressedBefore = totalUncompressedBytes
                    )

                totalUncompressedBytes += result.byteCount

                val text =
                    decodeUtf8Strict(result.bytes)
                        .removePrefix("\uFEFF")

                when (kind) {
                    "racelist" -> {
                        require(racelist == null) {
                            "duplicate racelist"
                        }
                        racelist = date to text
                    }

                    "horselist" -> {
                        require(horselist == null) {
                            "duplicate horselist"
                        }
                        horselist = date to text
                    }

                    "payback" -> {
                        require(payback == null) {
                            "duplicate payback"
                        }
                        payback = date to text
                    }
                }

                zip.closeEntry()
            }
        }

        val race =
            racelist
                ?: error("racelist not found")

        val horse =
            horselist
                ?: error("horselist not found")

        require(race.first == horse.first) {
            "racelist/horselist date mismatch"
        }

        if (payback != null) {
            require(payback!!.first == race.first) {
                "payback date mismatch"
            }
        }

        return DailyRaceData(
            date = race.first,
            racelistCsv = race.second,
            horselistCsv = horse.second,
            paybackCsv = payback?.second
        )
    }

    private data class EntryBytes(
        val bytes: ByteArray,
        val byteCount: Long
    )

    private fun readCurrentEntry(
        zip: ZipInputStream,
        totalUncompressedBefore: Long
    ): EntryBytes {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)

        var entryBytes = 0L

        while (true) {
            val count = zip.read(buffer)

            if (count < 0) {
                break
            }

            entryBytes += count.toLong()

            require(entryBytes <= MAX_ENTRY_BYTES) {
                "ZIP entry exceeded size limit"
            }

            require(
                totalUncompressedBefore + entryBytes <=
                    MAX_TOTAL_UNCOMPRESSED_BYTES
            ) {
                "ZIP exceeded total uncompressed size limit"
            }

            output.write(
                buffer,
                0,
                count
            )
        }

        return EntryBytes(
            bytes = output.toByteArray(),
            byteCount = entryBytes
        )
    }

    private fun decodeUtf8Strict(
        bytes: ByteArray
    ): String {
        val decoder =
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(
                    CodingErrorAction.REPORT
                )
                .onUnmappableCharacter(
                    CodingErrorAction.REPORT
                )

        return decoder
            .decode(
                ByteBuffer.wrap(bytes)
            )
            .toString()
    }

    private fun safeForMessage(
        value: String
    ): String =
        buildString {
            for (c in value.take(120)) {
                append(
                    if (c.isISOControl()) {
                        '?'
                    } else {
                        c
                    }
                )
            }
        }

    private class SizeLimitedInputStream(
        input: InputStream,
        private val maxBytes: Long,
        private val label: String
    ) : FilterInputStream(input) {

        private var bytesRead = 0L

        override fun read(): Int {
            val value = super.read()

            if (value >= 0) {
                account(1L)
            }

            return value
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int
        ): Int {
            val count =
                super.read(
                    buffer,
                    offset,
                    length
                )

            if (count > 0) {
                account(count.toLong())
            }

            return count
        }

        private fun account(
            count: Long
        ) {
            bytesRead += count

            require(bytesRead <= maxBytes) {
                "$label exceeded size limit"
            }
        }
    }
}
