package com.geovault.tracker.presentation

import com.geovault.tracker.R
import com.geovault.tracker.Tracker
import com.geovault.tracker.services.TrackingRuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerMapTopLeftChipMapperTest {

    private val mapper = TrackerMapTopLeftChipMapper()

    @Test
    fun groupMode_whenNotRunning_returnsGroupChip() {
        val state = baseState(
            mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
            currentGroupId = "g1",
            groupModeOptions = listOf(
                TrackerMapGroupModeOption(
                    groupId = "g1",
                    groupName = "Hike Team",
                    trackerIds = setOf("a"),
                )
            ),
        )

        val result = mapper.map(state, emptyList())

        assertTrue(result is TrackerMapTopLeftChipUiModel.Visible)
        val visible = result as TrackerMapTopLeftChipUiModel.Visible
        assertEquals(TrackerMapTopLeftChipMode.GROUP, visible.mode)
        assertEquals(R.drawable.ic_groups, visible.iconResId)
        assertEquals(TrackerMapTopLeftChipText.Value("Hike Team"), visible.title)
        assertTrue(visible.showReset)
    }

    @Test
    fun groupMode_whenRunning_hidesChip() {
        val state = baseState(
            mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
            runtime = TrackingRuntimeSnapshot(isRunning = true),
        )

        val result = mapper.map(state, emptyList())

        assertEquals(TrackerMapTopLeftChipUiModel.Hidden, result)
    }

    @Test
    fun allTrackersMode_whenNotRunning_returnsAllTrackersChip() {
        val state = baseState(mode = TrackerMapDisplayMode.ALL_QUEUE)

        val result = mapper.map(state, emptyList())

        assertTrue(result is TrackerMapTopLeftChipUiModel.Visible)
        val visible = result as TrackerMapTopLeftChipUiModel.Visible
        assertEquals(TrackerMapTopLeftChipMode.ALL_TRACKERS, visible.mode)
        assertEquals(TrackerMapTopLeftChipText.Resource(R.string.all_trackers), visible.title)
        assertTrue(visible.showReset)
    }

    @Test
    fun singleTracker_defaultSelectedTracker_hidesReset() {
        val state = baseState(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = "selected",
            displayedTrackerName = "Selected",
            runtime = TrackingRuntimeSnapshot(
                selectedTrackerId = "selected",
                selectedTrackerName = "Selected",
            ),
        )

        val result = mapper.map(state, emptyList()) as TrackerMapTopLeftChipUiModel.Visible

        assertEquals(TrackerMapTopLeftChipMode.SINGLE_TRACKER, result.mode)
        assertFalse(result.showReset)
    }

    @Test
    fun singleTracker_nonDefaultDisplayedTracker_showsReset() {
        val state = baseState(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = "other",
            displayedTrackerName = "Other",
            runtime = TrackingRuntimeSnapshot(
                selectedTrackerId = "selected",
                selectedTrackerName = "Selected",
            ),
        )

        val result = mapper.map(state, emptyList()) as TrackerMapTopLeftChipUiModel.Visible

        assertTrue(result.showReset)
        assertEquals(TrackerMapTopLeftChipText.Value("Other"), result.title)
    }

    @Test
    fun singleTracker_withoutAnyTracker_hidesChip() {
        val state = baseState(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = "",
            runtime = TrackingRuntimeSnapshot(selectedTrackerId = ""),
        )

        val result = mapper.map(state, emptyList())

        assertEquals(TrackerMapTopLeftChipUiModel.Hidden, result)
    }

    @Test
    fun groupMode_withoutCurrentGroupName_usesGroupsFallback() {
        val state = baseState(
            mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
            currentGroupId = "missing",
            groupModeOptions = listOf(
                TrackerMapGroupModeOption(
                    groupId = "other",
                    groupName = "Other",
                    trackerIds = setOf("a"),
                )
            ),
        )

        val result = mapper.map(state, emptyList()) as TrackerMapTopLeftChipUiModel.Visible

        assertEquals(TrackerMapTopLeftChipText.Resource(R.string.groups_title), result.title)
    }

    @Test
    fun singleTracker_viewingSelectedTracker_hidesLastUpdatedSubtitle() {
        val state = baseState(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = "a",
            displayedTrackerName = "A",
            runtime = TrackingRuntimeSnapshot(
                selectedTrackerId = "a",
                selectedTrackerName = "A",
            ),
        )
        val roster = listOf(
            Tracker(
                id = "a",
                name = "A",
                color = null,
                last_point = listOf(-122.0, 37.0, 1.0),
                updated_at = 1_700_000_000_000L,
            )
        )
        val result = mapper.map(state, roster) as TrackerMapTopLeftChipUiModel.Visible
        assertNull(result.subtitle)
    }

    @Test
    fun singleTracker_viewingOtherTracker_usesRelativeLastDataSubtitle() {
        val updatedAt = 1_700_000_000_000L
        val state = baseState(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = "other",
            displayedTrackerName = "Other",
            runtime = TrackingRuntimeSnapshot(
                selectedTrackerId = "sel",
                selectedTrackerName = "Sel",
            ),
        )
        val roster = listOf(
            Tracker(
                id = "other",
                name = "Other",
                color = null,
                last_point = listOf(-122.0, 37.0, 1.0),
                updated_at = updatedAt,
            )
        )
        val result = mapper.map(state, roster) as TrackerMapTopLeftChipUiModel.Visible
        val sub = result.subtitle
        assertTrue(sub is TrackerMapTopLeftChipText.RelativeLastData)
        val rel = sub as TrackerMapTopLeftChipText.RelativeLastData
        assertEquals(updatedAt, rel.lastDataEpochMs)
        assertEquals(updatedAt, rel.serverMetadataUpdatedAtMs)
    }

    @Test
    fun singleTracker_streamedDisplayedTrackerWithOwnerEmail_usesUserLabel() {
        val state = baseState(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = "other",
            displayedTrackerName = "Other",
            activeStreamedTrackerIds = setOf("other"),
            runtime = TrackingRuntimeSnapshot(
                selectedTrackerId = "sel",
                selectedTrackerName = "Sel",
            ),
        )
        val roster = listOf(
            Tracker(
                id = "other",
                name = "Other",
                color = null,
                last_point = listOf(-122.0, 37.0, 1.0),
                updated_at = 1_700_000_000_000L,
                owner_email = " owner@example.com ",
            )
        )

        val result = mapper.map(state, roster) as TrackerMapTopLeftChipUiModel.Visible

        assertEquals("owner@example.com", result.userLabel)
    }

    @Test
    fun singleTracker_nonStreamedDisplayedTracker_omitsUserLabel() {
        val state = baseState(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = "other",
            displayedTrackerName = "Other",
            runtime = TrackingRuntimeSnapshot(
                selectedTrackerId = "sel",
                selectedTrackerName = "Sel",
            ),
        )
        val roster = listOf(
            Tracker(
                id = "other",
                name = "Other",
                color = null,
                last_point = listOf(-122.0, 37.0, 1.0),
                updated_at = 1_700_000_000_000L,
                owner_email = "owner@example.com",
            )
        )

        val result = mapper.map(state, roster) as TrackerMapTopLeftChipUiModel.Visible

        assertNull(result.userLabel)
    }

    @Test
    fun singleTracker_streamedDisplayedTrackerWithBlankOwnerEmail_omitsUserLabel() {
        val state = baseState(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = "other",
            displayedTrackerName = "Other",
            streamTargetIds = setOf("other"),
            runtime = TrackingRuntimeSnapshot(
                selectedTrackerId = "sel",
                selectedTrackerName = "Sel",
            ),
        )
        val roster = listOf(
            Tracker(
                id = "other",
                name = "Other",
                color = null,
                last_point = listOf(-122.0, 37.0, 1.0),
                updated_at = 1_700_000_000_000L,
                owner_email = "   ",
            )
        )

        val result = mapper.map(state, roster) as TrackerMapTopLeftChipUiModel.Visible

        assertNull(result.userLabel)
    }

    @Test
    fun singleTracker_viewingOther_withNoPointData_usesWaitingSubtitle() {
        val state = baseState(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = "other",
            displayedTrackerName = "Other",
            runtime = TrackingRuntimeSnapshot(
                selectedTrackerId = "sel",
                selectedTrackerName = "Sel",
            ),
        )
        val roster = listOf(
            Tracker(
                id = "other",
                name = "Other",
                color = null,
                last_point = null,
                updated_at = null,
            )
        )
        val result = mapper.map(state, roster) as TrackerMapTopLeftChipUiModel.Visible
        assertEquals(TrackerMapTopLeftChipText.Resource(R.string.waiting_for_data), result.subtitle)
    }

    private fun baseState(
        mode: TrackerMapDisplayMode,
        displayedTrackerId: String = "",
        displayedTrackerName: String = "",
        currentGroupId: String = "",
        groupModeOptions: List<TrackerMapGroupModeOption> = emptyList(),
        activeStreamedTrackerIds: Set<String> = emptySet(),
        streamTargetIds: Set<String> = emptySet(),
        runtime: TrackingRuntimeSnapshot = TrackingRuntimeSnapshot(),
    ): TrackerMapUiState {
        return TrackerMapUiState(
            runtime = runtime,
            mode = mode,
            displayedTrackerId = displayedTrackerId,
            displayedTrackerName = displayedTrackerName,
            currentGroupId = currentGroupId,
            groupModeOptions = groupModeOptions,
            activeStreamedTrackerIds = activeStreamedTrackerIds,
            streamTargetIds = streamTargetIds,
        )
    }
}
