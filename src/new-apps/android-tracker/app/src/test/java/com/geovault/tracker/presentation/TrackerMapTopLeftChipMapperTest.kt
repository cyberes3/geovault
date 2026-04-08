package com.geovault.tracker.presentation

import com.geovault.tracker.R
import com.geovault.tracker.services.TrackingRuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        val result = mapper.map(state)

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

        val result = mapper.map(state)

        assertEquals(TrackerMapTopLeftChipUiModel.Hidden, result)
    }

    @Test
    fun allTrackersMode_whenNotRunning_returnsAllTrackersChip() {
        val state = baseState(mode = TrackerMapDisplayMode.ALL_QUEUE)

        val result = mapper.map(state)

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

        val result = mapper.map(state) as TrackerMapTopLeftChipUiModel.Visible

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

        val result = mapper.map(state) as TrackerMapTopLeftChipUiModel.Visible

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

        val result = mapper.map(state)

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

        val result = mapper.map(state) as TrackerMapTopLeftChipUiModel.Visible

        assertEquals(TrackerMapTopLeftChipText.Resource(R.string.groups_title), result.title)
    }

    private fun baseState(
        mode: TrackerMapDisplayMode,
        displayedTrackerId: String = "",
        displayedTrackerName: String = "",
        currentGroupId: String = "",
        groupModeOptions: List<TrackerMapGroupModeOption> = emptyList(),
        runtime: TrackingRuntimeSnapshot = TrackingRuntimeSnapshot(),
    ): TrackerMapUiState {
        return TrackerMapUiState(
            runtime = runtime,
            mode = mode,
            displayedTrackerId = displayedTrackerId,
            displayedTrackerName = displayedTrackerName,
            currentGroupId = currentGroupId,
            groupModeOptions = groupModeOptions,
        )
    }
}
