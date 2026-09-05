package com.geovault.tracker.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.tracker.Tracker
import com.geovault.tracker.db.QueuedLocation
import com.geovault.tracker.map.TrackerMapPorts
import com.geovault.tracker.map.TrackerMapRuntime
import com.geovault.tracker.policy.StreamingTargetPolicy
import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.services.RecordingRuntime
import com.geovault.tracker.services.TrackingRuntimeSnapshot
import kotlinx.coroutines.flow.StateFlow
import org.maplibre.android.geometry.LatLngBounds

class TrackerMapViewModel(application: Application) : AndroidViewModel(application) {
    internal enum class HistoryClearRefreshAction {
        REFRESH_GROUP_OR_ALL,
        REFRESH_DISPLAYED_SINGLE,
        REFRESH_SELECTED_SINGLE,
        NO_OP,
    }

    companion object {
        const val TAG = "TrackerMapViewModel"
        const val TRAIL_POINT_LIMIT = 4000

        // Upper bound for the local Room queue load. Mirrors TrackingService.MAX_QUEUE_SIZE so we
        // always read every retained point and let the session-aware decimator decide which to
        // drop, rather than letting `ORDER BY time DESC LIMIT TRAIL_POINT_LIMIT` silently truncate
        // the head of the previous session below the SQL layer.
        internal const val QUEUE_TRAIL_FETCH_LIMIT = 5000

        @JvmStatic
        internal fun resolveStreamTargetIds(
            mode: TrackerMapDisplayMode,
            runtimeRunning: Boolean,
            selectedTrackerId: String,
            displayedTrackerId: String,
            rosterTrackerIds: Set<String>,
            groupTrackerIds: Set<String> = emptySet(),
            groupId: String? = null,
        ): Set<String> {
            return TrackerMapSessionProjector.project(
                TrackerMapSessionIntent(
                    mode = mode,
                    runtime = TrackingRuntimeSnapshot(
                        isRunning = runtimeRunning,
                        recordingRuntime = RecordingRuntime(
                            sessionActive = runtimeRunning,
                            selectedTrackerId = selectedTrackerId,
                        ),
                        selectedTrackerId = selectedTrackerId,
                    ),
                    displayedTrackerId = displayedTrackerId,
                    displayedTrackerName = "",
                    rosterTrackerIds = rosterTrackerIds,
                    groupSelection = TrackerMapGroupModeSelection(groupId = groupId, trackerIds = groupTrackerIds),
                    activeStreamedTrackerIds = emptySet(),
                ),
            ).remoteSubscriptionIds
        }

        @JvmStatic
        internal fun resolveHistoryClearRefreshAction(
            mode: TrackerMapDisplayMode,
            displayedTrackerId: String,
            selectedTrackerId: String,
            clearedTrackerId: String,
        ): HistoryClearRefreshAction {
            if (mode == TrackerMapDisplayMode.GROUP_PLACEHOLDER || mode == TrackerMapDisplayMode.ALL_QUEUE) {
                return HistoryClearRefreshAction.REFRESH_GROUP_OR_ALL
            }
            val normalizedDisplayed = displayedTrackerId.trim()
            val normalizedSelected = selectedTrackerId.trim()
            val normalizedCleared = clearedTrackerId.trim()
            if (normalizedCleared.isEmpty()) return HistoryClearRefreshAction.NO_OP
            if (normalizedDisplayed.isNotEmpty() && normalizedDisplayed == normalizedCleared) {
                return HistoryClearRefreshAction.REFRESH_DISPLAYED_SINGLE
            }
            if (normalizedDisplayed.isEmpty() && normalizedSelected == normalizedCleared) {
                return HistoryClearRefreshAction.REFRESH_SELECTED_SINGLE
            }
            return HistoryClearRefreshAction.NO_OP
        }

        @JvmStatic
        internal fun resolveLiveHeadCoord(state: TrackerMapUiState): Pair<Double, Double>? {
            val displayedTrackerId = TrackerMapDisplayIds.effectiveDisplayedTrackerId(state)
            val acceptedRemoteIds = StreamingTargetPolicy.normalizeTrackerIds(state.streamTargetIds)
                .ifEmpty { state.remoteLastPoints.keys }
            val resolved = TrackerMapLastPointResolver.resolve(
                state = state,
                trackerId = displayedTrackerId.ifBlank { state.runtime.locallyRecordedTrackerId },
                tracker = null,
                acceptedRemoteTrackerIds = acceptedRemoteIds,
            ) ?: return null
            return resolved.latitude to resolved.longitude
        }

        @JvmStatic
        internal fun allQueueTrailsWithLocalRuntimeOverlay(
            mode: TrackerMapDisplayMode,
            runtime: TrackingRuntimeSnapshot,
            groupTrackerIds: Set<String>,
            allQueueTrailsByTracker: Map<String, List<QueuedLocation>>,
        ): Map<String, List<QueuedLocation>> {
            return TrackerMapEffectiveSessionProjector.allQueueTrailsWithLocalRuntimeOverlay(
                mode = mode,
                runtime = runtime,
                groupTrackerIds = groupTrackerIds,
                allQueueTrailsByTracker = allQueueTrailsByTracker,
                trailPointLimit = TRAIL_POINT_LIMIT,
            )
        }

        @JvmStatic
        internal fun singleTrailWithLocalRuntimeOverlay(
            mode: TrackerMapDisplayMode,
            runtime: TrackingRuntimeSnapshot,
            displayedTrackerId: String,
            trail: List<QueuedLocation>,
        ): List<QueuedLocation> {
            return TrackerMapEffectiveSessionProjector.singleTrailWithLocalRuntimeOverlay(
                mode = mode,
                runtime = runtime,
                displayedTrackerId = displayedTrackerId,
                trail = trail,
                trailPointLimit = TRAIL_POINT_LIMIT,
            )
        }

        @JvmStatic
        internal fun streamingActiveTargetsMatchDisplayed(
            mode: TrackerMapDisplayMode,
            displayedIds: Set<String>,
            localRecordingActive: Boolean,
            locallyRecordedTrackerId: String,
            activeStreamTargets: Set<String>,
        ): Boolean {
            if (mode != TrackerMapDisplayMode.GROUP_PLACEHOLDER && mode != TrackerMapDisplayMode.ALL_QUEUE) {
                return false
            }
            val excluded = if (localRecordingActive && locallyRecordedTrackerId.isNotBlank()) {
                setOf(locallyRecordedTrackerId.trim())
            } else {
                emptySet()
            }
            val expected = StreamingTargetPolicy.normalizeTrackerIds(displayedIds - excluded)
            if (expected.isEmpty()) return false
            val active = StreamingTargetPolicy.normalizeTrackerIds(activeStreamTargets)
            return active == expected
        }

        @JvmStatic
        internal fun displayedRosterHasServerHistory(
            mode: TrackerMapDisplayMode,
            rosterIds: Set<String>,
            allQueueTrailsByTracker: Map<String, List<QueuedLocation>>,
        ): Boolean {
            if (mode != TrackerMapDisplayMode.GROUP_PLACEHOLDER && mode != TrackerMapDisplayMode.ALL_QUEUE) {
                return false
            }
            val normalizedRosterIds = rosterIds.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            if (normalizedRosterIds.isEmpty()) return false
            return normalizedRosterIds.all { id ->
                allQueueTrailsByTracker[id].orEmpty().any(TrackerMapPointProvenancePolicy::isServerHistory)
            }
        }

        @JvmStatic
        internal fun resolveBottomCardVisibilityForMarkerTap(hasSelectionCard: Boolean): Boolean {
            return hasSelectionCard
        }

        @JvmStatic
        internal fun resolveBackgroundTapShouldCloseBottomCard(
            isBottomCardVisible: Boolean,
            hasSelectionCard: Boolean,
        ): Boolean {
            return isBottomCardVisible || hasSelectionCard
        }

        @JvmStatic
        internal fun resolveRenderSelectedMapTrackerId(
            isBottomCardVisible: Boolean,
            selectedMapTrackerId: String?,
        ): String? {
            return selectedMapTrackerId
                ?.trim()
                ?.takeIf { isBottomCardVisible && it.isNotEmpty() }
        }

        @JvmStatic
        internal fun resolveFocusActionVisible(mode: TrackerMapDisplayMode): Boolean {
            return mode != TrackerMapDisplayMode.SINGLE_SESSION
        }

        @JvmStatic
        internal fun filterRemoteLastPointsForAcceptedIds(
            remoteLastPoints: Map<String, TrackPointEvent>,
            acceptedRemoteTrackerIds: Set<String>,
        ): Map<String, TrackPointEvent> {
            val acceptedIds = acceptedRemoteTrackerIds
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
            if (acceptedIds.isEmpty()) return remoteLastPoints
            return remoteLastPoints.filterKeys { it.trim() in acceptedIds }
        }
    }

    private val rt = TrackerMapRuntime(TrackerMapPorts(application, viewModelScope))

    val uiState: StateFlow<TrackerMapUiState> = rt.uiState
    val renderPackage: StateFlow<TrackerMapRenderPackage> = rt.renderPackage
    val cameraDirective = rt.cameraDirective
    val cameraGenerationFlow: StateFlow<Long> = rt.cameraGenerationFlow

    fun cameraGeneration(): Long = rt.cameraGeneration()

    init {
        rt.start()
    }

    fun trackerRosterForMapChip(): List<Tracker> = rt.trackerRosterForMapChip()

    fun setMode(mode: TrackerMapDisplayMode) = rt.context.setMode(mode)

    fun setGroupModeGroup(groupId: String) = rt.context.setGroupModeGroup(groupId)

    fun openTrackerOnMap(trackerId: String, trackerName: String?) = rt.context.openTrackerOnMap(trackerId, trackerName)

    fun openGroupOnMap(groupId: String) = rt.context.openGroupOnMap(groupId)

    fun restoreSelectedTrackerAfterStreamingStop() = rt.context.restoreSelectedTrackerAfterStreamingStop()

    fun restoreSelectedTrackerMapContext() = rt.context.restoreSelectedTrackerMapContext()

    fun resolveListNavigationTarget(preferredTrackerIdOverride: String? = null) =
        rt.context.resolveListNavigationTarget(preferredTrackerIdOverride)

    fun onTrackerMarkerTapped(trackerId: String) = rt.context.onTrackerMarkerTapped(trackerId)

    fun onMapBackgroundTapped(): Boolean = rt.context.onMapBackgroundTapped()

    fun clearMapTrackerSelection() = rt.context.clearMapTrackerSelection()

    fun focusSelectedTrackerOnMap() = rt.context.focusSelectedTrackerOnMap()

    fun toggleSelectedTrackerLock() = rt.context.toggleSelectedTrackerLock()

    fun toggleDisplayedTrackerLock() = rt.context.toggleDisplayedTrackerLock()

    fun selectionLockPointOrNull(): Pair<Double, Double>? = rt.context.selectionLockPointOrNull()

    fun onHostPaused() = rt.context.onHostPaused()

    fun onHostResumed() = rt.context.onHostResumed()

    fun onMapSurfaceVisible() = rt.context.onMapSurfaceVisible()

    fun onMapSurfaceHidden(markBackground: Boolean = false) = rt.context.onMapSurfaceHidden(markBackground)

    fun setMapReady(isReady: Boolean) = rt.context.setMapReady(isReady)

    fun setFollowLock(enabled: Boolean) = rt.context.setFollowLock(enabled)

    fun setFollowPuck(latitude: Double, longitude: Double) {
        rt.cameraCoordinator.setFollowPuck(latitude, longitude)
        if (rt.stateHub.uiStateMutable.value.followLockEnabled) {
            rt.display.refreshFollowLockCamera()
        }
    }

    fun disableAllMapLocks() = rt.context.disableAllMapLocks()

    fun onUserOwnedZoom() = rt.context.onUserOwnedZoom()

    fun setLiveActiveFit(enabled: Boolean) = rt.context.setLiveActiveFit(enabled)

    fun requestFitTrail(mode: TrackerMapFitTrailMode = TrackerMapFitTrailMode.Animated) =
        rt.context.requestFitTrail(mode)

    fun buildMapRenderState(): com.geovault.common.maps.render.MapRenderState = rt.display.buildMapRenderState()

    fun trailBoundsOrNull(): LatLngBounds? = rt.display.trailBoundsOrNull()

    fun acceptedRemoteTrackerIdsForCurrentSession(): Set<String> = rt.display.acceptedRemoteTrackerIdsForCurrentSession()

    override fun onCleared() {
        rt.onCleared()
        super.onCleared()
    }
}
