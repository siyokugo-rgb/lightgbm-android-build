package com.keiba.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NarCsvParserTest {

    @Test
    fun parsesBomHeader() {
        val table = NarCsvParser.parseTable(
            "\uFEFF競馬場,レース番号\n大井,1\n"
        )

        assertEquals("大井", NarCsvParser.value(table, table.rows[0], "競馬場"))
        assertEquals("1", NarCsvParser.value(table, table.rows[0], "レース番号"))
    }

    @Test
    fun parsesQuotedComma() {
        val row = NarCsvParser.parseCsvLine(
            "1,\"東京,大井\",3"
        )

        assertEquals(listOf("1", "東京,大井", "3"), row)
    }

    @Test
    fun parsesEscapedQuote() {
        val row = NarCsvParser.parseCsvLine(
            "1,\"A\"\"B\",3"
        )

        assertEquals(listOf("1", "A\"B", "3"), row)
    }

    @Test
    fun emptyCsvFails() {
        assertThrows(IllegalArgumentException::class.java) {
            NarCsvParser.parseTable("")
        }
    }

    @Test
    fun unclosedQuoteFails() {
        assertThrows(IllegalArgumentException::class.java) {
            NarCsvParser.parseCsvLine(
                "1,\"broken,3"
            )
        }
    }
}
