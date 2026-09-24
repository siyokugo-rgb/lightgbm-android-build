package com.keiba.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset
import java.time.Instant
import java.time.ZoneId

class NarJmaObservedWeatherCsvParserTest {

    private val shiftJis =
        Charset.forName("Shift_JIS")

    @Test
    fun precipRowParsesWithLatestQualitySemantics() {
        val payload =
            NarJmaObservedWeatherCsvParser
                .parse(
                    NarJmaObservedWeatherProduct
                        .PRE1H00_RCT,
                    precipCsv(
                        dataRows =
                            listOf(
                                precipDataRow(
                                    stationId =
                                        "44166",
                                    currentQuality =
                                        "3"
                                )
                            )
                    )
                )

        val row =
            payload.rows
                .single() as NarJmaObservedPrecipitationRow

        assertEquals(
            1,
            row.windowHours
        )

        assertEquals(
            NarJmaObservedLatestQuality
                .QUESTIONABLE,
            row.currentValueQuality
        )
    }

    @Test
    fun latestCode2IsUnsuitableNotQuestionable() {
        val quality =
            NarJmaObservedLatestQuality
                .fromRawCode(2)

        assertEquals(
            NarJmaObservedLatestQuality
                .UNSUITABLE,
            quality
        )

        assertEquals(
            NarJmaObservedLatestQuality
                .QUESTIONABLE,
            NarJmaObservedLatestQuality
                .fromRawCode(3)
        )
    }

    @Test
    fun unknownLatestQualityCodeIsRejected() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarJmaObservedLatestQuality
                .fromRawCode(6)
        }

        assertThrows(
            IllegalArgumentException::class.java
        ) {
            parsePrecipRow(
                currentQuality = "7"
            )
        }
    }

    @Test
    fun allDocumentedLatestQualityCodesParse() {
        for (code in listOf(0, 1, 2, 3, 4, 5, 8)) {
            val row =
                parsePrecipRow(
                    currentQuality =
                        code.toString()
                ) as NarJmaObservedPrecipitationRow

            assertEquals(
                code,
                row.currentValueQuality
                    ?.rawCode
            )
        }
    }

    @Test
    fun precipitationWindowHoursFollowProductEnum() {
        val cases =
            mapOf(
                NarJmaObservedWeatherProduct.PRE1H00_RCT to 1,
                NarJmaObservedWeatherProduct.PRE3H00_RCT to 3,
                NarJmaObservedWeatherProduct.PRE6H00_RCT to 6,
                NarJmaObservedWeatherProduct.PRE12H00_RCT to 12,
                NarJmaObservedWeatherProduct.PRE24H00_RCT to 24
            )

        for ((product, hours) in cases) {
            val payload =
                NarJmaObservedWeatherCsvParser
                    .parse(
                        product,
                        precipCsv(
                            dataRows =
                                listOf(
                                    precipDataRow(
                                        stationId =
                                            "44166"
                                    )
                                )
                        )
                    )

            val row =
                payload.rows
                    .single() as NarJmaObservedPrecipitationRow

            assertEquals(
                hours,
                row.windowHours
            )
        }
    }

    @Test
    fun temperatureMaxAndMinUseDailyExtremeSemantics() {
        val maxPayload =
            NarJmaObservedWeatherCsvParser
                .parse(
                    NarJmaObservedWeatherProduct
                        .MXTEMSADEXT00_RCT,
                    temperatureCsv(
                        header =
                            TEMP_MAX_HEADER,
                        dataRows =
                            listOf(
                                temperatureDataRow(
                                    stationId =
                                        "53041",
                                    extremeValue =
                                        "31.2",
                                    extremeQuality =
                                        "8"
                                )
                            )
                    )
                )

        val maxRow =
            maxPayload.rows
                .single() as NarJmaObservedTemperatureExtremeRow

        assertEquals(
            NarJmaObservedTemperatureExtremeType.MAX,
            maxRow.extremeType
        )

        assertEquals(
            31.2,
            maxRow.dailyExtremeCelsius!!,
            0.0
        )

        val minPayload =
            NarJmaObservedWeatherCsvParser
                .parse(
                    NarJmaObservedWeatherProduct
                        .MNTEMSADEXT00_RCT,
                    temperatureCsv(
                        header =
                            TEMP_MIN_HEADER,
                        dataRows =
                            listOf(
                                temperatureDataRow(
                                    stationId =
                                        "53041",
                                    extremeValue =
                                        "18.0",
                                    extremeQuality =
                                        "8"
                                )
                            )
                    )
                )

        val minRow =
            minPayload.rows
                .single() as NarJmaObservedTemperatureExtremeRow

        assertEquals(
            NarJmaObservedTemperatureExtremeType.MIN,
            minRow.extremeType
        )
    }

    @Test
    fun windAndGustParseDailyMaximumNotInstantaneous() {
        val windPayload =
            NarJmaObservedWeatherCsvParser
                .parse(
                    NarJmaObservedWeatherProduct
                        .MXWSP00_RCT,
                    windCsv(
                        header =
                            WIND_HEADER,
                        dataRows =
                            listOf(
                                windDataRow(
                                    stationId =
                                        "44166",
                                    maxValue =
                                        "5.2",
                                    direction =
                                        "北"
                                )
                            )
                    )
                )

        val windRow =
            windPayload.rows
                .single() as NarJmaObservedWindExtremeRow

        assertEquals(
            NarJmaObservedWindExtremeKind.MAX_WIND,
            windRow.kind
        )

        assertEquals(
            "北",
            windRow.directionRaw
        )

        val gustPayload =
            NarJmaObservedWeatherCsvParser
                .parse(
                    NarJmaObservedWeatherProduct
                        .GUST00_RCT,
                    gustCsv(
                        header =
                            GUST_HEADER,
                        dataRows =
                            listOf(
                                windDataRow(
                                    stationId =
                                        "44166",
                                    maxValue =
                                        "10.1",
                                    direction =
                                        "南西"
                                )
                            )
                    )
                )

        val gustRow =
            gustPayload.rows
                .single() as NarJmaObservedWindExtremeRow

        assertEquals(
            NarJmaObservedWindExtremeKind.MAX_GUST,
            gustRow.kind
        )
    }

    @Test
    fun csvLexicalHandlesQuotedCommaAndEscapedQuote() {
        val text =
            "a,b\r\n" +
                "\"x,y\",\"z\"\"q\"\r\n"

        val rows =
            NarJmaObservedWeatherCsvLexical
                .parseTable(text)

        assertEquals(
            listOf("a", "b"),
            rows[0]
        )

        assertEquals(
            listOf("x,y", "z\"q"),
            rows[1]
        )
    }

    @Test
    fun invalidShiftJisIsRejected() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarJmaObservedWeatherCsvParser
                .parse(
                    NarJmaObservedWeatherProduct
                        .PRE1H00_RCT,
                    byteArrayOf(
                        0x80.toByte()
                    )
                )
        }
    }

    @Test
    fun duplicateStationIdFailsClosed() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarJmaObservedWeatherCsvParser
                .parse(
                    NarJmaObservedWeatherProduct
                        .PRE1H00_RCT,
                    precipCsv(
                        dataRows =
                            listOf(
                                precipDataRow(
                                    stationId =
                                        "44166"
                                ),
                                precipDataRow(
                                    stationId =
                                        "44166"
                                )
                            )
                    )
                )
        }
    }

    @Test
    fun blankNumericValueRemainsNull() {
        val row =
            parsePrecipRow(
                currentValue = "",
                currentQuality = "1"
            ) as NarJmaObservedPrecipitationRow

        assertNull(row.currentValueMm)
        assertEquals(
            NarJmaObservedLatestQuality
                .MISSING_OR_UNREPORTED,
            row.currentValueQuality
        )
    }

    @Test
    fun nanAndInfinityAreRejected() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            parsePrecipRow(
                currentValue = "NaN"
            )
        }

        assertThrows(
            IllegalArgumentException::class.java
        ) {
            parsePrecipRow(
                currentValue = "Infinity"
            )
        }
    }

    @Test
    fun negativePrecipitationIsRejected() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            parsePrecipRow(
                currentValue = "-0.1"
            )
        }
    }

    @Test
    fun productTimestampUsesAsiaTokyo() {
        val epoch =
            NarJmaObservedWeatherCsvParser
                .parseProductTimestampForTest(
                    year = "2026",
                    month = "9",
                    day = "22",
                    hour = "09",
                    minute = "40"
                )

        val zoned =
            Instant.ofEpochMilli(epoch)
                .atZone(
                    ZoneId.of("Asia/Tokyo")
                )

        assertEquals(2026, zoned.year)
        assertEquals(9, zoned.monthValue)
        assertEquals(22, zoned.dayOfMonth)
        assertEquals(9, zoned.hour)
        assertEquals(40, zoned.minute)
    }

    @Test
    fun invalidHour24IsRejectedWithoutOfficialEvidence() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarJmaObservedWeatherCsvParser
                .parseProductTimestampForTest(
                    year = "2026",
                    month = "9",
                    day = "22",
                    hour = "24",
                    minute = "00"
                )
        }
    }

    @Test
    fun renamedHeaderIsRejected() {
        val header =
            PRECIP_HEADER.replace(
                "現在値(mm)",
                "現在値(mm)改"
            )

        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarJmaObservedWeatherCsvParser
                .parse(
                    NarJmaObservedWeatherProduct
                        .PRE1H00_RCT,
                    encode(
                        header +
                            "\r\n" +
                            precipDataRow(
                                stationId =
                                    "44166"
                            )
                    )
                )
        }
    }

    @Test
    fun inconsistentRowColumnCountIsRejected() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarJmaObservedWeatherCsvParser
                .parse(
                    NarJmaObservedWeatherProduct
                        .PRE1H00_RCT,
                    encode(
                        PRECIP_HEADER +
                            "\r\n" +
                            "44166,東京都,羽田"
                    )
                )
        }
    }

    @Test
    fun rawBytesAreNotMutatedByParse() {
        val raw =
            precipCsv(
                dataRows =
                    listOf(
                        precipDataRow(
                            stationId =
                                "44166"
                        )
                    )
            )

        val copy =
            raw.copyOf()

        NarJmaObservedWeatherCsvParser
            .parse(
                NarJmaObservedWeatherProduct
                    .PRE1H00_RCT,
                raw
            )

        assertArrayEquals(copy, raw)
    }

    private fun parsePrecipRow(
        stationId: String = "44166",
        currentValue: String = "0.0",
        currentQuality: String = "8"
    ): NarJmaObservedWeatherRow {
        val payload =
            NarJmaObservedWeatherCsvParser
                .parse(
                    NarJmaObservedWeatherProduct
                        .PRE1H00_RCT,
                    precipCsv(
                        dataRows =
                            listOf(
                                precipDataRow(
                                    stationId =
                                        stationId,
                                    currentValue =
                                        currentValue,
                                    currentQuality =
                                        currentQuality
                                )
                            )
                    )
                )

        return payload.rows.single()
    }

    private fun precipCsv(
        dataRows: List<String>
    ): ByteArray =
        encode(
            PRECIP_HEADER +
                "\r\n" +
                dataRows.joinToString("\r\n")
        )

    private fun temperatureCsv(
        header: String,
        dataRows: List<String>
    ): ByteArray =
        encode(
            header +
                "\r\n" +
                dataRows.joinToString("\r\n")
        )

    private fun windCsv(
        header: String,
        dataRows: List<String>
    ): ByteArray =
        temperatureCsv(
            header,
            dataRows
        )

    private fun gustCsv(
        header: String,
        dataRows: List<String>
    ): ByteArray =
        temperatureCsv(
            header,
            dataRows
        )

    private fun precipDataRow(
        stationId: String,
        currentValue: String = "0.0",
        currentQuality: String = "8"
    ): String {
        val cells =
            MutableList(29) { "" }

        cells[0] = stationId
        cells[1] = "東京都"
        cells[2] = "羽田"
        cells[3] = ""
        cells[4] = "2026"
        cells[5] = "9"
        cells[6] = "22"
        cells[7] = "09"
        cells[8] = "40"
        cells[9] = currentValue
        cells[10] = currentQuality

        return cells.joinToString(",")
    }

    private fun temperatureDataRow(
        stationId: String,
        extremeValue: String,
        extremeQuality: String
    ): String {
        val cells =
            MutableList(37) { "" }

        cells[0] = stationId
        cells[1] = "三重県"
        cells[2] = "桑名"
        cells[4] = "2026"
        cells[5] = "9"
        cells[6] = "22"
        cells[7] = "09"
        cells[8] = "40"
        cells[9] = extremeValue
        cells[10] = extremeQuality

        return cells.joinToString(",")
    }

    private fun windDataRow(
        stationId: String,
        maxValue: String,
        direction: String
    ): String {
        val cells =
            MutableList(33) { "" }

        cells[0] = stationId
        cells[1] = "東京都"
        cells[2] = "羽田"
        cells[4] = "2026"
        cells[5] = "9"
        cells[6] = "22"
        cells[7] = "09"
        cells[8] = "40"
        cells[9] = maxValue
        cells[10] = "8"
        cells[11] = direction
        cells[12] = "8"

        return cells.joinToString(",")
    }

    private fun encode(
        text: String
    ): ByteArray =
        text.toByteArray(shiftJis)

    companion object {
        private val PRECIP_HEADER =
            "観測所番号,都道府県,地点,国際地点番号,現在時刻(年),現在時刻(月),現在時刻(日),現在時刻(時),現在時刻(分),現在値(mm),現在値の品質情報,22日の最大値(mm),22日の最大値の品質情報,22日の最大値起時（時）(まで),22日の最大値起時（分）(まで),22日の最大値起時(まで)の品質情報,極値更新,10年未満での極値更新,21日までの観測史上1位の値(mm),21日までの観測史上1位の値の品質情報,21日までの観測史上1位の値の年,21日までの観測史上1位の値の月,21日までの観測史上1位の値の日,21日までの9月の1位の値(mm),21日までの9月の1位の値の品質情報,21日までの9月の1位の値の年,21日までの9月の1位の値の月,21日までの9月の1位の値の日,統計開始年"

        private val TEMP_MAX_HEADER =
            "観測所番号,都道府県,地点,国際地点番号,現在時刻(年),現在時刻(月),現在時刻(日),現在時刻(時),現在時刻(分),22日の最高気温(℃),22日の最高気温の品質情報,22日の最高気温起時（時）,22日の最高気温起時（分）,22日の最高気温起時の品質情報,平年差（℃）,前日差（℃）,該当旬（月）,該当旬（旬）,極値更新,10年未満での極値更新,今年最高,今年の最高気温（℃)（21日まで）,今年の最高気温（21日まで）の品質情報,今年の最高気温（21日まで）を観測した起日（年）,今年の最高気温（21日まで）を観測した起日（月）,今年の最高気温（21日まで）を観測した起日（日）,21日までの観測史上1位の値（℃）,21日までの観測史上1位の値の品質情報,21日までの観測史上1位の値を観測した起日（年）,21日までの観測史上1位の値を観測した起日（月）,21日までの観測史上1位の値を観測した起日（日）,21日までの9月の1位の値,21日までの9月の1位の値の品質情報,21日までの9月の1位の値の起日（年）,21日までの9月の1位の値の起日（月）,21日までの9月の1位の値の起日（日）,統計開始年"

        private val TEMP_MIN_HEADER =
            "観測所番号,都道府県,地点,国際地点番号,現在時刻(年),現在時刻(月),現在時刻(日),現在時刻(時),現在時刻(分),22日の最低気温(℃),22日の最低気温の品質情報,22日の最低気温起時（時）,22日の最低気温起時（分）,22日の最低気温起時の品質情報,平年差（℃）,前日差（℃）,該当旬（月）,該当旬（旬）,極値更新,10年未満での極値更新,今季最低,今年の最低気温（℃)（21日まで）,今年の最低気温（21日まで）の品質情報,今年の最低気温（21日まで）を観測した起日（年）,今年の最低気温（21日まで）を観測した起日（月）,今年の最低気温（21日まで）を観測した起日（日）,21日までの観測史上1位の値（℃）,21日までの観測史上1位の値の品質情報,21日までの観測史上1位の値を観測した起日（年）,21日までの観測史上1位の値を観測した起日（月）,21日までの観測史上1位の値を観測した起日（日）,21日までの9月の1位の値,21日までの9月の1位の値の品質情報,21日までの9月の1位の値の起日（年）,21日までの9月の1位の値の起日（月）,21日までの9月の1位の値の起日（日）,統計開始年"

        private val WIND_HEADER =
            "観測所番号,都道府県,地点,国際地点番号,現在時刻(年),現在時刻(月),現在時刻(日),現在時刻(時),現在時刻(分),22日の最大値(m/s),22日の最大値の品質情報,22日の最大値観測時の風向,22日の最大値観測時の風向の品質情報,22日の最大値起時（時）,22日の最大値起時（分）,22日の最大値起時の品質情報,極値更新,10年未満での極値更新,21日までの観測史上1位の値(m/s),21日までの観測史上1位の値の品質情報,21日までの観測史上1位の値観測時の風向,21日までの観測史上1位の値観測時の風向の品質情報,21日までの観測史上1位の値観測時の年,21日までの観測史上1位の値観測時の月,21日までの観測史上1位の値観測時の日,21日までの9月の1位の値(m/s),21日までの9月の1位の値の品質情報,21日までの9月の1位の値観測時の風向,21日までの9月の1位の値観測時の風向の品質情報,21日までの9月の1位の値観測時の年,21日までの9月の1位の値観測時の月,21日までの9月の1位の値観測時の日,統計開始年"

        private val GUST_HEADER =
            "観測所番号,都道府県,地点,国際地点番号,現在時刻(年),現在時刻(月),現在時刻(日),現在時刻(時),現在時刻(分),22日の最大値(m/s),22日の最大値の品質情報,22日の最大値観測時の風向,22日の最大値観測時の風向の品質情報,22日の最大値起時（時）,22日の最大値起時（分）,22日の最大値起時の品質情報,極値更新,10年未満での極値更新,21日までの観測史上1位の値(m/s),21日までの観測史上1位の値の品質情報,21日までの観測史上1位の値観測時の風向,21日までの観測史上1位の値観測時の風向の品質情報,21日までの観測史上1位の値観測時の年,21日までの観測史上1位の値観測時の月,21日までの観測史上1位の値観測時の日,21日までの9月の1位の値（m/s）,21日までの9月の1位の値の品質情報,21日までの9月の1位の値観測時の風向,21日までの9月の1位の値観測時の風向の品質情報,21日までの9月の1位の値観測時の年,21日までの9月の1位の値観測時の月,21日までの9月の1位の値観測時の日,統計開始年"
    }
}
