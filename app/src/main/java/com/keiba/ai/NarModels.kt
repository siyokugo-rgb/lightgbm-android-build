package com.keiba.ai

data class RaceKey(
    val track: String,
    val date: String,
    val raceNo: Int
)

// 発走前に利用できるレース情報
data class RaceInfo(
    val key: RaceKey,
    val startTime: String,
    val raceType: String,
    val raceName: String,
    val surface: String,
    val direction: String,
    val distance: Int?,
    val weather: String,
    val trackCondition: String,
    val fieldSize: Int?,
    val conditions: String
)

// 発走前に利用できる馬情報
data class HorseEntry(
    val key: RaceKey,
    val horseNo: Int,
    val frameNo: Int?,
    val horseName: String,
    val sex: String,
    val age: Int?,
    val jockey: String,
    val assignedWeightRaw: String,
    val trainer: String,
    val bodyWeight: Int?,
    val bodyWeightChangeRaw: String,
    val careerRecord: String,
    val courseRecord: String,
    val distanceRecord: String
)

// 発走後に確定する結果
data class HorseResult(
    val key: RaceKey,
    val horseNo: Int,
    val finishPositionRaw: String,
    val timeRaw: String,
    val margin: String,
    val final3fRaw: String,
    val popularityRaw: String
)

// 払戻結果
data class PaybackInfo(
    val key: RaceKey,
    val winHorseNo: String,
    val winPayoutYen: String,
    val place1HorseNo: String,
    val place1PayoutYen: String,
    val place2HorseNo: String,
    val place2PayoutYen: String,
    val place3HorseNo: String,
    val place3PayoutYen: String,
    val frameQuinella1: String,
    val frameQuinella2: String,
    val frameQuinellaPayoutYen: String,
    val quinella1: String,
    val quinella2: String,
    val quinellaPayoutYen: String
)

data class PreRaceData(
    val race: RaceInfo,
    val entries: List<HorseEntry>
)

data class RaceOutcome(
    val key: RaceKey,
    val results: List<HorseResult>,
    val payback: PaybackInfo
)
