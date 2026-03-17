package com.geovault.tracker.fragments.map

import com.geovault.tracker.GeoJsonLineString
import com.geovault.tracker.Tracker
import com.geovault.tracker.pipeline.TrackPointEvent
import com.geovault.tracker.pipeline.TrackPointSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.android.geometry.LatLng

class MapDataFlowToRendererTest {
    private data class RendererHarness(
        val showAllTrackers: Boolean = false,
        val mapViewContext: MapViewContext = MapViewContext.SINGLE_TRACKER,
        val activeStreamedTrackerIds: Set<String> = emptySet(),
        val displayedTrackerId: String? = null,
        val isAdded: Boolean = true,
        val showMyLocationEnabled: Boolean = false,
        val followLockActive: Boolean = false,
        val liveActiveFitEnabled: Boolean = false,
        val trackers: List<Tracker>? = null,
        val selectedTracker: SelectedMapTracker? = null,
        val baseCoordsByTrackId: MutableMap<String, MutableList<List<Double>>> = mutableMapOf(),
        val multiTrackCoordsCache: MutableMap<String, MutableList<List<Double>>> = mutableMapOf(),
        val lastKnownUpdateTimeByTrackId: MutableMap<String, Long> = mutableMapOf(),
        val appendedTrackPoints: MutableList<Pair<LatLng, Long>> = mutableListOf(),
        var lastStreamedPointTimeMs: Long? = null,
        var updatedSelectedMapTrackerCount: Int = 0,
        var recenterFollowLockCount: Int = 0,
        var debouncedMultiRenderCount: Int = 0,
        var mapSelectionUiUpdateCount: Int = 0,
        var streamingUiUpdateCount: Int = 0,
        var trackLineUpdateCount: Int = 0,
        var zoomButtonUpdateCount: Int = 0,
        var debouncedSingleLiveFitCount: Int = 0
    )

    private fun buildCallbacks(harness: RendererHarness): MapLiveStreamPointCallbacks {
        return MapLiveStreamPointCallbacks(
            getShowAllTrackers = { harness.showAllTrackers },
            getMapViewContext = { harness.mapViewContext },
            getActiveStreamedTrackerIds = { harness.activeStreamedTrackerIds },
            getLastAllTrackers = { harness.trackers },
            getTrackerBaseCoordsForMultiContext = { _, trackId ->
                harness.baseCoordsByTrackId.getOrPut(trackId) { mutableListOf() }
            },
            setMultiTrackCoordsCache = { id, coords -> harness.multiTrackCoordsCache[id] = coords },
            setLastKnownUpdateTimeMsByTrackerId = { id, ms -> harness.lastKnownUpdateTimeByTrackId[id] = ms },
            getSelectedMapTracker = { harness.selectedTracker },
            onUpdateSelectedMapTracker = { _, _, _, _ -> harness.updatedSelectedMapTrackerCount++ },
            onRecenterFollowLock = { _ -> harness.recenterFollowLockCount++ },
            getShowMyLocationEnabled = { harness.showMyLocationEnabled },
            getIsFollowLockActive = { harness.followLockActive },
            scheduleDebouncedMultiTrackRender = { harness.debouncedMultiRenderCount++ },
            updateMapSelectionUi = { harness.mapSelectionUiUpdateCount++ },
            getDisplayedTrackerId = { harness.displayedTrackerId },
            getIsAdded = { harness.isAdded },
            setLastStreamedPointTimeMs = { harness.lastStreamedPointTimeMs = it },
            updateStreamingUi = { harness.streamingUiUpdateCount++ },
            addTrackPoint = { latLng, ts -> harness.appendedTrackPoints.add(latLng to ts) },
            scheduleTrackLineUpdate = { harness.trackLineUpdateCount++ },
            updateZoomToLatestButtonState = { harness.zoomButtonUpdateCount++ },
            scheduleDebouncedSingleLiveFit = { harness.debouncedSingleLiveFitCount++ },
            getLiveActiveFitEnabled = { harness.liveActiveFitEnabled }
        )
    }

    private fun runPipeline(
        context: MapTrackPointContext,
        event: TrackPointEvent,
        harness: RendererHarness
    ) {
        val state = MapTrackPointReducer.stateFromContext(context)
        if (MapTrackPointReducer.shouldAcceptPoint(event, state)) {
            MapLiveStreamPointHandler.applyLiveStreamPoint(
                trackId = event.trackId,
                lat = event.lat,
                lon = event.lon,
                timestampMs = event.timestampMs,
                callbacks = buildCallbacks(harness)
            )
        }
    }

    @Test
    fun gpsEvent_rejectedWhenNotTracking_noRendererEffects() {
        val harness = RendererHarness(displayedTrackerId = "tracker-1")
        val context = MapTrackPointContext(
            trackingRunning = false,
            showAllTrackers = false,
            mapViewContext = MapViewContext.SINGLE_TRACKER,
            displayedTrackerId = "tracker-1",
            activeStreamedTrackerIds = emptySet()
        )
        val event = TrackPointEvent(
            source = TrackPointSource.LOCAL_GPS,
            trackId = "tracker-1",
            lon = 12.0,
            lat = 34.0,
            timestampMs = 1_700_000_000_000L
        )

        runPipeline(context, event, harness)

        assertTrue(harness.appendedTrackPoints.isEmpty())
        assertEquals(0, harness.trackLineUpdateCount)
        assertEquals(0, harness.streamingUiUpdateCount)
    }

    @Test
    fun remoteSingleTrackerEvent_flowsToRendererCallbacks() {
        val harness = RendererHarness(displayedTrackerId = "tracker-1", liveActiveFitEnabled = true)
        val context = MapTrackPointContext(
            trackingRunning = false,
            showAllTrackers = false,
            mapViewContext = MapViewContext.SINGLE_TRACKER,
            displayedTrackerId = "tracker-1",
            activeStreamedTrackerIds = emptySet()
        )
        val event = TrackPointEvent(
            source = TrackPointSource.REMOTE_STREAM,
            trackId = "tracker-1",
            lon = 10.0,
            lat = 20.0,
            timestampMs = 1_700_000_000_123L
        )

        runPipeline(context, event, harness)

        assertEquals(1, harness.appendedTrackPoints.size)
        assertEquals(1, harness.trackLineUpdateCount)
        assertEquals(1, harness.zoomButtonUpdateCount)
        assertEquals(1, harness.streamingUiUpdateCount)
        assertEquals(1, harness.debouncedSingleLiveFitCount)
        assertEquals(1_700_000_000_123L, harness.lastStreamedPointTimeMs)
        assertEquals(1_700_000_000_123L, harness.lastKnownUpdateTimeByTrackId["tracker-1"])
    }

    @Test
    fun invalidLatLon_reachesPipelineButIsDroppedBeforeRendering() {
        val harness = RendererHarness(displayedTrackerId = "tracker-1")
        val context = MapTrackPointContext(
            trackingRunning = false,
            showAllTrackers = false,
            mapViewContext = MapViewContext.SINGLE_TRACKER,
            displayedTrackerId = "tracker-1",
            activeStreamedTrackerIds = emptySet()
        )
        val event = TrackPointEvent(
            source = TrackPointSource.REMOTE_STREAM,
            trackId = "tracker-1",
            lon = 200.0,
            lat = 95.0,
            timestampMs = 1_700_000_000_123L
        )

        runPipeline(context, event, harness)

        assertTrue(harness.appendedTrackPoints.isEmpty())
        assertEquals(0, harness.trackLineUpdateCount)
        assertNull(harness.lastKnownUpdateTimeByTrackId["tracker-1"])
    }

    @Test
    fun remoteMultiTrackerEvent_appendsAndSchedulesMultiRender() {
        val tracker = Tracker(
            id = "tracker-2",
            name = "Tracker 2",
            color = "#00FF00",
            geometry = GeoJsonLineString("LineString", emptyList())
        )
        val harness = RendererHarness(
            showAllTrackers = true,
            mapViewContext = MapViewContext.SINGLE_TRACKER,
            activeStreamedTrackerIds = setOf("tracker-2"),
            trackers = listOf(tracker),
            baseCoordsByTrackId = mutableMapOf(
                "tracker-2" to mutableListOf(listOf(10.0, 20.0, 1_700_000_000_000.0))
            )
        )
        val context = MapTrackPointContext(
            trackingRunning = false,
            showAllTrackers = true,
            mapViewContext = MapViewContext.SINGLE_TRACKER,
            displayedTrackerId = null,
            activeStreamedTrackerIds = setOf("tracker-2")
        )
        val event = TrackPointEvent(
            source = TrackPointSource.REMOTE_STREAM,
            trackId = "tracker-2",
            lon = 11.0,
            lat = 21.0,
            timestampMs = 1_700_000_000_500L
        )

        runPipeline(context, event, harness)

        assertEquals(1, harness.debouncedMultiRenderCount)
        assertEquals(1_700_000_000_500L, harness.lastKnownUpdateTimeByTrackId["tracker-2"])
        assertTrue(harness.multiTrackCoordsCache["tracker-2"]?.size == 2)
    }

    @Test
    fun staleMultiTrackerTimestamp_doesNotTriggerRender() {
        val tracker = Tracker(
            id = "tracker-2",
            name = "Tracker 2",
            color = "#00FF00",
            geometry = GeoJsonLineString("LineString", emptyList())
        )
        val harness = RendererHarness(
            showAllTrackers = true,
            mapViewContext = MapViewContext.SINGLE_TRACKER,
            activeStreamedTrackerIds = setOf("tracker-2"),
            trackers = listOf(tracker),
            baseCoordsByTrackId = mutableMapOf(
                "tracker-2" to mutableListOf(listOf(10.0, 20.0, 1_700_000_000_500.0))
            )
        )
        val context = MapTrackPointContext(
            trackingRunning = false,
            showAllTrackers = true,
            mapViewContext = MapViewContext.SINGLE_TRACKER,
            displayedTrackerId = null,
            activeStreamedTrackerIds = setOf("tracker-2")
        )
        val event = TrackPointEvent(
            source = TrackPointSource.REMOTE_STREAM,
            trackId = "tracker-2",
            lon = 11.0,
            lat = 21.0,
            timestampMs = 1_700_000_000_400L
        )

        runPipeline(context, event, harness)

        assertEquals(0, harness.debouncedMultiRenderCount)
        assertNull(harness.lastKnownUpdateTimeByTrackId["tracker-2"])
        assertTrue(harness.multiTrackCoordsCache.isEmpty())
    }
}
