package com.keiba.ai

import android.os.SystemClock

object NarDailyRaceDownloadTest {

    fun run(): String {

        val start =
            SystemClock.elapsedRealtime()

        return try {
            val daily =
                NarDailyRaceDownloader.download()

            val races =
                NarCsvParser.parseTable(
                    daily.racelistCsv
                )

            val horses =
                NarCsvParser.parseTable(
                    daily.horselistCsv
                )

            val paybackRows =
                daily.paybackCsv?.let {
                    NarCsvParser
                        .parseTable(it)
                        .rows
                        .size
                }

            require(
                daily.date.length == 8 &&
                    daily.date.all { it.isDigit() }
            ) {
                "invalid daily date: ${daily.date}"
            }

            require(races.rows.isNotEmpty()) {
                "racelist is empty"
            }

            require(horses.rows.isNotEmpty()) {
                "horselist is empty"
            }

            val raceKeys =
                races.rows.map { row ->
                    listOf(
                        NarCsvParser.value(
                            races,
                            row,
                            "競馬場"
                        ),
                        NarCsvParser.value(
                            races,
                            row,
                            "競走年月日"
                        ),
                        NarCsvParser.value(
                            races,
                            row,
                            "レース番号"
                        )
                    ).joinToString("|")
                }
                    .toSet()

            val horseRaceKeys =
                horses.rows.map { row ->
                    listOf(
                        NarCsvParser.value(
                            horses,
                            row,
                            "競馬場"
                        ),
                        NarCsvParser.value(
                            horses,
                            row,
                            "競走年月日"
                        ),
                        NarCsvParser.value(
                            horses,
                            row,
                            "レース番号"
                        )
                    ).joinToString("|")
                }
                    .toSet()

            require(
                horseRaceKeys.all {
                    it in raceKeys
                }
            ) {
                "horselist contains unknown race key"
            }

            val elapsed =
                SystemClock.elapsedRealtime() -
                    start

            buildString {
                append(
                    "NAR DAILY DOWNLOAD OK"
                )

                append("\ndate=")
                append(daily.date)

                append("\nracelist_rows=")
                append(races.rows.size)

                append("\nracelist_columns=")
                append(races.header.size)

                append("\nhorselist_rows=")
                append(horses.rows.size)

                append("\nhorselist_columns=")
                append(horses.header.size)

                append("\nraces=")
                append(raceKeys.size)

                append("\nhorse_races=")
                append(horseRaceKeys.size)

                append("\npayback_rows=")
                append(
                    paybackRows
                        ?.toString()
                        ?: "not_present"
                )

                append("\nelapsed_ms=")
                append(elapsed)
            }

        } catch (t: Throwable) {
            buildString {
                append(
                    "NAR DAILY DOWNLOAD FAIL"
                )

                append("\n")
                append(
                    t.javaClass.simpleName
                )

                append(": ")
                append(
                    t.message ?: "(no message)"
                )
            }
        }
    }
}
