package com.keiba.ai

import com.keiba.ai.model.RaceRecord
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Converts NAR race calendar date + scheduled post time to UTC epoch
 * seconds for [NarForecastWeatherTargetSelector].
 *
 * ## Observed raw contract (repo fixtures)
 *
 * Racelist column `発走時刻` examples in this repository:
 * - `1050`, `1120` ([NarCsvParserTest])
 * - `1545` ([NarRaceMapperTest])
 * - `1020` ([NarPreRaceFeatureContractTest])
 *
 * Formal raw string contract for Weather conversion:
 * - ASCII digits only
 * - exactly 4 characters (`HHMM`)
 * - no `:`, no fullwidth digits, no partial digit extraction
 *
 * Unproven forms (colon `HH:MM`, 3-digit raw strings, `24:00`) are
 * rejected rather than guessed.
 *
 * ## Int HHMM encoding
 *
 * [com.keiba.ai.model.RaceRecord.postTime] stores numeric HHMM
 * (`hour * 100 + minute`). Leading zeros are not preserved in `Int`
 * (e.g. raw `0930` → `930`), so the Int path accepts any valid
 * `hour∈[0,23]`, `minute∈[0,59]` encoding.
 *
 * ## Scheduled vs actual
 *
 * Per `docs/nar-pit-contract.md` / `docs/nar-model-contract.md`,
 * prediction uses **予定発走時刻 (scheduled start)**, not a later
 * actual start. This helper treats inputs as scheduled local times.
 *
 * Historical PIT provenance / change-history capture for post times
 * remains a deferred audit (see pit contract). This Step establishes
 * the runtime conversion boundary only.
 *
 * ## Timezone
 *
 * Local civil time is interpreted in `Asia/Tokyo` via
 * [ZoneId.of], then converted to UTC epoch seconds.
 */
object NarRaceScheduledStartTime {

    private val tokyo =
        ZoneId.of("Asia/Tokyo")

    private val rawPostTimeRegex =
        Regex("""^[0-9]{4}$""")

    /**
     * Strict raw racelist `発走時刻` → numeric HHMM.
     */
    fun parsePostTimeRaw(
        raw: String
    ): Int {
        require(
            rawPostTimeRegex.matches(raw)
        ) {
            "invalid raw post time"
        }

        val value =
            raw.toInt()

        validateHhmm(value)

        return value
    }

    /**
     * `YYYYMMDD` + numeric HHMM (Asia/Tokyo) → UTC epoch seconds.
     */
    fun toEpochSeconds(
        raceDateYyyymmdd: Int,
        postTimeHhmm: Int
    ): Long {
        val date =
            parseRaceDate(
                raceDateYyyymmdd
            )

        validateHhmm(postTimeHhmm)

        val hour =
            postTimeHhmm / 100

        val minute =
            postTimeHhmm % 100

        val local =
            LocalDateTime.of(
                date,
                LocalTime.of(
                    hour,
                    minute
                )
            )

        return local
            .atZone(tokyo)
            .toInstant()
            .epochSecond
    }

    /**
     * Convenience for mapped [RaceRecord].
     * Missing `postTime` → `null` (never invents midnight).
     */
    fun toEpochSecondsOrNull(
        race: RaceRecord
    ): Long? {
        val postTime =
            race.postTime
                ?: return null

        return toEpochSeconds(
            raceDateYyyymmdd =
                race.key.date,
            postTimeHhmm =
                postTime
        )
    }

    private fun parseRaceDate(
        raceDateYyyymmdd: Int
    ): LocalDate {
        require(
            raceDateYyyymmdd in
                1_000_0101..9_999_1231
        ) {
            "invalid race date"
        }

        val year =
            raceDateYyyymmdd / 10_000

        val month =
            (raceDateYyyymmdd / 100) %
                100

        val day =
            raceDateYyyymmdd % 100

        return try {
            LocalDate.of(
                year,
                month,
                day
            )
        } catch (error: DateTimeException) {
            throw IllegalArgumentException(
                "invalid race date",
                error
            )
        }
    }

    private fun validateHhmm(
        postTimeHhmm: Int
    ) {
        require(postTimeHhmm >= 0) {
            "invalid post time"
        }

        val hour =
            postTimeHhmm / 100

        val minute =
            postTimeHhmm % 100

        require(hour in 0..23) {
            "invalid post time hour"
        }

        require(minute in 0..59) {
            "invalid post time minute"
        }
    }
}
