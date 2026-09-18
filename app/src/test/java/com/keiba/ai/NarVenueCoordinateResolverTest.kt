package com.keiba.ai

import com.keiba.ai.model.RaceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets

class NarVenueCoordinateResolverTest {

    private val config: NarVenueCoordinateConfig by lazy {
        val candidates =
            listOf(
                File(
                    "config/nar-v3-venue-coordinates.json"
                ),
                File(
                    "../config/nar-v3-venue-coordinates.json"
                )
            )

        val file =
            candidates.firstOrNull {
                it.isFile
            }
                ?: error(
                    "missing config/nar-v3-venue-coordinates.json"
                )

        NarVenueCoordinateConfigParser
            .parse(
                file.readBytes()
            )
    }

    @Test
    fun parsesOfficialConfigWithFifteenVenues() {
        assertEquals(1, config.version)
        assertEquals(
            "WGS84",
            config.coordinateSystem
        )
        assertEquals(
            15,
            config.rulesByVenue.size
        )
    }

    @Test
    fun resolvesFixedVenueOoi() {
        val coordinate =
            NarVenueCoordinateResolver
                .resolve(
                    config = config,
                    track = "大井",
                    raceDateYyyymmdd =
                        20260829
                )

        assertNotNull(coordinate)
        assertEquals(
            35.591339,
            coordinate!!.latitude,
            0.0
        )
        assertEquals(
            139.742608,
            coordinate.longitude,
            0.0
        )
    }

    @Test
    fun resolvesMultipleVenuesByExactTrackMatch() {
        val obihiro =
            NarVenueCoordinateResolver
                .resolve(
                    config,
                    "帯広ば",
                    20250102
                )

        val sonoda =
            NarVenueCoordinateResolver
                .resolve(
                    config,
                    "園田",
                    20250102
                )

        assertEquals(
            42.921,
            obihiro!!.latitude,
            0.0
        )
        assertEquals(
            34.766583,
            sonoda!!.latitude,
            0.0
        )
    }

    @Test
    fun resolvesViaRaceKey() {
        val coordinate =
            NarVenueCoordinateResolver
                .resolve(
                    config,
                    RaceKey(
                        "船橋",
                        20210101,
                        1
                    )
                )

        assertEquals(
            35.684722,
            coordinate!!.latitude,
            0.0
        )
        assertEquals(
            139.997778,
            coordinate.longitude,
            0.0
        )
    }

    @Test
    fun nagoyaOldBoundaryInclusive() {
        val coordinate =
            NarVenueCoordinateResolver
                .resolve(
                    config,
                    "名古屋",
                    20220311
                )

        assertEquals(
            35.112458,
            coordinate!!.latitude,
            0.0
        )
        assertEquals(
            136.866569,
            coordinate.longitude,
            0.0
        )
    }

    @Test
    fun nagoyaNewBoundaryInclusive() {
        val coordinate =
            NarVenueCoordinateResolver
                .resolve(
                    config,
                    "名古屋",
                    20220408
                )

        assertEquals(
            35.0525,
            coordinate!!.latitude,
            0.0
        )
        assertEquals(
            136.78472,
            coordinate.longitude,
            0.0
        )
    }

    @Test
    fun nagoyaRelocationGapReturnsNull() {
        assertNull(
            NarVenueCoordinateResolver
                .resolve(
                    config,
                    "名古屋",
                    20220312
                )
        )
        assertNull(
            NarVenueCoordinateResolver
                .resolve(
                    config,
                    "名古屋",
                    20220407
                )
        )
    }

    @Test
    fun unknownVenueReturnsNull() {
        assertNull(
            NarVenueCoordinateResolver
                .resolve(
                    config,
                    "岩見ば",
                    19980801
                )
        )
    }

    @Test
    fun resolvedCoordinatesBuildForecastRequestUrl() {
        val coordinate =
            NarVenueCoordinateResolver
                .resolve(
                    config,
                    "大井",
                    20260829
                )!!

        val requestUrl =
            NarForecastWeatherDownloader
                .buildRequestUrl(
                    latitude =
                        coordinate.latitude,
                    longitude =
                        coordinate.longitude
                )

        assertTrue(
            requestUrl.contains(
                "latitude=${coordinate.latitude}"
            )
        )
        assertTrue(
            requestUrl.contains(
                "longitude=${coordinate.longitude}"
            )
        )
        assertTrue(
            requestUrl.startsWith(
                "https://api.open-meteo.com/v1/forecast?"
            )
        )
    }

    @Test
    fun rejectsMalformedRaceDate() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarVenueCoordinateResolver
                .resolve(
                    config,
                    "大井",
                    20230229
                )
        }
    }

    @Test
    fun rejectsUnsupportedVersion() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarVenueCoordinateConfigParser
                .parse(
                    """
                    {
                      "version":2,
                      "coordinate_system":"WGS84",
                      "venues":{
                        "大井":[{"from":null,"to":null,"latitude":35.0,"longitude":139.0}]
                      }
                    }
                    """.trimIndent()
                )
        }
    }

    @Test
    fun rejectsWrongCoordinateSystem() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarVenueCoordinateConfigParser
                .parse(
                    """
                    {
                      "version":1,
                      "coordinate_system":"TokyoDatum",
                      "venues":{
                        "大井":[{"from":null,"to":null,"latitude":35.0,"longitude":139.0}]
                      }
                    }
                    """.trimIndent()
                )
        }
    }

    @Test
    fun rejectsMissingRequiredField() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarVenueCoordinateConfigParser
                .parse(
                    """
                    {
                      "version":1,
                      "coordinate_system":"WGS84",
                      "venues":{
                        "大井":[{"from":null,"to":null,"longitude":139.0}]
                      }
                    }
                    """.trimIndent()
                )
        }
    }

    @Test
    fun rejectsInvalidLatitude() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarVenueCoordinateConfigParser
                .parse(
                    """
                    {
                      "version":1,
                      "coordinate_system":"WGS84",
                      "venues":{
                        "大井":[{"from":null,"to":null,"latitude":91.0,"longitude":139.0}]
                      }
                    }
                    """.trimIndent()
                )
        }
    }

    @Test
    fun rejectsNonFiniteLongitude() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarVenueCoordinateConfigParser
                .parse(
                    """
                    {
                      "version":1,
                      "coordinate_system":"WGS84",
                      "venues":{
                        "大井":[{"from":null,"to":null,"latitude":35.0,"longitude":"NaN"}]
                      }
                    }
                    """.trimIndent()
                )
        }
    }

    @Test
    fun rejectsMalformedFromTo() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarVenueCoordinateConfigParser
                .parse(
                    """
                    {
                      "version":1,
                      "coordinate_system":"WGS84",
                      "venues":{
                        "大井":[{"from":"2022-13-01","to":null,"latitude":35.0,"longitude":139.0}]
                      }
                    }
                    """.trimIndent()
                )
        }
    }

    @Test
    fun rejectsFromAfterTo() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarVenueCoordinateConfigParser
                .parse(
                    """
                    {
                      "version":1,
                      "coordinate_system":"WGS84",
                      "venues":{
                        "大井":[{"from":"2022-04-08","to":"2022-03-11","latitude":35.0,"longitude":139.0}]
                      }
                    }
                    """.trimIndent()
                )
        }
    }

    @Test
    fun rejectsOverlappingRules() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarVenueCoordinateConfigParser
                .parse(
                    """
                    {
                      "version":1,
                      "coordinate_system":"WGS84",
                      "venues":{
                        "大井":[
                          {"from":null,"to":"2022-06-01","latitude":35.0,"longitude":139.0},
                          {"from":"2022-05-01","to":null,"latitude":35.1,"longitude":139.1}
                        ]
                      }
                    }
                    """.trimIndent()
                )
        }
    }

    @Test
    fun rejectsEmptyVenueRules() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarVenueCoordinateConfigParser
                .parse(
                    """
                    {
                      "version":1,
                      "coordinate_system":"WGS84",
                      "venues":{
                        "大井":[]
                      }
                    }
                    """.trimIndent()
                )
        }
    }

    @Test
    fun rejectsOversizedConfig() {
        val bytes =
            ByteArray(
                (
                    NarVenueCoordinateConfigParser
                        .MAX_CONFIG_BYTES + 1L
                    ).toInt()
            )

        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarVenueCoordinateConfigParser
                .parse(
                    bytes
                )
        }
    }

    @Test
    fun rejectsInvalidUtf8() {
        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarVenueCoordinateConfigParser
                .parse(
                    byteArrayOf(
                        '{'.code.toByte(),
                        0x80.toByte()
                    )
                )
        }
    }

    @Test
    fun allowsHarmlessExtraMetadata() {
        val parsed =
            NarVenueCoordinateConfigParser
                .parse(
                    """
                    {
                      "version":1,
                      "purpose":"test",
                      "coordinate_system":"WGS84",
                      "notes":["x"],
                      "venues":{
                        "大井":[{
                          "from":null,
                          "to":null,
                          "latitude":35.5,
                          "longitude":139.5,
                          "location":"optional"
                        }]
                      }
                    }
                    """.trimIndent()
                        .toByteArray(
                            StandardCharsets.UTF_8
                        )
                )

        val coordinate =
            NarVenueCoordinateResolver
                .resolve(
                    parsed,
                    "大井",
                    20200101
                )

        assertEquals(
            35.5,
            coordinate!!.latitude,
            0.0
        )
    }
}
