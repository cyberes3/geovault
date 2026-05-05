package com.geovault.tracker.presentation

import android.content.Context
import com.geovault.tracker.services.LiveStreamRuntimeSnapshot
import com.geovault.tracker.services.LiveStreamRuntimeStateStore
import kotlinx.coroutines.flow.StateFlow

data class TrackerParamsStreamingStartInput(
    val trackerId: String,
    val trackerName: String?,
    val selectedTrackerId: String,
    val trackingRunning: Boolean,
    val liveStreamRunning: Boolean,
    val activeTrackerIds: Set<String>,
)

data class TrackerParamsStreamingStopInput(
    val session: TrackerParamsStreamingSession,
    val liveStreamRunning: Boolean,
    val activeTrackerIds: Set<String>,
)

data class TrackerParamsStreamingResolution(
    val session: TrackerParamsStreamingSession?,
    val command: TrackerParamsStreamingCommand,
)

data class TrackerParamsStreamingSession(
    val trackerId: String,
    val requestedTrackerIds: Set<String>,
    val baselineTrackerIds: Set<String>,
    val ownership: TrackerParamsStreamingOwnership,
)

enum class TrackerParamsStreamingOwnership {
    NoOp,
    AlreadyActive,
    StartedFromIdle,
    ExpandedExistingStream,
}

sealed class TrackerParamsStreamingCommand {
    data class Start(val trackerIds: Set<String>, val trackerName: String?) : TrackerParamsStreamingCommand()
    data object Stop : TrackerParamsStreamingCommand()
    data object NoOp : TrackerParamsStreamingCommand()
}

object TrackerParamsStreamingPolicy {
    fun resolveStart(input: TrackerParamsStreamingStartInput): TrackerParamsStreamingResolution {
        val trackerId = input.trackerId.trim()
        if (trackerId.isEmpty()) {
            return TrackerParamsStreamingResolution(
                session = null,
                command = TrackerParamsStreamingCommand.NoOp,
            )
        }
        val selectedTrackerId = input.selectedTrackerId.trim()
        if (input.trackingRunning && selectedTrackerId.isNotEmpty() && trackerId == selectedTrackerId) {
            return TrackerParamsStreamingResolution(
                session = TrackerParamsStreamingSession(
                    trackerId = trackerId,
                    requestedTrackerIds = emptySet(),
                    baselineTrackerIds = emptySet(),
                    ownership = TrackerParamsStreamingOwnership.NoOp,
                ),
                command = TrackerParamsStreamingCommand.NoOp,
            )
        }

        return TrackerParamsStreamingResolution(
            session = TrackerParamsStreamingSession(
                trackerId = trackerId,
                requestedTrackerIds = setOf(trackerId),
                baselineTrackerIds = emptySet(),
                ownership = TrackerParamsStreamingOwnership.StartedFromIdle,
            ),
            command = TrackerParamsStreamingCommand.Start(
                trackerIds = setOf(trackerId),
                trackerName = input.trackerName?.trim()?.ifBlank { null },
            ),
        )
    }

    fun resolveStop(input: TrackerParamsStreamingStopInput): TrackerParamsStreamingCommand {
        return when (input.session.ownership) {
            TrackerParamsStreamingOwnership.NoOp,
            TrackerParamsStreamingOwnership.AlreadyActive -> TrackerParamsStreamingCommand.NoOp

            TrackerParamsStreamingOwnership.StartedFromIdle,
            TrackerParamsStreamingOwnership.ExpandedExistingStream -> TrackerParamsStreamingCommand.Stop
        }
    }
}

class TrackerParamsStreamingController(
    private val appContext: Context,
    private val streamState: StateFlow<LiveStreamRuntimeSnapshot> = LiveStreamRuntimeStateStore.state,
) {
    private var session: TrackerParamsStreamingSession? = null
    private var sessionKey: String? = null

    fun onScreenStarted(
        trackerId: String,
        trackerName: String?,
        selectedTrackerId: String,
        trackingRunning: Boolean,
    ) {
        val nextSessionKey = "${trackerId.trim()}|${selectedTrackerId.trim()}|$trackingRunning"
        if (sessionKey == nextSessionKey) return
        onScreenStopped()

        val snapshot = streamState.value
        val resolution = TrackerParamsStreamingPolicy.resolveStart(
            TrackerParamsStreamingStartInput(
                trackerId = trackerId,
                trackerName = trackerName,
                selectedTrackerId = selectedTrackerId,
                trackingRunning = trackingRunning,
                liveStreamRunning = snapshot.isRunning,
                activeTrackerIds = snapshot.activeTrackerIds,
            )
        )
        session = resolution.session
        sessionKey = nextSessionKey
        replaceRequest(resolution.command, selectedTrackerId, trackingRunning)
    }

    fun onScreenStopped() {
        val activeSession = session
        session = null
        sessionKey = null
        if (activeSession == null) return
        val snapshot = streamState.value
        val command = TrackerParamsStreamingPolicy.resolveStop(
            TrackerParamsStreamingStopInput(
                session = activeSession,
                liveStreamRunning = snapshot.isRunning,
                activeTrackerIds = snapshot.activeTrackerIds,
            )
        )
        replaceRequest(command, activeSession.trackerId, false)
    }

    private fun replaceRequest(
        command: TrackerParamsStreamingCommand,
        selectedTrackerId: String,
        trackingRunning: Boolean,
    ) {
        when (command) {
            is TrackerParamsStreamingCommand.Start -> {
                val locallyRecordedTrackerId = selectedTrackerId.takeIf { trackingRunning }
                LiveTrackStreamingTargetCoordinator.replaceRequest(
                    context = appContext,
                    owner = LiveTrackStreamingOwner.Params,
                    request = LiveTrackStreamingTargetRequest(
                        trackerIds = command.trackerIds,
                        trackerName = command.trackerName,
                        locallyRecordedTrackerId = locallyRecordedTrackerId,
                    ),
                )
            }
            TrackerParamsStreamingCommand.Stop -> {
                LiveTrackStreamingTargetCoordinator.replaceRequest(
                    context = appContext,
                    owner = LiveTrackStreamingOwner.Params,
                    request = null,
                )
            }
            TrackerParamsStreamingCommand.NoOp -> Unit
        }
    }
}
