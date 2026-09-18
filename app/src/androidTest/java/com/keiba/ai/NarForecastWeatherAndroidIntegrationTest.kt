package com.keiba.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keiba.ai.model.RaceKey
import com.keiba.ai.model.RaceRecord
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Android runtime integration for ForecastWeather plumbing (Step F).
 *
 * Network is not used. Production noBackupFilesDir is never deleted.
 */
@RunWith(AndroidJUnit4::class)
class NarForecastWeatherAndroidIntegrationTest {

    private lateinit var isolatedRoot: File
    private lateinit var testContext: IsolatedNoBackupFilesContext

    @Before
    fun setUp() {
        val base =
            InstrumentationRegistry
                .getInstrumentation()
                .targetContext

        isolatedRoot =
            File(
                base.cacheDir,
                "r6-weather-it-" +
                    System.nanoTime()
            )

        require(isolatedRoot.mkdirs()) {
            "could not create isolated test root"
        }

        testContext =
            IsolatedNoBackupFilesContext(
                base = base,
                isolatedNoBackupDir =
                    File(
                        isolatedRoot,
                        "no_backup"
                    )
            )
    }

    @After
    fun tearDown() {
        if (::isolatedRoot.isInitialized) {
            isolatedRoot.deleteRecursively()
        }
    }

    @Test
    fun loadsVenueConfigAssetWithFifteenVenues() {
        val config =
            NarVenueCoordinateAssetLoader
                .load(
                    testContext
                )

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
    fun missingVenueAssetNameIsAbsentFromPackagedAssets() {
        val names =
            testContext.assets
                .list("")
                ?.toList()
                .orEmpty()

        assertTrue(
            names.contains(
                NarVenueCoordinateAssetLoader
                    .ASSET_NAME
            )
        )
        assertEquals(
            1,
            names.count {
                it ==
                    NarVenueCoordinateAssetLoader
                        .ASSET_NAME
            }
        )
    }

    @Test
    fun endToEndRaceRecordToTargetHourOnAndroidFilesystem() {
        val config =
            NarVenueCoordinateAssetLoader
                .load(
                    testContext
                )

        val race =
            RaceRecord(
                key = RaceKey(
                    track = "大井",
                    date = 20260829,
                    raceNumber = 1
                ),
                postTime = 1545,
                distanceMeters = 1600,
                weather = null,
                trackCondition = null,
                declaredCount = null,
                raceName = null
            )

        val raceStartEpochSeconds =
            NarRaceScheduledStartTime
                .toEpochSecondsOrNull(
                    race
                )

        assertNotNull(raceStartEpochSeconds)

        // 2026-08-29 15:45 JST = 2026-08-29 06:45 UTC
        assertEquals(
            1_787_985_900L,
            raceStartEpochSeconds!!
        )

        val coordinate =
            NarVenueCoordinateResolver
                .resolve(
                    config = config,
                    raceKey = race.key
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

        val floorHour =
            (raceStartEpochSeconds /
                3_600L) * 3_600L

        assertEquals(
            1_787_983_200L,
            floorHour
        )

        val hourlyTimes =
            listOf(
                floorHour - 7_200L,
                floorHour - 3_600L,
                floorHour,
                floorHour + 3_600L,
                floorHour + 7_200L
            )

        val responseBytes =
            syntheticForecastJson(
                times = hourlyTimes,
                temperatureMarker =
                    21.5
            ).toByteArray(
                StandardCharsets.UTF_8
            )

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

        val downloadedAt =
            1_788_000_000_000L

        val predictionAsOf =
            1_788_010_000_000L

        require(downloadedAt < predictionAsOf)

        val saved =
            NarForecastWeatherSnapshotStore
                .save(
                    testContext,
                    NarForecastWeatherDownloader
                        .ForecastResponse(
                            requestedLatitude =
                                coordinate.latitude,
                            requestedLongitude =
                                coordinate.longitude,
                            requestUrl =
                                requestUrl,
                            responseBytes =
                                responseBytes,
                            downloadedAtEpochMillis =
                                downloadedAt,
                            serverDateEpochMillis =
                                downloadedAt +
                                    1_000L
                        )
                )

        assertTrue(
            saved.directory
                .absolutePath
                .startsWith(
                    testContext
                        .noBackupFilesDir
                        .absolutePath
                )
        )
        assertTrue(
            File(
                saved.directory,
                "forecast.json"
            ).isFile
        )
        assertTrue(
            File(
                saved.directory,
                "manifest.txt"
            ).isFile
        )
        assertTrue(
            NarForecastWeatherSnapshotStore
                .verifySnapshot(
                    saved.directory
                )
        )

        val productionRoot =
            File(
                InstrumentationRegistry
                    .getInstrumentation()
                    .targetContext
                    .noBackupFilesDir,
                "nar-forecast-weather-snapshots"
            )

        assertFalse(
            "test must not write into production noBackup root",
            saved.directory
                .canonicalPath
                .startsWith(
                    productionRoot
                        .canonicalPath
                )
        )

        val snapshot =
            NarForecastWeatherSnapshotReader
                .read(
                    context = testContext,
                    latitude =
                        coordinate.latitude,
                    longitude =
                        coordinate.longitude,
                    predictionAsOfEpochMillis =
                        predictionAsOf
                )

        assertNotNull(snapshot)
        assertEquals(
            5,
            snapshot!!.payload.hourly.size
        )

        val selected =
            NarForecastWeatherTargetSelector
                .select(
                    payload =
                        snapshot.payload,
                    raceScheduledStartEpochSeconds =
                        raceStartEpochSeconds
                )

        assertNotNull(selected)
        assertEquals(
            floorHour,
            selected!!.targetEpochSeconds
        )
        assertEquals(
            21.5,
            selected.temperature2mCelsius,
            0.0
        )
    }

    @Test
    fun pitSelectorPrefersPastEligibleOverFutureSnapshot() {
        val config =
            NarVenueCoordinateAssetLoader
                .load(
                    testContext
                )

        val coordinate =
            NarVenueCoordinateResolver
                .resolve(
                    config,
                    "大井",
                    20260829
                )!!

        val floorHour =
            1_787_983_200L

        val times =
            listOf(
                floorHour - 3_600L,
                floorHour,
                floorHour + 3_600L
            )

        val predictionAsOf =
            1_787_990_000_000L

        val pastDownloadedAt =
            1_787_970_000_000L

        val futureDownloadedAt =
            1_788_010_000_000L

        require(pastDownloadedAt < predictionAsOf)
        require(futureDownloadedAt > predictionAsOf)

        val requestUrl =
            NarForecastWeatherDownloader
                .buildRequestUrl(
                    coordinate.latitude,
                    coordinate.longitude
                )

        NarForecastWeatherSnapshotStore.save(
            testContext,
            NarForecastWeatherDownloader
                .ForecastResponse(
                    requestedLatitude =
                        coordinate.latitude,
                    requestedLongitude =
                        coordinate.longitude,
                    requestUrl = requestUrl,
                    responseBytes =
                        syntheticForecastJson(
                            times = times,
                            temperatureMarker =
                                11.0
                        ).toByteArray(
                            StandardCharsets.UTF_8
                        ),
                    downloadedAtEpochMillis =
                        pastDownloadedAt,
                    serverDateEpochMillis =
                        pastDownloadedAt
                )
        )

        NarForecastWeatherSnapshotStore.save(
            testContext,
            NarForecastWeatherDownloader
                .ForecastResponse(
                    requestedLatitude =
                        coordinate.latitude,
                    requestedLongitude =
                        coordinate.longitude,
                    requestUrl = requestUrl,
                    responseBytes =
                        syntheticForecastJson(
                            times = times,
                            temperatureMarker =
                                99.0
                        ).toByteArray(
                            StandardCharsets.UTF_8
                        ),
                    downloadedAtEpochMillis =
                        futureDownloadedAt,
                    serverDateEpochMillis =
                        futureDownloadedAt
                )
        )

        val snapshot =
            NarForecastWeatherSnapshotReader
                .read(
                    context = testContext,
                    latitude =
                        coordinate.latitude,
                    longitude =
                        coordinate.longitude,
                    predictionAsOfEpochMillis =
                        predictionAsOf
                )

        assertNotNull(snapshot)
        assertEquals(
            pastDownloadedAt,
            snapshot!!
                .selection
                .downloadedAtEpochMillis
        )
        assertEquals(
            11.0,
            snapshot.payload
                .hourly[1]
                .temperature2mCelsius,
            0.0
        )
        assertTrue(
            snapshot.selection
                .pitEvidenceAtEpochMillis <=
                predictionAsOf
        )
    }

    @Test
    fun platformOrgJsonParsesValidForecastAndRejectsMalformed() {
        val valid =
            syntheticForecastJson(
                times = listOf(
                    1_788_022_800L,
                    1_788_026_400L
                ),
                temperatureMarker = 18.0
            ).toByteArray(
                StandardCharsets.UTF_8
            )

        val payload =
            NarForecastWeatherParser
                .parse(
                    valid
                )

        assertEquals(2, payload.hourly.size)
        assertEquals(
            18.0,
            payload.hourly[0]
                .temperature2mCelsius,
            0.0
        )

        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarForecastWeatherParser.parse(
                "{".toByteArray(
                    StandardCharsets.UTF_8
                )
            )
        }

        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarForecastWeatherParser.parse(
                """
                {
                  "latitude":null,
                  "longitude":139.75,
                  "utc_offset_seconds":0,
                  "hourly":{
                    "time":[1788022800],
                    "temperature_2m":[1.0],
                    "relative_humidity_2m":[1],
                    "pressure_msl":[1.0],
                    "surface_pressure":[1.0],
                    "precipitation":[0.0],
                    "weather_code":[1],
                    "wind_speed_10m":[1.0],
                    "wind_direction_10m":[1.0],
                    "wind_gusts_10m":[1.0]
                  }
                }
                """.trimIndent()
                    .toByteArray(
                        StandardCharsets.UTF_8
                    )
            )
        }

        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarForecastWeatherParser.parse(
                """
                {
                  "latitude":35.6,
                  "longitude":139.75,
                  "utc_offset_seconds":0,
                  "hourly":{
                    "time":[1788022800],
                    "temperature_2m":["bad"],
                    "relative_humidity_2m":[1],
                    "pressure_msl":[1.0],
                    "surface_pressure":[1.0],
                    "precipitation":[0.0],
                    "weather_code":[1],
                    "wind_speed_10m":[1.0],
                    "wind_direction_10m":[1.0],
                    "wind_gusts_10m":[1.0]
                  }
                }
                """.trimIndent()
                    .toByteArray(
                        StandardCharsets.UTF_8
                    )
            )
        }
    }

    @Test
    fun platformOrgJsonDuplicateKeyIsLastWinsButForecastParserRejects() {
        val duplicateLatitude =
            """
            {
              "latitude":1.0,
              "latitude":2.0,
              "longitude":139.75,
              "utc_offset_seconds":0,
              "hourly":{
                "time":[1788022800],
                "temperature_2m":[1.0],
                "relative_humidity_2m":[1],
                "pressure_msl":[1.0],
                "surface_pressure":[1.0],
                "precipitation":[0.0],
                "weather_code":[1],
                "wind_speed_10m":[1.0],
                "wind_direction_10m":[1.0],
                "wind_gusts_10m":[1.0]
              }
            }
            """.trimIndent()

        val rawRoot =
            JSONObject(
                duplicateLatitude
            )

        assertEquals(
            2.0,
            rawRoot.getDouble("latitude"),
            0.0
        )

        assertThrows(
            IllegalArgumentException::class.java
        ) {
            NarForecastWeatherParser
                .parse(
                    duplicateLatitude
                        .toByteArray(
                            StandardCharsets.UTF_8
                        )
                )
        }
    }

    private fun syntheticForecastJson(
        times: List<Long>,
        temperatureMarker: Double
    ): String {
        require(times.isNotEmpty())

        val timeCsv =
            times.joinToString(",")

        val temps =
            times.indices.joinToString(",") {
                temperatureMarker.toString()
            }

        val ones =
            times.indices.joinToString(",") {
                "1.0"
            }

        val humidity =
            times.indices.joinToString(",") {
                "50"
            }

        val codes =
            times.indices.joinToString(",") {
                "1"
            }

        val units =
            NarForecastWeatherHourlyUnits
                .expectedObjectJson()

        return """
            {
              "latitude":35.6,
              "longitude":139.75,
              "utc_offset_seconds":0,
              "hourly_units":$units,
              "hourly":{
                "time":[$timeCsv],
                "temperature_2m":[$temps],
                "relative_humidity_2m":[$humidity],
                "pressure_msl":[$ones],
                "surface_pressure":[$ones],
                "precipitation":[$ones],
                "weather_code":[$codes],
                "wind_speed_10m":[$ones],
                "wind_direction_10m":[$ones],
                "wind_gusts_10m":[$ones]
              }
            }
            """.trimIndent()
    }
}
