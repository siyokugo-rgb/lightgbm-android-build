package com.keiba.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.charset.Charset

class NarJmaObservedWeatherStationSelectorTest {

    private val shiftJis =
        Charset.forName("Shift_JIS")

    @Test
    fun exactStationIdMatchReturnsRow() {
        val payload =
            samplePayload(
                rows =
                    listOf(
                        precipRow(
                            "46061"
                        ),
                        precipRow(
                            "44166"
                        )
                    )
            )

        val selected =
            NarJmaObservedWeatherStationSelector
                .select(
                    payload,
                    "46061"
                ) as NarJmaObservedPrecipitationRow

        assertEquals(
            "46061",
            selected.stationId
        )
    }

    @Test
    fun absentStationReturnsNull() {
        val payload =
            samplePayload(
                rows =
                    listOf(
                        precipRow(
                            "44166"
                        )
                    )
            )

        assertNull(
            NarJmaObservedWeatherStationSelector
                .select(
                    payload,
                    "99999"
                )
        )
    }

    @Test
    fun duplicateStationIdInPayloadIsRejected() {
        val payload =
            NarJmaObservedWeatherPayload(
                product =
                    NarJmaObservedWeatherProduct
                        .PRE1H00_RCT,
                rows =
                    listOf(
                        precipRow("44166"),
                        precipRow("44166")
                    )
            )

        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarJmaObservedWeatherStationSelector
                .select(
                    payload,
                    "44166"
                )
        }
    }

    @Test
    fun stationNameMatchWithoutIdIsNotUsed() {
        val payload =
            samplePayload(
                rows =
                    listOf(
                        precipRow(
                            "44166",
                            stationName =
                                "羽田"
                        )
                    )
            )

        assertNull(
            NarJmaObservedWeatherStationSelector
                .select(
                    payload,
                    "46061"
                )
        )
    }

    @Test
    fun kawasakiProductSpecificStationIds() {
        val precipPayload =
            samplePayload(
                product =
                    NarJmaObservedWeatherProduct
                        .PRE1H00_RCT,
                rows =
                    listOf(
                        precipRow("46061"),
                        precipRow("44166")
                    )
            )

        val windPayload =
            samplePayload(
                product =
                    NarJmaObservedWeatherProduct
                        .MXWSP00_RCT,
                rows =
                    listOf(
                        windRow("44166"),
                        windRow("46061")
                    )
            )

        assertEquals(
            "46061",
            NarJmaObservedWeatherStationSelector
                .select(
                    precipPayload,
                    "46061"
                )
                ?.stationId
        )

        assertEquals(
            "44166",
            NarJmaObservedWeatherStationSelector
                .select(
                    windPayload,
                    "44166"
                )
                ?.stationId
        )
    }

    @Test
    fun nagoyaLiveMappingStationIdIsSelectable() {
        val payload =
            samplePayload(
                product =
                    NarJmaObservedWeatherProduct
                        .MXTEMSADEXT00_RCT,
                rows =
                    listOf(
                        temperatureRow(
                            "53041"
                        )
                    )
            )

        val row =
            NarJmaObservedWeatherStationSelector
                .select(
                    payload,
                    "53041"
                ) as NarJmaObservedTemperatureExtremeRow

        assertEquals(
            "53041",
            row.stationId
        )

        assertEquals(
            "桑名",
            row.stationName
        )
    }

    private fun samplePayload(
        product: NarJmaObservedWeatherProduct =
            NarJmaObservedWeatherProduct
                .PRE1H00_RCT,
        rows: List<NarJmaObservedWeatherRow>
    ): NarJmaObservedWeatherPayload =
        NarJmaObservedWeatherPayload(
            product = product,
            rows = rows
        )

    private fun precipRow(
        stationId: String,
        stationName: String = "日吉"
    ): NarJmaObservedPrecipitationRow {
        val payload =
            NarJmaObservedWeatherCsvParser
                .parse(
                    NarJmaObservedWeatherProduct
                        .PRE1H00_RCT,
                    buildPrecipCsv(
                        stationId,
                        stationName
                    )
                )

        return payload.rows
            .single() as NarJmaObservedPrecipitationRow
    }

    private fun windRow(
        stationId: String
    ): NarJmaObservedWindExtremeRow {
        val header =
            "観測所番号,都道府県,地点,国際地点番号,現在時刻(年),現在時刻(月),現在時刻(日),現在時刻(時),現在時刻(分),22日の最大値(m/s),22日の最大値の品質情報,22日の最大値観測時の風向,22日の最大値観測時の風向の品質情報,22日の最大値起時（時）,22日の最大値起時（分）,22日の最大値起時の品質情報,極値更新,10年未満での極値更新,21日までの観測史上1位の値(m/s),21日までの観測史上1位の値の品質情報,21日までの観測史上1位の値観測時の風向,21日までの観測史上1位の値観測時の風向の品質情報,21日までの観測史上1位の値観測時の年,21日までの観測史上1位の値観測時の月,21日までの観測史上1位の値観測時の日,21日までの9月の1位の値(m/s),21日までの9月の1位の値の品質情報,21日までの9月の1位の値観測時の風向,21日までの9月の1位の値観測時の風向の品質情報,21日までの9月の1位の値観測時の年,21日までの9月の1位の値観測時の月,21日までの9月の1位の値観測時の日,統計開始年"

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
        cells[9] = "3.0"
        cells[10] = "8"

        val csv =
            header +
                "\r\n" +
                cells.joinToString(",")

        val payload =
            NarJmaObservedWeatherCsvParser
                .parse(
                    NarJmaObservedWeatherProduct
                        .MXWSP00_RCT,
                    csv.toByteArray(shiftJis)
                )

        return payload.rows
            .single() as NarJmaObservedWindExtremeRow
    }

    private fun temperatureRow(
        stationId: String
    ): NarJmaObservedTemperatureExtremeRow {
        val header =
            "観測所番号,都道府県,地点,国際地点番号,現在時刻(年),現在時刻(月),現在時刻(日),現在時刻(時),現在時刻(分),22日の最高気温(℃),22日の最高気温の品質情報,22日の最高気温起時（時）,22日の最高気温起時（分）,22日の最高気温起時の品質情報,平年差（℃）,前日差（℃）,該当旬（月）,該当旬（旬）,極値更新,10年未満での極値更新,今年最高,今年の最高気温（℃)（21日まで）,今年の最高気温（21日まで）の品質情報,今年の最高気温（21日まで）を観測した起日（年）,今年の最高気温（21日まで）を観測した起日（月）,今年の最高気温（21日まで）を観測した起日（日）,21日までの観測史上1位の値（℃）,21日までの観測史上1位の値の品質情報,21日までの観測史上1位の値を観測した起日（年）,21日までの観測史上1位の値を観測した起日（月）,21日までの観測史上1位の値を観測した起日（日）,21日までの9月の1位の値,21日までの9月の1位の値の品質情報,21日までの9月の1位の値の起日（年）,21日までの9月の1位の値の起日（月）,21日までの9月の1位の値の起日（日）,統計開始年"

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
        cells[9] = "30.0"
        cells[10] = "8"

        val csv =
            header +
                "\r\n" +
                cells.joinToString(",")

        val payload =
            NarJmaObservedWeatherCsvParser
                .parse(
                    NarJmaObservedWeatherProduct
                        .MXTEMSADEXT00_RCT,
                    csv.toByteArray(shiftJis)
                )

        return payload.rows
            .single() as NarJmaObservedTemperatureExtremeRow
    }

    private fun buildPrecipCsv(
        stationId: String,
        stationName: String
    ): ByteArray {
        val header =
            "観測所番号,都道府県,地点,国際地点番号,現在時刻(年),現在時刻(月),現在時刻(日),現在時刻(時),現在時刻(分),現在値(mm),現在値の品質情報,22日の最大値(mm),22日の最大値の品質情報,22日の最大値起時（時）(まで),22日の最大値起時（分）(まで),22日の最大値起時(まで)の品質情報,極値更新,10年未満での極値更新,21日までの観測史上1位の値(mm),21日までの観測史上1位の値の品質情報,21日までの観測史上1位の値の年,21日までの観測史上1位の値の月,21日までの観測史上1位の値の日,21日までの9月の1位の値(mm),21日までの9月の1位の値の品質情報,21日までの9月の1位の値の年,21日までの9月の1位の値の月,21日までの9月の1位の値の日,統計開始年"

        val cells =
            MutableList(29) { "" }

        cells[0] = stationId
        cells[1] = "神奈川県"
        cells[2] = stationName
        cells[4] = "2026"
        cells[5] = "9"
        cells[6] = "22"
        cells[7] = "09"
        cells[8] = "40"
        cells[9] = "0.0"
        cells[10] = "8"

        val csv =
            header +
                "\r\n" +
                cells.joinToString(",")

        return csv.toByteArray(shiftJis)
    }
}
