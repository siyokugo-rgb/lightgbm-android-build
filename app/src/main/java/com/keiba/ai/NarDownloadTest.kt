package com.keiba.ai

import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

object NarDownloadTest {
    private const val TEST_URL =
        "https://www.keiba.go.jp/KeibaWeb/DataDownload/RaceDataDownload?k_month=8&k_year=1998&type=monthly"

    fun run(): String {
        val connection = (URL(TEST_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/zip")
            setRequestProperty("User-Agent", "KeibaAndroidTest/0.1")
        }

        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                return "NAR DOWNLOAD FAIL\nHTTP=$status"
            }

            val contentType = connection.contentType ?: "(unknown)"
            var raceList = -1
            var horseList = -1
            var payback = -1
            var totalBytes = 0L

            ZipInputStream(connection.inputStream.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory) {
                        var newlineCount = 0
                        var lastByte = -1
                        val buffer = ByteArray(8192)

                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break

                            totalBytes += count
                            for (i in 0 until count) {
                                val value = buffer[i].toInt() and 0xff
                                if (value == 0x0a) newlineCount++
                                lastByte = value
                            }
                        }

                        if (lastByte >= 0 && lastByte != 0x0a) {
                            newlineCount++
                        }

                        val dataRows = (newlineCount - 1).coerceAtLeast(0)

                        when (entry.name.substringAfterLast('/')) {
                            "199808_racelist.csv" -> raceList = dataRows
                            "199808_horselist.csv" -> horseList = dataRows
                            "199808_payback.csv" -> payback = dataRows
                        }
                    }
                    zip.closeEntry()
                }
            }

            val pass = raceList == 2054 &&
                horseList == 19065 &&
                payback == 2068

            return buildString {
                append("NAR DOWNLOAD ")
                append(if (pass) "OK" else "FAIL")
                append("\nHTTP=").append(status)
                append("\nContent-Type=").append(contentType)
                append("\nracelist=").append(raceList)
                append("\nhorselist=").append(horseList)
                append("\npayback=").append(payback)
                append("\nuncompressedBytes=").append(totalBytes)
            }
        } catch (t: Throwable) {
            return "NAR DOWNLOAD FAIL\n${t.javaClass.simpleName}: ${t.message}"
        } finally {
            connection.disconnect()
        }
    }
}
