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

enum class HorseOutcomeStatus {
    FINISHED,
    DID_NOT_FINISH,
    DISQUALIFIED
}

// 発走後に確定する競走結果
data class HorseOutcome(
    val key: RaceKey,
    val horseNumber: Int,
    val status: HorseOutcomeStatus,
    val finishPosition: Int?
)

enum class HorseNonStartStatus {
    SCRATCHED,
    EXCLUDED
}

// 出走しなかったことが確定した状態
data class HorseNonStart(
    val key: RaceKey,
    val horseNumber: Int,
    val status: HorseNonStartStatus
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
    val nonStarts: List<HorseNonStart>,
    val payouts: List<Payout>
)
