package com.keiba.ai

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/**
 * Selects the latest PIT-eligible ForecastWeather snapshot for a
 * requested coordinate pair.
 *
 * Eligibility:
 * `pit_evidence_at_epoch_millis <= prediction_as_of_epoch_millis`
 *
 * Does not parse forecast bodies for selection ranking.
 * Does not invent issuance/vintage timestamps.
 * Does not apply staleness thresholds or target-hour selection.
 */
object NarForecastWeatherSnapshotSelector {

    private const val ROOT_NAME =
        "nar-forecast-weather-snapshots"

    private const val FORMAT_VERSION =
        "1"

    private const val PROVIDER =
        "open-meteo"

    private const val MAX_MANIFEST_BYTES =
        32L * 1024L

    private val dayDirectoryRegex =
        Regex("""^\d{8}$""")

    private val snapshotDirectoryRegex =
        Regex(
            """^(\d+)-([0-9a-f]{64})$"""
        )

    private val stagingDirectoryRegex =
        Regex(
            """^\.(\d+)\.tmp-""" +
                """[0-9a-f]{8}-""" +
                """[0-9a-f]{4}-""" +
                """[0-9a-f]{4}-""" +
                """[0-9a-f]{4}-""" +
                """[0-9a-f]{12}$"""
        )

    private val sha256Regex =
        Regex("""^[0-9a-f]{64}$""")

    private val basicDateFormatter =
        DateTimeFormatter.BASIC_ISO_DATE
            .withResolverStyle(
                ResolverStyle.STRICT
            )

    private val requiredManifestKeys =
        setOf(
            "format_version",
            "provider",
            "capture_date_utc",
            "requested_latitude",
            "requested_longitude",
            "request_url",
            "downloaded_at_epoch_millis",
            "server_date_epoch_millis",
            "pit_evidence_at_epoch_millis",
            "forecast_sha256",
            "snapshot_sha256"
        )

    data class Selection(
        val requestedLatitude: Double,
        val requestedLongitude: Double,
        val requestUrl: String,
        val captureDateUtc: String,
        val downloadedAtEpochMillis: Long,
        val serverDateEpochMillis: Long?,
        val pitEvidenceAtEpochMillis: Long,
        val predictionAsOfEpochMillis: Long,
        val ageMillis: Long,
        val forecastSha256: String,
        val snapshotSha256: String,
        val directory: File
    )

    private data class PrefetchedManifest(
        val text: String,
        val requestedLatitudeText: String,
        val requestedLongitudeText: String,
        val requestUrl: String,
        val captureDateUtc: String,
        val downloadedAtEpochMillis: Long,
        val serverDateEpochMillis: Long?,
        val pitEvidenceAtEpochMillis: Long,
        val forecastSha256: String,
        val snapshotSha256: String
    )

    fun selectLatest(
        context: Context,
        latitude: Double,
        longitude: Double,
        predictionAsOfEpochMillis: Long
    ): Selection? =
        selectLatestFromRoot(
            root = File(
                context.noBackupFilesDir,
                ROOT_NAME
            ),
            latitude = latitude,
            longitude = longitude,
            predictionAsOfEpochMillis =
                predictionAsOfEpochMillis
        )

    internal fun selectLatestFromRoot(
        root: File,
        latitude: Double,
        longitude: Double,
        predictionAsOfEpochMillis: Long
    ): Selection? {
        require(
            latitude.isFinite() &&
                latitude in -90.0..90.0
        ) {
            "invalid latitude"
        }

        require(
            longitude.isFinite() &&
                longitude in -180.0..180.0
        ) {
            "invalid longitude"
        }

        require(
            predictionAsOfEpochMillis > 0L
        ) {
            "invalid predictionAsOf"
        }

        val canonicalRequestUrl =
            NarForecastWeatherDownloader
                .buildRequestUrl(
                    latitude,
                    longitude
                )

        if (!root.exists()) {
            return null
        }

        require(root.isDirectory) {
            "weather snapshot root is not a directory"
        }

        val canonicalRoot =
            root.canonicalFile

        val predictionDateUtc =
            captureDateUtc(
                predictionAsOfEpochMillis
            )

        val dayEntries =
            canonicalRoot.listFiles()
                ?: error(
                    "could not list weather snapshot root"
                )

        var best: Selection? = null

        for (dayEntry in dayEntries) {
            val dayName =
                dayEntry.name

            val canonicalDay =
                dayEntry.canonicalFile

            require(
                canonicalDay.parentFile ==
                    canonicalRoot &&
                    canonicalDay.name ==
                    dayName
            ) {
                "weather day path escaped root"
            }

            require(
                dayDirectoryRegex.matches(
                    dayName
                )
            ) {
                "unexpected entry in weather snapshot root: $dayName"
            }

            require(canonicalDay.isDirectory) {
                "weather day path is not a directory: $dayName"
            }

            require(
                runCatching {
                    LocalDate.parse(
                        dayName,
                        basicDateFormatter
                    )
                }.isSuccess
            ) {
                "invalid weather day calendar date: $dayName"
            }

            // Future capture days cannot contain
            // downloadedAt <= predictionAsOf.
            if (dayName > predictionDateUtc) {
                continue
            }

            val snapshotEntries =
                canonicalDay.listFiles()
                    ?: error(
                        "could not list weather day directory: $dayName"
                    )

            for (snapshotEntry in snapshotEntries) {
                val snapshotName =
                    snapshotEntry.name

                val canonicalSnapshot =
                    snapshotEntry.canonicalFile

                require(
                    canonicalSnapshot.parentFile ==
                        canonicalDay &&
                        canonicalSnapshot.name ==
                        snapshotName
                ) {
                    "weather snapshot path escaped day directory"
                }

                if (
                    stagingDirectoryRegex
                        .matches(snapshotName)
                ) {
                    require(
                        canonicalSnapshot.isDirectory
                    ) {
                        "weather staging path is not a directory"
                    }

                    continue
                }

                val nameMatch =
                    snapshotDirectoryRegex
                        .matchEntire(
                            snapshotName
                        )
                        ?: throw IllegalArgumentException(
                            "unexpected entry in weather day directory: $snapshotName"
                        )

                require(
                    canonicalSnapshot.isDirectory
                ) {
                    "weather snapshot entry is not a directory: $snapshotName"
                }

                val downloadedAtFromName =
                    nameMatch.groupValues[1]
                        .toLongOrNull()
                        ?: throw IllegalArgumentException(
                            "invalid weather snapshot downloadedAt in name"
                        )

                require(
                    downloadedAtFromName > 0L
                ) {
                    "invalid weather snapshot downloadedAt in name"
                }

                val snapshotShaFromName =
                    nameMatch.groupValues[2]

                // Future download completion:
                // skip without opening any files.
                if (
                    downloadedAtFromName >
                        predictionAsOfEpochMillis
                ) {
                    continue
                }

                val prefetched =
                    readAndValidateManifestPreflight(
                        directory =
                            canonicalSnapshot,
                        dayDirectoryName =
                            dayName,
                        downloadedAtFromName =
                            downloadedAtFromName,
                        snapshotShaFromName =
                            snapshotShaFromName
                    )

                if (
                    prefetched.requestUrl !=
                        canonicalRequestUrl
                ) {
                    continue
                }

                // serverDate may push pitEvidence
                // past predictionAsOf even when
                // downloadedAt is eligible.
                // Skip without opening forecast.json.
                if (
                    prefetched.pitEvidenceAtEpochMillis >
                        predictionAsOfEpochMillis
                ) {
                    continue
                }

                val manifestBefore =
                    prefetched.text

                require(
                    NarForecastWeatherSnapshotStore
                        .verifySnapshot(
                            canonicalSnapshot
                        )
                ) {
                    "eligible weather snapshot failed integrity verification"
                }

                val manifestAfter =
                    readManifestTextStrict(
                        canonicalSnapshot
                    )

                require(
                    manifestBefore ==
                        manifestAfter
                ) {
                    "weather snapshot manifest changed during verification"
                }

                val verified =
                    readAndValidateManifestPreflight(
                        directory =
                            canonicalSnapshot,
                        dayDirectoryName =
                            dayName,
                        downloadedAtFromName =
                            downloadedAtFromName,
                        snapshotShaFromName =
                            snapshotShaFromName,
                        expectedText =
                            manifestAfter
                    )

                val candidate =
                    Selection(
                        requestedLatitude =
                            latitude,
                        requestedLongitude =
                            longitude,
                        requestUrl =
                            verified.requestUrl,
                        captureDateUtc =
                            verified.captureDateUtc,
                        downloadedAtEpochMillis =
                            verified.downloadedAtEpochMillis,
                        serverDateEpochMillis =
                            verified.serverDateEpochMillis,
                        pitEvidenceAtEpochMillis =
                            verified.pitEvidenceAtEpochMillis,
                        predictionAsOfEpochMillis =
                            predictionAsOfEpochMillis,
                        ageMillis =
                            Math.subtractExact(
                                predictionAsOfEpochMillis,
                                verified
                                    .pitEvidenceAtEpochMillis
                            ),
                        forecastSha256 =
                            verified.forecastSha256,
                        snapshotSha256 =
                            verified.snapshotSha256,
                        directory =
                            canonicalSnapshot
                    )

                best =
                    chooseBest(
                        currentBest = best,
                        candidate = candidate
                    )
            }
        }

        return best
    }

    private fun chooseBest(
        currentBest: Selection?,
        candidate: Selection
    ): Selection {
        if (currentBest == null) {
            return candidate
        }

        if (
            candidate.pitEvidenceAtEpochMillis >
                currentBest.pitEvidenceAtEpochMillis
        ) {
            return candidate
        }

        if (
            candidate.pitEvidenceAtEpochMillis <
                currentBest.pitEvidenceAtEpochMillis
        ) {
            return currentBest
        }

        if (
            candidate.downloadedAtEpochMillis >
                currentBest.downloadedAtEpochMillis
        ) {
            return candidate
        }

        if (
            candidate.downloadedAtEpochMillis <
                currentBest.downloadedAtEpochMillis
        ) {
            return currentBest
        }

        if (
            candidate.snapshotSha256 ==
                currentBest.snapshotSha256 &&
                candidate.directory ==
                currentBest.directory
        ) {
            return currentBest
        }

        error(
            "ambiguous weather snapshot selection"
        )
    }

    private fun readAndValidateManifestPreflight(
        directory: File,
        dayDirectoryName: String,
        downloadedAtFromName: Long,
        snapshotShaFromName: String,
        expectedText: String? = null
    ): PrefetchedManifest {
        val text =
            expectedText
                ?: readManifestTextStrict(
                    directory
                )

        val fields =
            parseManifest(text)

        require(
            fields.keys ==
                requiredManifestKeys
        ) {
            "weather manifest key set mismatch"
        }

        require(
            fields["format_version"] ==
                FORMAT_VERSION
        ) {
            "unsupported weather manifest format"
        }

        require(
            fields["provider"] ==
                PROVIDER
        ) {
            "unexpected weather provider"
        }

        val captureDateUtc =
            fields.getValue(
                "capture_date_utc"
            )

        require(
            dayDirectoryRegex.matches(
                captureDateUtc
            )
        ) {
            "invalid weather capture_date_utc"
        }

        require(
            captureDateUtc ==
                dayDirectoryName
        ) {
            "weather capture day mismatch"
        }

        val requestedLatitudeText =
            fields.getValue(
                "requested_latitude"
            )

        val requestedLongitudeText =
            fields.getValue(
                "requested_longitude"
            )

        val requestedLatitude =
            requestedLatitudeText
                .toDoubleOrNull()
                ?: throw IllegalArgumentException(
                    "invalid weather requested_latitude"
                )

        val requestedLongitude =
            requestedLongitudeText
                .toDoubleOrNull()
                ?: throw IllegalArgumentException(
                    "invalid weather requested_longitude"
                )

        require(
            requestedLatitude.isFinite() &&
                requestedLatitude in -90.0..90.0
        ) {
            "invalid weather requested_latitude"
        }

        require(
            requestedLongitude.isFinite() &&
                requestedLongitude in -180.0..180.0
        ) {
            "invalid weather requested_longitude"
        }

        val requestUrl =
            fields.getValue(
                "request_url"
            )

        val expectedRequestUrl =
            NarForecastWeatherDownloader
                .buildRequestUrl(
                    requestedLatitude,
                    requestedLongitude
                )

        require(
            requestUrl ==
                expectedRequestUrl
        ) {
            "weather request URL/provenance mismatch"
        }

        require(
            requestedLatitudeText ==
                requestedLatitude.toString() &&
                requestedLongitudeText ==
                requestedLongitude.toString()
        ) {
            "weather requested coordinate text mismatch"
        }

        val downloadedAt =
            fields.getValue(
                "downloaded_at_epoch_millis"
            )
                .toLongOrNull()
                ?: throw IllegalArgumentException(
                    "invalid weather downloaded_at"
                )

        require(downloadedAt > 0L) {
            "invalid weather downloaded_at"
        }

        require(
            downloadedAt ==
                downloadedAtFromName
        ) {
            "weather directory downloadedAt mismatch"
        }

        require(
            captureDateUtc ==
                captureDateUtc(
                    downloadedAt
                )
        ) {
            "weather capture_date_utc mismatch"
        }

        val serverDateText =
            fields.getValue(
                "server_date_epoch_millis"
            )

        val serverDate =
            if (serverDateText.isEmpty()) {
                null
            } else {
                serverDateText
                    .toLongOrNull()
                    ?.also {
                        require(it > 0L) {
                            "invalid weather server date"
                        }
                    }
                    ?: throw IllegalArgumentException(
                        "invalid weather server date"
                    )
            }

        val pitEvidenceAt =
            fields.getValue(
                "pit_evidence_at_epoch_millis"
            )
                .toLongOrNull()
                ?: throw IllegalArgumentException(
                    "invalid weather pit evidence"
                )

        require(pitEvidenceAt > 0L) {
            "invalid weather pit evidence"
        }

        val expectedPitEvidence =
            maxOf(
                downloadedAt,
                serverDate ?: 0L
            )

        require(
            pitEvidenceAt ==
                expectedPitEvidence
        ) {
            "weather pit evidence calculation mismatch"
        }

        val forecastSha256 =
            fields.getValue(
                "forecast_sha256"
            )

        require(
            sha256Regex.matches(
                forecastSha256
            )
        ) {
            "malformed weather forecast SHA"
        }

        val snapshotSha256 =
            fields.getValue(
                "snapshot_sha256"
            )

        require(
            sha256Regex.matches(
                snapshotSha256
            )
        ) {
            "malformed weather snapshot SHA"
        }

        require(
            snapshotSha256 ==
                snapshotShaFromName
        ) {
            "weather directory snapshot SHA mismatch"
        }

        return PrefetchedManifest(
            text = text,
            requestedLatitudeText =
                requestedLatitudeText,
            requestedLongitudeText =
                requestedLongitudeText,
            requestUrl = requestUrl,
            captureDateUtc =
                captureDateUtc,
            downloadedAtEpochMillis =
                downloadedAt,
            serverDateEpochMillis =
                serverDate,
            pitEvidenceAtEpochMillis =
                pitEvidenceAt,
            forecastSha256 =
                forecastSha256,
            snapshotSha256 =
                snapshotSha256
        )
    }

    private fun readManifestTextStrict(
        directory: File
    ): String {
        val manifestFile =
            File(
                directory,
                "manifest.txt"
            )

        require(
            isSafeRegularFile(
                parent = directory,
                file = manifestFile,
                maxBytes =
                    MAX_MANIFEST_BYTES
            )
        ) {
            "unsafe weather manifest file"
        }

        val bytes =
            readFileLimited(
                manifestFile,
                MAX_MANIFEST_BYTES
            )

        return decodeUtf8Strict(bytes)
    }

    private fun parseManifest(
        text: String
    ): Map<String, String> {
        val result =
            linkedMapOf<String, String>()

        for (line in text.split('\n')) {
            if (line.isEmpty()) {
                continue
            }

            val index =
                line.indexOf('=')

            require(index > 0) {
                "invalid weather snapshot manifest"
            }

            val key =
                line.substring(
                    0,
                    index
                )

            val value =
                line.substring(
                    index + 1
                )

            require(
                key.matches(
                    Regex(
                        """^[a-z0-9_]+$"""
                    )
                )
            ) {
                "invalid weather manifest key"
            }

            require(key !in result) {
                "duplicate weather manifest key"
            }

            result[key] = value
        }

        return result
    }

    private fun captureDateUtc(
        epochMillis: Long
    ): String =
        Instant.ofEpochMilli(
            epochMillis
        )
            .atZone(
                ZoneOffset.UTC
            )
            .toLocalDate()
            .format(
                DateTimeFormatter.BASIC_ISO_DATE
            )

    private fun isSafeRegularFile(
        parent: File,
        file: File,
        maxBytes: Long
    ): Boolean {
        if (!file.isFile) {
            return false
        }

        if (
            file.length() < 0L ||
            file.length() > maxBytes
        ) {
            return false
        }

        val canonicalParent =
            try {
                parent.canonicalFile
            } catch (_: Exception) {
                return false
            }

        val canonicalFile =
            try {
                file.canonicalFile
            } catch (_: Exception) {
                return false
            }

        return canonicalFile.parentFile ==
            canonicalParent
    }

    private fun readFileLimited(
        file: File,
        maxBytes: Long
    ): ByteArray {
        require(
            file.length() <= maxBytes
        ) {
            "weather snapshot file too large"
        }

        val output =
            ByteArrayOutputStream()

        val buffer =
            ByteArray(8192)

        var total = 0L

        FileInputStream(file).use { input ->
            while (true) {
                val count =
                    input.read(buffer)

                if (count < 0) {
                    break
                }

                total =
                    Math.addExact(
                        total,
                        count.toLong()
                    )

                require(total <= maxBytes) {
                    "weather snapshot exceeded size limit"
                }

                output.write(
                    buffer,
                    0,
                    count
                )
            }
        }

        return output.toByteArray()
    }

    private fun decodeUtf8Strict(
        bytes: ByteArray
    ): String {
        val decoder =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(
                    CodingErrorAction.REPORT
                )
                .onUnmappableCharacter(
                    CodingErrorAction.REPORT
                )

        return try {
            decoder.decode(
                ByteBuffer.wrap(bytes)
            ).toString()
        } catch (error: java.nio.charset.CharacterCodingException) {
            throw IllegalArgumentException(
                "weather manifest is not valid UTF-8",
                error
            )
        }
    }
}
