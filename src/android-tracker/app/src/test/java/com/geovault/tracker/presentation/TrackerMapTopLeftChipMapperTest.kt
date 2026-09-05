package com.geovault.tracker.presentation

import com.geovault.tracker.R
import com.geovault.tracker.Tracker
import com.geovault.tracker.policy.TrackPointEvent
import com.geovault.tracker.policy.TrackPointSource
import com.geovault.tracker.services.RecordingRuntime
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
    fun groupMode_whenRunning_returnsGroupChip() {
        val state = baseState(
            mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
            currentGroupId = "g1",
            groupModeOptions = listOf(
                TrackerMapGroupModeOption(
                    groupId = "g1",
                    groupName = "Streaming Group",
                    trackerIds = setOf("local", "remote"),
                )
            ),
            runtime = TrackingRuntimeSnapshot(
                isRunning = true,
                recordingRuntime = RecordingRuntime(sessionActive = true),
            ),
        )

        val result = mapper.map(state, emptyList())

        assertTrue(result is TrackerMapTopLeftChipUiModel.Visible)
        val visible = result as TrackerMapTopLeftChipUiModel.Visible
        assertEquals(TrackerMapTopLeftChipMode.GROUP, visible.mode)
        assertEquals(TrackerMapTopLeftChipText.Value("Streaming Group"), visible.title)
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
    fun allTrackersMode_whenRunning_returnsAllTrackersChip() {
        val state = baseState(
            mode = TrackerMapDisplayMode.ALL_QUEUE,
            runtime = TrackingRuntimeSnapshot(
                isRunning = true,
                recordingRuntime = RecordingRuntime(sessionActive = true),
            ),
        )

        val result = mapper.map(state, emptyList())

        assertTrue(result is TrackerMapTopLeftChipUiModel.Visible)
        val visible = result as TrackerMapTopLeftChipUiModel.Visible
        assertEquals(TrackerMapTopLeftChipMode.ALL_TRACKERS, visible.mode)
        assertEquals(TrackerMapTopLeftChipText.Resource(R.string.all_trackers), visible.title)
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
    fun singleTracker_withUnavailableNoticeAndNoFallbackSelection_showsUnavailableChip() {
        val state = baseState(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = "",
            runtime = TrackingRuntimeSnapshot(selectedTrackerId = ""),
        ).copy(unavailableTrackerNotice = TrackerMapUnavailableNotice(trackerId = "tracker1", trackerName = "Alice"))

        val result = mapper.map(state, emptyList()) as TrackerMapTopLeftChipUiModel.Visible

        assertEquals(TrackerMapTopLeftChipMode.SINGLE_TRACKER, result.mode)
        assertEquals(TrackerMapTopLeftChipText.Value("Alice"), result.title)
        assertEquals(TrackerMapTopLeftChipText.Resource(R.string.tracker_no_longer_available), result.subtitle)
        assertFalse(result.showReset)
    }

    @Test
    fun singleTracker_withUnavailableNoticeButSelectedTrackerFallback_showsSelectedTrackerInstead() {
        // The instant `displayedTrackerId` is blank AND `selectedTrackerId` resolves to something,
        // the normal fallback title takes over -- a stale notice for a *different*, now-superseded
        // tracker must never resurface once the view has moved on.
        val state = baseState(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = "",
            runtime = TrackingRuntimeSnapshot(selectedTrackerId = "tracker2", selectedTrackerName = "Bob"),
        ).copy(unavailableTrackerNotice = TrackerMapUnavailableNotice(trackerId = "tracker1", trackerName = "Alice"))

        val result = mapper.map(state, emptyList()) as TrackerMapTopLeftChipUiModel.Visible

        assertEquals(TrackerMapTopLeftChipText.Value("Bob"), result.title)
    }

    @Test
    fun singleTracker_withUnavailableNoticeButNewDisplayedTracker_ignoresStaleNotice() {
        // Once a *new* tracker is explicitly displayed, `displayedTrackerId` is non-blank again,
        // so the notice branch is bypassed entirely regardless of what it still holds.
        val state = baseState(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = "tracker3",
            displayedTrackerName = "Carol",
            runtime = TrackingRuntimeSnapshot(selectedTrackerId = ""),
        ).copy(unavailableTrackerNotice = TrackerMapUnavailableNotice(trackerId = "tracker1", trackerName = "Alice"))

        val result = mapper.map(state, emptyList()) as TrackerMapTopLeftChipUiModel.Visible

        assertEquals(TrackerMapTopLeftChipText.Value("Carol"), result.title)
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
    fun singleTracker_viewingLocallyRecordedTracker_usesLastPointSentAtMs() {
        val sentAt = 1_700_000_100_000L
        val state = baseState(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = "local",
            displayedTrackerName = "Local",
            runtime = TrackingRuntimeSnapshot(
                selectedTrackerId = "other",
                selectedTrackerName = "Other",
                recordingRuntime = RecordingRuntime(sessionActive = true, selectedTrackerId = "local"),
                lastPointSentAtMs = sentAt,
            ),
        )
        val roster = listOf(
            Tracker(
                id = "local",
                name = "Local",
                color = null,
                last_point = listOf(-122.0, 37.0, 1.0),
                updated_at = 1_700_000_000_000L,
            )
        )
        val result = mapper.map(state, roster) as TrackerMapTopLeftChipUiModel.Visible
        val rel = result.subtitle as TrackerMapTopLeftChipText.RelativeLastData
        assertEquals(sentAt, rel.lastDataEpochMs)
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

    @Test
    fun singleTracker_unacceptedRemoteHead_doesNotDriveSubtitle() {
        val state = baseState(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            displayedTrackerId = "other",
            displayedTrackerName = "Other",
            runtime = TrackingRuntimeSnapshot(
                selectedTrackerId = "sel",
                selectedTrackerName = "Sel",
            ),
        ).copy(
            remoteLastPoints = mapOf(
                "other" to TrackPointEvent(
                    source = TrackPointSource.REMOTE_STREAM,
                    trackId = "other",
                    lon = -122.0,
                    lat = 37.0,
                    timestampMs = 1_700_000_000_000L,
                )
            )
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

        val result = mapper.map(
            state = state,
            roster = roster,
            acceptedRemoteTrackerIds = emptySet(),
        ) as TrackerMapTopLeftChipUiModel.Visible

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

private fun TrackerMapTopLeftChipMapper.map(
    state: TrackerMapUiState,
    roster: List<Tracker>
): TrackerMapTopLeftChipUiModel {
    return map(
        state = state,
        roster = roster,
        acceptedRemoteTrackerIds = state.streamTargetIds + state.activeStreamedTrackerIds,
    )
}
