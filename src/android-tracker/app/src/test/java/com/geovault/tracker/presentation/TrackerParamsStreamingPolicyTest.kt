package com.geovault.tracker.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackerParamsStreamingPolicyTest {

    @Test
    fun resolveStart_localActiveTracker_noops() {
        val resolution = TrackerParamsStreamingPolicy.resolveStart(
            startInput(
                trackerId = "self",
                selectedTrackerId = "self",
                trackingRunning = true,
            )
        )

        assertEquals(TrackerParamsStreamingCommand.NoOp, resolution.command)
        assertEquals(TrackerParamsStreamingOwnership.NoOp, resolution.session?.ownership)
    }

    @Test
    fun resolveStart_idleRemoteTracker_startsParamsStream() {
        val resolution = TrackerParamsStreamingPolicy.resolveStart(
            startInput(trackerId = "remote")
        )

        val command = resolution.command as TrackerParamsStreamingCommand.Start
        assertEquals(setOf("remote"), command.trackerIds)
        assertEquals("Remote", command.trackerName)
        assertEquals(TrackerParamsStreamingOwnership.StartedFromIdle, resolution.session?.ownership)
    }

    @Test
    fun resolveStop_startedFromIdle_stopsWhenParamsStillOwnsStream() {
        val session = TrackerParamsStreamingSession(
            trackerId = "remote",
            requestedTrackerIds = setOf("remote"),
            baselineTrackerIds = emptySet(),
            ownership = TrackerParamsStreamingOwnership.StartedFromIdle,
        )

        val command = TrackerParamsStreamingPolicy.resolveStop(
            TrackerParamsStreamingStopInput(
                session = session,
                liveStreamRunning = true,
                activeTrackerIds = setOf("remote"),
            )
        )

        assertEquals(TrackerParamsStreamingCommand.Stop, command)
    }

    @Test
    fun resolveStart_alreadyStreamingTracker_takesParamsOwnership() {
        val resolution = TrackerParamsStreamingPolicy.resolveStart(
            startInput(
                trackerId = "remote",
                liveStreamRunning = true,
                activeTrackerIds = setOf("remote"),
            )
        )

        val command = resolution.command as TrackerParamsStreamingCommand.Start
        assertEquals(setOf("remote"), command.trackerIds)
        assertEquals(TrackerParamsStreamingOwnership.StartedFromIdle, resolution.session?.ownership)
        val stopCommand = TrackerParamsStreamingPolicy.resolveStop(
            TrackerParamsStreamingStopInput(
                session = requireNotNull(resolution.session),
                liveStreamRunning = true,
                activeTrackerIds = setOf("remote"),
            )
        )
        assertEquals(TrackerParamsStreamingCommand.Stop, stopCommand)
    }

    @Test
    fun resolveStart_existingDifferentStream_startsOnlyParamsTarget() {
        val resolution = TrackerParamsStreamingPolicy.resolveStart(
            startInput(
                trackerId = "params",
                liveStreamRunning = true,
                activeTrackerIds = setOf("map"),
            )
        )

        val command = resolution.command as TrackerParamsStreamingCommand.Start
        assertEquals(setOf("params"), command.trackerIds)
        assertEquals(TrackerParamsStreamingOwnership.StartedFromIdle, resolution.session?.ownership)
    }

    @Test
    fun resolveStop_expandedExistingStream_clearsParamsRequest() {
        val session = TrackerParamsStreamingSession(
            trackerId = "params",
            requestedTrackerIds = setOf("map", "params"),
            baselineTrackerIds = setOf("map"),
            ownership = TrackerParamsStreamingOwnership.ExpandedExistingStream,
        )

        val command = TrackerParamsStreamingPolicy.resolveStop(
            TrackerParamsStreamingStopInput(
                session = session,
                liveStreamRunning = true,
                activeTrackerIds = setOf("map", "params"),
            )
        )

        assertEquals(TrackerParamsStreamingCommand.Stop, command)
    }

    @Test
    fun resolveStop_externalStreamChange_stillClearsParamsRequest() {
        val session = TrackerParamsStreamingSession(
            trackerId = "params",
            requestedTrackerIds = setOf("map", "params"),
            baselineTrackerIds = setOf("map"),
            ownership = TrackerParamsStreamingOwnership.ExpandedExistingStream,
        )

        val command = TrackerParamsStreamingPolicy.resolveStop(
            TrackerParamsStreamingStopInput(
                session = session,
                liveStreamRunning = true,
                activeTrackerIds = setOf("other"),
            )
        )

        assertEquals(TrackerParamsStreamingCommand.Stop, command)
    }

    private fun startInput(
        trackerId: String,
        selectedTrackerId: String = "",
        trackingRunning: Boolean = false,
        liveStreamRunning: Boolean = false,
        activeTrackerIds: Set<String> = emptySet(),
    ): TrackerParamsStreamingStartInput {
        return TrackerParamsStreamingStartInput(
            trackerId = trackerId,
            trackerName = "Remote",
            selectedTrackerId = selectedTrackerId,
            trackingRunning = trackingRunning,
            liveStreamRunning = liveStreamRunning,
            activeTrackerIds = activeTrackerIds,
        )
    }
}
