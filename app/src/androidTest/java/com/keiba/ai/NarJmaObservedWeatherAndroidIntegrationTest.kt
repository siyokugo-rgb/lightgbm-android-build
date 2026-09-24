package com.keiba.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.keiba.ai.model.RaceKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.charset.Charset

/**
 * Android runtime integration for ObservedWeather Step J.
 *
 * Network is not used. Production observed snapshot root is not deleted.
 */
@RunWith(AndroidJUnit4::class)
class NarJmaObservedWeatherAndroidIntegrationTest {

    private lateinit var isolatedRoot: File
    private lateinit var testContext: IsolatedNoBackupFilesContext

    private val shiftJis =
        Charset.forName("Shift_JIS")

    @Before
    fun setUp() {
        val base =
            InstrumentationRegistry
                .getInstrumentation()
                .targetContext

        isolatedRoot =
            File(
                base.cacheDir,
                "r6-observed-it-" +
                    System.nanoTime()
            )

        require(isolatedRoot.mkdirs()) {
            "could not create isolated observed test root"
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
    fun loadsMappingAssetWithFifteenVenues() {
        val mapping =
            NarJmaObservedLiveStationMappingAssetLoader
                .load(
                    testContext
                )

        assertEquals(1, mapping.version)
        assertEquals(
            "LIVE_CAPTURE_MAPPING",
            mapping.status
        )
        assertEquals(
            "NOT_YET_AUTHORIZED",
            mapping.featureEligibility
        )
        assertEquals(15, mapping.venues.size)
    }

    @Test
    fun packagedAssetsContainExactlyOneMappingAndVenueSoT() {
        val names =
            testContext.assets
                .list("")
                ?.toList()
                .orEmpty()

        assertEquals(
            1,
            names.count {
                it ==
                    NarJmaObservedLiveStationMappingAssetLoader
                        .ASSET_NAME
            }
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
    fun resolverAndPitReaderOnIsolatedRoot() {
        val mapping =
            NarJmaObservedLiveStationMappingAssetLoader
                .load(
                    testContext
                )

        val selection =
            NarJmaObservedLiveStationResolver
                .resolve(
                    mapping = mapping,
                    raceKey = RaceKey(
                        "川崎",
                        20260922,
                        1
                    ),
                    product =
                        NarJmaObservedWeatherProduct
                            .PRE1H00_RCT
                )

        assertEquals(
            "46061",
            selection!!.stationId
        )

        val root =
            File(
                testContext.noBackupFilesDir,
                "nar-jma-observed-weather-snapshots"
            )

        NarJmaObservedWeatherSnapshotStore
            .saveToRoot(
                root = root,
                data =
                    syntheticPrecip(
                        downloadedAt =
                            1_788_001_100_000L,
                        stationId = "46061"
                    )
            )

        val runtime =
            NarJmaObservedWeatherRuntimeReader
                .readForRaceFromRoot(
                    root = root,
                    mapping = mapping,
                    raceKey = RaceKey(
                        "川崎",
                        20260922,
                        1
                    ),
                    product =
                        NarJmaObservedWeatherProduct
                            .PRE1H00_RCT,
                    predictionAsOfEpochMillis =
                        1_788_001_200_000L
                )

        assertNotNull(runtime)
        assertEquals(
            "46061",
            runtime!!.snapshot.stationId
        )
        assertTrue(
            runtime.snapshot.selectedRow is
                NarJmaObservedPrecipitationRow
        )
    }

    @Test
    fun selfCheckDoesNotTouchProductionRoot() {
        val production =
            File(
                testContext.noBackupFilesDir,
                "nar-jma-observed-weather-snapshots"
            )

        val before =
            if (production.exists()) {
                production.walkTopDown()
                    .count()
            } else {
                -1
            }

        val result =
            NarJmaObservedWeatherRuntimeSelfCheck
                .run(
                    testContext
                )

        assertEquals(
            NarJmaObservedWeatherRuntimeSelfCheck
                .Verdict.OK,
            result.verdict
        )

        val after =
            if (production.exists()) {
                production.walkTopDown()
                    .count()
            } else {
                -1
            }

        assertEquals(before, after)
        assertTrue(
            result.productionRootUntouchedOk
        )
    }

    private fun syntheticPrecip(
        downloadedAt: Long,
        stationId: String
    ): NarJmaObservedWeatherDownloader.ObservedCsvResponse {
        val product =
            NarJmaObservedWeatherProduct
                .PRE1H00_RCT

        val header =
            "観測所番号,都道府県,地点,国際地点番号,現在時刻(年),現在時刻(月),現在時刻(日),現在時刻(時),現在時刻(分),現在値(mm),現在値の品質情報,29日の最大値(mm),29日の最大値の品質情報,29日の最大値起時（時）(まで),29日の最大値起時（分）(まで),29日の最大値起時(まで)の品質情報,極値更新,10年未満での極値更新,21日までの観測史上1位の値(mm),21日までの観測史上1位の値の品質情報,21日までの観測史上1位の値の年,21日までの観測史上1位の値の月,21日までの観測史上1位の値の日,21日までの8月の1位の値(mm),21日までの8月の1位の値の品質情報,21日までの8月の1位の値の年,21日までの8月の1位の値の月,21日までの8月の1位の値の日,統計開始年"

        val cells =
            MutableList(29) { "" }

        cells[0] = stationId
        cells[1] = "神奈川県"
        cells[2] = "日吉"
        cells[4] = "2026"
        cells[5] = "8"
        cells[6] = "29"
        cells[7] = "09"
        cells[8] = "40"
        cells[9] = "0.5"
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
                serverDateEpochMillis = null,
                lastModifiedEpochMillis = null
            )
    }
}
