package com.keiba.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL

class NarJmaObservedWeatherDownloaderTest {

    @Test
    fun allowlistedProductsUseHttpsJmaHost() {
        for (product in NarJmaObservedWeatherProduct.entries) {
            val url =
                URL(
                    product.canonicalUrl()
                )

            assertEquals(
                "https",
                url.protocol
            )

            assertEquals(
                "www.data.jma.go.jp",
                url.host
            )

            assertTrue(
                url.path.endsWith(
                    ".csv"
                )
            )

            assertEquals(
                product.productId,
                NarJmaObservedWeatherProduct
                    .fromProductId(
                        product.productId
                    )
                    .productId
            )
        }

        assertEquals(
            9,
            NarJmaObservedWeatherProduct
                .entries
                .size
        )
    }

    @Test
    fun unknownProductIdIsRejected() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarJmaObservedWeatherProduct
                .fromProductId(
                    "not_a_real_product"
                )
        }
    }

    @Test
    fun expectedCsvContentTypeIsAccepted() {
        NarJmaObservedWeatherDownloader
            .validateContentTypeForTest(
                "text/csv"
            )

        NarJmaObservedWeatherDownloader
            .validateContentTypeForTest(
                "text/csv; charset=Shift_JIS"
            )
    }

    @Test
    fun wrongContentTypeIsRejected() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarJmaObservedWeatherDownloader
                .validateContentTypeForTest(
                    "text/html"
                )
        }

        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarJmaObservedWeatherDownloader
                .validateContentTypeForTest(
                    "application/json"
                )
        }
    }

    @Test
    fun emptyBodyIsRejected() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarJmaObservedWeatherDownloader
                .validateResponseBytes(
                    ByteArray(0)
                )
        }
    }

    @Test
    fun htmlLookingBodyIsRejected() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarJmaObservedWeatherDownloader
                .validateResponseBytes(
                    "<!DOCTYPE html><html></html>"
                        .toByteArray(
                            Charsets.US_ASCII
                        )
                )
        }
    }

    @Test
    fun rawBytesAreNotMutatedByValidation() {
        val bytes =
            sampleCsvBytes()

        val copy =
            bytes.copyOf()

        NarJmaObservedWeatherDownloader
            .validateResponseBytes(
                bytes
            )

        assertArrayEquals(
            copy,
            bytes
        )
    }

    @Test
    fun streamingOversizedBodyIsRejected() {
        val oversized =
            ByteArray(
                (
                    NarJmaObservedWeatherDownloader
                        .MAX_RESPONSE_BYTES + 1L
                ).toInt()
            ) { 0x41 }

        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarJmaObservedWeatherDownloader
                .readLimitedForTest(
                    oversized
                )
        }
    }

    @Test
    fun bodyAtExactLimitIsAcceptedByStreamGuard() {
        val exact =
            ByteArray(
                NarJmaObservedWeatherDownloader
                    .MAX_RESPONSE_BYTES
                    .toInt()
            ) { 0x41 }

        val read =
            NarJmaObservedWeatherDownloader
                .readLimitedForTest(
                    exact
                )

        assertEquals(
            exact.size,
            read.size
        )
    }

    @Test
    fun pitEvidenceUsesDownloadWhenServerDateAbsent() {
        assertEquals(
            1_788_001_100_000L,
            NarJmaObservedWeatherDownloader
                .computePitEvidenceAtEpochMillis(
                    downloadedAtEpochMillis =
                        1_788_001_100_000L,
                    serverDateEpochMillis =
                        null
                )
        )
    }

    @Test
    fun pitEvidenceUsesMaxOfDownloadAndServerDate() {
        assertEquals(
            1_788_001_120_000L,
            NarJmaObservedWeatherDownloader
                .computePitEvidenceAtEpochMillis(
                    downloadedAtEpochMillis =
                        1_788_001_100_000L,
                    serverDateEpochMillis =
                        1_788_001_120_000L
                )
        )

        assertEquals(
            1_788_001_200_000L,
            NarJmaObservedWeatherDownloader
                .computePitEvidenceAtEpochMillis(
                    downloadedAtEpochMillis =
                        1_788_001_200_000L,
                    serverDateEpochMillis =
                        1_788_001_120_000L
                )
        )
    }

    @Test
    fun maxResponseBytesHasDocumentedMargin() {
        // Live probe max documented CSV ~147 KiB (2026-09-22).
        assertTrue(
            NarJmaObservedWeatherDownloader
                .MAX_RESPONSE_BYTES >=
                147L * 1024L
        )

        assertTrue(
            NarJmaObservedWeatherDownloader
                .MAX_RESPONSE_BYTES <=
                1024L * 1024L
        )
    }

    private fun sampleCsvBytes(): ByteArray =
        (
            "観測所番号,地点,現在値(mm),現在値の品質情報\n" +
                "44166,羽田,0,8\n"
        ).toByteArray(
            Charsets.UTF_8
        )
}
