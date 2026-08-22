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

data class HorseResult(
    val key: RaceKey,
    val horseNumber: Int,
    val horseName: String,
    val jockey: String?,
    val assignedWeightKg: Double?,
    val bodyWeightKg: Int?,
    val finishPosition: Int?
)

enum class BetType {
    WIN,
    PLACE,
    BRACKET_QUINELLA,
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
    val horses: List<HorseResult>,
    val payouts: List<Payout>
)
