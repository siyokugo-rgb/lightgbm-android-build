package com.keiba.ai

object NarCsvParser {
    data class CsvTable(
        val header: Map<String, Int>,
        val rows: List<List<String>>
    )

    fun parseTable(text: String): CsvTable {
        val records = splitCsvRecords(text)
            .filter { it.isNotBlank() }

        require(records.isNotEmpty()) { "CSV is empty" }

        val headerRow = parseCsvLine(records.first())
        val header = headerRow.mapIndexed { index, name ->
            name.removePrefix("\uFEFF") to index
        }.toMap()

        val rows = records.drop(1).map(::parseCsvLine)
        return CsvTable(header, rows)
    }

    fun value(table: CsvTable, row: List<String>, column: String): String {
        val index = table.header[column] ?: return ""
        return row.getOrElse(index) { "" }
    }

    private fun splitCsvRecords(text: String): List<String> {
        val result = mutableListOf<String>()
        val record = StringBuilder()
        var quoted = false
        var i = 0

        while (i < text.length) {
            val c = text[i]

            when {
                c == '"' && quoted && i + 1 < text.length && text[i + 1] == '"' -> {
                    record.append('"')
                    record.append('"')
                    i++
                }

                c == '"' -> {
                    quoted = !quoted
                    record.append(c)
                }

                (c == '\r' || c == '\n') && !quoted -> {
                    result += record.toString()
                    record.setLength(0)

                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') {
                        i++
                    }
                }

                else -> record.append(c)
            }

            i++
        }

        require(!quoted) {
            "Unclosed quoted field"
        }

        if (record.isNotEmpty()) {
            result += record.toString()
        }

        return result
    }

    fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0

        while (i < line.length) {
            val c = line[i]

            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> {
                    field.append('"')
                    i++
                }

                c == '"' -> quoted = !quoted

                c == ',' && !quoted -> {
                    result += field.toString()
                    field.setLength(0)
                }

                else -> field.append(c)
            }

            i++
        }

        require(!quoted) {
            "Unclosed quoted field"
        }

        result += field.toString()
        return result
    }
}
