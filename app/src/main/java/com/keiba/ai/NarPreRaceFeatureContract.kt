package com.keiba.ai

object NarPreRaceFeatureContract {

    enum class Source {
        RACELIST,
        HORSELIST,
        PAYBACK
    }

    enum class DataContext {
        LIVE_PIT_SNAPSHOT,
        HISTORICAL_MONTHLY
    }

    enum class FeatureDecision {
        ALLOWED,
        PENDING_PIT,
        DEFERRED_SEMANTIC,
        DEFERRED_DYNAMIC,
        DEFERRED_AUDIT,
        FORBIDDEN_RESULT,
        NOT_FEATURE,
        DENIED_UNKNOWN
    }

    data class ColumnPolicy(
        val isKey: Boolean,
        val historicalMonthly: FeatureDecision,
        val livePitSnapshot: FeatureDecision
    ) {
        fun decision(
            context: DataContext
        ): FeatureDecision =
            when (context) {
                DataContext.HISTORICAL_MONTHLY ->
                    historicalMonthly

                DataContext.LIVE_PIT_SNAPSHOT ->
                    livePitSnapshot
            }
    }

    private val raceKeys =
        setOf(
            "競馬場",
            "競走年月日",
            "レース番号"
        )

    private val entryKeys =
        raceKeys +
            setOf(
                "馬番"
            )

    /*
     * Historical monthly CSVs are final-state exports, so only fields
     * whose meaning does not depend on a race-time publication/update
     * are allowed here without an additional PIT audit.
     */
    private val historicalAllowedRacelist =
        setOf(
            "競馬場",
            "競走年月日"
        )

    /*
     * Race-program values are pre-race candidates, but the final monthly
     * export does not prove that the final value was already known at an
     * arbitrary predictionAsOf. Verified live snapshots can prove this.
     */
    private val liveOnlyRacelist =
        setOf(
            "競走種類名称",
            "芝ダート区分",
            "回り",
            "距離",
            "条件",
            "1着賞金(円)",
            "2着賞金(円)",
            "3着賞金(円)",
            "4着賞金(円)",
            "5着賞金(円)",
            "頭数"
        )

    /*
     * Horse-intrinsic fields are safe for historical monthly use because
     * they are static, or deterministically derivable from static identity
     * plus race date, rather than being race-result updates.
     */
    private val historicalAllowedHorselist =
        setOf(
            "毛色",
            "生年月日",
            "父馬名",
            "母馬名",
            "母父馬名"
        )

    /*
     * Race-assignment, connections, weights, and cumulative-stat fields
     * are not historically approved until their as-of semantics are
     * audited. A verified live PIT snapshot proves the exact value existed
     * at the snapshot evidence time, so they are usable in LIVE_PIT_SNAPSHOT.
     */
    /*
     * NAR changed horse-age counting in 2001 and standardized sex labels.
     * Raw 1998-2000 values therefore cannot be treated as one stable
     * historical feature domain without normalization.
     *
     * Current verified live snapshots use the current convention, so raw
     * values are available live, while historical monthly use is deferred
     * until normalization/audit is implemented.
     */
    private val semanticPendingLiveAllowedHorselist =
        setOf(
            "性",
            "齢"
        )

    private val liveOnlyHorselist =
        setOf(
            "枠番",
            "馬番",
            "騎手名",
            "騎手所属",
            "負担重量",
            "騎手成績",
            "調教師",
            "調教師所属",
            "全成績",
            "ダート左成績",
            "ダート右成績",
            "当競馬場成績",
            "うち当距離成績",
            "最高タイム",
            "最高タイム良馬場"
        )

    private val deferredDynamicRacelist =
        setOf(
            "天候",
            "馬場"
        )

    private val deferredDynamicHorselist =
        setOf(
            "馬体重",
            "馬体重増減",
            "人気"
        )

    private val deferredAuditRacelist =
        setOf(
            "発走時刻"
        )

    private val forbiddenRacelist =
        buildSet {
            add("上がり4F")
            add("上がり3F")

            for (i in 1..15) {
                add("ハロンタイム$i")
            }

            for (i in 1..8) {
                add("コーナー通過順$i")
            }
        }

    private val forbiddenHorselist =
        setOf(
            "着順",
            "タイム",
            "着差",
            "上がり3F"
        )

    init {
        requireFeatureGroupsDisjoint(
            source = Source.RACELIST,
            groups = listOf(
                historicalAllowedRacelist,
                liveOnlyRacelist,
                deferredDynamicRacelist,
                deferredAuditRacelist,
                forbiddenRacelist
            )
        )

        requireFeatureGroupsDisjoint(
            source = Source.HORSELIST,
            groups = listOf(
                historicalAllowedHorselist,
                semanticPendingLiveAllowedHorselist,
                liveOnlyHorselist,
                deferredDynamicHorselist,
                forbiddenHorselist
            )
        )
    }

    fun policy(
        source: Source,
        column: String
    ): ColumnPolicy {
        if (source == Source.PAYBACK) {
            return ColumnPolicy(
                isKey = false,
                historicalMonthly =
                    FeatureDecision.FORBIDDEN_RESULT,
                livePitSnapshot =
                    FeatureDecision.FORBIDDEN_RESULT
            )
        }

        val isKey =
            when (source) {
                Source.RACELIST ->
                    column in raceKeys

                Source.HORSELIST ->
                    column in entryKeys

                Source.PAYBACK ->
                    false
            }

        return when (source) {
            Source.RACELIST ->
                featurePolicy(
                    column = column,
                    isKey = isKey,
                    historicalAllowed =
                        historicalAllowedRacelist,
                    semanticPendingLiveAllowed =
                        emptySet(),
                    liveOnly =
                        liveOnlyRacelist,
                    deferredDynamic =
                        deferredDynamicRacelist,
                    deferredAudit =
                        deferredAuditRacelist,
                    forbidden =
                        forbiddenRacelist
                )

            Source.HORSELIST ->
                featurePolicy(
                    column = column,
                    isKey = isKey,
                    historicalAllowed =
                        historicalAllowedHorselist,
                    semanticPendingLiveAllowed =
                        semanticPendingLiveAllowedHorselist,
                    liveOnly =
                        liveOnlyHorselist,
                    deferredDynamic =
                        deferredDynamicHorselist,
                    deferredAudit =
                        emptySet(),
                    forbidden =
                        forbiddenHorselist
                )

            Source.PAYBACK ->
                error(
                    "unreachable PAYBACK branch"
                )
        }
    }

    fun featureDecision(
        source: Source,
        column: String,
        context: DataContext
    ): FeatureDecision =
        policy(
            source,
            column
        ).decision(context)

    fun isKeyColumn(
        source: Source,
        column: String
    ): Boolean =
        policy(
            source,
            column
        ).isKey

    fun readFeatureValue(
        context: DataContext,
        source: Source,
        table: NarCsvParser.CsvTable,
        row: List<String>,
        column: String
    ): String {
        val decision =
            featureDecision(
                source = source,
                column = column,
                context = context
            )

        require(
            decision ==
                FeatureDecision.ALLOWED
        ) {
            "prediction feature access denied: " +
                "${context.name}/${source.name}/" +
                "$column/$decision"
        }

        return readExistingColumn(
            table = table,
            row = row,
            column = column
        )
    }

    fun readKeyValue(
        source: Source,
        table: NarCsvParser.CsvTable,
        row: List<String>,
        column: String
    ): String {
        val columnPolicy =
            policy(
                source,
                column
            )

        require(
            columnPolicy.isKey
        ) {
            "prediction key access denied: " +
                "${source.name}/$column"
        }

        return readExistingColumn(
            table = table,
            row = row,
            column = column
        )
    }

    fun allowedFeatureColumns(
        source: Source,
        context: DataContext
    ): Set<String> {
        val candidates =
            when (source) {
                Source.RACELIST ->
                    historicalAllowedRacelist +
                        liveOnlyRacelist

                Source.HORSELIST ->
                    historicalAllowedHorselist +
                        semanticPendingLiveAllowedHorselist +
                        liveOnlyHorselist

                Source.PAYBACK ->
                    emptySet()
            }

        return candidates.filterTo(
            linkedSetOf()
        ) {
            featureDecision(
                source = source,
                column = it,
                context = context
            ) == FeatureDecision.ALLOWED
        }
    }

    fun isTrainServingCompatibleFeature(
        source: Source,
        column: String,
        trainingContext: DataContext,
        servingContext: DataContext
    ): Boolean =
        featureDecision(
            source = source,
            column = column,
            context = trainingContext
        ) == FeatureDecision.ALLOWED &&
            featureDecision(
                source = source,
                column = column,
                context = servingContext
            ) == FeatureDecision.ALLOWED

    fun keyColumns(
        source: Source
    ): Set<String> =
        when (source) {
            Source.RACELIST ->
                raceKeys

            Source.HORSELIST ->
                entryKeys

            Source.PAYBACK ->
                emptySet()
        }

    private fun featurePolicy(
        column: String,
        isKey: Boolean,
        historicalAllowed: Set<String>,
        semanticPendingLiveAllowed: Set<String>,
        liveOnly: Set<String>,
        deferredDynamic: Set<String>,
        deferredAudit: Set<String>,
        forbidden: Set<String>
    ): ColumnPolicy =
        when (column) {
            in historicalAllowed ->
                ColumnPolicy(
                    isKey = isKey,
                    historicalMonthly =
                        FeatureDecision.ALLOWED,
                    livePitSnapshot =
                        FeatureDecision.ALLOWED
                )

            in semanticPendingLiveAllowed ->
                ColumnPolicy(
                    isKey = isKey,
                    historicalMonthly =
                        FeatureDecision.DEFERRED_SEMANTIC,
                    livePitSnapshot =
                        FeatureDecision.ALLOWED
                )

            in liveOnly ->
                ColumnPolicy(
                    isKey = isKey,
                    historicalMonthly =
                        FeatureDecision.PENDING_PIT,
                    livePitSnapshot =
                        FeatureDecision.ALLOWED
                )

            in deferredDynamic ->
                ColumnPolicy(
                    isKey = isKey,
                    historicalMonthly =
                        FeatureDecision.DEFERRED_DYNAMIC,
                    livePitSnapshot =
                        FeatureDecision.DEFERRED_DYNAMIC
                )

            in deferredAudit ->
                ColumnPolicy(
                    isKey = isKey,
                    historicalMonthly =
                        FeatureDecision.DEFERRED_AUDIT,
                    livePitSnapshot =
                        FeatureDecision.DEFERRED_AUDIT
                )

            in forbidden ->
                ColumnPolicy(
                    isKey = isKey,
                    historicalMonthly =
                        FeatureDecision.FORBIDDEN_RESULT,
                    livePitSnapshot =
                        FeatureDecision.FORBIDDEN_RESULT
                )

            else -> {
                val decision =
                    if (isKey) {
                        FeatureDecision.NOT_FEATURE
                    } else {
                        FeatureDecision.DENIED_UNKNOWN
                    }

                ColumnPolicy(
                    isKey = isKey,
                    historicalMonthly = decision,
                    livePitSnapshot = decision
                )
            }
        }

    private fun readExistingColumn(
        table: NarCsvParser.CsvTable,
        row: List<String>,
        column: String
    ): String {
        val index =
            table.header[column]
                ?: error(
                    "required CSV column missing: $column"
                )

        require(
            index >= 0 &&
                index < row.size
        ) {
            "CSV row shorter than header for column: $column"
        }

        return row[index]
    }

    private fun requireFeatureGroupsDisjoint(
        source: Source,
        groups: List<Set<String>>
    ) {
        val seen =
            mutableSetOf<String>()

        for (group in groups) {
            for (column in group) {
                require(
                    seen.add(column)
                ) {
                    "feature contract overlap: " +
                        "${source.name}/$column"
                }
            }
        }
    }
}
