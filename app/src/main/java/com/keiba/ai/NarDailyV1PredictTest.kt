package com.keiba.ai

import android.content.Context
import android.os.SystemClock
import java.util.Locale

object NarDailyV1PredictTest {

    fun run(
        context: Context
    ): String {

        val start =
            SystemClock.elapsedRealtime()

        return try {

            val daily =
                NarDailyV1Predictor.predict(
                    context
                )

            require(
                daily.races.isNotEmpty()
            ) {
                "no predicted races"
            }

            var entryCount = 0

            var minPrediction =
                Double.POSITIVE_INFINITY

            var maxPrediction =
                Double.NEGATIVE_INFINITY

            for (race in daily.races) {

                require(
                    race.entries.isNotEmpty()
                ) {
                    "race has no predictions: " +
                        race.key
                }

                val horseNumbers =
                    race.entries.map {
                        it.horseNumber
                    }

                require(
                    horseNumbers
                        .distinct()
                        .size ==
                        horseNumbers.size
                ) {
                    "duplicate horse number: " +
                        race.key
                }

                for (entry in race.entries) {

                    require(
                        entry.prediction
                            .isFinite()
                    ) {
                        "non-finite prediction"
                    }

                    require(
                        entry.prediction >= 0.0 &&
                            entry.prediction <= 1.0
                    ) {
                        "prediction out of range"
                    }

                    entryCount++

                    if (
                        entry.prediction <
                        minPrediction
                    ) {
                        minPrediction =
                            entry.prediction
                    }

                    if (
                        entry.prediction >
                        maxPrediction
                    ) {
                        maxPrediction =
                            entry.prediction
                    }
                }
            }

            val elapsed =
                SystemClock.elapsedRealtime() -
                    start

            val firstRace =
                daily.races.first()

            buildString {

                append(
                    "NAR DAILY V1 PREDICT OK"
                )

                append("\ndate=")
                append(daily.date)

                append("\nraces=")
                append(daily.races.size)

                append("\nentries=")
                append(entryCount)

                append("\npredicted=")
                append(entryCount)

                append("\nfailed=0")

                append("\nmin_prediction=")
                append(
                    String.format(
                        Locale.US,
                        "%.9f",
                        minPrediction
                    )
                )

                append("\nmax_prediction=")
                append(
                    String.format(
                        Locale.US,
                        "%.9f",
                        maxPrediction
                    )
                )

                append("\nelapsed_ms=")
                append(elapsed)

                append(
                    "\n\n=== FIRST RACE ==="
                )

                append("\n")
                append(firstRace.key.track)

                append(" ")
                append(
                    firstRace.key.raceNumber
                )
                append("R")

                append("\nentries=")
                append(
                    firstRace.entries.size
                )

                for (
                    (index, entry)
                    in firstRace.entries
                        .take(5)
                        .withIndex()
                ) {
                    append("\n")

                    append(index + 1)
                    append("位 ")

                    append(
                        entry.horseNumber
                    )
                    append("番 ")

                    append(
                        entry.horseName
                    )

                    append(" prediction=")

                    append(
                        String.format(
                            Locale.US,
                            "%.9f",
                            entry.prediction
                        )
                    )
                }
            }

        } catch (t: Throwable) {

            buildString {

                append(
                    "NAR DAILY V1 PREDICT FAIL"
                )

                append("\n")
                append(
                    t.javaClass.simpleName
                )

                append(": ")

                append(
                    t.message
                        ?: "(no message)"
                )
            }
        }
    }
}
