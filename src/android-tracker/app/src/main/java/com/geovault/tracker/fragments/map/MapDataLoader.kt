package com.geovault.tracker.fragments.map

import com.geovault.tracker.Tracker

internal data class InitialTargetMeta(
    val displayedTracker: Tracker?,
    val displayedTrackerId: String?,
    val displayedTrackerName: String?,
    val displayedTrackerIsOwner: Boolean,
    val displayedGroupName: String?,
    val mapViewContext: MapViewContext,
    val lastCachedUpdateTimeMs: Long?,
    val currentTrackerColor: String?,
    val lastStreamedAccuracyMeters: Float?
)

internal object MapDataLoader {
    fun shouldSkipSeedTrack(
        trackerId: String,
        showAllTrackers: Boolean,
        mapViewContext: MapViewContext
    ): Boolean {
        return trackerId.isEmpty() || showAllTrackers || mapViewContext == MapViewContext.GROUP
    }

    fun resolveActiveTrackerId(displayedTrackerId: String?, defaultId: String): String {
        return displayedTrackerId?.takeIf { it.isNotEmpty() } ?: defaultId
    }

    fun resolveHistoryTrackerId(displayedTrackerId: String?, defaultId: String): String {
        return displayedTrackerId?.takeIf { it.isNotEmpty() } ?: defaultId
    }

    fun isExternalStreaming(
        forceReplace: Boolean,
        hasTrackPoints: Boolean,
        displayedTrackerId: String?,
        defaultId: String
    ): Boolean {
        return !forceReplace && hasTrackPoints &&
            displayedTrackerId != null && displayedTrackerId != defaultId
    }

    fun shouldAutoZoomDefaultTracker(trackerId: String, defaultTrackerId: String, trackPointsEmpty: Boolean): Boolean {
        return trackerId == defaultTrackerId && trackPointsEmpty
    }

    fun shouldAllowTrackerCameraMoveInMyLocation(
        showMyLocationEnabled: Boolean,
        activeCameraIntent: CameraIntent
    ): Boolean {
        val explicitTrackerFocus =
            activeCameraIntent == CameraIntent.SINGLE_TRACKER_FOCUS ||
                activeCameraIntent == CameraIntent.GROUP_MEMBER_FOCUS
        return !showMyLocationEnabled || explicitTrackerFocus
    }

    fun buildInitialTargetMeta(
        initial: Tracker?,
        defaultTrackerId: String,
        defaultTrackerName: String,
        defaultTrackerColor: String,
        trackerLastUpdateMs: (Tracker) -> Long?
    ): InitialTargetMeta {
        if (initial != null) {
            val resolvedColor = (initial.color ?: defaultTrackerColor).let { if (it.startsWith("#")) it else "#$it" }
            val streamedAccuracy = (initial.point_params?.lastOrNull()?.get("acc") as? Number)
                ?.toFloat()
                ?.takeIf { it > 0f }
            return InitialTargetMeta(
                displayedTracker = initial,
                displayedTrackerId = initial.id,
                displayedTrackerName = initial.name,
                displayedTrackerIsOwner = initial.isOwner(),
                displayedGroupName = null,
                mapViewContext = if (initial.id != defaultTrackerId) MapViewContext.SPECIFIC_TRACKER else MapViewContext.DEFAULT_TRACKER,
                lastCachedUpdateTimeMs = trackerLastUpdateMs(initial),
                currentTrackerColor = resolvedColor,
                lastStreamedAccuracyMeters = streamedAccuracy
            )
        }

        return InitialTargetMeta(
            displayedTracker = null,
            displayedTrackerId = defaultTrackerId,
            displayedTrackerName = defaultTrackerName.ifEmpty { null },
            displayedTrackerIsOwner = true,
            displayedGroupName = null,
            mapViewContext = MapViewContext.DEFAULT_TRACKER,
            lastCachedUpdateTimeMs = null,
            currentTrackerColor = null,
            lastStreamedAccuracyMeters = null
        )
    }
}
