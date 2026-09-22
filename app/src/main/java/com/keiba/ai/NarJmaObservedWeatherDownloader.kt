package com.keiba.ai

import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * HTTPS downloader for documented JMA public latest CSV products.
 *
 * Preserves raw response bytes without decode/normalize.
 * Does not parse CSV rows, stations, or values.
 */
object NarJmaObservedWeatherDownloader {

    private const val ALLOWED_HOST =
        NarJmaObservedWeatherProduct.HOST

    private const val EXPECTED_CONTENT_TYPE =
        "text/csv"

    /**
     * Step F/G live probes (2026-09-22) observed documented product
     * CSV bodies around 120–147 KiB. Cap at 512 KiB for margin
     * without unbounded reads.
     */
    internal const val MAX_RESPONSE_BYTES =
        512L * 1024L

    data class ObservedCsvResponse(
        val productId: String,
        val requestUrl: String,
        val responseBytes: ByteArray,
        val contentType: String,
        val downloadedAtEpochMillis: Long,
        val serverDateEpochMillis: Long?,
        val lastModifiedEpochMillis: Long?
    )

    fun download(
        product: NarJmaObservedWeatherProduct
    ): ObservedCsvResponse {
        val requestUrl =
            product.canonicalUrl()

        val url = URL(requestUrl)

        require(url.protocol == "https") {
            "observed weather URL must use HTTPS"
        }

        require(
            url.host.equals(
                ALLOWED_HOST,
                ignoreCase = true
            )
        ) {
            "unexpected observed weather host"
        }

        val connection =
            url.openConnection()
                as? HttpsURLConnection
                ?: error(
                    "observed weather connection is not HTTPS"
                )

        connection.requestMethod = "GET"
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = false
        connection.useCaches = false

        connection.setRequestProperty(
            "Accept",
            EXPECTED_CONTENT_TYPE
        )

        connection.setRequestProperty(
            "User-Agent",
            "KeibaAI-Android/0.1"
        )

        try {
            val responseCode =
                connection.responseCode

            require(
                responseCode ==
                    HttpsURLConnection.HTTP_OK
            ) {
                if (responseCode in 300..399) {
                    "observed weather redirect rejected: HTTP $responseCode"
                } else {
                    "observed weather HTTP $responseCode"
                }
            }

            require(
                connection.url.protocol == "https" &&
                    connection.url.host.equals(
                        ALLOWED_HOST,
                        ignoreCase = true
                    )
            ) {
                "observed weather connection changed origin"
            }

            val contentTypeHeader =
                connection.contentType

            validateContentType(
                contentTypeHeader
            )

            val contentLength =
                connection.contentLengthLong

            require(
                contentLength < 0L ||
                    contentLength <=
                        MAX_RESPONSE_BYTES
            ) {
                "observed weather response too large"
            }

            val serverDate =
                connection.date
                    .takeIf { it > 0L }

            val lastModified =
                connection.lastModified
                    .takeIf { it > 0L }

            val bytes =
                SizeLimitedInputStream(
                    connection.inputStream,
                    MAX_RESPONSE_BYTES,
                    "observed weather response"
                )
                    .buffered()
                    .use {
                        it.readBytes()
                    }

            validateResponseBytes(bytes)

            // PIT evidence must not precede completion
            // of the response body download.
            val downloadedAt =
                System.currentTimeMillis()

            return ObservedCsvResponse(
                productId =
                    product.productId,
                requestUrl =
                    requestUrl,
                responseBytes =
                    bytes,
                contentType =
                    EXPECTED_CONTENT_TYPE,
                downloadedAtEpochMillis =
                    downloadedAt,
                serverDateEpochMillis =
                    serverDate,
                lastModifiedEpochMillis =
                    lastModified
            )
        } finally {
            connection.disconnect()
        }
    }

    internal fun validateContentTypeForTest(
        contentType: String?
    ) {
        validateContentType(contentType)
    }

    internal fun readLimitedForTest(
        bytes: ByteArray
    ): ByteArray =
        SizeLimitedInputStream(
            ByteArrayInputStream(bytes),
            MAX_RESPONSE_BYTES,
            "test observed weather response"
        ).use {
            it.readBytes()
        }

    internal fun computePitEvidenceAtEpochMillis(
        downloadedAtEpochMillis: Long,
        serverDateEpochMillis: Long?
    ): Long {
        require(downloadedAtEpochMillis > 0L) {
            "invalid observed weather downloaded-at"
        }

        val server =
            serverDateEpochMillis
                ?.also {
                    require(it > 0L) {
                        "invalid observed weather server date"
                    }
                }

        return maxOf(
            downloadedAtEpochMillis,
            server ?: 0L
        )
    }

    private fun validateContentType(
        contentType: String?
    ) {
        val mediaType =
            contentType
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase()
                .orEmpty()

        require(
            mediaType ==
                EXPECTED_CONTENT_TYPE
        ) {
            "unexpected observed weather Content-Type"
        }
    }

    /**
     * Transport-only guards. Does not decode charset,
     * normalize newlines, or parse CSV schema.
     */
    internal fun validateResponseBytes(
        bytes: ByteArray
    ) {
        require(bytes.isNotEmpty()) {
            "empty observed weather response"
        }

        require(
            bytes.size.toLong() <=
                MAX_RESPONSE_BYTES
        ) {
            "observed weather response too large"
        }

        rejectObviousHtmlPrefix(bytes)
    }

    private fun rejectObviousHtmlPrefix(
        bytes: ByteArray
    ) {
        val probeLen =
            minOf(
                bytes.size,
                256
            )

        val probe =
            bytes
                .copyOfRange(
                    0,
                    probeLen
                )
                .toString(
                    Charsets.US_ASCII
                )
                .lowercase()
                .trimStart()

        require(
            !probe.startsWith("<!doctype") &&
                !probe.startsWith("<html")
        ) {
            "observed weather response looks like HTML"
        }
    }

    private class SizeLimitedInputStream(
        input: InputStream,
        private val maxBytes: Long,
        private val label: String
    ) : FilterInputStream(input) {

        private var count = 0L

        override fun read(): Int {
            val value =
                super.read()

            if (value >= 0) {
                addBytes(1)
            }

            return value
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int
        ): Int {
            val read =
                super.read(
                    buffer,
                    offset,
                    length
                )

            if (read > 0) {
                addBytes(read.toLong())
            }

            return read
        }

        private fun addBytes(
            amount: Long
        ) {
            count =
                Math.addExact(
                    count,
                    amount
                )

            require(count <= maxBytes) {
                "$label exceeds size limit"
            }
        }
    }
}
