package com.geovault.tracker.presentation

import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerCatalogSettings
import com.geovault.tracker.domain.TrackPoint
import com.geovault.tracker.policy.TrackPointBus
import com.geovault.tracker.runtime.TrackerRuntimeStore

data class ParamsProjection(
    val tracker: Tracker?,
    val catalogSettings: TrackerCatalogSettings,
    val livePoint: TrackPoint?,
    val locallyRecorded: Boolean,
)

object ParamsProjector {
    fun project(tracker: Tracker?): ParamsProjection {
        val recordedId = TrackerRuntimeStore.value.locallyRecordedTrackerId.trim()
        val trackerId = tracker?.id.orEmpty()
        val live = TrackPointBus.events.replayCache.lastOrNull { it.trackerId.trim() == trackerId }
        return ParamsProjection(
            tracker = tracker,
            catalogSettings = tracker?.catalogSettings ?: TrackerCatalogSettings(),
            livePoint = live,
            locallyRecorded = trackerId.isNotEmpty() && trackerId == recordedId,
        )
    }
}
