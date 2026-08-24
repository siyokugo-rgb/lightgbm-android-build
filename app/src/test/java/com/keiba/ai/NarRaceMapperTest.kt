package com.keiba.ai

import com.keiba.ai.model.BetType
import com.keiba.ai.model.HorseEntry
import com.keiba.ai.model.RaceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NarRaceMapperTest {

    @Test
    fun mapsPreRaceOutcomeAndPayoutSeparately() {
        val races = NarCsvParser.parseTable(
            """
            競馬場,競走年月日,レース番号,発走時刻,距離,天候,馬場,頭数,レース名
            大井,19980806,1,1545,1600,曇,稍重,2,４才
            """.trimIndent()
        )

        val horses = NarCsvParser.parseTable(
            """
            競馬場,競走年月日,レース番号,馬番,馬名,騎手名,負担重量,馬体重,着順
            大井,19980806,1,2,ヨシノフトー,本村直,53,454,2
            大井,19980806,1,1,タヤスナミカゼ,佐々竹,53,434,1
            """.trimIndent()
        )

        val paybacks = NarCsvParser.parseTable(
            """
            競馬場,競走年月日,レース番号,単勝組番,単勝払戻金（円）,馬複組番1,馬複組番2,馬複払戻金（円）
            大井,19980806,1,1,350,1,2,1290
            """.trimIndent()
        )

        val bundle = NarRaceMapper.map(
            races = races,
            horses = horses,
            paybacks = paybacks,
            key = RaceKey("大井", 19980806, 1)
        )

        assertEquals(2, bundle.entries.size)
        assertEquals(listOf(1, 2), bundle.entries.map { it.horseNumber })

        assertEquals(2, bundle.outcomes.size)
        assertEquals(listOf(1, 2), bundle.outcomes.map { it.horseNumber })
        assertEquals(listOf(1, 2), bundle.outcomes.map { it.finishPosition })

        assertEquals(
            350,
            bundle.payouts.first { it.betType == BetType.WIN }.amountYen
        )
        assertEquals(
            listOf(1, 2),
            bundle.payouts.first { it.betType == BetType.QUINELLA }.combination
        )

        assertFalse(
            HorseEntry::class.java.declaredFields.any {
                it.name == "finishPosition"
            }
        )
    }
    @Test
    fun missingHorseNumberFails() {
        val races = NarCsvParser.parseTable(
            """
            競馬場,競走年月日,レース番号,頭数
            大井,19980806,1,1
            """.trimIndent()
        )

        val horses = NarCsvParser.parseTable(
            """
            競馬場,競走年月日,レース番号,馬番,馬名
            大井,19980806,1,,テスト馬
            """.trimIndent()
        )

        val paybacks = NarCsvParser.parseTable(
            """
            競馬場,競走年月日,レース番号
            """.trimIndent()
        )

        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            NarRaceMapper.map(
                races,
                horses,
                paybacks,
                RaceKey("大井", 19980806, 1)
            )
        }
    }

    @Test
    fun missingHorseNameFails() {
        val races = NarCsvParser.parseTable(
            """
            競馬場,競走年月日,レース番号,頭数
            大井,19980806,1,1
            """.trimIndent()
        )

        val horses = NarCsvParser.parseTable(
            """
            競馬場,競走年月日,レース番号,馬番,馬名
            大井,19980806,1,1,
            """.trimIndent()
        )

        val paybacks = NarCsvParser.parseTable(
            """
            競馬場,競走年月日,レース番号
            """.trimIndent()
        )

        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            NarRaceMapper.map(
                races,
                horses,
                paybacks,
                RaceKey("大井", 19980806, 1)
            )
        }
    }

    @Test
    fun preRaceRowsDoNotCreateOutcomes() {
        val races = NarCsvParser.parseTable(
            """
            競馬場,競走年月日,レース番号,頭数
            大井,19980806,1,2
            """.trimIndent()
        )

        val horses = NarCsvParser.parseTable(
            """
            競馬場,競走年月日,レース番号,馬番,馬名,着順
            大井,19980806,1,1,テスト馬A,
            大井,19980806,1,2,テスト馬B,
            """.trimIndent()
        )

        val paybacks = NarCsvParser.parseTable(
            """
            競馬場,競走年月日,レース番号
            """.trimIndent()
        )

        val bundle = NarRaceMapper.map(
            races,
            horses,
            paybacks,
            RaceKey("大井", 19980806, 1)
        )

        assertEquals(2, bundle.entries.size)
        assertEquals(0, bundle.outcomes.size)
        assertEquals(0, bundle.payouts.size)
    }

    @Test
    fun missingPaybackRowCreatesEmptyPayouts() {
        val races = NarCsvParser.parseTable(
            """
            競馬場,競走年月日,レース番号,頭数
            大井,19980806,1,1
            """.trimIndent()
        )

        val horses = NarCsvParser.parseTable(
            """
            競馬場,競走年月日,レース番号,馬番,馬名,着順
            大井,19980806,1,1,テスト馬,1
            """.trimIndent()
        )

        val paybacks = NarCsvParser.parseTable(
            """
            競馬場,競走年月日,レース番号
            船橋,19980806,1
            """.trimIndent()
        )

        val bundle = NarRaceMapper.map(
            races,
            horses,
            paybacks,
            RaceKey("大井", 19980806, 1)
        )

        assertEquals(1, bundle.entries.size)
        assertEquals(1, bundle.outcomes.size)
        assertEquals(0, bundle.payouts.size)
    }

}
