package com.keiba.ai.model

data class RaceKey(
    val track: String,
    val date: Int,
    val raceNumber: Int
)

data class RaceRecord(
    val key: RaceKey,
    val postTime: Int?,
    val distanceMeters: Int?,
    val weather: String?,
    val trackCondition: String?,
    val declaredCount: Int?,
    val raceName: String?
)

// 発走前に利用できる馬情報
data class HorseEntry(
    val key: RaceKey,
    val horseNumber: Int,
    val horseName: String,
    val jockey: String?,
    val assignedWeightKg: Double?,
    val bodyWeightKg: Int?
)

// 発走後に確定する結果
data class HorseOutcome(
    val key: RaceKey,
    val horseNumber: Int,
    val finishPosition: Int?
)

enum class BetType {
    WIN,
    PLACE,
    BRACKET_QUINELLA,
    BRACKET_EXACTA,
    QUINELLA,
    EXACTA,
    WIDE,
    TRIO,
    TRIFECTA
}

data class Payout(
    val key: RaceKey,
    val betType: BetType,
    val combination: List<Int>,
    val amountYen: Int
)

data class NarRaceBundle(
    val race: RaceRecord,
    val entries: List<HorseEntry>,
    val outcomes: List<HorseOutcome>,
    val payouts: List<Payout>
)
