package com.geovault.tracker.fragments.map

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class MapLiveStreamCoordinator(
    private val scope: CoroutineScope
) {
    private var multiTrackRenderJob: Job? = null
    private var singleLiveFitJob: Job? = null

    fun scheduleMultiTrackRender(debounceMs: Long, block: suspend () -> Unit) {
        multiTrackRenderJob?.cancel()
        multiTrackRenderJob = scope.launch {
            delay(debounceMs)
            block()
        }
    }

    fun scheduleSingleLiveFit(debounceMs: Long, block: suspend () -> Unit) {
        singleLiveFitJob?.cancel()
        singleLiveFitJob = scope.launch {
            delay(debounceMs)
            block()
        }
    }

    fun cancelSingleLiveFit() {
        singleLiveFitJob?.cancel()
        singleLiveFitJob = null
    }

    fun clearAll() {
        multiTrackRenderJob?.cancel()
        multiTrackRenderJob = null
        cancelSingleLiveFit()
    }
}
