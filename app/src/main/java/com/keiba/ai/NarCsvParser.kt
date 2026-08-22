package com.keiba.ai

object NarCsvParser {
    data class CsvTable(
        val header: Map<String, Int>,
        val rows: List<List<String>>
    )

    fun parseTable(text: String): CsvTable {
        val lines = text.lineSequence()
            .filter { it.isNotBlank() }
            .toList()

        require(lines.isNotEmpty()) { "CSV is empty" }

        val headerRow = parseCsvLine(lines.first())
        val header = headerRow.mapIndexed { index, name ->
            name.removePrefix("\uFEFF") to index
        }.toMap()

        val rows = lines.drop(1).map(::parseCsvLine)
        return CsvTable(header, rows)
    }

    fun value(table: CsvTable, row: List<String>, column: String): String {
        val index = table.header[column] ?: return ""
        return row.getOrElse(index) { "" }
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

        result += field.toString()
        return result
    }
}
