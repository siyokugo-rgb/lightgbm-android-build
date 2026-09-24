package com.keiba.ai

import android.content.Context
import com.keiba.ai.model.RaceKey
import java.io.File
import java.nio.charset.Charset
import java.util.UUID

/**
 * Debug-only ObservedWeather Android runtime self-check.
 *
 * Uses dedicated
 * `noBackupFilesDir/r6-observed-weather-selfcheck/`
 * and never writes/deletes production
 * `nar-jma-observed-weather-snapshots`.
 */
object NarJmaObservedWeatherRuntimeSelfCheck {

    private const val SELF_CHECK_DIR =
        "r6-observed-weather-selfcheck"

    private const val PRODUCTION_ROOT_NAME =
        "nar-jma-observed-weather-snapshots"

    private val shiftJis =
        Charset.forName("Shift_JIS")

    enum class Verdict {
        OK,
        NEEDS_REVIEW,
        FAIL
    }

    data class Result(
        val verdict: Verdict,
        val mappingAssetOk: Boolean,
        val mappingContractOk: Boolean,
        val venueCount: Int?,
        val kawasakiPrecipStationId: String?,
        val kawasakiWindStationId: String?,
        val nagoyaStationId: String?,
        val snapshotSaveOk: Boolean,
        val pitReaderOk: Boolean,
        val parserOk: Boolean,
        val exactStationOk: Boolean,
        val futureExclusionOk: Boolean,
        val productionRootUntouchedOk: Boolean,
        val errorClass: String? = null,
        val errorMessage: String? = null
    ) {
        fun formatDisplay(): String =
            buildString {
                append(
                    "=== R6 ObservedWeather 実機検証 ==="
                )

                append("\nObserved mapping asset=")
                append(okLabel(mappingAssetOk))

                append(
                    "\nObserved mapping contract="
                )
                append(okLabel(mappingContractOk))

                append("\nObserved venue count=")
                append(
                    venueCount?.toString()
                        ?: "取得失敗"
                )

                append(
                    "\nKawasaki precip station="
                )
                append(
                    kawasakiPrecipStationId
                        ?: "取得失敗"
                )

                append(
                    "\nKawasaki wind station="
                )
                append(
                    kawasakiWindStationId
                        ?: "取得失敗"
                )

                append("\nNagoya station=")
                append(
                    nagoyaStationId
                        ?: "取得失敗"
                )

                append(
                    "\nObserved snapshot save="
                )
                append(okLabel(snapshotSaveOk))

                append(
                    "\nObserved PIT reader="
                )
                append(okLabel(pitReaderOk))

                append("\nObserved parser=")
                append(okLabel(parserOk))

                append(
                    "\nObserved exact station="
                )
                append(okLabel(exactStationOk))

                append(
                    "\nObserved future exclusion="
                )
                append(okLabel(futureExclusionOk))

                append(
                    "\nObserved production root untouched="
                )
                append(
                    okLabel(
                        productionRootUntouchedOk
                    )
                )

                append("\nObserved総合判定=")
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

        val productionMarkerBefore =
            productionRootSignature(
                productionRoot
            )

        try {
            require(
                runRoot.mkdirs() ||
                    runRoot.isDirectory
            ) {
                "could not create observed weather self-check root"
            }

            require(
                !runRoot.canonicalPath
                    .startsWith(
                        productionRoot
                            .canonicalPath
                    )
            ) {
                "self-check root must not be under production observed snapshots"
            }

            return runInRoot(
                context = context,
                runRoot = runRoot,
                productionRoot = productionRoot,
                productionMarkerBefore =
                    productionMarkerBefore
            )
        } catch (error: Throwable) {
            return Result(
                verdict = Verdict.FAIL,
                mappingAssetOk = false,
                mappingContractOk = false,
                venueCount = null,
                kawasakiPrecipStationId = null,
                kawasakiWindStationId = null,
                nagoyaStationId = null,
                snapshotSaveOk = false,
                pitReaderOk = false,
                parserOk = false,
                exactStationOk = false,
                futureExclusionOk = false,
                productionRootUntouchedOk =
                    productionRootSignature(
                        productionRoot
                    ) ==
                        productionMarkerBefore,
                errorClass =
                    error.javaClass.simpleName,
                errorMessage =
                    error.message
            )
        } finally {
            if (selfCheckRoot.exists()) {
                selfCheckRoot.deleteRecursively()
            }
        }
    }

    private fun runInRoot(
        context: Context,
        runRoot: File,
        productionRoot: File,
        productionMarkerBefore: String
    ): Result {
        val mapping =
            NarJmaObservedLiveStationMappingAssetLoader
                .load(
                    context
                )

        val mappingAssetOk =
            true

        val mappingContractOk =
            mapping.version == 1 &&
                mapping.status ==
                "LIVE_CAPTURE_MAPPING" &&
                mapping.role ==
                "LIVE_CAPTURED_OBSERVED" &&
                mapping.acquisitionStrategy ==
                "PUBLIC_ONLY_WITH_DEFERRED_GAPS" &&
                mapping.pitStatus ==
                "CANDIDATE" &&
                mapping.featureEligibility ==
                "NOT_YET_AUTHORIZED" &&
                mapping.mappingScope ==
                "prospective_live_capture_only" &&
                mapping.venues.size == 15

        val venueCount =
            mapping.venues.size

        val kawasakiPrecip =
            NarJmaObservedLiveStationResolver
                .resolve(
                    mapping = mapping,
                    raceKey = RaceKey(
                        track = "川崎",
                        date = 20260922,
                        raceNumber = 1
                    ),
                    product =
                        NarJmaObservedWeatherProduct
                            .PRE1H00_RCT
                )

        val kawasakiWind =
            NarJmaObservedLiveStationResolver
                .resolve(
                    mapping = mapping,
                    raceKey = RaceKey(
                        track = "川崎",
                        date = 20260922,
                        raceNumber = 1
                    ),
                    product =
                        NarJmaObservedWeatherProduct
                            .MXWSP00_RCT
                )

        val nagoya =
            NarJmaObservedLiveStationResolver
                .resolve(
                    mapping = mapping,
                    raceKey = RaceKey(
                        track = "名古屋",
                        date = 20260922,
                        raceNumber = 1
                    ),
                    product =
                        NarJmaObservedWeatherProduct
                            .PRE1H00_RCT
                )

        val pastDownloadedAt =
            1_788_001_100_000L

        val predictionAsOf =
            1_788_001_200_000L

        val saved =
            NarJmaObservedWeatherSnapshotStore
                .saveToRoot(
                    root = runRoot,
                    data =
                        syntheticPrecipResponse(
                            downloadedAt =
                                pastDownloadedAt,
                            serverDate = null,
                            stationId =
                                "46061",
                            stationName =
                                "日吉",
                            prefecture =
                                "神奈川県",
                            currentValue =
                                "0.5"
                        )
                )

        val snapshotSaveOk =
            saved.status ==
                NarJmaObservedWeatherSnapshotStore
                    .SaveStatus.CREATED

        val runtime =
            NarJmaObservedWeatherRuntimeReader
                .readForRaceFromRoot(
                    root = runRoot,
                    mapping = mapping,
                    raceKey = RaceKey(
                        track = "川崎",
                        date = 20260922,
                        raceNumber = 1
                    ),
                    product =
                        NarJmaObservedWeatherProduct
                            .PRE1H00_RCT,
                    predictionAsOfEpochMillis =
                        predictionAsOf
                )

        val pitReaderOk =
            runtime != null

        val parserOk =
            runtime?.snapshot
                ?.selectedRow is
                NarJmaObservedPrecipitationRow

        val exactStationOk =
            runtime?.mappingSelection
                ?.stationId == "46061" &&
                runtime.snapshot.stationId ==
                "46061"

        val future =
            NarJmaObservedWeatherSnapshotStore
                .saveToRoot(
                    root = runRoot,
                    data =
                        syntheticPrecipResponse(
                            downloadedAt =
                                1_788_001_300_000L,
                            serverDate = null,
                            stationId =
                                "46061",
                            stationName =
                                "日吉",
                            prefecture =
                                "神奈川県",
                            currentValue =
                                "9.9"
                        )
                )

        File(
            future.directory,
            "observed.csv"
        ).writeText(
            "CORRUPTED_FUTURE_BODY"
        )

        val stillPast =
            NarJmaObservedWeatherSnapshotSelector
                .selectLatestFromRoot(
                    root = runRoot,
                    product =
                        NarJmaObservedWeatherProduct
                            .PRE1H00_RCT,
                    predictionAsOfEpochMillis =
                        predictionAsOf
                )

        val futureExclusionOk =
            stillPast != null &&
                stillPast.directory
                    .canonicalFile ==
                saved.directory
                    .canonicalFile

        val productionRootUntouchedOk =
            productionRootSignature(
                productionRoot
            ) ==
                productionMarkerBefore

        val checks =
            listOf(
                mappingAssetOk,
                mappingContractOk,
                venueCount == 15,
                kawasakiPrecip?.stationId ==
                    "46061",
                kawasakiWind?.stationId ==
                    "44166",
                nagoya?.stationId ==
                    "53041",
                snapshotSaveOk,
                pitReaderOk,
                parserOk,
                exactStationOk,
                futureExclusionOk,
                productionRootUntouchedOk
            )

        val verdict =
            if (checks.all { it }) {
                Verdict.OK
            } else if (
                mappingAssetOk &&
                mappingContractOk
            ) {
                Verdict.NEEDS_REVIEW
            } else {
                Verdict.FAIL
            }

        return Result(
            verdict = verdict,
            mappingAssetOk =
                mappingAssetOk,
            mappingContractOk =
                mappingContractOk,
            venueCount = venueCount,
            kawasakiPrecipStationId =
                kawasakiPrecip?.stationId,
            kawasakiWindStationId =
                kawasakiWind?.stationId,
            nagoyaStationId =
                nagoya?.stationId,
            snapshotSaveOk =
                snapshotSaveOk,
            pitReaderOk = pitReaderOk,
            parserOk = parserOk,
            exactStationOk =
                exactStationOk,
            futureExclusionOk =
                futureExclusionOk,
            productionRootUntouchedOk =
                productionRootUntouchedOk
        )
    }

    private fun syntheticPrecipResponse(
        downloadedAt: Long,
        serverDate: Long?,
        stationId: String,
        stationName: String,
        prefecture: String,
        currentValue: String
    ): NarJmaObservedWeatherDownloader.ObservedCsvResponse {
        val product =
            NarJmaObservedWeatherProduct
                .PRE1H00_RCT

        val header =
            "観測所番号,都道府県,地点,国際地点番号,現在時刻(年),現在時刻(月),現在時刻(日),現在時刻(時),現在時刻(分),現在値(mm),現在値の品質情報,29日の最大値(mm),29日の最大値の品質情報,29日の最大値起時（時）(まで),29日の最大値起時（分）(まで),29日の最大値起時(まで)の品質情報,極値更新,10年未満での極値更新,21日までの観測史上1位の値(mm),21日までの観測史上1位の値の品質情報,21日までの観測史上1位の値の年,21日までの観測史上1位の値の月,21日までの観測史上1位の値の日,21日までの8月の1位の値(mm),21日までの8月の1位の値の品質情報,21日までの8月の1位の値の年,21日までの8月の1位の値の月,21日までの8月の1位の値の日,統計開始年"

        val cells =
            MutableList(29) { "" }

        cells[0] = stationId
        cells[1] = prefecture
        cells[2] = stationName
        cells[4] = "2026"
        cells[5] = "8"
        cells[6] = "29"
        cells[7] = "09"
        cells[8] = "40"
        cells[9] = currentValue
        cells[10] = "8"

        return NarJmaObservedWeatherDownloader
            .ObservedCsvResponse(
                productId =
                    product.productId,
                requestUrl =
                    product.canonicalUrl(),
                responseBytes =
                    (
                        header +
                            "\r\n" +
                            cells.joinToString(",")
                    ).toByteArray(shiftJis),
                contentType = "text/csv",
                downloadedAtEpochMillis =
                    downloadedAt,
                serverDateEpochMillis =
                    serverDate,
                lastModifiedEpochMillis = null
            )
    }

    private fun productionRootSignature(
        productionRoot: File
    ): String {
        if (!productionRoot.exists()) {
            return "missing"
        }

        val files =
            productionRoot
                .walkTopDown()
                .filter { it.isFile }
                .map {
                    it.relativeTo(
                        productionRoot
                    ).path +
                        ":" +
                        it.length()
                }
                .sorted()
                .toList()

        return files.joinToString("|")
    }
}
