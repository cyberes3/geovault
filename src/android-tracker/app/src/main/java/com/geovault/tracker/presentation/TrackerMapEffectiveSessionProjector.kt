package com.geovault.tracker.presentation

import com.geovault.common.logging.CaptureLogThrottle
import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.services.TrackingRuntimeSnapshot

data class TrackerMapEffectiveSessionInput(
    val state: TrackerMapUiState,
    val plan: TrackerMapStreamingPlan,
    val trailPointLimit: Int,
    val sessionWindows: TrackerMapSessionWindowState = TrackerMapSessionWindowState(),
    val visibleTrackerIds: Set<String>? = null,
    val nowMs: Long = System.currentTimeMillis(),
)

data class TrackerMapEffectiveSession(
    val snapshot: TrackerMapSessionSnapshot,
    val liveHead: Pair<Double, Double>?,
)

object TrackerMapEffectiveSessionProjector {
    private const val TAG = "TrackerMapEffectiveSessionProjector"

    fun project(input: TrackerMapEffectiveSessionInput): TrackerMapEffectiveSession {
        val state = input.state
        val plan = input.plan
        val renderTrails = allQueueTrailsWithLocalRuntimeOverlay(
            mode = state.mode,
            runtime = state.runtime,
            groupTrackerIds = plan.groupTrackerIds,
            allQueueTrailsByTracker = state.allQueueTrailsByTracker,
            trailPointLimit = input.trailPointLimit,
        )
        val singleTrail = singleTrailWithLocalRuntimeOverlay(
            mode = state.mode,
            runtime = state.runtime,
            displayedTrackerId = plan.displayedTrackerId,
            trail = state.trail,
            trailPointLimit = input.trailPointLimit,
        )
        val effectiveState = if (singleTrail === state.trail) {
            state
        } else {
            state.copy(trail = singleTrail)
        }
        val snapshot = TrackerMapSessionEngine.build(
            TrackerMapSessionBuildInput(
                state = effectiveState,
                plan = plan,
                localRuntimeOverlayTrails = renderTrails,
                sessionWindows = input.sessionWindows,
                visibleTrackerIds = input.visibleTrackerIds,
                nowMs = input.nowMs,
            )
        )
        val liveHead = resolveLiveHead(snapshot)
        val projectSignature =
            "mode=${state.mode}|displayed=${plan.displayedTrackerId}|single=${state.trail.size}->${snapshot.singleTrail.size}|" +
                "multi=${snapshot.renderTrailsByTracker.mapValues { it.value.size }}|liveHead=$liveHead"
        if (CaptureLogThrottle.shouldLogOnChange("effective_project", projectSignature)) {
            GeoVaultCaptureLog.d(
                TAG,
                "map_update effective_project mode=${state.mode} displayed=${plan.displayedTrackerId} " +
                    "singleRaw=${state.trail.size} singleEffective=${snapshot.singleTrail.size} " +
                    "multiRaw=${state.allQueueTrailsByTracker.mapValues { it.value.size }} " +
                    "multiEffective=${snapshot.renderTrailsByTracker.mapValues { it.value.size }} " +
                    "visible=${input.visibleTrackerIds?.sorted()} liveHead=$liveHead"
            )
        }
        return TrackerMapEffectiveSession(
            snapshot = snapshot,
            liveHead = liveHead,
        )
    }

    fun resolveLiveHead(snapshot: TrackerMapSessionSnapshot): Pair<Double, Double>? {
        val state = snapshot.uiState
        return when (state.mode) {
            TrackerMapDisplayMode.SINGLE_SESSION -> resolveSingleLiveHead(snapshot)
            TrackerMapDisplayMode.ALL_QUEUE,
            TrackerMapDisplayMode.GROUP_PLACEHOLDER -> resolveMultiLiveHead(snapshot)
        }
    }

    fun allQueueTrailsWithLocalRuntimeOverlay(
        mode: TrackerMapDisplayMode,
        runtime: TrackingRuntimeSnapshot,
        groupTrackerIds: Set<String>,
        allQueueTrailsByTracker: Map<String, List<QueuedLocation>>,
        trailPointLimit: Int,
    ): Map<String, List<QueuedLocation>> {
        if (mode != TrackerMapDisplayMode.ALL_QUEUE && mode != TrackerMapDisplayMode.GROUP_PLACEHOLDER) {
            return allQueueTrailsByTracker
        }
        if (!runtime.localRecordingActive) return allQueueTrailsByTracker
        val trackerId = runtime.locallyRecordedTrackerId
        if (trackerId.isEmpty()) return allQueueTrailsByTracker
        if (mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER && trackerId !in groupTrackerIds) {
            return allQueueTrailsByTracker
        }
        val currentTrail = allQueueTrailsByTracker[trackerId].orEmpty()
        val nextTrail = trailWithLocalRuntimeOverlay(
            runtime = runtime,
            trackerId = trackerId,
            currentTrail = currentTrail,
            trailPointLimit = trailPointLimit,
        )
        if (nextTrail === currentTrail) {
            GeoVaultCaptureLog.v(
                TAG,
                "map_update effective_overlay_multi_skipped mode=$mode tracker=$trackerId current=${currentTrail.size}"
            )
            return allQueueTrailsByTracker
        }
        GeoVaultCaptureLog.d(
            TAG,
            "map_update effective_overlay_multi_added mode=$mode tracker=$trackerId " +
                "from=${currentTrail.size} to=${nextTrail.size} runtimeTs=${runtime.lastTrackedTimestampMs}"
        )
        return allQueueTrailsByTracker.toMutableMap().apply {
            this[trackerId] = nextTrail
        }
    }

    fun singleTrailWithLocalRuntimeOverlay(
        mode: TrackerMapDisplayMode,
        runtime: TrackingRuntimeSnapshot,
        displayedTrackerId: String,
        trail: List<QueuedLocation>,
        trailPointLimit: Int,
    ): List<QueuedLocation> {
        if (mode != TrackerMapDisplayMode.SINGLE_SESSION) return trail
        if (!runtime.localRecordingActive) return trail
        val trackerId = runtime.locallyRecordedTrackerId
        if (trackerId.isEmpty()) return trail
        val effectiveDisplayedId = displayedTrackerId.trim().ifBlank {
            runtime.selectedTrackerId.trim()
        }
        if (effectiveDisplayedId != trackerId) return trail
        val nextTrail = trailWithLocalRuntimeOverlay(
            runtime = runtime,
            trackerId = trackerId,
            currentTrail = trail,
            trailPointLimit = trailPointLimit,
        )
        if (nextTrail !== trail) {
            GeoVaultCaptureLog.d(
                TAG,
                "map_update effective_overlay_single_added tracker=$trackerId displayed=$effectiveDisplayedId " +
                    "from=${trail.size} to=${nextTrail.size} runtimeTs=${runtime.lastTrackedTimestampMs}"
            )
        } else {
            GeoVaultCaptureLog.v(
                TAG,
                "map_update effective_overlay_single_skipped tracker=$trackerId displayed=$effectiveDisplayedId current=${trail.size}"
            )
        }
        return nextTrail
    }

    private fun resolveSingleLiveHead(snapshot: TrackerMapSessionSnapshot): Pair<Double, Double>? {
        snapshot.singleTrail.lastOrNull()?.let { return it.latitude to it.longitude }
        val runtime = snapshot.runtime
        val displayedId = snapshot.plan.displayedTrackerId.trim()
        val selectedId = snapshot.plan.selectedTrackerId.trim()
        if (displayedId.isNotEmpty() && selectedId.isNotEmpty() && displayedId != selectedId) {
            return null
        }
        val lat = runtime.lastTrackedLatitude
        val lon = runtime.lastTrackedLongitude
        if (lat != null && lon != null && runtime.lastTrackedTimestampMs > 0L) {
            return lat to lon
        }
        return null
    }

    private fun resolveMultiLiveHead(snapshot: TrackerMapSessionSnapshot): Pair<Double, Double>? {
        val runtime = snapshot.runtime
        val trackerId = runtime.locallyRecordedTrackerId.trim().ifBlank {
            runtime.selectedTrackerId.trim()
        }
        if (trackerId.isNotEmpty()) {
            snapshot.renderTrailsByTracker[trackerId]?.lastOrNull()?.let {
                return it.latitude to it.longitude
            }
        }
        if (trackerId !in snapshot.renderTrailsByTracker.keys) {
            GeoVaultCaptureLog.d(
                TAG,
                "map_update effective_live_head_none reason=tracker_not_rendered mode=${snapshot.mode} tracker=$trackerId " +
                    "rendered=${snapshot.renderTrailsByTracker.keys.sorted()}"
            )
            return null
        }
        val lat = runtime.lastTrackedLatitude
        val lon = runtime.lastTrackedLongitude
        if (lat != null && lon != null && runtime.lastTrackedTimestampMs > 0L) {
            return lat to lon
        }
        return null
    }

    private fun trailWithLocalRuntimeOverlay(
        runtime: TrackingRuntimeSnapshot,
        trackerId: String,
        currentTrail: List<QueuedLocation>,
        trailPointLimit: Int,
    ): List<QueuedLocation> {
        val point = localRuntimeOverlayPoint(runtime, trackerId) ?: return currentTrail
        val last = currentTrail.lastOrNull()
        val activeSessionStart = point.startTimestampMs
        val tailSession = last?.startTimestampMs
        val tailMatchesActiveSession = last != null &&
            tailSession != null &&
            activeSessionStart != null &&
            tailSession == activeSessionStart
        if (tailMatchesActiveSession && last.time >= point.time) {
            return currentTrail
        }
        return TrackerMapTrailDecimationPolicy.fitToCount(
            currentTrail + point,
            trailPointLimit,
        )
    }

    private fun localRuntimeOverlayPoint(
        runtime: TrackingRuntimeSnapshot,
        trackerId: String,
    ): QueuedLocation? {
        val lat = runtime.lastTrackedLatitude ?: return null
        val lon = runtime.lastTrackedLongitude ?: return null
        val runtimeTs = runtime.lastTrackedTimestampMs
        if (runtimeTs <= 0L) return null
        val activeSessionStart = runtime.sessionStartTimeMs.takeIf { it > 0L }
        return QueuedLocation(
            id = 0L,
            trackerId = trackerId,
            time = runtimeTs,
            latitude = lat,
            longitude = lon,
            altitude = null,
            speed = null,
            bearing = null,
            accuracy = runtime.lastAccuracyMeters,
            sat = null,
            prov = TrackerMapPointProvenancePolicy.PROVENANCE_LOCAL_GPS_RUNTIME,
            dist = null,
            startTimestampMs = activeSessionStart,
        )
    }
}
