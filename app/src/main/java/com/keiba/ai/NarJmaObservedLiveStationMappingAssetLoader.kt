package com.keiba.ai

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Loads the packaged observed live station mapping from Android assets.
 *
 * Source of Truth remains
 * `config/nar-jma-observed-live-station-mapping.json`.
 */
object NarJmaObservedLiveStationMappingAssetLoader {

    const val ASSET_NAME =
        "nar-jma-observed-live-station-mapping.json"

    fun load(
        context: Context
    ): NarJmaObservedLiveStationMapping {
        val input =
            try {
                context.assets.open(
                    ASSET_NAME
                )
            } catch (error: IOException) {
                throw IllegalStateException(
                    "missing observed live station mapping asset: $ASSET_NAME",
                    error
                )
            }

        input.use { stream ->
            val bytes =
                readBounded(
                    stream,
                    NarJmaObservedLiveStationMappingParser
                        .MAX_CONFIG_BYTES
                )

            return NarJmaObservedLiveStationMappingParser
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
            "invalid observed mapping asset max bytes"
        }

        require(maxBytes <= Int.MAX_VALUE.toLong()) {
            "observed mapping asset max bytes too large"
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
                    "observed mapping asset too large"
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
            "empty observed mapping asset"
        }

        return buffer.toByteArray()
    }
}
