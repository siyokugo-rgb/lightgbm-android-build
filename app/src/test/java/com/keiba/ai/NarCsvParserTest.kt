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
    fun parsesQuotedMultilineField() {
        val table = NarCsvParser.parseTable(
            "競馬場,競走年月日,レース番号,発走時刻,備考\n" +
                "宇都宮,20040304,1,1050,\"1行目\n2行目\"\n" +
                "宇都宮,20040304,2,1120,通常\n"
        )

        assertEquals(2, table.rows.size)

        val first = table.rows[0]
        assertEquals("宇都宮", NarCsvParser.value(table, first, "競馬場"))
        assertEquals("20040304", NarCsvParser.value(table, first, "競走年月日"))
        assertEquals("1", NarCsvParser.value(table, first, "レース番号"))
        assertEquals("1050", NarCsvParser.value(table, first, "発走時刻"))
        assertEquals("1行目\n2行目", NarCsvParser.value(table, first, "備考"))

        val second = table.rows[1]
        assertEquals("2", NarCsvParser.value(table, second, "レース番号"))
        assertEquals("1120", NarCsvParser.value(table, second, "発走時刻"))
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

    @Test
    fun unclosedMultilineQuoteFails() {
        assertThrows(IllegalArgumentException::class.java) {
            NarCsvParser.parseTable(
                "a,b\n1,\"broken\n2,still broken\n"
            )
        }
    }
}
