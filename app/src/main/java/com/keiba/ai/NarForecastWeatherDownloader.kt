package com.keiba.ai

import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import javax.net.ssl.HttpsURLConnection

object NarForecastWeatherDownloader {

    private const val BASE_URL =
        "https://api.open-meteo.com/v1/forecast"

    private const val ALLOWED_HOST =
        "api.open-meteo.com"

    private const val EXPECTED_CONTENT_TYPE =
        "application/json"

    internal const val MAX_RESPONSE_BYTES =
        2L * 1024L * 1024L

    private const val FORECAST_HOURS =
        72

    private val hourlyFields =
        listOf(
            "temperature_2m",
            "relative_humidity_2m",
            "pressure_msl",
            "surface_pressure",
            "precipitation",
            "weather_code",
            "wind_speed_10m",
            "wind_direction_10m",
            "wind_gusts_10m"
        )

    data class ForecastResponse(
        val requestedLatitude: Double,
        val requestedLongitude: Double,
        val requestUrl: String,
        val responseBytes: ByteArray,
        val downloadedAtEpochMillis: Long,
        val serverDateEpochMillis: Long?
    )

    fun download(
        latitude: Double,
        longitude: Double
    ): ForecastResponse {
        val requestUrl =
            buildRequestUrl(
                latitude = latitude,
                longitude = longitude
            )

        val url = URL(requestUrl)

        require(url.protocol == "https") {
            "weather URL must use HTTPS"
        }

        require(
            url.host.equals(
                ALLOWED_HOST,
                ignoreCase = true
            )
        ) {
            "unexpected weather host"
        }

        val connection =
            url.openConnection()
                as? HttpsURLConnection
                ?: error(
                    "weather connection is not HTTPS"
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
                    "weather redirect rejected: HTTP $responseCode"
                } else {
                    "weather HTTP $responseCode"
                }
            }

            require(
                connection.url.protocol == "https" &&
                    connection.url.host.equals(
                        ALLOWED_HOST,
                        ignoreCase = true
                    )
            ) {
                "weather connection changed origin"
            }

            validateContentType(
                connection.contentType
            )

            val contentLength =
                connection.contentLengthLong

            require(
                contentLength < 0L ||
                    contentLength <=
                        MAX_RESPONSE_BYTES
            ) {
                "weather response too large"
            }

            val serverDate =
                connection.date
                    .takeIf { it > 0L }

            val bytes =
                SizeLimitedInputStream(
                    connection.inputStream,
                    MAX_RESPONSE_BYTES,
                    "weather response"
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

            return ForecastResponse(
                requestedLatitude = latitude,
                requestedLongitude = longitude,
                requestUrl = requestUrl,
                responseBytes = bytes,
                downloadedAtEpochMillis =
                    downloadedAt,
                serverDateEpochMillis =
                    serverDate
            )
        } finally {
            connection.disconnect()
        }
    }

    internal fun buildRequestUrlForTest(
        latitude: Double,
        longitude: Double
    ): String =
        buildRequestUrl(
            latitude,
            longitude
        )

    internal fun validateContentTypeForTest(
        contentType: String?
    ) {
        validateContentType(contentType)
    }

    internal fun validateResponseBytesForTest(
        bytes: ByteArray
    ) {
        validateResponseBytes(bytes)
    }

    internal fun readLimitedForTest(
        bytes: ByteArray
    ): ByteArray =
        SizeLimitedInputStream(
            ByteArrayInputStream(bytes),
            MAX_RESPONSE_BYTES,
            "test weather response"
        ).use {
            it.readBytes()
        }

    private fun buildRequestUrl(
        latitude: Double,
        longitude: Double
    ): String {
        require(
            latitude.isFinite() &&
                latitude in -90.0..90.0
        ) {
            "invalid latitude"
        }

        require(
            longitude.isFinite() &&
                longitude in -180.0..180.0
        ) {
            "invalid longitude"
        }

        val hourly =
            hourlyFields.joinToString(",")

        return BASE_URL +
            "?latitude=$latitude" +
            "&longitude=$longitude" +
            "&hourly=$hourly" +
            "&forecast_hours=$FORECAST_HOURS" +
            "&timeformat=unixtime" +
            "&timezone=GMT" +
            "&temperature_unit=celsius" +
            "&wind_speed_unit=ms" +
            "&precipitation_unit=mm" +
            "&cell_selection=land"
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
            "unexpected weather Content-Type"
        }
    }

    private fun validateResponseBytes(
        bytes: ByteArray
    ) {
        require(bytes.isNotEmpty()) {
            "empty weather response"
        }

        require(
            bytes.size.toLong() <=
                MAX_RESPONSE_BYTES
        ) {
            "weather response too large"
        }

        val decoder =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(
                    CodingErrorAction.REPORT
                )
                .onUnmappableCharacter(
                    CodingErrorAction.REPORT
                )

        val text =
            decoder.decode(
                ByteBuffer.wrap(bytes)
            ).toString()

        val trimmed =
            text.trim()

        require(
            trimmed.startsWith("{") &&
                trimmed.endsWith("}")
        ) {
            "weather response is not JSON object"
        }

        val utcOffsetZero =
            Regex(
                "\"utc_offset_seconds\"\\s*:\\s*0(?:\\s*[,}])"
            )

        require(
            utcOffsetZero.containsMatchIn(text)
        ) {
            "weather response UTC offset is not zero"
        }

        val requiredTokens =
            listOf(
                "\"latitude\"",
                "\"longitude\"",
                "\"utc_offset_seconds\"",
                "\"hourly\"",
                "\"time\""
            ) +
                hourlyFields.map {
                    "\"$it\""
                }

        for (token in requiredTokens) {
            require(
                text.contains(token)
            ) {
                "weather response missing field: $token"
            }
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
