package com.keiba.ai

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

object NarDailyRaceDownloader {

    private const val DAILY_URL =
        "https://www.keiba.go.jp/KeibaWeb/DataDownload/" +
            "RaceDataDownload?type=daily"

    data class DailyRaceData(
        val date: String,
        val racelistCsv: String,
        val horselistCsv: String,
        val paybackCsv: String?
    )

    fun download(): DailyRaceData {

        val connection =
            URL(DAILY_URL)
                .openConnection() as HttpURLConnection

        connection.requestMethod = "GET"
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000

        connection.setRequestProperty(
            "Accept",
            "application/zip"
        )

        connection.setRequestProperty(
            "User-Agent",
            "KeibaAI-Android/0.1"
        )

        connection.instanceFollowRedirects = true

        try {
            val responseCode =
                connection.responseCode

            require(responseCode == 200) {
                "NAR daily HTTP $responseCode"
            }

            val contentType =
                connection.contentType
                    ?: ""

            require(
                contentType
                    .lowercase()
                    .contains("zip")
            ) {
                "unexpected Content-Type: $contentType"
            }

            var racelist: Pair<String, String>? =
                null

            var horselist: Pair<String, String>? =
                null

            var payback: Pair<String, String>? =
                null

            ZipInputStream(
                connection
                    .inputStream
                    .buffered()
            ).use { zip ->

                while (true) {
                    val entry =
                        zip.nextEntry
                            ?: break

                    if (!entry.isDirectory) {

                        val name =
                            entry.name
                                .substringAfterLast('/')

                        val match =
                            Regex(
                                """^(\d{8})_(racelist|horselist|payback)\.csv$"""
                            )
                                .matchEntire(name)

                        if (match != null) {

                            val date =
                                match.groupValues[1]

                            val kind =
                                match.groupValues[2]

                            val text =
                                readCurrentEntry(zip)
                                    .removePrefix(
                                        "\uFEFF"
                                    )

                            when (kind) {
                                "racelist" -> {
                                    require(
                                        racelist == null
                                    ) {
                                        "duplicate racelist"
                                    }

                                    racelist =
                                        date to text
                                }

                                "horselist" -> {
                                    require(
                                        horselist == null
                                    ) {
                                        "duplicate horselist"
                                    }

                                    horselist =
                                        date to text
                                }

                                "payback" -> {
                                    require(
                                        payback == null
                                    ) {
                                        "duplicate payback"
                                    }

                                    payback =
                                        date to text
                                }
                            }
                        }
                    }

                    zip.closeEntry()
                }
            }

            val race =
                racelist
                    ?: error(
                        "racelist not found"
                    )

            val horse =
                horselist
                    ?: error(
                        "horselist not found"
                    )

            require(
                race.first ==
                    horse.first
            ) {
                "racelist/horselist date mismatch"
            }

            if (payback != null) {
                require(
                    payback!!.first ==
                        race.first
                ) {
                    "payback date mismatch"
                }
            }

            return DailyRaceData(
                date = race.first,
                racelistCsv = race.second,
                horselistCsv = horse.second,
                paybackCsv =
                    payback?.second
            )

        } finally {
            connection.disconnect()
        }
    }

    private fun readCurrentEntry(
        zip: ZipInputStream
    ): String {

        val output =
            ByteArrayOutputStream()

        val buffer =
            ByteArray(8192)

        while (true) {
            val count =
                zip.read(buffer)

            if (count < 0) {
                break
            }

            output.write(
                buffer,
                0,
                count
            )
        }

        return output.toString(
            Charsets.UTF_8.name()
        )
    }
}
