package com.geovault.tracker.fragments.map

import android.content.Context
import com.geovault.tracker.SelectedTrackerPrefs

enum class SingleTrackerLoadMode {
    RUNTIME,
    BOOTSTRAP
}

class LoadSingleTrackerMapUseCase(
    runtimeRepository: RuntimeMapTrackRepository,
    bootstrapRepository: BootstrapMapTrackRepository
) {
    private val runtimeUseCase = MapSingleTrackerRuntimeUseCase(runtimeRepository, bootstrapRepository)
    private val bootstrapUseCase = MapSingleTrackerBootstrapUseCase(bootstrapRepository)

    suspend fun execute(
        context: Context,
        trackerId: String?,
        displayedTrackerId: String?,
        forceReplace: Boolean,
        trackingRunning: Boolean = false,
        mode: SingleTrackerLoadMode = SingleTrackerLoadMode.RUNTIME
    ): MapTrackSnapshot? {
        val selectedTrackerId = SelectedTrackerPrefs.selectedTrackerId(context)
        val resolvedId = trackerId ?: MapDataLoader.resolveActiveSingleTrackerId(
            trackingRunning = trackingRunning,
            displayedTrackerId = displayedTrackerId,
            selectedTrackerId = selectedTrackerId
        )
        if (resolvedId.isBlank()) return null

        return when (mode) {
            SingleTrackerLoadMode.RUNTIME -> runtimeUseCase.execute(
                context = context,
                trackerId = resolvedId,
                forceReplace = forceReplace
            )
            SingleTrackerLoadMode.BOOTSTRAP -> bootstrapUseCase.execute(
                context = context,
                trackerId = resolvedId,
                forceReplace = forceReplace
            )
        }
    }
}

