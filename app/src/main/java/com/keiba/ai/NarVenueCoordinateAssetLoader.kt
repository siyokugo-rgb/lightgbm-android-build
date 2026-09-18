package com.keiba.ai

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Loads the packaged venue coordinate config from Android assets.
 *
 * Source of Truth remains
 * `config/nar-v3-venue-coordinates.json` in the repository.
 * The APK receives a Gradle-copied package of that single file —
 * coordinates are never hard-coded here.
 *
 * No filesystem path access beyond [Context.assets].
 */
object NarVenueCoordinateAssetLoader {

    const val ASSET_NAME =
        "nar-v3-venue-coordinates.json"

    fun load(
        context: Context
    ): NarVenueCoordinateConfig {
        val input =
            try {
                context.assets.open(
                    ASSET_NAME
                )
            } catch (error: IOException) {
                throw IllegalStateException(
                    "missing venue coordinate asset: $ASSET_NAME",
                    error
                )
            }

        input.use { stream ->
            val bytes =
                readBounded(
                    stream,
                    NarVenueCoordinateConfigParser
                        .MAX_CONFIG_BYTES
                )

            return NarVenueCoordinateConfigParser
                .parse(
                    bytes
                )
        }
    }

    private fun readBounded(
        input: InputStream,
        maxBytes: Long
    ): ByteArray {
        require(maxBytes > 0L) {
            "invalid venue coordinate asset max bytes"
        }

        require(maxBytes <= Int.MAX_VALUE.toLong()) {
            "venue coordinate asset max bytes too large"
        }

        val limit =
            maxBytes.toInt()

        val buffer =
            ByteArrayOutputStream(
                minOf(
                    limit,
                    8 * 1024
                )
            )

        val chunk =
            ByteArray(4 * 1024)

        var total = 0

        while (true) {
            val remaining =
                limit - total

            if (remaining <= 0) {
                val overflow =
                    input.read()

                require(overflow < 0) {
                    "venue coordinate asset too large"
                }

                break
            }

            val read =
                input.read(
                    chunk,
                    0,
                    minOf(
                        chunk.size,
                        remaining
                    )
                )

            if (read < 0) {
                break
            }

            buffer.write(
                chunk,
                0,
                read
            )

            total += read
        }

        require(total > 0) {
            "empty venue coordinate asset"
        }

        return buffer.toByteArray()
    }
}
