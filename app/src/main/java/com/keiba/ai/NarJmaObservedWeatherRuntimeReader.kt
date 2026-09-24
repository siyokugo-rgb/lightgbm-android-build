package com.keiba.ai

import android.content.Context
import com.keiba.ai.model.RaceKey
import java.io.File

/**
 * Android runtime facade:
 * mapping asset → station resolve → PIT snapshot reader.
 *
 * Does not perform network I/O.
 * Does not invent predictionAsOf.
 * Does not authorize production features.
 */
object NarJmaObservedWeatherRuntimeReader {

    data class RuntimeInput(
        val mappingSelection:
            NarJmaObservedLiveStationSelection,
        val snapshot:
            NarJmaObservedWeatherSnapshotReader.SnapshotInput
    )

    fun readForRace(
        context: Context,
        raceKey: RaceKey,
        product: NarJmaObservedWeatherProduct,
        predictionAsOfEpochMillis: Long
    ): RuntimeInput? {
        val mapping =
            NarJmaObservedLiveStationMappingAssetLoader
                .load(
                    context
                )

        return readForRaceWithMapping(
            context = context,
            mapping = mapping,
            raceKey = raceKey,
            product = product,
            predictionAsOfEpochMillis =
                predictionAsOfEpochMillis
        )
    }

    internal fun readForRaceWithMapping(
        context: Context,
        mapping: NarJmaObservedLiveStationMapping,
        raceKey: RaceKey,
        product: NarJmaObservedWeatherProduct,
        predictionAsOfEpochMillis: Long
    ): RuntimeInput? {
        val selection =
            NarJmaObservedLiveStationResolver
                .resolve(
                    mapping = mapping,
                    raceKey = raceKey,
                    product = product
                )
                ?: return null

        val snapshot =
            NarJmaObservedWeatherSnapshotReader
                .read(
                    context = context,
                    product = product,
                    stationId =
                        selection.stationId,
                    predictionAsOfEpochMillis =
                        predictionAsOfEpochMillis
                )
                ?: return null

        return RuntimeInput(
            mappingSelection =
                selection,
            snapshot = snapshot
        )
    }

    internal fun readForRaceFromRoot(
        root: File,
        mapping: NarJmaObservedLiveStationMapping,
        raceKey: RaceKey,
        product: NarJmaObservedWeatherProduct,
        predictionAsOfEpochMillis: Long
    ): RuntimeInput? {
        val selection =
            NarJmaObservedLiveStationResolver
                .resolve(
                    mapping = mapping,
                    raceKey = raceKey,
                    product = product
                )
                ?: return null

        val snapshot =
            NarJmaObservedWeatherSnapshotReader
                .readFromRoot(
                    root = root,
                    product = product,
                    stationId =
                        selection.stationId,
                    predictionAsOfEpochMillis =
                        predictionAsOfEpochMillis
                )
                ?: return null

        return RuntimeInput(
            mappingSelection =
                selection,
            snapshot = snapshot
        )
    }
}
