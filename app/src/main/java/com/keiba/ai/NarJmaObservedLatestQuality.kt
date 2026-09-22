package com.keiba.ai

/**
 * Quality semantics for JMA public「最新の気象データ」CSV only.
 *
 * Not interchangeable with historical final (obsdl) quality codes.
 */
enum class NarJmaObservedLatestQuality(
    val rawCode: Int
) {
    NOT_STATISTIC(0),
    MISSING_OR_UNREPORTED(1),
    UNSUITABLE(2),
    QUESTIONABLE(3),
    INSUFFICIENT_DATA(4),
    SEMI_NORMAL(5),
    NORMAL(8);

    companion object {
        fun fromRawCode(
            rawCode: Int
        ): NarJmaObservedLatestQuality =
            entries.firstOrNull {
                it.rawCode == rawCode
            }
                ?: throw IllegalArgumentException(
                    "unknown JMA latest CSV quality code: $rawCode"
                )

        fun parseOptionalCell(
            cell: String
        ): NarJmaObservedLatestQuality? {
            val trimmed =
                cell.trim()

            if (trimmed.isEmpty()) {
                return null
            }

            val code =
                trimmed.toIntOrNull()
                    ?: throw IllegalArgumentException(
                        "invalid JMA latest CSV quality cell"
                    )

            return fromRawCode(code)
        }
    }
}
