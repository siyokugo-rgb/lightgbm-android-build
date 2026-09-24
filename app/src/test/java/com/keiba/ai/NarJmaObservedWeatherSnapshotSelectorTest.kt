package com.keiba.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import java.util.UUID

class NarJmaObservedWeatherSnapshotSelectorTest {

    private val product =
        NarJmaObservedWeatherProduct.PRE1H00_RCT

    private val shiftJis =
        Charset.forName("Shift_JIS")

    @Test
    fun selectsSingleEligibleSnapshot() {
        withRoot { root ->
            val saved =
                saveSnapshot(
                    root = root,
                    product = product,
                    downloadedAt = 1_788_001_100_000L,
                    serverDate = 1_788_001_050_000L,
                    stationId = "44166"
                )

            val predictionAsOf =
                1_788_001_200_000L

            val selection =
                NarJmaObservedWeatherSnapshotSelector
                    .selectLatestFromRoot(
                        root = root,
                        product = product,
                        predictionAsOfEpochMillis =
                            predictionAsOf
                    )

            assertNotNull(selection)
            assertEquals(
                saved.directory.canonicalFile,
                selection!!.directory
            )
            assertEquals(
                saved.pitEvidenceAtEpochMillis,
                selection.pitEvidenceAtEpochMillis
            )
            assertEquals(
                predictionAsOf -
                    saved.pitEvidenceAtEpochMillis,
                selection.ageMillis
            )
        }
    }

    @Test
    fun selectsLatestPitEvidenceAmongMatches() {
        withRoot { root ->
            saveSnapshot(
                root = root,
                product = product,
                downloadedAt = 1_788_001_100_000L,
                serverDate = 1_788_001_100_000L,
                stationId = "44166",
                currentValue = "0.0"
            )

            val newer =
                saveSnapshot(
                    root = root,
                    product = product,
                    downloadedAt = 1_788_001_150_000L,
                    serverDate = 1_788_001_160_000L,
                    stationId = "44166",
                    currentValue = "1.0"
                )

            val selection =
                NarJmaObservedWeatherSnapshotSelector
                    .selectLatestFromRoot(
                        root = root,
                        product = product,
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )

            assertEquals(
                newer.directory.canonicalFile,
                selection!!.directory
            )
            assertEquals(
                1_788_001_160_000L,
                selection.pitEvidenceAtEpochMillis
            )
        }
    }

    @Test
    fun pitEvidenceTieUsesLatestDownloadedAt() {
        withRoot { root ->
            saveSnapshot(
                root = root,
                product = product,
                downloadedAt = 1_788_001_100_000L,
                serverDate = 1_788_001_150_000L,
                stationId = "44166",
                currentValue = "0.0"
            )

            val laterDownload =
                saveSnapshot(
                    root = root,
                    product = product,
                    downloadedAt = 1_788_001_140_000L,
                    serverDate = 1_788_001_150_000L,
                    stationId = "44166",
                    currentValue = "2.0"
                )

            val selection =
                NarJmaObservedWeatherSnapshotSelector
                    .selectLatestFromRoot(
                        root = root,
                        product = product,
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )

            assertEquals(
                laterDownload.directory.canonicalFile,
                selection!!.directory
            )
        }
    }

    @Test
    fun missingRootReturnsNull() {
        val missing =
            File(
                Files.createTempDirectory(
                    "obs-missing-parent"
                ).toFile(),
                "does-not-exist"
            )

        assertNull(
            NarJmaObservedWeatherSnapshotSelector
                .selectLatestFromRoot(
                    root = missing,
                    product = product,
                    predictionAsOfEpochMillis =
                        1_788_001_200_000L
                )
        )
    }

    @Test
    fun missingProductDirectoryReturnsNull() {
        withRoot { root ->
            assertNull(
                NarJmaObservedWeatherSnapshotSelector
                    .selectLatestFromRoot(
                        root = root,
                        product = product,
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )
            )
        }
    }

    @Test
    fun noEligibleSnapshotReturnsNull() {
        withRoot { root ->
            saveSnapshot(
                root = root,
                product = product,
                downloadedAt = 1_788_001_300_000L,
                serverDate = null,
                stationId = "44166"
            )

            assertNull(
                NarJmaObservedWeatherSnapshotSelector
                    .selectLatestFromRoot(
                        root = root,
                        product = product,
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )
            )
        }
    }

    @Test
    fun ageMillisUsesPitEvidence() {
        withRoot { root ->
            saveSnapshot(
                root = root,
                product = product,
                downloadedAt = 1_788_001_100_000L,
                serverDate = 1_788_001_150_000L,
                stationId = "44166"
            )

            val selection =
                NarJmaObservedWeatherSnapshotSelector
                    .selectLatestFromRoot(
                        root = root,
                        product = product,
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )

            assertEquals(
                50_000L,
                selection!!.ageMillis
            )
        }
    }

    @Test
    fun futureDownloadedSnapshotBodyIsNotOpened() {
        withRoot { root ->
            val past =
                saveSnapshot(
                    root = root,
                    product = product,
                    downloadedAt = 1_788_001_100_000L,
                    serverDate = null,
                    stationId = "44166"
                )

            val future =
                saveSnapshot(
                    root = root,
                    product = product,
                    downloadedAt = 1_788_001_300_000L,
                    serverDate = null,
                    stationId = "44166",
                    currentValue = "9.0"
                )

            File(
                future.directory,
                "observed.csv"
            ).writeText(
                "CORRUPTED_FUTURE_BODY"
            )

            assertTrue(
                File(
                    future.directory,
                    "observed.csv"
                ).readText()
                    .startsWith("CORRUPTED")
            )

            val selection =
                NarJmaObservedWeatherSnapshotSelector
                    .selectLatestFromRoot(
                        root = root,
                        product = product,
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )

            assertEquals(
                past.directory.canonicalFile,
                selection!!.directory
            )
        }
    }

    @Test
    fun futurePitEvidenceFromServerDateDoesNotOpenRawBody() {
        withRoot { root ->
            val past =
                saveSnapshot(
                    root = root,
                    product = product,
                    downloadedAt = 1_788_001_100_000L,
                    serverDate = null,
                    stationId = "44166"
                )

            val futurePit =
                saveSnapshot(
                    root = root,
                    product = product,
                    downloadedAt = 1_788_001_180_000L,
                    serverDate = 1_788_001_250_000L,
                    stationId = "44166",
                    currentValue = "3.0"
                )

            File(
                futurePit.directory,
                "observed.csv"
            ).writeText(
                "CORRUPTED_SERVER_DATE_FUTURE"
            )

            val selection =
                NarJmaObservedWeatherSnapshotSelector
                    .selectLatestFromRoot(
                        root = root,
                        product = product,
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )

            assertEquals(
                past.directory.canonicalFile,
                selection!!.directory
            )
        }
    }

    @Test
    fun futureDayDirectoryIsSkippedWithoutOpeningBody() {
        withRoot { root ->
            val past =
                saveSnapshot(
                    root = root,
                    product = product,
                    downloadedAt = 1_788_001_100_000L,
                    serverDate = null,
                    stationId = "44166"
                )

            val productDir =
                File(
                    root,
                    product.productId
                )

            val futureDay =
                File(productDir, "20990101")

            require(futureDay.mkdirs())

            val futureSnapshot =
                File(
                    futureDay,
                    "9999999999999-" +
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                )

            require(futureSnapshot.mkdir())

            File(
                futureSnapshot,
                "observed.csv"
            ).writeText(
                "SHOULD_NOT_BE_OPENED"
            )

            val selection =
                NarJmaObservedWeatherSnapshotSelector
                    .selectLatestFromRoot(
                        root = root,
                        product = product,
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )

            assertEquals(
                past.directory.canonicalFile,
                selection!!.directory
            )
        }
    }

    @Test
    fun corruptEligibleRawFailsClosed() {
        withRoot { root ->
            val saved =
                saveSnapshot(
                    root = root,
                    product = product,
                    downloadedAt = 1_788_001_100_000L,
                    serverDate = null,
                    stationId = "44166"
                )

            File(
                saved.directory,
                "observed.csv"
            ).writeText(
                "CORRUPT"
            )

            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarJmaObservedWeatherSnapshotSelector
                    .selectLatestFromRoot(
                        root = root,
                        product = product,
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )
            }
        }
    }

    @Test
    fun wrongProductManifestIsRejected() {
        withRoot { root ->
            val saved =
                saveSnapshot(
                    root = root,
                    product = product,
                    downloadedAt = 1_788_001_100_000L,
                    serverDate = null,
                    stationId = "44166"
                )

            rewriteManifestKey(
                saved.directory,
                "product_id",
                "pre3h00_rct"
            )

            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarJmaObservedWeatherSnapshotSelector
                    .selectLatestFromRoot(
                        root = root,
                        product = product,
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )
            }
        }
    }

    @Test
    fun wrongSourceUrlIsRejected() {
        withRoot { root ->
            val saved =
                saveSnapshot(
                    root = root,
                    product = product,
                    downloadedAt = 1_788_001_100_000L,
                    serverDate = null,
                    stationId = "44166"
                )

            rewriteManifestKey(
                saved.directory,
                "source_url",
                NarJmaObservedWeatherProduct
                    .PRE3H00_RCT
                    .canonicalUrl()
            )

            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarJmaObservedWeatherSnapshotSelector
                    .selectLatestFromRoot(
                        root = root,
                        product = product,
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )
            }
        }
    }

    @Test
    fun downloadedAtPathMismatchIsRejected() {
        withRoot { root ->
            val saved =
                saveSnapshot(
                    root = root,
                    product = product,
                    downloadedAt = 1_788_001_100_000L,
                    serverDate = null,
                    stationId = "44166"
                )

            rewriteManifestKey(
                saved.directory,
                "downloaded_at_epoch_millis",
                "1788001100001"
            )

            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarJmaObservedWeatherSnapshotSelector
                    .selectLatestFromRoot(
                        root = root,
                        product = product,
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )
            }
        }
    }

    @Test
    fun pitEvidenceRecomputationMismatchIsRejected() {
        withRoot { root ->
            val saved =
                saveSnapshot(
                    root = root,
                    product = product,
                    downloadedAt = 1_788_001_100_000L,
                    serverDate = 1_788_001_120_000L,
                    stationId = "44166"
                )

            rewriteManifestKey(
                saved.directory,
                "pit_evidence_at_epoch_millis",
                "1"
            )

            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarJmaObservedWeatherSnapshotSelector
                    .selectLatestFromRoot(
                        root = root,
                        product = product,
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )
            }
        }
    }

    @Test
    fun duplicateManifestKeyIsRejected() {
        withRoot { root ->
            val saved =
                saveSnapshot(
                    root = root,
                    product = product,
                    downloadedAt = 1_788_001_100_000L,
                    serverDate = null,
                    stationId = "44166"
                )

            File(
                saved.directory,
                "manifest.txt"
            ).appendText(
                "product_id=pre1h00_rct\n"
            )

            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarJmaObservedWeatherSnapshotSelector
                    .selectLatestFromRoot(
                        root = root,
                        product = product,
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )
            }
        }
    }

    @Test
    fun unknownManifestKeyIsRejected() {
        withRoot { root ->
            val saved =
                saveSnapshot(
                    root = root,
                    product = product,
                    downloadedAt = 1_788_001_100_000L,
                    serverDate = null,
                    stationId = "44166"
                )

            File(
                saved.directory,
                "manifest.txt"
            ).appendText(
                "station_id=44166\n"
            )

            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarJmaObservedWeatherSnapshotSelector
                    .selectLatestFromRoot(
                        root = root,
                        product = product,
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )
            }
        }
    }

    @Test
    fun stagingDirectoryIsIgnored() {
        withRoot { root ->
            val saved =
                saveSnapshot(
                    root = root,
                    product = product,
                    downloadedAt = 1_788_001_100_000L,
                    serverDate = null,
                    stationId = "44166"
                )

            val staging =
                File(
                    saved.directory.parentFile,
                    ".1788001100000.tmp-" +
                        UUID.randomUUID().toString()
                )

            require(staging.mkdir())

            val selection =
                NarJmaObservedWeatherSnapshotSelector
                    .selectLatestFromRoot(
                        root = root,
                        product = product,
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )

            assertEquals(
                saved.directory.canonicalFile,
                selection!!.directory
            )
        }
    }

    @Test
    fun malformedDayDirectoryIsRejected() {
        withRoot { root ->
            saveSnapshot(
                root = root,
                product = product,
                downloadedAt = 1_788_001_100_000L,
                serverDate = null,
                stationId = "44166"
            )

            val productDir =
                File(
                    root,
                    product.productId
                )

            require(
                File(
                    productDir,
                    "notaday"
                ).mkdir()
            )

            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarJmaObservedWeatherSnapshotSelector
                    .selectLatestFromRoot(
                        root = root,
                        product = product,
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )
            }
        }
    }

    @Test
    fun unexpectedFileInDayDirectoryIsRejected() {
        withRoot { root ->
            val saved =
                saveSnapshot(
                    root = root,
                    product = product,
                    downloadedAt = 1_788_001_100_000L,
                    serverDate = null,
                    stationId = "44166"
                )

            File(
                saved.directory.parentFile,
                "extra.txt"
            ).writeText(
                "nope"
            )

            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarJmaObservedWeatherSnapshotSelector
                    .selectLatestFromRoot(
                        root = root,
                        product = product,
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )
            }
        }
    }

    @Test
    fun ambiguousTieFailsClosed() {
        withRoot { root ->
            saveSnapshot(
                root = root,
                product = product,
                downloadedAt = 1_788_001_100_000L,
                serverDate = 1_788_001_100_000L,
                stationId = "44166",
                currentValue = "0.0"
            )

            saveSnapshot(
                root = root,
                product = product,
                downloadedAt = 1_788_001_100_000L,
                serverDate = 1_788_001_100_000L,
                stationId = "44166",
                currentValue = "1.5"
            )

            assertThrows(
                IllegalStateException::class.java
            ) {
                NarJmaObservedWeatherSnapshotSelector
                    .selectLatestFromRoot(
                        root = root,
                        product = product,
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )
            }
        }
    }

    private fun withRoot(
        block: (File) -> Unit
    ) {
        val root =
            Files.createTempDirectory(
                "obs-selector"
            ).toFile()

        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun saveSnapshot(
        root: File,
        product: NarJmaObservedWeatherProduct,
        downloadedAt: Long,
        serverDate: Long?,
        stationId: String,
        currentValue: String = "0.0",
        lastModified: Long? = null
    ): NarJmaObservedWeatherSnapshotStore.SaveResult {
        val data =
            NarJmaObservedWeatherDownloader
                .ObservedCsvResponse(
                    productId =
                        product.productId,
                    requestUrl =
                        product.canonicalUrl(),
                    responseBytes =
                        precipCsvBytes(
                            stationId =
                                stationId,
                            currentValue =
                                currentValue
                        ),
                    contentType =
                        "text/csv",
                    downloadedAtEpochMillis =
                        downloadedAt,
                    serverDateEpochMillis =
                        serverDate,
                    lastModifiedEpochMillis =
                        lastModified
                )

        return NarJmaObservedWeatherSnapshotStore
            .saveToRoot(
                root = root,
                data = data
            )
    }

    private fun rewriteManifestKey(
        directory: File,
        key: String,
        value: String
    ) {
        val manifest =
            File(
                directory,
                "manifest.txt"
            )

        val text =
            manifest.readText(
                Charsets.UTF_8
            )

        manifest.writeText(
            text.replace(
                Regex(
                    "^$key=.*$",
                    RegexOption.MULTILINE
                ),
                "$key=$value"
            ),
            Charsets.UTF_8
        )
    }

    private fun precipCsvBytes(
        stationId: String,
        currentValue: String
    ): ByteArray {
        val header =
            "観測所番号,都道府県,地点,国際地点番号,現在時刻(年),現在時刻(月),現在時刻(日),現在時刻(時),現在時刻(分),現在値(mm),現在値の品質情報,22日の最大値(mm),22日の最大値の品質情報,22日の最大値起時（時）(まで),22日の最大値起時（分）(まで),22日の最大値起時(まで)の品質情報,極値更新,10年未満での極値更新,21日までの観測史上1位の値(mm),21日までの観測史上1位の値の品質情報,21日までの観測史上1位の値の年,21日までの観測史上1位の値の月,21日までの観測史上1位の値の日,21日までの9月の1位の値(mm),21日までの9月の1位の値の品質情報,21日までの9月の1位の値の年,21日までの9月の1位の値の月,21日までの9月の1位の値の日,統計開始年"

        val cells =
            MutableList(29) { "" }

        cells[0] = stationId
        cells[1] = "東京都"
        cells[2] = "羽田"
        cells[4] = "2026"
        cells[5] = "8"
        cells[6] = "29"
        cells[7] = "09"
        cells[8] = "40"
        cells[9] = currentValue
        cells[10] = "8"

        return (
            header +
                "\r\n" +
                cells.joinToString(",")
        ).toByteArray(shiftJis)
    }
}
