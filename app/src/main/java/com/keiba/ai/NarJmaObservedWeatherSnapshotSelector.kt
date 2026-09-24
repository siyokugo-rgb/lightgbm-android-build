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
 * Selects the latest PIT-eligible LIVE_CAPTURED_OBSERVED snapshot
 * for one documented JMA product.
 *
 * Eligibility:
 * `pit_evidence_at_epoch_millis <= prediction_as_of_epoch_millis`
 *
 * Future snapshot raw bodies are never opened.
 * Does not load station mapping or parse CSV for ranking.
 */
object NarJmaObservedWeatherSnapshotSelector {

    private const val ROOT_NAME =
        "nar-jma-observed-weather-snapshots"

    private const val FORMAT_VERSION =
        "1"

    private const val PROVIDER =
        "jma"

    private const val ROLE =
        "LIVE_CAPTURED_OBSERVED"

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
            "role",
            "product_id",
            "source_url",
            "capture_date_utc",
            "downloaded_at_epoch_millis",
            "server_date_epoch_millis",
            "last_modified_epoch_millis",
            "pit_evidence_at_epoch_millis",
            "content_type",
            "content_length",
            "raw_sha256",
            "snapshot_sha256"
        )

    data class Selection(
        val product: NarJmaObservedWeatherProduct,
        val sourceUrl: String,
        val captureDateUtc: String,
        val downloadedAtEpochMillis: Long,
        val serverDateEpochMillis: Long?,
        val lastModifiedEpochMillis: Long?,
        val pitEvidenceAtEpochMillis: Long,
        val predictionAsOfEpochMillis: Long,
        val ageMillis: Long,
        val contentLength: Long,
        val rawSha256: String,
        val snapshotSha256: String,
        val directory: File
    )

    private data class PrefetchedManifest(
        val text: String,
        val productId: String,
        val sourceUrl: String,
        val captureDateUtc: String,
        val downloadedAtEpochMillis: Long,
        val serverDateEpochMillis: Long?,
        val lastModifiedEpochMillis: Long?,
        val pitEvidenceAtEpochMillis: Long,
        val contentLength: Long,
        val rawSha256: String,
        val snapshotSha256: String
    )

    fun selectLatest(
        context: Context,
        product: NarJmaObservedWeatherProduct,
        predictionAsOfEpochMillis: Long
    ): Selection? =
        selectLatestFromRoot(
            root = File(
                context.noBackupFilesDir,
                ROOT_NAME
            ),
            product = product,
            predictionAsOfEpochMillis =
                predictionAsOfEpochMillis
        )

    internal fun selectLatestFromRoot(
        root: File,
        product: NarJmaObservedWeatherProduct,
        predictionAsOfEpochMillis: Long
    ): Selection? {
        require(
            predictionAsOfEpochMillis > 0L
        ) {
            "invalid predictionAsOf"
        }

        val canonicalSourceUrl =
            product.canonicalUrl()

        if (!root.exists()) {
            return null
        }

        require(root.isDirectory) {
            "observed weather snapshot root is not a directory"
        }

        val canonicalRoot =
            root.canonicalFile

        val productDir =
            File(
                canonicalRoot,
                product.productId
            )

        if (!productDir.exists()) {
            return null
        }

        val canonicalProduct =
            productDir.canonicalFile

        require(
            canonicalProduct.parentFile ==
                canonicalRoot &&
                canonicalProduct.name ==
                product.productId
        ) {
            "observed weather product path escaped root"
        }

        require(canonicalProduct.isDirectory) {
            "observed weather product path is not a directory"
        }

        val predictionDateUtc =
            captureDateUtc(
                predictionAsOfEpochMillis
            )

        val dayEntries =
            canonicalProduct.listFiles()
                ?: error(
                    "could not list observed weather product directory"
                )

        var best: Selection? = null

        for (dayEntry in dayEntries) {
            val dayName =
                dayEntry.name

            val canonicalDay =
                dayEntry.canonicalFile

            require(
                canonicalDay.parentFile ==
                    canonicalProduct &&
                    canonicalDay.name ==
                    dayName
            ) {
                "observed weather day path escaped product directory"
            }

            require(
                dayDirectoryRegex.matches(
                    dayName
                )
            ) {
                "unexpected entry in observed weather product directory: $dayName"
            }

            require(canonicalDay.isDirectory) {
                "observed weather day path is not a directory: $dayName"
            }

            require(
                runCatching {
                    LocalDate.parse(
                        dayName,
                        basicDateFormatter
                    )
                }.isSuccess
            ) {
                "invalid observed weather day calendar date: $dayName"
            }

            // Future capture days cannot contain
            // downloadedAt <= predictionAsOf.
            if (dayName > predictionDateUtc) {
                continue
            }

            val snapshotEntries =
                canonicalDay.listFiles()
                    ?: error(
                        "could not list observed weather day directory: $dayName"
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
                    "observed weather snapshot path escaped day directory"
                }

                if (
                    stagingDirectoryRegex
                        .matches(snapshotName)
                ) {
                    require(
                        canonicalSnapshot.isDirectory
                    ) {
                        "observed weather staging path is not a directory"
                    }

                    continue
                }

                val nameMatch =
                    snapshotDirectoryRegex
                        .matchEntire(
                            snapshotName
                        )
                        ?: throw IllegalArgumentException(
                            "unexpected entry in observed weather day directory: $snapshotName"
                        )

                require(
                    canonicalSnapshot.isDirectory
                ) {
                    "observed weather snapshot entry is not a directory: $snapshotName"
                }

                val downloadedAtFromName =
                    nameMatch.groupValues[1]
                        .toLongOrNull()
                        ?: throw IllegalArgumentException(
                            "invalid observed weather snapshot downloadedAt in name"
                        )

                require(
                    downloadedAtFromName > 0L
                ) {
                    "invalid observed weather snapshot downloadedAt in name"
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
                        product = product,
                        dayDirectoryName =
                            dayName,
                        downloadedAtFromName =
                            downloadedAtFromName,
                        snapshotShaFromName =
                            snapshotShaFromName
                    )

                require(
                    prefetched.sourceUrl ==
                        canonicalSourceUrl
                ) {
                    "observed weather source URL mismatch"
                }

                // serverDate may push pitEvidence
                // past predictionAsOf even when
                // downloadedAt is eligible.
                // Skip without opening observed.csv.
                if (
                    prefetched.pitEvidenceAtEpochMillis >
                        predictionAsOfEpochMillis
                ) {
                    continue
                }

                val manifestBefore =
                    prefetched.text

                require(
                    NarJmaObservedWeatherSnapshotStore
                        .verifySnapshot(
                            canonicalSnapshot
                        )
                ) {
                    "eligible observed weather snapshot failed integrity verification"
                }

                val manifestAfter =
                    readManifestTextStrict(
                        canonicalSnapshot
                    )

                require(
                    manifestBefore ==
                        manifestAfter
                ) {
                    "observed weather snapshot manifest changed during verification"
                }

                val verified =
                    readAndValidateManifestPreflight(
                        directory =
                            canonicalSnapshot,
                        product = product,
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
                        product = product,
                        sourceUrl =
                            verified.sourceUrl,
                        captureDateUtc =
                            verified.captureDateUtc,
                        downloadedAtEpochMillis =
                            verified.downloadedAtEpochMillis,
                        serverDateEpochMillis =
                            verified.serverDateEpochMillis,
                        lastModifiedEpochMillis =
                            verified.lastModifiedEpochMillis,
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
                        contentLength =
                            verified.contentLength,
                        rawSha256 =
                            verified.rawSha256,
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
            "ambiguous observed weather snapshot selection"
        )
    }

    private fun readAndValidateManifestPreflight(
        directory: File,
        product: NarJmaObservedWeatherProduct,
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
            "observed weather manifest key set mismatch"
        }

        require(
            fields["format_version"] ==
                FORMAT_VERSION
        ) {
            "unsupported observed weather manifest format"
        }

        require(
            fields["provider"] ==
                PROVIDER
        ) {
            "unexpected observed weather provider"
        }

        require(
            fields["role"] ==
                ROLE
        ) {
            "unexpected observed weather role"
        }

        val productId =
            fields.getValue(
                "product_id"
            )

        require(
            productId ==
                product.productId
        ) {
            "observed weather product_id mismatch"
        }

        val sourceUrl =
            fields.getValue(
                "source_url"
            )

        require(
            sourceUrl ==
                product.canonicalUrl()
        ) {
            "observed weather source URL mismatch"
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
            "invalid observed weather capture_date_utc"
        }

        require(
            captureDateUtc ==
                dayDirectoryName
        ) {
            "observed weather capture day mismatch"
        }

        val downloadedAt =
            fields.getValue(
                "downloaded_at_epoch_millis"
            )
                .toLongOrNull()
                ?: throw IllegalArgumentException(
                    "invalid observed weather downloaded_at"
                )

        require(downloadedAt > 0L) {
            "invalid observed weather downloaded_at"
        }

        require(
            downloadedAt ==
                downloadedAtFromName
        ) {
            "observed weather directory downloadedAt mismatch"
        }

        require(
            captureDateUtc ==
                captureDateUtc(
                    downloadedAt
                )
        ) {
            "observed weather capture_date_utc mismatch"
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
                            "invalid observed weather server date"
                        }
                    }
                    ?: throw IllegalArgumentException(
                        "invalid observed weather server date"
                    )
            }

        val lastModifiedText =
            fields.getValue(
                "last_modified_epoch_millis"
            )

        val lastModified =
            if (lastModifiedText.isEmpty()) {
                null
            } else {
                lastModifiedText
                    .toLongOrNull()
                    ?.also {
                        require(it > 0L) {
                            "invalid observed weather last-modified"
                        }
                    }
                    ?: throw IllegalArgumentException(
                        "invalid observed weather last-modified"
                    )
            }

        val pitEvidenceAt =
            fields.getValue(
                "pit_evidence_at_epoch_millis"
            )
                .toLongOrNull()
                ?: throw IllegalArgumentException(
                    "invalid observed weather pit evidence"
                )

        require(pitEvidenceAt > 0L) {
            "invalid observed weather pit evidence"
        }

        val expectedPitEvidence =
            NarJmaObservedWeatherDownloader
                .computePitEvidenceAtEpochMillis(
                    downloadedAt,
                    serverDate
                )

        require(
            pitEvidenceAt ==
                expectedPitEvidence
        ) {
            "observed weather pit evidence calculation mismatch"
        }

        require(
            fields.getValue(
                "content_type"
            ) == "text/csv"
        ) {
            "unexpected observed weather content_type"
        }

        val contentLength =
            fields.getValue(
                "content_length"
            )
                .toLongOrNull()
                ?: throw IllegalArgumentException(
                    "invalid observed weather content_length"
                )

        require(
            contentLength > 0L &&
                contentLength <=
                NarJmaObservedWeatherDownloader
                    .MAX_RESPONSE_BYTES
        ) {
            "invalid observed weather content_length"
        }

        val rawSha256 =
            fields.getValue(
                "raw_sha256"
            )

        require(
            sha256Regex.matches(
                rawSha256
            )
        ) {
            "malformed observed weather raw SHA"
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
            "malformed observed weather snapshot SHA"
        }

        require(
            snapshotSha256 ==
                snapshotShaFromName
        ) {
            "observed weather directory snapshot SHA mismatch"
        }

        return PrefetchedManifest(
            text = text,
            productId = productId,
            sourceUrl = sourceUrl,
            captureDateUtc =
                captureDateUtc,
            downloadedAtEpochMillis =
                downloadedAt,
            serverDateEpochMillis =
                serverDate,
            lastModifiedEpochMillis =
                lastModified,
            pitEvidenceAtEpochMillis =
                pitEvidenceAt,
            contentLength =
                contentLength,
            rawSha256 =
                rawSha256,
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
            "unsafe observed weather manifest file"
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
                "invalid observed weather snapshot manifest"
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
                "invalid observed weather manifest key"
            }

            require(
                '\n' !in value &&
                    '\r' !in value
            ) {
                "observed weather manifest value contains newline"
            }

            require(key !in result) {
                "duplicate observed weather manifest key"
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
            "observed weather snapshot file too large"
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
                    "observed weather snapshot exceeded size limit"
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
                "observed weather manifest is not valid UTF-8",
                error
            )
        }
    }
}
