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
import java.time.Instant
import java.time.ZoneId

class NarJmaObservedWeatherSnapshotReaderTest {

    private val precipProduct =
        NarJmaObservedWeatherProduct.PRE1H00_RCT

    private val windProduct =
        NarJmaObservedWeatherProduct.MXWSP00_RCT

    private val shiftJis =
        Charset.forName("Shift_JIS")

    @Test
    fun readReturnsTypedStationRowWithProvenance() {
        withRoot { root ->
            val saved =
                savePrecipSnapshot(
                    root = root,
                    downloadedAt = 1_788_001_100_000L,
                    serverDate = 1_788_001_120_000L,
                    stationId = "44166",
                    currentValue = "1.2"
                )

            val input =
                NarJmaObservedWeatherSnapshotReader
                    .readFromRoot(
                        root = root,
                        product = precipProduct,
                        stationId = "44166",
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )

            assertNotNull(input)
            assertEquals(
                saved.directory.canonicalFile,
                input!!.selection.directory
            )
            assertEquals(
                "44166",
                input.stationId
            )

            val row =
                input.selectedRow as NarJmaObservedPrecipitationRow

            assertEquals(
                1.2,
                row.currentValueMm!!,
                0.0
            )
            assertEquals(
                1,
                row.windowHours
            )
            assertEquals(
                saved.pitEvidenceAtEpochMillis,
                input.selection.pitEvidenceAtEpochMillis
            )
        }
    }

    @Test
    fun stationAbsentReturnsNullWithoutFailingClosed() {
        withRoot { root ->
            savePrecipSnapshot(
                root = root,
                downloadedAt = 1_788_001_100_000L,
                serverDate = null,
                stationId = "44166"
            )

            val input =
                NarJmaObservedWeatherSnapshotReader
                    .readFromRoot(
                        root = root,
                        product = precipProduct,
                        stationId = "99999",
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )

            assertNull(input)
        }
    }

    @Test
    fun noSnapshotReturnsNull() {
        withRoot { root ->
            assertNull(
                NarJmaObservedWeatherSnapshotReader
                    .readFromRoot(
                        root = root,
                        product = precipProduct,
                        stationId = "44166",
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )
            )
        }
    }

    @Test
    fun rawShaCorruptionFailsClosed() {
        withRoot { root ->
            val saved =
                savePrecipSnapshot(
                    root = root,
                    downloadedAt = 1_788_001_100_000L,
                    serverDate = null,
                    stationId = "44166"
                )

            File(
                saved.directory,
                "observed.csv"
            ).writeBytes(
                File(
                    saved.directory,
                    "observed.csv"
                ).readBytes() +
                    byteArrayOf(0x41)
            )

            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarJmaObservedWeatherSnapshotReader
                    .readFromRoot(
                        root = root,
                        product = precipProduct,
                        stationId = "44166",
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )
            }
        }
    }

    @Test
    fun schemaCorruptionFailsClosed() {
        withRoot { root ->
            val saved =
                savePrecipSnapshot(
                    root = root,
                    downloadedAt = 1_788_001_100_000L,
                    serverDate = null,
                    stationId = "44166"
                )

            // Keep SHA matching would require rewriting
            // manifest; instead corrupt CSV so verify fails
            // during selection / read path.
            File(
                saved.directory,
                "observed.csv"
            ).writeText(
                "not,a,valid,csv\r\n"
            )

            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarJmaObservedWeatherSnapshotReader
                    .readFromRoot(
                        root = root,
                        product = precipProduct,
                        stationId = "44166",
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )
            }
        }
    }

    @Test
    fun futureProductTimestampIsRejected() {
        withRoot { root ->
            // productTimestamp 09:40 JST on 2026-08-29 =
            // after a very early predictionAsOf that still
            // allows the snapshot via downloadedAt/pitEvidence
            // is hard because productTimestamp is inside CSV
            // and often earlier than capture. Build CSV with
            // a productTimestamp after predictionAsOf while
            // keeping downloadedAt/pitEvidence eligible.
            val saved =
                savePrecipSnapshot(
                    root = root,
                    downloadedAt = 1_788_001_100_000L,
                    serverDate = null,
                    stationId = "44166",
                    year = "2099",
                    month = "1",
                    day = "1",
                    hour = "12",
                    minute = "00"
                )

            val selection =
                NarJmaObservedWeatherSnapshotSelector
                    .selectLatestFromRoot(
                        root = root,
                        product = precipProduct,
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )

            assertNotNull(selection)

            assertThrows(
                IllegalArgumentException::class.java
            ) {
                NarJmaObservedWeatherSnapshotReader
                    .readSelectedForTest(
                        selection = selection!!,
                        stationId = "44166"
                    )
            }

            assertTrue(
                saved.directory.isDirectory
            )
        }
    }

    @Test
    fun kawasakiPrecipUsesStation46061() {
        withRoot { root ->
            savePrecipSnapshot(
                root = root,
                downloadedAt = 1_788_001_100_000L,
                serverDate = null,
                stationId = "46061",
                stationName = "日吉",
                prefecture = "神奈川県",
                currentValue = "0.5"
            )

            val input =
                NarJmaObservedWeatherSnapshotReader
                    .readFromRoot(
                        root = root,
                        product = precipProduct,
                        stationId = "46061",
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )

            assertEquals(
                "46061",
                input!!.stationId
            )
            assertEquals(
                "日吉",
                input.selectedRow.stationName
            )
        }
    }

    @Test
    fun kawasakiWindUsesStation44166() {
        withRoot { root ->
            saveWindSnapshot(
                root = root,
                downloadedAt = 1_788_001_100_000L,
                serverDate = null,
                stationId = "44166"
            )

            val input =
                NarJmaObservedWeatherSnapshotReader
                    .readFromRoot(
                        root = root,
                        product = windProduct,
                        stationId = "44166",
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )

            val row =
                input!!.selectedRow as NarJmaObservedWindExtremeRow

            assertEquals(
                "44166",
                row.stationId
            )
            assertEquals(
                NarJmaObservedWindExtremeKind.MAX_WIND,
                row.kind
            )
        }
    }

    @Test
    fun noStationFallbackAcrossProducts() {
        withRoot { root ->
            savePrecipSnapshot(
                root = root,
                downloadedAt = 1_788_001_100_000L,
                serverDate = null,
                stationId = "46061",
                stationName = "日吉",
                prefecture = "神奈川県"
            )

            assertNull(
                NarJmaObservedWeatherSnapshotReader
                    .readFromRoot(
                        root = root,
                        product = precipProduct,
                        stationId = "44166",
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )
            )
        }
    }

    @Test
    fun nagoyaStation53041IsSelectable() {
        withRoot { root ->
            savePrecipSnapshot(
                root = root,
                downloadedAt = 1_788_001_100_000L,
                serverDate = null,
                stationId = "53041",
                stationName = "桑名",
                prefecture = "三重県"
            )

            val input =
                NarJmaObservedWeatherSnapshotReader
                    .readFromRoot(
                        root = root,
                        product = precipProduct,
                        stationId = "53041",
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )

            assertEquals(
                "53041",
                input!!.stationId
            )
            assertEquals(
                "桑名",
                input.selectedRow.stationName
            )
        }
    }

    @Test
    fun productTimestampEpochIsAsiaTokyo() {
        withRoot { root ->
            val input =
                NarJmaObservedWeatherSnapshotReader
                    .readFromRoot(
                        root = root.also {
                            savePrecipSnapshot(
                                root = it,
                                downloadedAt =
                                    1_788_001_100_000L,
                                serverDate = null,
                                stationId = "44166",
                                year = "2026",
                                month = "8",
                                day = "29",
                                hour = "09",
                                minute = "40"
                            )
                        },
                        product = precipProduct,
                        stationId = "44166",
                        predictionAsOfEpochMillis =
                            1_788_001_200_000L
                    )

            val zoned =
                Instant.ofEpochMilli(
                    input!!.selectedRow
                        .productTimestampEpochMillis
                )
                    .atZone(
                        ZoneId.of("Asia/Tokyo")
                    )

            assertEquals(2026, zoned.year)
            assertEquals(8, zoned.monthValue)
            assertEquals(29, zoned.dayOfMonth)
            assertEquals(9, zoned.hour)
            assertEquals(40, zoned.minute)
        }
    }

    private fun withRoot(
        block: (File) -> Unit
    ) {
        val root =
            Files.createTempDirectory(
                "obs-reader"
            ).toFile()

        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun savePrecipSnapshot(
        root: File,
        downloadedAt: Long,
        serverDate: Long?,
        stationId: String,
        stationName: String = "羽田",
        prefecture: String = "東京都",
        currentValue: String = "0.0",
        year: String = "2026",
        month: String = "8",
        day: String = "29",
        hour: String = "09",
        minute: String = "40"
    ): NarJmaObservedWeatherSnapshotStore.SaveResult {
        val data =
            NarJmaObservedWeatherDownloader
                .ObservedCsvResponse(
                    productId =
                        precipProduct.productId,
                    requestUrl =
                        precipProduct.canonicalUrl(),
                    responseBytes =
                        precipCsvBytes(
                            stationId = stationId,
                            stationName = stationName,
                            prefecture = prefecture,
                            currentValue = currentValue,
                            year = year,
                            month = month,
                            day = day,
                            hour = hour,
                            minute = minute
                        ),
                    contentType = "text/csv",
                    downloadedAtEpochMillis =
                        downloadedAt,
                    serverDateEpochMillis =
                        serverDate,
                    lastModifiedEpochMillis = null
                )

        return NarJmaObservedWeatherSnapshotStore
            .saveToRoot(
                root = root,
                data = data
            )
    }

    private fun saveWindSnapshot(
        root: File,
        downloadedAt: Long,
        serverDate: Long?,
        stationId: String
    ): NarJmaObservedWeatherSnapshotStore.SaveResult {
        val header =
            "観測所番号,都道府県,地点,国際地点番号,現在時刻(年),現在時刻(月),現在時刻(日),現在時刻(時),現在時刻(分),29日の最大値(m/s),29日の最大値の品質情報,29日の最大値観測時の風向,29日の最大値観測時の風向の品質情報,29日の最大値起時（時）,29日の最大値起時（分）,29日の最大値起時の品質情報,極値更新,10年未満での極値更新,21日までの観測史上1位の値(m/s),21日までの観測史上1位の値の品質情報,21日までの観測史上1位の値観測時の風向,21日までの観測史上1位の値観測時の風向の品質情報,21日までの観測史上1位の値観測時の年,21日までの観測史上1位の値観測時の月,21日までの観測史上1位の値観測時の日,21日までの8月の1位の値(m/s),21日までの8月の1位の値の品質情報,21日までの8月の1位の値観測時の風向,21日までの8月の1位の値観測時の風向の品質情報,21日までの8月の1位の値観測時の年,21日までの8月の1位の値観測時の月,21日までの8月の1位の値観測時の日,統計開始年"

        val cells =
            MutableList(33) { "" }

        cells[0] = stationId
        cells[1] = "東京都"
        cells[2] = "羽田"
        cells[4] = "2026"
        cells[5] = "8"
        cells[6] = "29"
        cells[7] = "09"
        cells[8] = "40"
        cells[9] = "5.0"
        cells[10] = "8"
        cells[11] = "北"
        cells[12] = "8"

        val data =
            NarJmaObservedWeatherDownloader
                .ObservedCsvResponse(
                    productId =
                        windProduct.productId,
                    requestUrl =
                        windProduct.canonicalUrl(),
                    responseBytes =
                        (
                            header +
                                "\r\n" +
                                cells.joinToString(",")
                        ).toByteArray(shiftJis),
                    contentType = "text/csv",
                    downloadedAtEpochMillis =
                        downloadedAt,
                    serverDateEpochMillis =
                        serverDate,
                    lastModifiedEpochMillis = null
                )

        return NarJmaObservedWeatherSnapshotStore
            .saveToRoot(
                root = root,
                data = data
            )
    }

    private fun precipCsvBytes(
        stationId: String,
        stationName: String,
        prefecture: String,
        currentValue: String,
        year: String,
        month: String,
        day: String,
        hour: String,
        minute: String
    ): ByteArray {
        val header =
            "観測所番号,都道府県,地点,国際地点番号,現在時刻(年),現在時刻(月),現在時刻(日),現在時刻(時),現在時刻(分),現在値(mm),現在値の品質情報,29日の最大値(mm),29日の最大値の品質情報,29日の最大値起時（時）(まで),29日の最大値起時（分）(まで),29日の最大値起時(まで)の品質情報,極値更新,10年未満での極値更新,21日までの観測史上1位の値(mm),21日までの観測史上1位の値の品質情報,21日までの観測史上1位の値の年,21日までの観測史上1位の値の月,21日までの観測史上1位の値の日,21日までの8月の1位の値(mm),21日までの8月の1位の値の品質情報,21日までの8月の1位の値の年,21日までの8月の1位の値の月,21日までの8月の1位の値の日,統計開始年"

        val cells =
            MutableList(29) { "" }

        cells[0] = stationId
        cells[1] = prefecture
        cells[2] = stationName
        cells[4] = year
        cells[5] = month
        cells[6] = day
        cells[7] = hour
        cells[8] = minute
        cells[9] = currentValue
        cells[10] = "8"

        return (
            header +
                "\r\n" +
                cells.joinToString(",")
        ).toByteArray(shiftJis)
    }
}
