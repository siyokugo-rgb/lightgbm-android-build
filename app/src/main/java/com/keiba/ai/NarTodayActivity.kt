package com.keiba.ai

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.util.Locale

class NarTodayActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var trackSpinner: Spinner
    private lateinit var raceSpinner: Spinner
    private lateinit var resultView: TextView
    private lateinit var resultScroll: ScrollView
    private lateinit var refreshButton: Button

    private var daily:
        NarDailyV1Predictor.DailyPrediction? =
        null

    private var selectedRaces:
        List<NarDailyV1Predictor.RacePrediction> =
        emptyList()

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        actionBar?.hide()

        val root =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    32,
                    32,
                    32,
                    32
                )

                setOnApplyWindowInsetsListener { v, insets ->
                    @Suppress("DEPRECATION")
                    v.setPadding(
                        32,
                        32 + insets.systemWindowInsetTop,
                        32,
                        32 + insets.systemWindowInsetBottom
                    )
                    insets
                }
            }

        val title =
            TextView(this).apply {
                text = "KeibaAI - NAR V1"
                textSize = 24f
                gravity = Gravity.CENTER
            }

        statusView =
            TextView(this).apply {
                text = "準備中"
                textSize = 16f

                setPadding(
                    0,
                    24,
                    0,
                    16
                )
            }

        refreshButton =
            Button(this).apply {
                text = "当日データを更新"
            }

        val trackLabel =
            TextView(this).apply {
                text = "開催場"
                textSize = 16f

                setPadding(
                    0,
                    24,
                    0,
                    4
                )
            }

        trackSpinner =
            Spinner(this)

        val raceLabel =
            TextView(this).apply {
                text = "レース"
                textSize = 16f

                setPadding(
                    0,
                    20,
                    0,
                    4
                )
            }

        raceSpinner =
            Spinner(this)

        resultView =
            TextView(this).apply {
                textSize = 18f

                setPadding(
                    0,
                    24,
                    0,
                    48
                )
            }

        resultScroll =
            ScrollView(this).apply {
                addView(resultView)
            }

        root.addView(title)
        root.addView(statusView)
        root.addView(refreshButton)
        root.addView(trackLabel)
        root.addView(trackSpinner)
        root.addView(raceLabel)
        root.addView(raceSpinner)

        root.addView(
            resultScroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)

        refreshButton.setOnClickListener {
            loadToday()
        }

        trackSpinner.onItemSelectedListener =
            object :
                AdapterView.OnItemSelectedListener {

                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    selectTrack(position)
                }

                override fun onNothingSelected(
                    parent: AdapterView<*>?
                ) {
                }
            }

        raceSpinner.onItemSelectedListener =
            object :
                AdapterView.OnItemSelectedListener {

                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    selectRace(position)
                }

                override fun onNothingSelected(
                    parent: AdapterView<*>?
                ) {
                }
            }

        loadToday()
    }

    private fun loadToday() {

        refreshButton.isEnabled = false
        trackSpinner.isEnabled = false
        raceSpinner.isEnabled = false

        statusView.text =
            "NAR公式から当日データを取得・予測中..."

        resultView.text = ""

        Thread {

            try {

                val prediction =
                    NarDailyV1Predictor.predict(
                        this@NarTodayActivity
                    )

                runOnUiThread {

                    daily = prediction

                    val totalEntries =
                        prediction.races.sumOf {
                            it.entries.size
                        }

                    statusView.text =
                        "${formatDate(prediction.date)}" +
                            " / " +
                            "${prediction.races.size}レース" +
                            " / " +
                            "${totalEntries}頭"

                    val tracks =
                        prediction.races
                            .map {
                                it.key.track
                            }
                            .distinct()

                    require(
                        tracks.isNotEmpty()
                    ) {
                        "開催場がありません"
                    }

                    trackSpinner.adapter =
                        ArrayAdapter(
                            this,
                            android.R.layout
                                .simple_spinner_item,
                            tracks
                        ).apply {
                            setDropDownViewResource(
                                android.R.layout
                                    .simple_spinner_dropdown_item
                            )
                        }

                    trackSpinner.isEnabled = true
                    refreshButton.isEnabled = true
                }

            } catch (t: Throwable) {

                runOnUiThread {

                    statusView.text =
                        "当日データ取得・予測に失敗"

                    resultView.text =
                        "${t.javaClass.simpleName}: " +
                            "${t.message}"

                    refreshButton.isEnabled = true
                }
            }

        }.start()
    }

    private fun selectTrack(
        position: Int
    ) {

        val current =
            daily ?: return

        val tracks =
            current.races
                .map {
                    it.key.track
                }
                .distinct()

        if (
            position !in tracks.indices
        ) {
            return
        }

        val track =
            tracks[position]

        selectedRaces =
            current.races
                .filter {
                    it.key.track == track
                }
                .sortedBy {
                    it.key.raceNumber
                }

        val labels =
            selectedRaces.map {
                "${it.key.raceNumber}R " +
                    "(${it.entries.size}頭)"
            }

        raceSpinner.adapter =
            ArrayAdapter(
                this,
                android.R.layout
                    .simple_spinner_item,
                labels
            ).apply {
                setDropDownViewResource(
                    android.R.layout
                        .simple_spinner_dropdown_item
                )
            }

        raceSpinner.isEnabled =
            selectedRaces.isNotEmpty()
    }

    private fun selectRace(
        position: Int
    ) {

        if (
            position !in selectedRaces.indices
        ) {
            return
        }

        renderRace(
            selectedRaces[position]
        )
    }

    private fun renderRace(
        race:
            NarDailyV1Predictor.RacePrediction
    ) {

        resultView.text =
            buildString {

                append(race.key.track)
                append(" ")
                append(race.key.raceNumber)
                append("R")

                append("\n出走=")
                append(race.entries.size)
                append("頭")

                append(
                    "\n\n=== V1予測順位 ==="
                )

                for (
                    (index, entry)
                    in race.entries.withIndex()
                ) {

                    append("\n\n")
                    append(index + 1)
                    append("位  ")

                    append(
                        entry.horseNumber
                    )
                    append("番  ")

                    append(
                        entry.horseName
                    )

                    append("\nV1予測値=")

                    append(
                        String.format(
                            Locale.US,
                            "%.6f",
                            entry.prediction
                        )
                    )
                }

                append(
                    "\n\n※V1予測値はモデル出力で、" +
                        "レース内で合計100%になる" +
                        "正規化確率ではありません。"
                )
            }

        resultScroll.post {
            resultScroll.scrollTo(0, 0)
        }
    }

    private fun formatDate(
        raw: String
    ): String {

        if (raw.length != 8) {
            return raw
        }

        return (
            raw.substring(0, 4) +
                "/" +
                raw.substring(4, 6) +
                "/" +
                raw.substring(6, 8)
            )
    }
}
