package com.keiba.ai

import android.content.Context
import com.keiba.ai.model.HorseEntry
import com.keiba.ai.model.RaceKey
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object NarPitSnapshotReader {

    private const val ROOT_NAME =
        "nar-daily-snapshots"

    private const val MAX_SNAPSHOT_CSV_BYTES =
        8L * 1024L * 1024L

    private const val MAX_MANIFEST_BYTES =
        32L * 1024L

    private val sha256Regex =
        Regex("""^[0-9a-f]{64}$""")

    private val requiredManifestKeys =
        setOf(
            "format_version",
            "date",
            "source_file_name",
            "source_timestamp_epoch_second",
            "downloaded_at_epoch_millis",
            "server_date_epoch_millis",
            "pit_evidence_at_epoch_millis",
            "racelist_sha256",
            "horselist_sha256",
            "snapshot_sha256"
        )

    /*
     * These are the only raw model features currently approved in BOTH:
     * - HISTORICAL_MONTHLY training context
     * - LIVE_PIT_SNAPSHOT serving context
     *
     * Do not add a column here unless NarPreRaceFeatureContract says it is
     * train-serving compatible.
     */
    private val productionRaceFeatureColumns =
        listOf(
            "競馬場",
            "競走年月日"
        )

    private val productionHorseFeatureColumns =
        listOf(
            "毛色",
            "生年月日",
            "父馬名",
            "母馬名",
            "母父馬名"
        )

    /*
     * Identity/display data is deliberately separate from model features.
     * It may be returned to the UI/domain model but must never be inserted
     * into productionRawFeatures by this reader.
     */
    private val identityOnlyHorseColumns =
        setOf(
            "馬名"
        )

    data class EntryInput(
        val horseEntry: HorseEntry,
        val productionRawFeatures: Map<String, String>
    )

    data class RaceInput(
        val key: RaceKey,
        val entries: List<EntryInput>
    )

    data class SnapshotInput(
        val selection: NarPitSnapshotSelector.Selection,
        val races: List<RaceInput>
    )

    init {
        for (column in productionRaceFeatureColumns) {
            require(
                NarPreRaceFeatureContract
                    .isTrainServingCompatibleFeature(
                        source =
                            NarPreRaceFeatureContract
                                .Source.RACELIST,
                        column = column,
                        trainingContext =
                            NarPreRaceFeatureContract
                                .DataContext.HISTORICAL_MONTHLY,
                        servingContext =
                            NarPreRaceFeatureContract
                                .DataContext.LIVE_PIT_SNAPSHOT
                    )
            ) {
                "non-compatible production race feature: $column"
            }
        }

        for (column in productionHorseFeatureColumns) {
            require(
                NarPreRaceFeatureContract
                    .isTrainServingCompatibleFeature(
                        source =
                            NarPreRaceFeatureContract
                                .Source.HORSELIST,
                        column = column,
                        trainingContext =
                            NarPreRaceFeatureContract
                                .DataContext.HISTORICAL_MONTHLY,
                        servingContext =
                            NarPreRaceFeatureContract
                                .DataContext.LIVE_PIT_SNAPSHOT
                    )
            ) {
                "non-compatible production horse feature: $column"
            }
        }
    }

    fun read(
        context: Context,
        date: String,
        predictionAsOfEpochMillis: Long
    ): SnapshotInput? =
        readFromRoot(
            root = File(
                context.noBackupFilesDir,
                ROOT_NAME
            ),
            date = date,
            predictionAsOfEpochMillis =
                predictionAsOfEpochMillis
        )

    internal fun readFromRoot(
        root: File,
        date: String,
        predictionAsOfEpochMillis: Long
    ): SnapshotInput? {
        val selection =
            NarPitSnapshotSelector
                .selectLatestFromRoot(
                    root = root,
                    date = date,
                    predictionAsOfEpochMillis =
                        predictionAsOfEpochMillis
                )
                ?: return null

        return readSelected(
            selection
        )
    }

    internal fun readSelectedForTest(
        selection: NarPitSnapshotSelector.Selection
    ): SnapshotInput =
        readSelected(
            selection
        )

    private fun readSelected(
        selection: NarPitSnapshotSelector.Selection
    ): SnapshotInput {
        require(
            selection.pitEvidenceAtEpochMillis <=
                selection.predictionAsOfEpochMillis
        ) {
            "future NAR snapshot selection rejected"
        }

        require(
            selection.ageMillis ==
                Math.subtractExact(
                    selection.predictionAsOfEpochMillis,
                    selection.pitEvidenceAtEpochMillis
                )
        ) {
            "invalid NAR snapshot age"
        }

        val directory =
            selection.directory
                .canonicalFile

        require(
            directory ==
                selection.directory
        ) {
            "NAR snapshot directory is not canonical"
        }

        val manifestBefore =
            readSelectedManifest(
                directory = directory,
                selection = selection
            )

        require(
            NarDailySnapshotStore
                .verifySnapshot(
                    directory
                )
        ) {
            "NAR snapshot failed pre-read integrity verification"
        }

        val racelistBytes =
            readStrictChildBytes(
                directory = directory,
                name = "racelist.csv",
                maxBytes =
                    MAX_SNAPSHOT_CSV_BYTES
            )

        val horselistBytes =
            readStrictChildBytes(
                directory = directory,
                name = "horselist.csv",
                maxBytes =
                    MAX_SNAPSHOT_CSV_BYTES
            )

        require(
            sha256Hex(
                racelistBytes
            ) ==
                manifestBefore.racelistSha256
        ) {
            "racelist bytes do not match selected snapshot"
        }

        require(
            sha256Hex(
                horselistBytes
            ) ==
                manifestBefore.horselistSha256
        ) {
            "horselist bytes do not match selected snapshot"
        }

        val manifestAfter =
            readSelectedManifest(
                directory = directory,
                selection = selection
            )

        require(
            manifestBefore ==
                manifestAfter
        ) {
            "NAR snapshot manifest changed during read"
        }

        require(
            NarDailySnapshotStore
                .verifySnapshot(
                    directory
                )
        ) {
            "NAR snapshot changed during read"
        }

        val races =
            NarCsvParser.parseTable(
                decodeStrictUtf8(
                    racelistBytes
                )
            )

        val horses =
            NarCsvParser.parseTable(
                decodeStrictUtf8(
                    horselistBytes
                )
            )

        requireRectangular(
            table = races,
            sourceName = "racelist"
        )

        requireRectangular(
            table = horses,
            sourceName = "horselist"
        )

        val raceRowsByKey =
            linkedMapOf<RaceKey, List<String>>()

        for (row in races.rows) {
            val key =
                parseRaceKey(
                    source =
                        NarPreRaceFeatureContract
                            .Source.RACELIST,
                    table = races,
                    row = row
                )

            require(
                key.date.toString()
                    .padStart(8, '0') ==
                    selection.date
            ) {
                "racelist contains row from another date: $key"
            }

            require(
                key !in raceRowsByKey
            ) {
                "duplicate racelist key: $key"
            }

            raceRowsByKey[key] = row
        }

        require(
            raceRowsByKey.isNotEmpty()
        ) {
            "racelist contains no race rows"
        }

        val horseRowsByKey =
            linkedMapOf<
                RaceKey,
                MutableList<List<String>>
            >()

        for (row in horses.rows) {
            val key =
                parseRaceKey(
                    source =
                        NarPreRaceFeatureContract
                            .Source.HORSELIST,
                    table = horses,
                    row = row
                )

            require(
                key.date.toString()
                    .padStart(8, '0') ==
                    selection.date
            ) {
                "horselist contains row from another date: $key"
            }

            require(
                key in raceRowsByKey
            ) {
                "horselist references missing race: $key"
            }

            horseRowsByKey
                .getOrPut(
                    key
                ) {
                    mutableListOf()
                }
                .add(row)
        }

        require(
            horseRowsByKey.isNotEmpty()
        ) {
            "horselist contains no entry rows"
        }

        val raceInputs =
            raceRowsByKey
                .entries
                .map { (key, raceRow) ->
                    val horseRows =
                        horseRowsByKey[key]
                            ?: error(
                                "race has no horselist rows: $key"
                            )

                    RaceInput(
                        key = key,
                        entries =
                            buildEntries(
                                key = key,
                                raceTable = races,
                                raceRow = raceRow,
                                horseTable = horses,
                                horseRows = horseRows
                            )
                    )
                }
                .sortedWith(
                    compareBy<RaceInput>(
                        { it.key.track },
                        { it.key.date },
                        { it.key.raceNumber }
                    )
                )

        require(
            horseRowsByKey.keys ==
                raceRowsByKey.keys
        ) {
            "racelist/horselist race key mismatch"
        }

        return SnapshotInput(
            selection = selection,
            races = raceInputs
        )
    }

    private fun buildEntries(
        key: RaceKey,
        raceTable: NarCsvParser.CsvTable,
        raceRow: List<String>,
        horseTable: NarCsvParser.CsvTable,
        horseRows: List<List<String>>
    ): List<EntryInput> {
        val seenHorseNumbers =
            mutableSetOf<Int>()

        val entries =
            horseRows.map { horseRow ->
                val horseNumber =
                    readKey(
                        source =
                            NarPreRaceFeatureContract
                                .Source.HORSELIST,
                        table = horseTable,
                        row = horseRow,
                        column = "馬番"
                    )
                        .toPositiveInt(
                            "horse number"
                        )

                require(
                    seenHorseNumbers.add(
                        horseNumber
                    )
                ) {
                    "duplicate horse number for $key: $horseNumber"
                }

                val horseName =
                    readIdentityValue(
                        table = horseTable,
                        row = horseRow,
                        column = "馬名"
                    )

                require(
                    horseName.isNotBlank()
                ) {
                    "blank horse name for $key/$horseNumber"
                }

                val jockey =
                    readLiveFeature(
                        source =
                            NarPreRaceFeatureContract
                                .Source.HORSELIST,
                        table = horseTable,
                        row = horseRow,
                        column = "騎手名"
                    )
                        .takeIf {
                            it.isNotBlank()
                        }

                val assignedWeight =
                    readLiveFeature(
                        source =
                            NarPreRaceFeatureContract
                                .Source.HORSELIST,
                        table = horseTable,
                        row = horseRow,
                        column = "負担重量"
                    )
                        .parseBurdenWeightOrNull()

                val raw =
                    linkedMapOf<String, String>()

                for (
                    column in
                    productionRaceFeatureColumns
                ) {
                    raw[
                        "feature_race__$column"
                    ] =
                        readLiveFeature(
                            source =
                                NarPreRaceFeatureContract
                                    .Source.RACELIST,
                            table = raceTable,
                            row = raceRow,
                            column = column
                        )
                }

                for (
                    column in
                    productionHorseFeatureColumns
                ) {
                    raw[
                        "feature_entry__$column"
                    ] =
                        readLiveFeature(
                            source =
                                NarPreRaceFeatureContract
                                    .Source.HORSELIST,
                            table = horseTable,
                            row = horseRow,
                            column = column
                        )
                }

                require(
                    raw.size ==
                        productionRaceFeatureColumns.size +
                        productionHorseFeatureColumns.size
                ) {
                    "unexpected production raw feature count"
                }

                EntryInput(
                    horseEntry =
                        HorseEntry(
                            key = key,
                            horseNumber =
                                horseNumber,
                            horseName =
                                horseName,
                            jockey =
                                jockey,
                            assignedWeightKg =
                                assignedWeight,

                            // Contract still marks 馬体重 as DEFERRED_DYNAMIC.
                            // Do not populate it until the dynamic PIT audit
                            // is explicitly completed.
                            bodyWeightKg =
                                null
                        ),
                    productionRawFeatures =
                        raw.toMap()
                )
            }
                .sortedBy {
                    it.horseEntry
                        .horseNumber
                }

        require(
            entries.isNotEmpty()
        ) {
            "race has no entries: $key"
        }

        return entries
    }

    private fun parseRaceKey(
        source: NarPreRaceFeatureContract.Source,
        table: NarCsvParser.CsvTable,
        row: List<String>
    ): RaceKey {
        val track =
            readKey(
                source = source,
                table = table,
                row = row,
                column = "競馬場"
            )

        require(
            track.isNotBlank() &&
                track == track.trim()
        ) {
            "invalid race track"
        }

        val dateText =
            readKey(
                source = source,
                table = table,
                row = row,
                column = "競走年月日"
            )

        require(
            dateText.matches(
                Regex("""^\d{8}$""")
            )
        ) {
            "invalid race date"
        }

        val raceNumber =
            readKey(
                source = source,
                table = table,
                row = row,
                column = "レース番号"
            )
                .toPositiveInt(
                    "race number"
                )

        return RaceKey(
            track = track,
            date =
                dateText.toInt(),
            raceNumber =
                raceNumber
        )
    }

    private fun readKey(
        source: NarPreRaceFeatureContract.Source,
        table: NarCsvParser.CsvTable,
        row: List<String>,
        column: String
    ): String =
        NarPreRaceFeatureContract
            .readKeyValue(
                source = source,
                table = table,
                row = row,
                column = column
            )

    private fun readLiveFeature(
        source: NarPreRaceFeatureContract.Source,
        table: NarCsvParser.CsvTable,
        row: List<String>,
        column: String
    ): String =
        NarPreRaceFeatureContract
            .readFeatureValue(
                context =
                    NarPreRaceFeatureContract
                        .DataContext.LIVE_PIT_SNAPSHOT,
                source = source,
                table = table,
                row = row,
                column = column
            )

    private fun readIdentityValue(
        table: NarCsvParser.CsvTable,
        row: List<String>,
        column: String
    ): String {
        require(
            column in
                identityOnlyHorseColumns
        ) {
            "identity column access denied: $column"
        }

        val index =
            table.header[column]
                ?: error(
                    "horselist missing identity column: $column"
                )

        require(
            index >= 0 &&
                index < row.size
        ) {
            "horselist row shorter than identity column: $column"
        }

        return row[index]
    }

    private fun requireRectangular(
        table: NarCsvParser.CsvTable,
        sourceName: String
    ) {
        require(
            table.header.isNotEmpty()
        ) {
            "$sourceName header is empty"
        }

        val headerIndices =
            table.header
                .values
                .sorted()

        require(
            headerIndices ==
                (0 until table.header.size)
                    .toList()
        ) {
            "$sourceName contains duplicate or invalid header columns"
        }

        for (
            (index, row) in
            table.rows.withIndex()
        ) {
            require(
                row.size ==
                    table.header.size
            ) {
                "$sourceName field count mismatch at row ${index + 2}"
            }
        }
    }

    private fun String.toPositiveInt(
        label: String
    ): Int =
        trim()
            .toIntOrNull()
            ?.takeIf {
                it > 0
            }
            ?: error(
                "invalid $label"
            )

    private fun String.parseBurdenWeightOrNull():
        Double? {
        val value =
            trim()

        if (value.isEmpty()) {
            return null
        }

        val match =
            Regex(
                """^[▲△◇☆★]?([0-9]+(?:\.[0-9]+)?)$"""
            )
                .matchEntire(
                    value
                )
                ?: error(
                    "invalid burden weight"
                )

        val parsed =
            match.groupValues[1]
                .toDoubleOrNull()
                ?: error(
                    "invalid burden weight"
                )

        require(
            parsed > 0.0 &&
                parsed.isFinite()
        ) {
            "invalid burden weight"
        }

        return parsed
    }

    private data class SelectedManifest(
        val date: String,
        val sourceFileName: String,
        val sourceTimestampEpochSecond: Long,
        val pitEvidenceAtEpochMillis: Long,
        val snapshotSha256: String,
        val racelistSha256: String,
        val horselistSha256: String
    )

    private fun readSelectedManifest(
        directory: File,
        selection: NarPitSnapshotSelector.Selection
    ): SelectedManifest {
        val bytes =
            readStrictChildBytes(
                directory = directory,
                name = "manifest.txt",
                maxBytes =
                    MAX_MANIFEST_BYTES
            )

        val manifest =
            parseManifest(
                decodeStrictUtf8(
                    bytes
                )
            )

        require(
            manifest.keys ==
                requiredManifestKeys
        ) {
            "unexpected snapshot manifest keys"
        }

        require(
            manifest["format_version"] ==
                "1"
        ) {
            "unsupported snapshot manifest version"
        }

        val date =
            manifest["date"]
                ?: error(
                    "snapshot date missing"
                )

        val sourceFileName =
            manifest["source_file_name"]
                ?: error(
                    "snapshot source filename missing"
                )

        val sourceTimestamp =
            manifest[
                "source_timestamp_epoch_second"
            ]
                ?.toLongOrNull()
                ?: error(
                    "invalid snapshot source timestamp"
                )

        val pitEvidenceAt =
            manifest[
                "pit_evidence_at_epoch_millis"
            ]
                ?.toLongOrNull()
                ?: error(
                    "invalid snapshot PIT evidence time"
                )

        val snapshotSha256 =
            manifest["snapshot_sha256"]
                ?: error(
                    "snapshot SHA-256 missing"
                )

        val racelistSha256 =
            manifest["racelist_sha256"]
                ?: error(
                    "racelist SHA-256 missing"
                )

        val horselistSha256 =
            manifest["horselist_sha256"]
                ?: error(
                    "horselist SHA-256 missing"
                )

        for (
            hash in listOf(
                snapshotSha256,
                racelistSha256,
                horselistSha256
            )
        ) {
            require(
                sha256Regex.matches(
                    hash
                )
            ) {
                "invalid snapshot SHA-256"
            }
        }

        require(
            date ==
                selection.date &&
                sourceFileName ==
                    selection.sourceFileName &&
                sourceTimestamp ==
                    selection.sourceTimestampEpochSecond &&
                pitEvidenceAt ==
                    selection.pitEvidenceAtEpochMillis &&
                snapshotSha256 ==
                    selection.snapshotSha256
        ) {
            "selected snapshot identity changed"
        }

        return SelectedManifest(
            date = date,
            sourceFileName =
                sourceFileName,
            sourceTimestampEpochSecond =
                sourceTimestamp,
            pitEvidenceAtEpochMillis =
                pitEvidenceAt,
            snapshotSha256 =
                snapshotSha256,
            racelistSha256 =
                racelistSha256,
            horselistSha256 =
                horselistSha256
        )
    }

    private fun readStrictChildBytes(
        directory: File,
        name: String,
        maxBytes: Long
    ): ByteArray {
        require(
            name == "racelist.csv" ||
                name == "horselist.csv" ||
                name == "manifest.txt"
        ) {
            "snapshot file access denied: $name"
        }

        val file =
            File(
                directory,
                name
            )

        val canonicalFile =
            file.canonicalFile

        require(
            canonicalFile.parentFile ==
                directory &&
                canonicalFile.name ==
                    name
        ) {
            "snapshot file escaped directory"
        }

        require(
            canonicalFile.isFile
        ) {
            "snapshot file missing: $name"
        }

        return readLimitedBytes(
            file = canonicalFile,
            maxBytes = maxBytes
        )
    }

    private fun decodeStrictUtf8(
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

        return decoder.decode(
            ByteBuffer.wrap(bytes)
        ).toString()
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

            require(
                index > 0
            ) {
                "invalid snapshot manifest"
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
                "invalid snapshot manifest key"
            }

            require(
                key !in result
            ) {
                "duplicate snapshot manifest key"
            }

            result[key] = value
        }

        return result
    }

    private fun sha256Hex(
        bytes: ByteArray
    ): String =
        MessageDigest
            .getInstance(
                "SHA-256"
            )
            .digest(bytes)
            .joinToString(
                separator = ""
            ) {
                "%02x".format(it)
            }

    private fun readLimitedBytes(
        file: File,
        maxBytes: Long
    ): ByteArray {
        require(
            maxBytes > 0L &&
                maxBytes <= Int.MAX_VALUE
        ) {
            "invalid read limit"
        }

        val output =
            ByteArrayOutputStream()

        val buffer =
            ByteArray(8192)

        var total =
            0L

        FileInputStream(file).use { input ->
            while (true) {
                val count =
                    input.read(
                        buffer
                    )

                if (count < 0) {
                    break
                }

                total =
                    Math.addExact(
                        total,
                        count.toLong()
                    )

                require(
                    total <=
                        maxBytes
                ) {
                    "snapshot CSV too large"
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
}
