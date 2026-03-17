package com.geovault.tracker.fragments.map

import com.geovault.tracker.Tracker
import org.maplibre.android.geometry.LatLng

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

    fun resolveActiveTrackerId(displayedTrackerId: String?, selectedTrackerId: String): String {
        return displayedTrackerId?.takeIf { it.isNotEmpty() } ?: selectedTrackerId
    }

    fun resolveHistoryTrackerId(displayedTrackerId: String?, selectedTrackerId: String): String {
        return displayedTrackerId?.takeIf { it.isNotEmpty() } ?: selectedTrackerId
    }

    fun isExternalStreaming(
        forceReplace: Boolean,
        hasTrackPoints: Boolean,
        displayedTrackerId: String?
    ): Boolean {
        return !forceReplace && hasTrackPoints && displayedTrackerId != null
    }

    fun shouldAutoZoomSingleTracker(trackPointsEmpty: Boolean): Boolean {
        return trackPointsEmpty
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
        selectedTrackerId: String,
        selectedTrackerName: String,
        baseTrackerColor: String,
        trackerLastUpdateMs: (Tracker) -> Long?
    ): InitialTargetMeta {
        if (initial != null) {
            val resolvedColor = (initial.color ?: baseTrackerColor).let { if (it.startsWith("#")) it else "#$it" }
            val streamedAccuracy = (initial.point_params?.lastOrNull()?.get("acc") as? Number)
                ?.toFloat()
                ?.takeIf { it > 0f }
            return InitialTargetMeta(
                displayedTracker = initial,
                displayedTrackerId = initial.id,
                displayedTrackerName = initial.name,
                displayedTrackerIsOwner = initial.isOwner(),
                displayedGroupName = null,
                mapViewContext = MapViewContext.SINGLE_TRACKER,
                lastCachedUpdateTimeMs = trackerLastUpdateMs(initial),
                currentTrackerColor = resolvedColor,
                lastStreamedAccuracyMeters = streamedAccuracy
            )
        }

        return InitialTargetMeta(
            displayedTracker = null,
            displayedTrackerId = selectedTrackerId,
            displayedTrackerName = selectedTrackerName.ifEmpty { null },
            displayedTrackerIsOwner = true,
            displayedGroupName = null,
            mapViewContext = MapViewContext.SINGLE_TRACKER,
            lastCachedUpdateTimeMs = null,
            currentTrackerColor = null,
            lastStreamedAccuracyMeters = null
        )
    }

    /**
     * Resolve the single-tracker camera zoom target. Prefer the latest rendered point so the
     * chevron target matches what is drawn, and fall back to tracker.last_point when needed.
     */
    fun resolveSingleTrackerZoomTarget(
        trackPoints: List<LatLng>,
        fallbackLastPoint: List<Double>?
    ): LatLng? {
        trackPoints.lastOrNull()?.let { return it }
        val lp = fallbackLastPoint ?: return null
        if (lp.size < 2) return null
        return LatLng(lp[1], lp[0])
    }
}
