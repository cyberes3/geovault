package com.geovault.tracker.presentation

import com.geovault.tracker.services.TrackingRuntimeSnapshot
import com.geovault.tracker.services.RecordingRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerMapSessionProjectorTest {

    @Test
    fun singleRemoteWhileTracking_subscribesAndLoadsDisplayedFromServer() {
        val plan = project(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            selectedTrackerId = "local",
            displayedTrackerId = "remote",
            runtimeRunning = true,
        )

        assertEquals(setOf("remote"), plan.remoteSubscriptionIds)
        assertEquals(setOf("remote"), plan.acceptedRemoteTrackerIds)
        assertTrue(plan.localOverlayTrackerIds.isEmpty())
        assertEquals(TrackerMapTrailSource.SINGLE_SERVER, plan.trailReloadPlan.source)
        assertEquals("remote", plan.trailReloadPlan.singleTrackerId)
    }

    @Test
    fun singleLocalWhileTracking_loadsServerHistoryWithLocalOverlayAndNoRemoteSubscription() {
        val plan = project(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            selectedTrackerId = "local",
            displayedTrackerId = "local",
            runtimeRunning = true,
        )

        assertTrue(plan.remoteSubscriptionIds.isEmpty())
        assertEquals(setOf("local"), plan.localOverlayTrackerIds)
        assertEquals(TrackerMapTrailSource.SINGLE_SERVER, plan.trailReloadPlan.source)
        assertEquals("local", plan.trailReloadPlan.singleTrackerId)
        assertEquals("local", plan.trailReloadPlan.overlayTrackerId)
    }

    @Test
    fun groupWhileTrackingMember_streamsRemoteMembersAndOverlaysLocal() {
        val plan = project(
            mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
            selectedTrackerId = "local",
            displayedTrackerId = "",
            runtimeRunning = true,
            groupTrackerIds = setOf("local", "remote-a", "remote-b"),
        )

        assertEquals(setOf("remote-a", "remote-b"), plan.remoteSubscriptionIds)
        assertEquals(setOf("local"), plan.localOverlayTrackerIds)
        assertEquals(TrackerMapTrailSource.MULTI_SERVER, plan.trailReloadPlan.source)
        assertEquals("local", plan.trailReloadPlan.overlayTrackerId)
    }

    @Test
    fun groupWhileTracking_usesRecordingTrackerAsLocalEvenIfSelectedStateDiffers() {
        val plan = project(
            mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
            selectedTrackerId = "selected",
            recordingTrackerId = "local",
            displayedTrackerId = "",
            runtimeRunning = true,
            groupTrackerIds = setOf("selected", "local", "remote-a", "remote-b"),
        )

        // GROUP STREAMING: selected ("selected") stays in the subscription set because group mode
        // is an explicit multi-tracker stream. Only the locally-recorded tracker ("local") is
        // dropped (its GPS feed is local).
        assertEquals(setOf("selected", "remote-a", "remote-b"), plan.remoteSubscriptionIds)
        assertEquals(setOf("local"), plan.localOverlayTrackerIds)
        assertEquals("local", plan.trailReloadPlan.overlayTrackerId)
    }

    @Test
    fun groupWhileTrackingOutsideGroup_streamsWholeGroupAndNoLocalOverlay() {
        val plan = project(
            mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
            selectedTrackerId = "outside",
            displayedTrackerId = "",
            runtimeRunning = true,
            groupTrackerIds = setOf("remote-a", "remote-b"),
        )

        assertEquals(setOf("remote-a", "remote-b"), plan.remoteSubscriptionIds)
        assertTrue(plan.localOverlayTrackerIds.isEmpty())
        assertEquals(null, plan.trailReloadPlan.overlayTrackerId)
    }

    @Test
    fun allWhileTracking_streamsRosterExceptLocalAndOverlaysLocal() {
        val plan = project(
            mode = TrackerMapDisplayMode.ALL_QUEUE,
            selectedTrackerId = "local",
            displayedTrackerId = "",
            runtimeRunning = true,
            rosterTrackerIds = setOf("local", "remote"),
        )

        assertEquals(setOf("remote"), plan.remoteSubscriptionIds)
        assertEquals(setOf("local"), plan.localOverlayTrackerIds)
    }

    @Test
    fun groupMode_usesGroupTrackerIdsAndGroupId() {
        val plan = project(
            mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
            selectedTrackerId = "local",
            displayedTrackerId = "ignored",
            runtimeRunning = false,
            rosterTrackerIds = setOf("not-used"),
            groupTrackerIds = setOf("a", "b"),
            groupId = "group-1",
        )

        assertEquals(setOf("a", "b"), plan.remoteSubscriptionIds)
        assertEquals("group-1", plan.resolvedGroupId)
    }

    @Test
    fun groupModeNotRunning_includesSelectedWhenSelectedIsGroupMember() {
        // GROUP STREAMING: when the user picks a group that contains their own/selected tracker
        // and is not actively recording, the selected tracker must be part of the streaming
        // subscription set. Group mode is an explicit multi-tracker stream and excluding the
        // selected tracker silently dropped the user's own tracker from group streams.
        val plan = project(
            mode = TrackerMapDisplayMode.GROUP_PLACEHOLDER,
            selectedTrackerId = "a",
            displayedTrackerId = "",
            runtimeRunning = false,
            groupTrackerIds = setOf("a", "b", "c"),
        )

        assertEquals(setOf("a", "b", "c"), plan.remoteSubscriptionIds)
        assertEquals(emptySet<String>(), plan.locallyRecordedTrackerIds)
    }

    @Test
    fun singleSelectedNotTracking_neverStreamsSelected() {
        val plan = project(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            selectedTrackerId = "selected",
            displayedTrackerId = "selected",
            runtimeRunning = false,
        )

        assertEquals(emptySet<String>(), plan.remoteSubscriptionIds)
        assertEquals(emptySet<String>(), plan.acceptedRemoteTrackerIds)
        assertEquals(TrackerMapTrailSource.SINGLE_SERVER, plan.trailReloadPlan.source)
        assertEquals("selected", plan.trailReloadPlan.singleTrackerId)
    }

    @Test
    fun allQueueNotTracking_includesSelectedInStreamingAndHistory() {
        // STREAMING EXCLUSION: when the user is NOT recording, the selected tracker is just
        // another tracker in the roster. ALL_QUEUE should subscribe to it and load its history
        // alongside the rest. The selected exclusion only applies while tracking, where the
        // local GPS feed is already the source of truth for our own recording.
        val plan = project(
            mode = TrackerMapDisplayMode.ALL_QUEUE,
            selectedTrackerId = "selected",
            displayedTrackerId = "",
            runtimeRunning = false,
            rosterTrackerIds = setOf("selected", "remote"),
        )

        assertEquals(setOf("selected", "remote"), plan.remoteSubscriptionIds)
        assertEquals(setOf("selected", "remote"), plan.trailReloadPlan.trackerIds)
    }

    @Test
    fun allQueueWhileTracking_excludesLocallyRecordedFromStreaming() {
        // STREAMING EXCLUSION (recording): once the user is recording, locallyRecordedTrackerId
        // (== selectedTrackerId in the typical case) is dropped from streaming because the live
        // GPS feed is already the local source of truth.
        val plan = project(
            mode = TrackerMapDisplayMode.ALL_QUEUE,
            selectedTrackerId = "selected",
            displayedTrackerId = "",
            runtimeRunning = true,
            rosterTrackerIds = setOf("selected", "remote"),
        )

        assertEquals(setOf("remote"), plan.remoteSubscriptionIds)
        assertEquals(setOf("selected"), plan.locallyRecordedTrackerIds)
    }

    @Test
    fun singleSessionDisplayedRemote_targetsDisplayedWhenDifferentFromLocal() {
        val plan = project(
            mode = TrackerMapDisplayMode.SINGLE_SESSION,
            selectedTrackerId = "local",
            displayedTrackerId = "remote",
            runtimeRunning = true,
            rosterTrackerIds = setOf("ignored"),
        )

        assertEquals(setOf("remote"), plan.remoteSubscriptionIds)
        assertEquals("", plan.resolvedGroupId)
    }

    private fun project(
        mode: TrackerMapDisplayMode,
        selectedTrackerId: String,
        recordingTrackerId: String = selectedTrackerId,
        displayedTrackerId: String,
        runtimeRunning: Boolean,
        rosterTrackerIds: Set<String> = emptySet(),
        groupTrackerIds: Set<String> = emptySet(),
        groupId: String = "g1",
    ): TrackerMapStreamingPlan {
        return TrackerMapSessionProjector.project(
            TrackerMapSessionIntent(
                mode = mode,
                runtime = TrackingRuntimeSnapshot(
                    isRunning = runtimeRunning,
                    recordingRuntime = RecordingRuntime(
                        sessionActive = runtimeRunning,
                        selectedTrackerId = recordingTrackerId,
                    ),
                    selectedTrackerId = selectedTrackerId,
                ),
                displayedTrackerId = displayedTrackerId,
                displayedTrackerName = "",
                rosterTrackerIds = rosterTrackerIds,
                groupSelection = TrackerMapGroupModeSelection(groupId = groupId, trackerIds = groupTrackerIds),
                activeStreamedTrackerIds = emptySet(),
            )
        )
    }
}
