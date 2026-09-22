package com.keiba.ai

/**
 * Documented JMA public latest-CSV product allowlist.
 *
 * Raw archive is product-wide (one CSV per product capture).
 * Station / venue mapping is applied by a later parser / row-selection
 * layer — not by the downloader or snapshot store.
 */
enum class NarJmaObservedWeatherProduct(
    val productId: String,
    val family: Family,
    private val documentedPath: String
) {
    PRE1H00_RCT(
        productId = "pre1h00_rct",
        family = Family.PRECIPITATION,
        documentedPath =
            "/stats/data/mdrr/pre_rct/alltable/pre1h00_rct.csv"
    ),
    PRE3H00_RCT(
        productId = "pre3h00_rct",
        family = Family.PRECIPITATION,
        documentedPath =
            "/stats/data/mdrr/pre_rct/alltable/pre3h00_rct.csv"
    ),
    PRE6H00_RCT(
        productId = "pre6h00_rct",
        family = Family.PRECIPITATION,
        documentedPath =
            "/stats/data/mdrr/pre_rct/alltable/pre6h00_rct.csv"
    ),
    PRE12H00_RCT(
        productId = "pre12h00_rct",
        family = Family.PRECIPITATION,
        documentedPath =
            "/stats/data/mdrr/pre_rct/alltable/pre12h00_rct.csv"
    ),
    PRE24H00_RCT(
        productId = "pre24h00_rct",
        family = Family.PRECIPITATION,
        documentedPath =
            "/stats/data/mdrr/pre_rct/alltable/pre24h00_rct.csv"
    ),
    MXTEMSADEXT00_RCT(
        productId = "mxtemsadext00_rct",
        family = Family.TEMPERATURE_EXTREME,
        documentedPath =
            "/stats/data/mdrr/tem_rct/alltable/mxtemsadext00_rct.csv"
    ),
    MNTEMSADEXT00_RCT(
        productId = "mntemsadext00_rct",
        family = Family.TEMPERATURE_EXTREME,
        documentedPath =
            "/stats/data/mdrr/tem_rct/alltable/mntemsadext00_rct.csv"
    ),
    MXWSP00_RCT(
        productId = "mxwsp00_rct",
        family = Family.WIND_MAX,
        documentedPath =
            "/stats/data/mdrr/wind_rct/alltable/mxwsp00_rct.csv"
    ),
    GUST00_RCT(
        productId = "gust00_rct",
        family = Family.GUST_MAX,
        documentedPath =
            "/stats/data/mdrr/wind_rct/alltable/gust00_rct.csv"
    );

    enum class Family {
        PRECIPITATION,
        TEMPERATURE_EXTREME,
        WIND_MAX,
        GUST_MAX
    }

    fun canonicalUrl(): String =
        "https://" +
            HOST +
            documentedPath

    companion object {
        const val HOST =
            "www.data.jma.go.jp"

        private val byId =
            entries.associateBy {
                it.productId
            }

        fun fromProductId(
            productId: String
        ): NarJmaObservedWeatherProduct =
            byId[productId]
                ?: throw IllegalArgumentException(
                    "unknown JMA observed weather product"
                )

        fun isKnownProductId(
            productId: String
        ): Boolean =
            productId in byId
    }
}
