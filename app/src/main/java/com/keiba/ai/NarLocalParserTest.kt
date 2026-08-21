package com.keiba.ai

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

object NarLocalParserTest {
    private const val ZIP_NAME = "199808_race.zip"

    private const val TARGET_TRACK = "大井"
    private const val TARGET_DATE = "19980806"
    private const val TARGET_RACE = "1"

    private data class CsvTable(
        val header: Map<String, Int>,
        val rows: List<List<String>>
    )

    fun run(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return "LOCAL PARSE FAIL\nAndroid 10+ required"
        }

        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val relativePath = Environment.DIRECTORY_DOWNLOADS + "/KeibaAI/"

        var zipId: Long? = null

        resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
                "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(ZIP_NAME, relativePath),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                zipId = cursor.getLong(
                    cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                )
            }
        }

        val id = zipId
            ?: return "LOCAL PARSE FAIL\n$ZIP_NAME not found"

        val zipUri = ContentUris.withAppendedId(collection, id)

        return try {
            var raceText: String? = null
            var horseText: String? = null
            var paybackText: String? = null

            resolver.openInputStream(zipUri)!!.use { input ->
                ZipInputStream(input.buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break

                        if (!entry.isDirectory) {
                            val name = entry.name.substringAfterLast('/')

                            if (
                                name == "199808_racelist.csv" ||
                                name == "199808_horselist.csv" ||
                                name == "199808_payback.csv"
                            ) {
                                val out = ByteArrayOutputStream()
                                val buffer = ByteArray(8192)

                                while (true) {
                                    val count = zip.read(buffer)
                                    if (count < 0) break
                                    out.write(buffer, 0, count)
                                }

                                val text = out.toString("UTF-8")
                                    .removePrefix("\uFEFF")

                                when (name) {
                                    "199808_racelist.csv" -> raceText = text
                                    "199808_horselist.csv" -> horseText = text
                                    "199808_payback.csv" -> paybackText = text
                                }
                            }
                        }

                        zip.closeEntry()
                    }
                }
            }

            val races = parseTable(
                raceText ?: return "LOCAL PARSE FAIL\nracelist not found"
            )

            val horses = parseTable(
                horseText ?: return "LOCAL PARSE FAIL\nhorselist not found"
            )

            val paybacks = parseTable(
                paybackText ?: return "LOCAL PARSE FAIL\npayback not found"
            )

            val raceRow = races.rows.firstOrNull { row ->
                value(races, row, "競馬場") == TARGET_TRACK &&
                    value(races, row, "競走年月日") == TARGET_DATE &&
                    value(races, row, "レース番号") == TARGET_RACE
            } ?: return "LOCAL PARSE FAIL\nTarget race not found"

            val horseRows = horses.rows.filter { row ->
                value(horses, row, "競馬場") == TARGET_TRACK &&
                    value(horses, row, "競走年月日") == TARGET_DATE &&
                    value(horses, row, "レース番号") == TARGET_RACE
            }.sortedBy {
                value(horses, it, "馬番").toIntOrNull() ?: 999
            }

            val paybackRow = paybacks.rows.firstOrNull { row ->
                value(paybacks, row, "競馬場") == TARGET_TRACK &&
                    value(paybacks, row, "競走年月日") == TARGET_DATE &&
                    value(paybacks, row, "レース番号") == TARGET_RACE
            } ?: return "LOCAL PARSE FAIL\nTarget payback not found"

            val declaredCount =
                value(races, raceRow, "頭数").toIntOrNull() ?: -1

            val paybackPass =
                value(paybacks, paybackRow, "単勝組番") == "6" &&
                    value(paybacks, paybackRow, "単勝払戻金（円）") == "350" &&
                    value(paybacks, paybackRow, "馬複組番1") == "5" &&
                    value(paybacks, paybackRow, "馬複組番2") == "6" &&
                    value(paybacks, paybackRow, "馬複払戻金（円）") == "1290"

            val pass =
                declaredCount == horseRows.size &&
                    horseRows.size == 12 &&
                    paybackPass

            buildString {
                append("LOCAL CSV PARSE ")
                append(if (pass) "OK" else "FAIL")

                append("\nsource=Download/KeibaAI/").append(ZIP_NAME)

                append("\n\n=== RACE ===")
                append("\n競馬場=").append(value(races, raceRow, "競馬場"))
                append("\n日付=").append(value(races, raceRow, "競走年月日"))
                append("\nレース=").append(value(races, raceRow, "レース番号")).append("R")
                append("\n発走=").append(value(races, raceRow, "発走時刻"))
                append("\n距離=").append(value(races, raceRow, "距離"))
                append("\n天候=").append(value(races, raceRow, "天候"))
                append("\n馬場=").append(value(races, raceRow, "馬場"))
                append("\n頭数=").append(value(races, raceRow, "頭数"))
                append("\nレース名=").append(value(races, raceRow, "レース名"))

                append("\n\n=== HORSES ===")
                append("\nCSV抽出頭数=").append(horseRows.size)

                for (row in horseRows) {
                    append("\n")
                    append(value(horses, row, "馬番"))
                    append(" ")
                    append(value(horses, row, "馬名"))
                    append(" / 騎手=")
                    append(value(horses, row, "騎手名"))
                    append(" / 斤量=")
                    append(value(horses, row, "負担重量"))
                    append(" / 馬体重=")
                    append(value(horses, row, "馬体重"))
                    append(" / 着順=")
                    append(value(horses, row, "着順"))
                }

                append("\n\n=== PAYBACK ===")
                append("\n単勝=")
                append(value(paybacks, paybackRow, "単勝組番"))
                append(" ")
                append(value(paybacks, paybackRow, "単勝払戻金（円）"))
                append("円")

                append("\n複勝1=")
                append(value(paybacks, paybackRow, "複勝組番1"))
                append(" ")
                append(value(paybacks, paybackRow, "複勝払戻金1（円）"))
                append("円")

                append("\n複勝2=")
                append(value(paybacks, paybackRow, "複勝組番2"))
                append(" ")
                append(value(paybacks, paybackRow, "複勝払戻金2（円）"))
                append("円")

                append("\n複勝3=")
                append(value(paybacks, paybackRow, "複勝組番3"))
                append(" ")
                append(value(paybacks, paybackRow, "複勝払戻金3（円）"))
                append("円")

                append("\n枠複=")
                append(value(paybacks, paybackRow, "枠複組番1"))
                append("-")
                append(value(paybacks, paybackRow, "枠複組番2"))
                append(" ")
                append(value(paybacks, paybackRow, "枠複払戻金（円）"))
                append("円")

                append("\n馬複=")
                append(value(paybacks, paybackRow, "馬複組番1"))
                append("-")
                append(value(paybacks, paybackRow, "馬複組番2"))
                append(" ")
                append(value(paybacks, paybackRow, "馬複払戻金（円）"))
                append("円")
            }
        } catch (t: Throwable) {
            "LOCAL PARSE FAIL\n${t.javaClass.simpleName}: ${t.message}"
        }
    }

    private fun parseTable(text: String): CsvTable {
        val lines = text.lineSequence()
            .filter { it.isNotBlank() }
            .toList()

        require(lines.isNotEmpty())

        val headerRow = parseCsvLine(lines.first())

        val header = headerRow.mapIndexed { index, name ->
            name.removePrefix("\uFEFF") to index
        }.toMap()

        val rows = lines.drop(1).map {
            parseCsvLine(it)
        }

        return CsvTable(header, rows)
    }

    private fun value(
        table: CsvTable,
        row: List<String>,
        column: String
    ): String {
        val index = table.header[column] ?: return ""
        return row.getOrElse(index) { "" }
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0

        while (i < line.length) {
            val c = line[i]

            when {
                c == '"' && quoted &&
                    i + 1 < line.length &&
                    line[i + 1] == '"' -> {
                    field.append('"')
                    i++
                }

                c == '"' -> {
                    quoted = !quoted
                }

                c == ',' && !quoted -> {
                    result += field.toString()
                    field.setLength(0)
                }

                else -> {
                    field.append(c)
                }
            }

            i++
        }

        result += field.toString()

        return result
    }
}
