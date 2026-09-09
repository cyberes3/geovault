package com.geovault.tracker.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.tracker.Tracker
import com.geovault.tracker.di.TrackerAppServices
import com.geovault.tracker.map.MapSessionDocument
import com.geovault.tracker.map.TrackerMapPorts
import com.geovault.tracker.map.TrackerMapRuntime
import com.geovault.tracker.map.TrailView
import kotlinx.coroutines.flow.StateFlow
import org.maplibre.android.geometry.LatLngBounds

class TrackerMapViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        const val TAG = "TrackerMapViewModel"
        const val TRAIL_POINT_LIMIT = 4000

        // Upper bound for the local Room queue load. Mirrors TrackingService.MAX_QUEUE_SIZE so we
        // always read every retained point and let the session-aware decimator decide which to
        // drop, rather than letting `ORDER BY time DESC LIMIT TRAIL_POINT_LIMIT` silently truncate
        // the head of the previous session below the SQL layer.
        internal const val QUEUE_TRAIL_FETCH_LIMIT = 5000
    }

    private val rt = TrackerMapRuntime(TrackerMapPorts(application, viewModelScope))

    internal val uiState: StateFlow<TrackerMapUiState> = rt.uiState
    val renderPackage: StateFlow<TrackerMapRenderPackage> = rt.renderEngine.renderPackage
    val chrome: StateFlow<TrackerMapChromeModel> = rt.renderEngine.chrome
    val cameraDirective = rt.renderEngine.cameraDirective
    val sessionDocument: StateFlow<MapSessionDocument> = rt.sessionEngine.document
    val trailView: StateFlow<TrailView> = rt.trailEngine.trail
    val cameraGenerationFlow: StateFlow<Long> = rt.cameraGenerationFlow

    fun cameraGeneration(): Long = rt.cameraGeneration()

    init {
        rt.start()
    }

    fun updateLocationSurface(input: TrackerMapUserLocationInput) =
        rt.renderEngine.updateLocationSurface(input)

    fun requestLiveGpsPuck() = rt.sessionEngine.requestLiveGpsPuck()

    fun setMode(mode: TrackerMapDisplayMode) = rt.sessionEngine.setMode(mode)

    fun setGroupModeGroup(groupId: String) = rt.sessionEngine.setGroupModeGroup(groupId)

    fun openTrackerOnMap(trackerId: String, trackerName: String?) = rt.sessionEngine.openTrackerOnMap(trackerId, trackerName)

    fun openGroupOnMap(groupId: String) = rt.sessionEngine.openGroupOnMap(groupId)

    fun restoreSelectedTrackerAfterStreamingStop() = rt.sessionEngine.restoreSelectedTrackerAfterStreamingStop()

    fun restoreSelectedTrackerMapContext() = rt.sessionEngine.restoreSelectedTrackerMapContext()

    fun resolveListNavigationTarget(preferredTrackerIdOverride: String? = null) =
        rt.sessionEngine.resolveListNavigationTarget(preferredTrackerIdOverride)

    fun onTrackerMarkerTapped(trackerId: String) = rt.sessionEngine.onTrackerMarkerTapped(trackerId)

    fun onMapBackgroundTapped(): Boolean = rt.sessionEngine.onMapBackgroundTapped()

    fun clearMapTrackerSelection() = rt.sessionEngine.clearMapTrackerSelection()

    fun focusSelectedTrackerOnMap() = rt.sessionEngine.focusSelectedTrackerOnMap()

    fun toggleSelectedTrackerLock() = rt.sessionEngine.toggleSelectedTrackerLock()

    fun toggleDisplayedTrackerLock() = rt.sessionEngine.toggleDisplayedTrackerLock()

    fun selectionLockPointOrNull(): Pair<Double, Double>? = rt.sessionEngine.selectionLockPointOrNull()

    fun onHostPaused() = rt.sessionEngine.onHostPaused()

    fun onHostResumed() = rt.sessionEngine.onHostResumed()

    fun onMapSurfaceVisible() = rt.sessionEngine.onMapSurfaceVisible()

    fun onMapSurfaceHidden(markBackground: Boolean = false) = rt.sessionEngine.onMapSurfaceHidden(markBackground)

    fun setMapReady(isReady: Boolean) = rt.sessionEngine.setMapReady(isReady)

    fun setFollowLock(enabled: Boolean) = rt.sessionEngine.setFollowLock(enabled)

    fun setFollowPuck(latitude: Double, longitude: Double) {
        rt.renderEngine.setFollowPuck(latitude, longitude)
        if (rt.stateHub.uiStateMutable.value.followLockEnabled) {
            rt.renderEngine.refreshFollowLockCamera()
        }
    }

    fun setGpsHomeAnchor(latitude: Double, longitude: Double) {
        rt.renderEngine.setGpsHomeAnchor(latitude, longitude)
    }

    fun disableAllMapLocks() = rt.sessionEngine.disableAllMapLocks()

    fun onUserOwnedZoom() = rt.sessionEngine.onUserOwnedZoom()

    fun setLiveActiveFit(enabled: Boolean) = rt.sessionEngine.setLiveActiveFit(enabled)

    fun requestFitTrail(mode: TrackerMapFitTrailMode = TrackerMapFitTrailMode.Animated) =
        rt.sessionEngine.requestFitTrail(mode)

    fun buildMapRenderState(): com.geovault.common.maps.render.MapRenderState = rt.renderEngine.buildMapRenderState()

    fun trailBoundsOrNull(): LatLngBounds? = rt.renderEngine.trailBoundsOrNull()

    fun catalogTracker(trackerId: String): Tracker? {
        return TrackerAppServices.from(getApplication()).catalogStateStore().tracker(trackerId)
    }

    override fun onCleared() {
        rt.onCleared()
        super.onCleared()
    }
}
