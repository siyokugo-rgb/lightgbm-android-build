package com.keiba.ai

internal object NarJmaObservedWeatherCsvSchema {

    private val COMMON_PREFIX =
        listOf(
            "観測所番号",
            "都道府県",
            "地点",
            "国際地点番号",
            "現在時刻(年)",
            "現在時刻(月)",
            "現在時刻(日)",
            "現在時刻(時)",
            "現在時刻(分)"
        )

    private sealed class Expect {
        data class Exact(
            val value: String
        ) : Expect()

        data class Regex(
            val pattern: kotlin.text.Regex
        ) : Expect()
    }

    fun validateHeader(
        product: NarJmaObservedWeatherProduct,
        header: List<String>
    ) {
        val expectations =
            expectationsFor(product)

        require(
            header.size ==
                expectations.size
        ) {
            "observed CSV header column count mismatch"
        }

        val seen =
            linkedSetOf<String>()

        for (index in header.indices) {
            val name =
                header[index].trim()

            require(name.isNotEmpty()) {
                "blank observed CSV header cell"
            }

            require(seen.add(name)) {
                "duplicate observed CSV header"
            }

            when (val rule = expectations[index]) {
                is Expect.Exact ->
                    require(name == rule.value) {
                        "observed CSV header mismatch at $index"
                    }

                is Expect.Regex ->
                    require(
                        rule.pattern.matches(
                            name
                        )
                    ) {
                        "observed CSV header pattern mismatch at $index"
                    }
            }
        }
    }

    private fun expectationsFor(
        product: NarJmaObservedWeatherProduct
    ): List<Expect> =
        when (product.family) {
            NarJmaObservedWeatherProduct.Family.PRECIPITATION ->
                precipitationExpectations()

            NarJmaObservedWeatherProduct.Family.TEMPERATURE_EXTREME ->
                when (product) {
                    NarJmaObservedWeatherProduct.MXTEMSADEXT00_RCT ->
                        temperatureMaxExpectations()

                    NarJmaObservedWeatherProduct.MNTEMSADEXT00_RCT ->
                        temperatureMinExpectations()

                    else ->
                        error(
                            "unexpected temperature product"
                        )
                }

            NarJmaObservedWeatherProduct.Family.WIND_MAX ->
                windExpectations()

            NarJmaObservedWeatherProduct.Family.GUST_MAX ->
                gustExpectations()
        }

    private fun precipitationExpectations(): List<Expect> {
        val dayValue =
            Regex(
                """^\d{1,2}日の最大値\(mm\)$"""
            )

        val dayQuality =
            Regex(
                """^\d{1,2}日の最大値の品質情報$"""
            )

        val dayHour =
            Regex(
                """^\d{1,2}日の最大値起時（時）\(まで\)$"""
            )

        val dayMinute =
            Regex(
                """^\d{1,2}日の最大値起時（分）\(まで\)$"""
            )

        val dayOccQuality =
            Regex(
                """^\d{1,2}日の最大値起時\(まで\)の品質情報$"""
            )

        val monthValue =
            Regex(
                """^21日までの\d{1,2}月の1位の値\(mm\)$"""
            )

        val monthQuality =
            Regex(
                """^21日までの\d{1,2}月の1位の値の品質情報$"""
            )

        return COMMON_PREFIX.map { Expect.Exact(it) } +
            listOf(
                Expect.Exact("現在値(mm)"),
                Expect.Exact("現在値の品質情報"),
                Expect.Regex(dayValue),
                Expect.Regex(dayQuality),
                Expect.Regex(dayHour),
                Expect.Regex(dayMinute),
                Expect.Regex(dayOccQuality),
                Expect.Exact("極値更新"),
                Expect.Exact("10年未満での極値更新"),
                Expect.Exact("21日までの観測史上1位の値(mm)"),
                Expect.Exact("21日までの観測史上1位の値の品質情報"),
                Expect.Exact("21日までの観測史上1位の値の年"),
                Expect.Exact("21日までの観測史上1位の値の月"),
                Expect.Exact("21日までの観測史上1位の値の日"),
                Expect.Regex(monthValue),
                Expect.Regex(monthQuality),
                Expect.Regex(
                    Regex(
                        """^21日までの\d{1,2}月の1位の値の年$"""
                    )
                ),
                Expect.Regex(
                    Regex(
                        """^21日までの\d{1,2}月の1位の値の月$"""
                    )
                ),
                Expect.Regex(
                    Regex(
                        """^21日までの\d{1,2}月の1位の値の日$"""
                    )
                ),
                Expect.Exact("統計開始年")
            )
    }

    private fun temperatureMaxExpectations(): List<Expect> {
        val dayValue =
            Regex(
                """^\d{1,2}日の最高気温\(℃\)$"""
            )

        val dayQuality =
            Regex(
                """^\d{1,2}日の最高気温の品質情報$"""
            )

        val dayHour =
            Regex(
                """^\d{1,2}日の最高気温起時（時）$"""
            )

        val dayMinute =
            Regex(
                """^\d{1,2}日の最高気温起時（分）$"""
            )

        val dayOccQuality =
            Regex(
                """^\d{1,2}日の最高気温起時の品質情報$"""
            )

        return COMMON_PREFIX.map { Expect.Exact(it) } +
            listOf(
                Expect.Regex(dayValue),
                Expect.Regex(dayQuality),
                Expect.Regex(dayHour),
                Expect.Regex(dayMinute),
                Expect.Regex(dayOccQuality),
                Expect.Exact("平年差（℃）"),
                Expect.Exact("前日差（℃）"),
                Expect.Exact("該当旬（月）"),
                Expect.Exact("該当旬（旬）"),
                Expect.Exact("極値更新"),
                Expect.Exact("10年未満での極値更新"),
                Expect.Exact("今年最高"),
                Expect.Exact("今年の最高気温（℃)（21日まで）"),
                Expect.Exact("今年の最高気温（21日まで）の品質情報"),
                Expect.Exact("今年の最高気温（21日まで）を観測した起日（年）"),
                Expect.Exact("今年の最高気温（21日まで）を観測した起日（月）"),
                Expect.Exact("今年の最高気温（21日まで）を観測した起日（日）"),
                Expect.Exact("21日までの観測史上1位の値（℃）"),
                Expect.Exact("21日までの観測史上1位の値の品質情報"),
                Expect.Exact("21日までの観測史上1位の値を観測した起日（年）"),
                Expect.Exact("21日までの観測史上1位の値を観測した起日（月）"),
                Expect.Exact("21日までの観測史上1位の値を観測した起日（日）"),
                Expect.Regex(
                    Regex(
                        """^21日までの\d{1,2}月の1位の値$"""
                    )
                ),
                Expect.Regex(
                    Regex(
                        """^21日までの\d{1,2}月の1位の値の品質情報$"""
                    )
                ),
                Expect.Regex(
                    Regex(
                        """^21日までの\d{1,2}月の1位の値の起日（年）$"""
                    )
                ),
                Expect.Regex(
                    Regex(
                        """^21日までの\d{1,2}月の1位の値の起日（月）$"""
                    )
                ),
                Expect.Regex(
                    Regex(
                        """^21日までの\d{1,2}月の1位の値の起日（日）$"""
                    )
                ),
                Expect.Exact("統計開始年")
            )
    }

    private fun temperatureMinExpectations(): List<Expect> {
        val dayValue =
            Regex(
                """^\d{1,2}日の最低気温\(℃\)$"""
            )

        val dayQuality =
            Regex(
                """^\d{1,2}日の最低気温の品質情報$"""
            )

        val dayHour =
            Regex(
                """^\d{1,2}日の最低気温起時（時）$"""
            )

        val dayMinute =
            Regex(
                """^\d{1,2}日の最低気温起時（分）$"""
            )

        val dayOccQuality =
            Regex(
                """^\d{1,2}日の最低気温起時の品質情報$"""
            )

        return COMMON_PREFIX.map { Expect.Exact(it) } +
            listOf(
                Expect.Regex(dayValue),
                Expect.Regex(dayQuality),
                Expect.Regex(dayHour),
                Expect.Regex(dayMinute),
                Expect.Regex(dayOccQuality),
                Expect.Exact("平年差（℃）"),
                Expect.Exact("前日差（℃）"),
                Expect.Exact("該当旬（月）"),
                Expect.Exact("該当旬（旬）"),
                Expect.Exact("極値更新"),
                Expect.Exact("10年未満での極値更新"),
                Expect.Exact("今季最低"),
                Expect.Exact("今年の最低気温（℃)（21日まで）"),
                Expect.Exact("今年の最低気温（21日まで）の品質情報"),
                Expect.Exact("今年の最低気温（21日まで）を観測した起日（年）"),
                Expect.Exact("今年の最低気温（21日まで）を観測した起日（月）"),
                Expect.Exact("今年の最低気温（21日まで）を観測した起日（日）"),
                Expect.Exact("21日までの観測史上1位の値（℃）"),
                Expect.Exact("21日までの観測史上1位の値の品質情報"),
                Expect.Exact("21日までの観測史上1位の値を観測した起日（年）"),
                Expect.Exact("21日までの観測史上1位の値を観測した起日（月）"),
                Expect.Exact("21日までの観測史上1位の値を観測した起日（日）"),
                Expect.Regex(
                    Regex(
                        """^21日までの\d{1,2}月の1位の値$"""
                    )
                ),
                Expect.Regex(
                    Regex(
                        """^21日までの\d{1,2}月の1位の値の品質情報$"""
                    )
                ),
                Expect.Regex(
                    Regex(
                        """^21日までの\d{1,2}月の1位の値の起日（年）$"""
                    )
                ),
                Expect.Regex(
                    Regex(
                        """^21日までの\d{1,2}月の1位の値の起日（月）$"""
                    )
                ),
                Expect.Regex(
                    Regex(
                        """^21日までの\d{1,2}月の1位の値の起日（日）$"""
                    )
                ),
                Expect.Exact("統計開始年")
            )
    }

    private fun windExpectations(): List<Expect> {
        val dayValue =
            Regex(
                """^\d{1,2}日の最大値\(m/s\)$"""
            )

        val dayQuality =
            Regex(
                """^\d{1,2}日の最大値の品質情報$"""
            )

        val dayDirection =
            Regex(
                """^\d{1,2}日の最大値観測時の風向$"""
            )

        val dayDirectionQuality =
            Regex(
                """^\d{1,2}日の最大値観測時の風向の品質情報$"""
            )

        val dayHour =
            Regex(
                """^\d{1,2}日の最大値起時（時）$"""
            )

        val dayMinute =
            Regex(
                """^\d{1,2}日の最大値起時（分）$"""
            )

        val dayOccQuality =
            Regex(
                """^\d{1,2}日の最大値起時の品質情報$"""
            )

        return COMMON_PREFIX.map { Expect.Exact(it) } +
            listOf(
                Expect.Regex(dayValue),
                Expect.Regex(dayQuality),
                Expect.Regex(dayDirection),
                Expect.Regex(dayDirectionQuality),
                Expect.Regex(dayHour),
                Expect.Regex(dayMinute),
                Expect.Regex(dayOccQuality),
                Expect.Exact("極値更新"),
                Expect.Exact("10年未満での極値更新"),
                Expect.Exact("21日までの観測史上1位の値(m/s)"),
                Expect.Exact("21日までの観測史上1位の値の品質情報"),
                Expect.Exact("21日までの観測史上1位の値観測時の風向"),
                Expect.Exact("21日までの観測史上1位の値観測時の風向の品質情報"),
                Expect.Exact("21日までの観測史上1位の値観測時の年"),
                Expect.Exact("21日までの観測史上1位の値観測時の月"),
                Expect.Exact("21日までの観測史上1位の値観測時の日"),
                Expect.Regex(
                    Regex(
                        """^21日までの\d{1,2}月の1位の値\(m/s\)$"""
                    )
                ),
                Expect.Regex(
                    Regex(
                        """^21日までの\d{1,2}月の1位の値の品質情報$"""
                    )
                ),
                Expect.Regex(
                    Regex(
                        """^21日までの\d{1,2}月の1位の値観測時の風向$"""
                    )
                ),
                Expect.Regex(
                    Regex(
                        """^21日までの\d{1,2}月の1位の値観測時の風向の品質情報$"""
                    )
                ),
                Expect.Regex(
                    Regex(
                        """^21日までの\d{1,2}月の1位の値観測時の年$"""
                    )
                ),
                Expect.Regex(
                    Regex(
                        """^21日までの\d{1,2}月の1位の値観測時の月$"""
                    )
                ),
                Expect.Regex(
                    Regex(
                        """^21日までの\d{1,2}月の1位の値観測時の日$"""
                    )
                ),
                Expect.Exact("統計開始年")
            )
    }

    private fun gustExpectations(): List<Expect> {
        val dayValue =
            Regex(
                """^\d{1,2}日の最大値\(m/s\)$"""
            )

        val dayQuality =
            Regex(
                """^\d{1,2}日の最大値の品質情報$"""
            )

        val dayDirection =
            Regex(
                """^\d{1,2}日の最大値観測時の風向$"""
            )

        val dayDirectionQuality =
            Regex(
                """^\d{1,2}日の最大値観測時の風向の品質情報$"""
            )

        val dayHour =
            Regex(
                """^\d{1,2}日の最大値起時（時）$"""
            )

        val dayMinute =
            Regex(
                """^\d{1,2}日の最大値起時（分）$"""
            )

        val dayOccQuality =
            Regex(
                """^\d{1,2}日の最大値起時の品質情報$"""
            )

        return COMMON_PREFIX.map { Expect.Exact(it) } +
            listOf(
                Expect.Regex(dayValue),
                Expect.Regex(dayQuality),
                Expect.Regex(dayDirection),
                Expect.Regex(dayDirectionQuality),
                Expect.Regex(dayHour),
                Expect.Regex(dayMinute),
                Expect.Regex(dayOccQuality),
                Expect.Exact("極値更新"),
                Expect.Exact("10年未満での極値更新"),
                Expect.Exact("21日までの観測史上1位の値(m/s)"),
                Expect.Exact("21日までの観測史上1位の値の品質情報"),
                Expect.Exact("21日までの観測史上1位の値観測時の風向"),
                Expect.Exact("21日までの観測史上1位の値観測時の風向の品質情報"),
                Expect.Exact("21日までの観測史上1位の値観測時の年"),
                Expect.Exact("21日までの観測史上1位の値観測時の月"),
                Expect.Exact("21日までの観測史上1位の値観測時の日"),
                Expect.Regex(
                    Regex(
                        """^21日までの\d{1,2}月の1位の値（m/s）$"""
                    )
                ),
                Expect.Regex(
                    Regex(
                        """^21日までの\d{1,2}月の1位の値の品質情報$"""
                    )
                ),
                Expect.Regex(
                    Regex(
                        """^21日までの\d{1,2}月の1位の値観測時の風向$"""
                    )
                ),
                Expect.Regex(
                    Regex(
                        """^21日までの\d{1,2}月の1位の値観測時の風向の品質情報$"""
                    )
                ),
                Expect.Regex(
                    Regex(
                        """^21日までの\d{1,2}月の1位の値観測時の年$"""
                    )
                ),
                Expect.Regex(
                    Regex(
                        """^21日までの\d{1,2}月の1位の値観測時の月$"""
                    )
                ),
                Expect.Regex(
                    Regex(
                        """^21日までの\d{1,2}月の1位の値観測時の日$"""
                    )
                ),
                Expect.Exact("統計開始年")
            )
    }
}
