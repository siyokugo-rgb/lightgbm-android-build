package com.keiba.ai

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Bounded Shift_JIS CSV lexical parser for JMA latest products.
 * CRLF records; quoted fields; no raw-byte mutation.
 */
internal object NarJmaObservedWeatherCsvLexical {

    internal const val MAX_DECODED_CHARS =
        NarJmaObservedWeatherDownloader
            .MAX_RESPONSE_BYTES
            .toInt()

    internal const val MAX_ROWS =
        5_000

    internal const val MAX_COLUMNS =
        64

    internal const val MAX_CELL_LENGTH =
        4_096

    private val shiftJis =
        Charset.forName("Shift_JIS")
            .newDecoder()
            .onMalformedInput(
                CodingErrorAction.REPORT
            )
            .onUnmappableCharacter(
                CodingErrorAction.REPORT
            )

    fun decodeShiftJisStrict(
        rawBytes: ByteArray
    ): String {
        require(
            rawBytes.size.toLong() <=
                NarJmaObservedWeatherDownloader
                    .MAX_RESPONSE_BYTES
        ) {
            "observed CSV exceeds raw size limit"
        }

        val decoded =
            try {
                shiftJis.decode(
                    ByteBuffer.wrap(rawBytes)
                )
            } catch (error: Exception) {
                throw IllegalArgumentException(
                    "observed CSV is not valid Shift_JIS",
                    error
                )
            }

        require(
            decoded.length <=
                MAX_DECODED_CHARS
        ) {
            "observed CSV decoded text too large"
        }

        return decoded.toString()
    }

    fun parseTable(
        text: String
    ): List<List<String>> {
        require(
            text.length <= MAX_DECODED_CHARS
        ) {
            "observed CSV text too large"
        }

        val records =
            splitCsvRecords(text)
                .filter {
                    it.isNotBlank()
                }

        require(records.isNotEmpty()) {
            "observed CSV is empty"
        }

        require(records.size <= MAX_ROWS) {
            "observed CSV has too many rows"
        }

        val header =
            parseCsvLine(records.first())

        require(
            header.size <= MAX_COLUMNS
        ) {
            "observed CSV header has too many columns"
        }

        val rows =
            mutableListOf<List<String>>()

        rows += header

        for (record in records.drop(1)) {
            val line =
                parseCsvLine(record)

            require(
                line.size == header.size
            ) {
                "observed CSV row column count mismatch"
            }

            rows += line
        }

        return rows
    }

    internal fun splitCsvRecordsForTest(
        text: String
    ): List<String> =
        splitCsvRecords(text)

    internal fun parseCsvLineForTest(
        line: String
    ): List<String> =
        parseCsvLine(line)

    private fun splitCsvRecords(
        text: String
    ): List<String> {
        val result =
            mutableListOf<String>()

        val record =
            StringBuilder()

        var quoted = false
        var i = 0

        while (i < text.length) {
            val c = text[i]

            when {
                c == '"' &&
                    quoted &&
                    i + 1 < text.length &&
                    text[i + 1] == '"' -> {
                    record.append('"')
                    record.append('"')
                    i++
                }

                c == '"' -> {
                    quoted = !quoted
                    record.append(c)
                }

                (c == '\r' || c == '\n') &&
                    !quoted -> {
                    result += record.toString()
                    record.setLength(0)

                    if (
                        c == '\r' &&
                        i + 1 < text.length &&
                        text[i + 1] == '\n'
                    ) {
                        i++
                    }
                }

                else ->
                    record.append(c)
            }

            i++
        }

        require(!quoted) {
            "unclosed quoted field"
        }

        if (record.isNotEmpty()) {
            result += record.toString()
        }

        return result
    }

    private fun parseCsvLine(
        line: String
    ): List<String> {
        val result =
            mutableListOf<String>()

        val field =
            StringBuilder()

        var quoted = false
        var i = 0

        while (i < line.length) {
            val c = line[i]

            when {
                c == '"' &&
                    quoted &&
                    i + 1 < line.length &&
                    line[i + 1] == '"' -> {
                    field.append('"')
                    i++
                }

                c == '"' ->
                    quoted = !quoted

                c == ',' && !quoted -> {
                    appendField(result, field)
                    field.setLength(0)
                }

                else ->
                    field.append(c)
            }

            i++
        }

        require(!quoted) {
            "unclosed quoted field"
        }

        appendField(result, field)

        return result
    }

    private fun appendField(
        result: MutableList<String>,
        field: StringBuilder
    ) {
        val value =
            field.toString()

        require(
            value.length <= MAX_CELL_LENGTH
        ) {
            "observed CSV cell too large"
        }

        result += value
    }
}
