package com.geovault.tracker.fragments.map

import com.geovault.tracker.GeoJsonLineString
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackUpdateHelper
import com.geovault.tracker.pipeline.TrackPointBus
import com.geovault.tracker.pipeline.TrackPointEvent
import com.geovault.tracker.pipeline.TrackPointSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.android.geometry.LatLng

class MapTrackPointLifecycleIntegrationTest {
    @Before
    fun resetBus() {
        TrackPointBus.resetForTests()
    }

    private data class Harness(
        val displayedTrackerId: String?,
        val selectedTrackerId: String? = null,
        val trackingRunning: Boolean = false,
        val showAllTrackers: Boolean = false,
        val mapViewContext: MapViewContext = MapViewContext.SINGLE_TRACKER,
        val activeStreamedTrackerIds: Set<String> = emptySet(),
        val trackers: List<Tracker>? = null,
        val appendedTrackPoints: MutableList<LatLng> = mutableListOf(),
        val appendedTrackTimestamps: MutableList<Long> = mutableListOf(),
        val multiTrackCoordsCache: MutableMap<String, MutableList<List<Double>>> = mutableMapOf(),
        var lastStreamedPointTimeMs: Long? = null,
        var debouncedMultiRenderCount: Int = 0
    )

    private fun callbacks(h: Harness): MapLiveStreamPointCallbacks {
        return MapLiveStreamPointCallbacks(
            getShowAllTrackers = { h.showAllTrackers },
            getMapViewContext = { h.mapViewContext },
            getActiveStreamedTrackerIds = { h.activeStreamedTrackerIds },
            getLastAllTrackers = { h.trackers },
            getTrackerBaseCoordsForMultiContext = { _, id ->
                h.multiTrackCoordsCache.getOrPut(id) { mutableListOf() }
            },
            setMultiTrackCoordsCache = { id, coords -> h.multiTrackCoordsCache[id] = coords },
            setLastKnownUpdateTimeMsByTrackerId = { _, _ -> },
            getSelectedMapTracker = { null },
            onUpdateSelectedMapTracker = { _, _, _, _ -> },
            onRecenterFollowLock = { },
            getShowMyLocationEnabled = { false },
            getLockMode = { MapLockMode.NONE },
            scheduleDebouncedMultiTrackRender = { h.debouncedMultiRenderCount++ },
            updateMapSelectionUi = { },
            getIsAdded = { true },
            getLastStreamedPointTimeMs = { h.lastStreamedPointTimeMs },
            setLastStreamedPointTimeMs = { h.lastStreamedPointTimeMs = it },
            updateStreamingUi = { },
            addTrackPoint = { latLng, ts ->
                TrackUpdateHelper.updateTrack(h.appendedTrackPoints, h.appendedTrackTimestamps, latLng, ts)
            },
            scheduleTrackLineUpdate = { },
            updateZoomToLatestButtonState = { },
            scheduleDebouncedSingleLiveFit = { },
            getLiveActiveFitEnabled = { false }
        )
    }

    private fun startCollector(
        scope: CoroutineScope,
        context: MapTrackPointContext,
        harness: Harness
    ): Job {
        return scope.launch {
            TrackPointBus.events.collect { event ->
                val state = MapTrackPointReducer.stateFromContext(context)
                if (!MapTrackPointReducer.shouldAcceptPoint(event, state)) return@collect
                MapLiveStreamPointHandler.applyLiveStreamPoint(
                    trackId = event.trackId,
                    lat = event.lat,
                    lon = event.lon,
                    timestampMs = event.timestampMs,
                    callbacks = callbacks(harness)
                )
            }
        }
    }

    private suspend fun waitUntil(condition: () -> Boolean) {
        withTimeout(2000L) {
            while (!condition()) {
                delay(10L)
            }
        }
    }

    @Test
    fun missedSingleTrackerEvent_isReplayedAfterCollectorRestart() = runBlocking {
        val trackerId = "lifecycle-single-${System.nanoTime()}"
        val context = MapTrackPointContext(
            trackingRunning = false,
            showAllTrackers = false,
            mapViewContext = MapViewContext.SINGLE_TRACKER,
            displayedTrackerId = trackerId,
            selectedTrackerId = null,
            activeStreamedTrackerIds = emptySet()
        )
        val harness = Harness(displayedTrackerId = trackerId)
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val initialTs = System.currentTimeMillis()
            TrackPointBus.publish(
                TrackPointEvent(
                    source = TrackPointSource.REMOTE_STREAM,
                    trackId = trackerId,
                    lon = 10.0,
                    lat = 20.0,
                    timestampMs = initialTs,
                    orderingKey = 1L
                )
            )
            var job = startCollector(scope, context, harness)
            waitUntil { harness.appendedTrackTimestamps.contains(initialTs) }
            job.cancel()

            val missedWhilePausedTs = initialTs + 1000L
            TrackPointBus.publish(
                TrackPointEvent(
                    source = TrackPointSource.REMOTE_STREAM,
                    trackId = trackerId,
                    lon = 11.0,
                    lat = 21.0,
                    timestampMs = missedWhilePausedTs,
                    orderingKey = 1L
                )
            )

            job = startCollector(scope, context, harness)
            waitUntil { harness.appendedTrackTimestamps.contains(missedWhilePausedTs) }
            job.cancel()

            assertTrue(harness.appendedTrackTimestamps.contains(initialTs))
            assertTrue(harness.appendedTrackTimestamps.contains(missedWhilePausedTs))
            assertTrue(harness.appendedTrackTimestamps.zipWithNext().all { it.first <= it.second })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun replayedMultiTrackerEvents_stillRespectActiveTrackerFiltering() = runBlocking {
        val activeTrackerId = "lifecycle-multi-active-${System.nanoTime()}"
        val inactiveTrackerId = "lifecycle-multi-inactive-${System.nanoTime()}"
        val activeTracker = Tracker(
            id = activeTrackerId,
            name = "Active",
            color = "#00FF00",
            geometry = GeoJsonLineString("LineString", emptyList())
        )
        val context = MapTrackPointContext(
            trackingRunning = false,
            showAllTrackers = true,
            mapViewContext = MapViewContext.SINGLE_TRACKER,
            displayedTrackerId = null,
            selectedTrackerId = null,
            activeStreamedTrackerIds = setOf(activeTrackerId)
        )
        val harness = Harness(
            displayedTrackerId = null,
            showAllTrackers = true,
            mapViewContext = MapViewContext.SINGLE_TRACKER,
            activeStreamedTrackerIds = setOf(activeTrackerId),
            trackers = listOf(activeTracker)
        )
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            TrackPointBus.publish(
                TrackPointEvent(
                    source = TrackPointSource.REMOTE_STREAM,
                    trackId = inactiveTrackerId,
                    lon = 30.0,
                    lat = 40.0,
                    timestampMs = System.currentTimeMillis(),
                    orderingKey = 1L
                )
            )
            val activeTs = System.currentTimeMillis() + 1000L
            TrackPointBus.publish(
                TrackPointEvent(
                    source = TrackPointSource.REMOTE_STREAM,
                    trackId = activeTrackerId,
                    lon = 31.0,
                    lat = 41.0,
                    timestampMs = activeTs,
                    orderingKey = 1L
                )
            )

            val job = startCollector(scope, context, harness)
            waitUntil { (harness.multiTrackCoordsCache[activeTrackerId]?.size ?: 0) >= 1 }
            job.cancel()

            assertTrue(harness.multiTrackCoordsCache.containsKey(activeTrackerId))
            assertTrue(!harness.multiTrackCoordsCache.containsKey(inactiveTrackerId))
            assertTrue(harness.debouncedMultiRenderCount >= 1)
        } finally {
            scope.cancel()
        }
    }
}
