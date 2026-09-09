package com.geovault.tracker.map

import com.geovault.tracker.presentation.TrackerMapDisplayMode
import com.geovault.tracker.presentation.TrackerMapSelectionCard
import com.geovault.tracker.presentation.TrackerMapUiState
import com.geovault.tracker.streaming.StreamIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MapSessionEngineSnapshotTest {

    @Test
    fun project_copiesSessionFieldsAndHoldIsNullIntent() {
        val engine = MapSessionEngine()
        engine.project(
            state = TrackerMapUiState(
                displayedTrackerId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                currentGroupId = "",
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
            ),
            streamIntent = null,
        )
        val document = engine.document.value
        assertEquals(TrackerMapDisplayMode.SINGLE_SESSION, document.mode)
        assertEquals("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", document.displayedTrackerId)
        assertEquals(emptySet<String>(), document.visibleTrackerIds)
        assertNull(document.streamIntent)
        assertEquals("", document.selectionCardTrackerId)
        assertNull(document.selectionCard)
        assertEquals(MapCameraLock(), document.cameraLock)
        assertEquals(MapSurfaceFlags(), document.surface)
    }

    @Test
    fun project_copiesCameraLockSelectionAndSurface() {
        val engine = MapSessionEngine()
        val card = TrackerMapSelectionCard(
            trackerId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            trackerName = "A",
            latitude = 1.0,
            longitude = 2.0,
            lastUpdatedMs = 1_800_000_000_000L,
            accuracyMeters = null,
            isOwned = true,
        )
        engine.project(
            state = TrackerMapUiState(
                displayedTrackerId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                mode = TrackerMapDisplayMode.SINGLE_SESSION,
                liveActiveFitEnabled = true,
                selectionLockTrackerId = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                isBottomCardVisible = true,
                selectedMapTracker = card,
                isGeometryLoading = true,
                batteryOptimizationHintVisible = true,
            ),
            streamIntent = null,
        )
        val document = engine.document.value
        assertEquals("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", document.selectionCardTrackerId)
        assertEquals(card, document.selectionCard)
        assertEquals(true, document.cameraLock.liveActiveFitEnabled)
        assertEquals("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa", document.cameraLock.selectionTrackerId)
        assertEquals(true, document.surface.geometryLoading)
        assertEquals(true, document.surface.batteryOptimizationHintVisible)
        assertEquals(true, document.surface.bottomCardVisible)
    }

    @Test
    fun project_keepsStreamIntentForGroup() {
        val engine = MapSessionEngine()
        val intent = StreamIntent(
            trackerIds = setOf("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
            displayName = "Trail",
            locallyRecordedTrackerId = null,
        )
        engine.project(
            state = TrackerMapUiState(
                mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
                currentGroupId = "g1",
            ),
            streamIntent = intent,
        )
        assertEquals(setOf("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"), engine.document.value.streamIntent?.trackerIds)
        assertEquals("g1", engine.document.value.groupId)
    }
}
