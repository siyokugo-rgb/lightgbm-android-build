package com.keiba.ai

import android.content.Context
import com.keiba.ai.model.RaceKey
import com.keiba.ai.model.RaceRecord
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * In-app R6 ForecastWeather runtime self-check for Debug builds.
 *
 * Uses a dedicated temporary root under [Context.getNoBackupFilesDir]
 * and never writes to the production
 * `nar-forecast-weather-snapshots` directory.
 */
object NarForecastWeatherRuntimeSelfCheck {

    private const val SELF_CHECK_DIR =
        "r6-weather-selfcheck"

    private const val PRODUCTION_ROOT_NAME =
        "nar-forecast-weather-snapshots"

    enum class Verdict {
        OK,
        NEEDS_REVIEW,
        FAIL
    }

    enum class DuplicateKeyBehavior {
        REJECT,
        LAST_WINS,
        OTHER
    }

    data class Result(
        val verdict: Verdict,
        val venueAssetOk: Boolean,
        val venueCount: Int?,
        val platformOrgJsonValidOk: Boolean,
        val platformOrgJsonMalformedRejectOk: Boolean,
        val duplicateKeyBehavior: DuplicateKeyBehavior?,
        val scheduledUtcEpochSeconds: Long?,
        val venueLatitude: Double?,
        val venueLongitude: Double?,
        val snapshotSaveOk: Boolean,
        val pitReaderOk: Boolean,
        val futureExclusionOk: Boolean,
        val targetHourOk: Boolean,
        val targetEpochSeconds: Long?,
        val targetTemperatureCelsius: Double?,
        val errorClass: String? = null,
        val errorMessage: String? = null
    ) {
        fun formatDisplay(): String =
            buildString {
                append("=== R6 Weather 実機検証 ===")

                append("\nvenue asset=")
                append(okLabel(venueAssetOk))

                append("\nvenue count=")
                append(
                    venueCount?.toString()
                        ?: "取得失敗"
                )

                append("\nplatform org.json=")
                append(
                    if (
                        platformOrgJsonValidOk &&
                        platformOrgJsonMalformedRejectOk
                    ) {
                        "正常"
                    } else {
                        "異常"
                    }
                )

                append("\nduplicate key=")
                append(
                    when (duplicateKeyBehavior) {
                        DuplicateKeyBehavior.REJECT ->
                            "REJECT"

                        DuplicateKeyBehavior.LAST_WINS ->
                            "LAST_WINS"

                        DuplicateKeyBehavior.OTHER ->
                            "OTHER"

                        null ->
                            "取得失敗"
                    }
                )

                append("\nscheduled UTC=")
                append(
                    scheduledUtcEpochSeconds
                        ?.toString()
                        ?: "取得失敗"
                )

                append("\nvenue coordinate=")
                if (
                    venueLatitude != null &&
                    venueLongitude != null
                ) {
                    append(venueLatitude)
                    append(",")
                    append(venueLongitude)
                } else {
                    append("取得失敗")
                }

                append("\nsnapshot save=")
                append(okLabel(snapshotSaveOk))

                append("\nPIT reader=")
                append(okLabel(pitReaderOk))

                append("\nfuture exclusion=")
                append(okLabel(futureExclusionOk))

                append("\ntarget hour=")
                if (targetHourOk && targetEpochSeconds != null) {
                    append("正常 epoch=")
                    append(targetEpochSeconds)
                    if (targetTemperatureCelsius != null) {
                        append(" temp=")
                        append(targetTemperatureCelsius)
                    }
                } else {
                    append("異常")
                }

                append("\n総合判定=")
                append(
                    when (verdict) {
                        Verdict.OK ->
                            "正常"

                        Verdict.NEEDS_REVIEW ->
                            "要確認"

                        Verdict.FAIL ->
                            "異常"
                    }
                )

                if (errorClass != null) {
                    append("\nerror=")
                    append(errorClass)
                    if (!errorMessage.isNullOrBlank()) {
                        append(": ")
                        append(
                            errorMessage.take(160)
                        )
                    }
                }

                if (
                    duplicateKeyBehavior ==
                        DuplicateKeyBehavior.LAST_WINS
                ) {
                    append(
                        "\n\n※duplicate-key=LAST_WINS は" +
                            "既知Medium（Android platform org.json）。" +
                            "fail-closedではないため総合判定は要確認。"
                    )
                }
            }

        private fun okLabel(
            ok: Boolean
        ): String =
            if (ok) "正常" else "異常"
    }

    fun run(
        context: Context
    ): Result {
        val selfCheckRoot =
            File(
                context.noBackupFilesDir,
                SELF_CHECK_DIR
            )

        val runRoot =
            File(
                selfCheckRoot,
                UUID.randomUUID().toString()
            )

        val productionRoot =
            File(
                context.noBackupFilesDir,
                PRODUCTION_ROOT_NAME
            )

        try {
            require(
                runRoot.mkdirs() ||
                    runRoot.isDirectory
            ) {
                "could not create weather self-check root"
            }

            require(
                !runRoot.canonicalPath
                    .startsWith(
                        productionRoot
                            .canonicalPath
                    )
            ) {
                "self-check root must not be under production snapshots"
            }

            return runInRoot(
                context = context,
                runRoot = runRoot,
                productionRoot = productionRoot
            )
        } catch (error: Throwable) {
            return Result(
                verdict = Verdict.FAIL,
                venueAssetOk = false,
                venueCount = null,
                platformOrgJsonValidOk = false,
                platformOrgJsonMalformedRejectOk =
                    false,
                duplicateKeyBehavior = null,
                scheduledUtcEpochSeconds = null,
                venueLatitude = null,
                venueLongitude = null,
                snapshotSaveOk = false,
                pitReaderOk = false,
                futureExclusionOk = false,
                targetHourOk = false,
                targetEpochSeconds = null,
                targetTemperatureCelsius = null,
                errorClass =
                    error.javaClass.simpleName,
                errorMessage =
                    error.message
            )
        } finally {
            // Only delete our dedicated self-check tree.
            if (selfCheckRoot.exists()) {
                selfCheckRoot.deleteRecursively()
            }
        }
    }

    private fun runInRoot(
        context: Context,
        runRoot: File,
        productionRoot: File
    ): Result {
        val config =
            NarVenueCoordinateAssetLoader
                .load(
                    context
                )

        val venueAssetOk =
            config.version == 1 &&
                config.coordinateSystem ==
                "WGS84" &&
                config.rulesByVenue.size == 15

        val venueCount =
            config.rulesByVenue.size

        val orgJson =
            probePlatformOrgJson()

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

        val scheduledUtc =
            NarRaceScheduledStartTime
                .toEpochSecondsOrNull(
                    race
                )

        require(scheduledUtc != null) {
            "scheduled start missing"
        }

        require(
            scheduledUtc ==
                1_787_985_900L
        ) {
            "unexpected scheduled UTC"
        }

        val coordinate =
            NarVenueCoordinateResolver
                .resolve(
                    config = config,
                    raceKey = race.key
                )

        require(coordinate != null) {
            "venue coordinate missing"
        }

        require(
            coordinate.latitude ==
                35.591339 &&
                coordinate.longitude ==
                139.742608
        ) {
            "unexpected Ooi coordinates"
        }

        val floorHour =
            (scheduledUtc / 3_600L) *
                3_600L

        require(
            floorHour ==
                1_787_983_200L
        ) {
            "unexpected floor hour"
        }

        val hourlyTimes =
            listOf(
                floorHour - 7_200L,
                floorHour - 3_600L,
                floorHour,
                floorHour + 3_600L,
                floorHour + 7_200L
            )

        val temperatureMarker =
            21.5

        val responseBytes =
            syntheticForecastJson(
                times = hourlyTimes,
                temperatureMarker =
                    temperatureMarker
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

        val downloadedAt =
            1_787_970_000_000L

        val predictionAsOf =
            1_787_990_000_000L

        require(downloadedAt < predictionAsOf)

        val saved =
            NarForecastWeatherSnapshotStore
                .saveToRoot(
                    root = runRoot,
                    data =
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

        val snapshotSaveOk =
            File(
                saved.directory,
                "forecast.json"
            ).isFile &&
                File(
                    saved.directory,
                    "manifest.txt"
                ).isFile &&
                NarForecastWeatherSnapshotStore
                    .verifySnapshot(
                        saved.directory
                    ) &&
                saved.directory
                    .canonicalPath
                    .startsWith(
                        runRoot.canonicalPath
                    ) &&
                !saved.directory
                    .canonicalPath
                    .startsWith(
                        productionRoot
                            .canonicalPath
                    )

        val snapshot =
            NarForecastWeatherSnapshotReader
                .readFromRoot(
                    root = runRoot,
                    latitude =
                        coordinate.latitude,
                    longitude =
                        coordinate.longitude,
                    predictionAsOfEpochMillis =
                        predictionAsOf
                )

        val pitReaderOk =
            snapshot != null &&
                snapshot.payload.hourly.size ==
                5

        val selected =
            if (snapshot != null) {
                NarForecastWeatherTargetSelector
                    .select(
                        payload =
                            snapshot.payload,
                        raceScheduledStartEpochSeconds =
                            scheduledUtc
                    )
            } else {
                null
            }

        val targetHourOk =
            selected != null &&
                selected.targetEpochSeconds ==
                floorHour &&
                selected.temperature2mCelsius ==
                temperatureMarker

        // Future exclusion: past eligible vs future snapshot.
        val futureDownloadedAt =
            1_788_010_000_000L

        require(futureDownloadedAt > predictionAsOf)

        NarForecastWeatherSnapshotStore
            .saveToRoot(
                root = runRoot,
                data =
                    NarForecastWeatherDownloader
                        .ForecastResponse(
                            requestedLatitude =
                                coordinate.latitude,
                            requestedLongitude =
                                coordinate.longitude,
                            requestUrl =
                                requestUrl,
                            responseBytes =
                                syntheticForecastJson(
                                    times =
                                        listOf(
                                            floorHour -
                                                3_600L,
                                            floorHour,
                                            floorHour +
                                                3_600L
                                        ),
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

        val afterFuture =
            NarForecastWeatherSnapshotReader
                .readFromRoot(
                    root = runRoot,
                    latitude =
                        coordinate.latitude,
                    longitude =
                        coordinate.longitude,
                    predictionAsOfEpochMillis =
                        predictionAsOf
                )

        val futureExclusionOk =
            afterFuture != null &&
                afterFuture.selection
                    .downloadedAtEpochMillis ==
                downloadedAt &&
                afterFuture.selection
                    .pitEvidenceAtEpochMillis <=
                predictionAsOf &&
                afterFuture.payload
                    .hourly[2]
                    .temperature2mCelsius ==
                temperatureMarker

        val coreOk =
            venueAssetOk &&
                orgJson.validOk &&
                orgJson.malformedRejectOk &&
                scheduledUtc ==
                1_787_985_900L &&
                snapshotSaveOk &&
                pitReaderOk &&
                futureExclusionOk &&
                targetHourOk

        val verdict =
            when {
                !coreOk ->
                    Verdict.FAIL

                orgJson.duplicateKeyBehavior ==
                    DuplicateKeyBehavior.LAST_WINS ->
                    Verdict.NEEDS_REVIEW

                else ->
                    Verdict.OK
            }

        return Result(
            verdict = verdict,
            venueAssetOk = venueAssetOk,
            venueCount = venueCount,
            platformOrgJsonValidOk =
                orgJson.validOk,
            platformOrgJsonMalformedRejectOk =
                orgJson.malformedRejectOk,
            duplicateKeyBehavior =
                orgJson.duplicateKeyBehavior,
            scheduledUtcEpochSeconds =
                scheduledUtc,
            venueLatitude =
                coordinate.latitude,
            venueLongitude =
                coordinate.longitude,
            snapshotSaveOk = snapshotSaveOk,
            pitReaderOk = pitReaderOk,
            futureExclusionOk =
                futureExclusionOk,
            targetHourOk = targetHourOk,
            targetEpochSeconds =
                selected?.targetEpochSeconds,
            targetTemperatureCelsius =
                selected?.temperature2mCelsius
        )
    }

    private data class OrgJsonProbe(
        val validOk: Boolean,
        val malformedRejectOk: Boolean,
        val duplicateKeyBehavior:
            DuplicateKeyBehavior
    )

    private fun probePlatformOrgJson():
        OrgJsonProbe {
        val validBody =
            syntheticForecastJson(
                times = listOf(
                    1_787_983_200L,
                    1_787_986_800L
                ),
                temperatureMarker = 18.0
            ).toByteArray(
                StandardCharsets.UTF_8
            )

        val validOk =
            try {
                val payload =
                    NarForecastWeatherParser
                        .parse(
                            validBody
                        )

                payload.hourly.size == 2 &&
                    payload.hourly[0]
                        .temperature2mCelsius ==
                    18.0
            } catch (_: Throwable) {
                false
            }

        val malformedRejectOk =
            try {
                NarForecastWeatherParser.parse(
                    "{".toByteArray(
                        StandardCharsets.UTF_8
                    )
                )
                false
            } catch (_: IllegalArgumentException) {
                true
            } catch (_: Throwable) {
                false
            }

        val duplicateBody =
            """
            {
              "latitude":1.0,
              "latitude":2.0,
              "longitude":139.75,
              "utc_offset_seconds":0,
              "hourly":{
                "time":[1787983200],
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

        val duplicateKeyBehavior =
            try {
                val raw =
                    JSONObject(
                        duplicateBody
                    )

                val observed =
                    raw.getDouble(
                        "latitude"
                    )

                when (observed) {
                    2.0 ->
                        DuplicateKeyBehavior
                            .LAST_WINS

                    1.0 ->
                        DuplicateKeyBehavior
                            .OTHER

                    else ->
                        DuplicateKeyBehavior
                            .OTHER
                }
            } catch (_: Throwable) {
                DuplicateKeyBehavior.REJECT
            }

        return OrgJsonProbe(
            validOk = validOk,
            malformedRejectOk =
                malformedRejectOk,
            duplicateKeyBehavior =
                duplicateKeyBehavior
        )
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

        return """
            {
              "latitude":35.6,
              "longitude":139.75,
              "utc_offset_seconds":0,
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
