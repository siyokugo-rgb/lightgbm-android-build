package com.keiba.ai

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object NarFileSaveTest {
    private const val TEST_URL =
        "https://www.keiba.go.jp/KeibaWeb/DataDownload/RaceDataDownload?k_month=8&k_year=1998&type=monthly"

    private const val EXPECTED_SHA256 =
        "1a0ffe8bb1da5a0b98f77fcbe022032712dab59aae7b1ee07f8ab096773a902f"

    private const val ZIP_NAME = "199808_race.zip"
    private const val SHA_NAME = "199808_race.sha256.txt"

    fun run(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return "SAVE FAIL\nAndroid 10+ required for this test"
        }

        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val relativePath = Environment.DIRECTORY_DOWNLOADS + "/KeibaAI"

        fun deleteOld(name: String) {
            resolver.delete(
                collection,
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
                    "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(name, "$relativePath/")
            )
        }

        deleteOld(ZIP_NAME)
        deleteOld(SHA_NAME)

        val zipValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, ZIP_NAME)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val zipUri = resolver.insert(collection, zipValues)
            ?: return "SAVE FAIL\nCould not create ZIP in Downloads"

        var connection: HttpURLConnection? = null

        try {
            connection = (URL(TEST_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/zip")
                setRequestProperty("User-Agent", "KeibaAndroidTest/0.1")
            }

            val status = connection.responseCode
            if (status !in 200..299) {
                resolver.delete(zipUri, null, null)
                return "SAVE FAIL\nHTTP=$status"
            }

            var savedBytes = 0L

            resolver.openOutputStream(zipUri, "w")!!.use { output ->
                connection.inputStream.buffered().use { input ->
                    val buffer = ByteArray(64 * 1024)

                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break

                        output.write(buffer, 0, count)
                        savedBytes += count
                    }

                    output.flush()
                }
            }

            val publishValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(zipUri, publishValues, null, null)

            val digest = MessageDigest.getInstance("SHA-256")

            resolver.openInputStream(zipUri)!!.buffered().use { input ->
                val buffer = ByteArray(64 * 1024)

                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }

            val actualSha256 =
                digest.digest().joinToString("") { "%02x".format(it) }

            val shaMatch =
                actualSha256.equals(EXPECTED_SHA256, ignoreCase = true)

            val shaValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, SHA_NAME)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            }

            val shaUri = resolver.insert(collection, shaValues)

            if (shaUri != null) {
                resolver.openOutputStream(shaUri, "w")!!.bufferedWriter().use {
                    it.write("$actualSha256  $ZIP_NAME\n")
                    it.write("expected=$EXPECTED_SHA256\n")
                    it.write("match=$shaMatch\n")
                    it.write("bytes=$savedBytes\n")
                }
            }

            return buildString {
                append("NAR FILE SAVE ")
                append(if (shaMatch) "OK" else "FAIL")
                append("\nHTTP=").append(status)
                append("\npath=Download/KeibaAI/").append(ZIP_NAME)
                append("\nbytes=").append(savedBytes)
                append("\nSHA-256=").append(actualSha256)
                append("\nexpected=").append(EXPECTED_SHA256)
                append("\nmatch=").append(shaMatch)
            }
        } catch (t: Throwable) {
            resolver.delete(zipUri, null, null)
            return "SAVE FAIL\n${t.javaClass.simpleName}: ${t.message}"
        } finally {
            connection?.disconnect()
        }
    }
}
