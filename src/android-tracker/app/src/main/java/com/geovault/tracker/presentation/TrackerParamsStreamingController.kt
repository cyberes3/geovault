package com.geovault.tracker.presentation

import android.content.Context
import com.geovault.tracker.MapStreamingServiceHelper
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

        val baselineIds = if (input.liveStreamRunning) {
            cleanIds(input.activeTrackerIds)
        } else {
            emptySet()
        }
        if (trackerId in baselineIds) {
            return TrackerParamsStreamingResolution(
                session = TrackerParamsStreamingSession(
                    trackerId = trackerId,
                    requestedTrackerIds = baselineIds,
                    baselineTrackerIds = baselineIds,
                    ownership = TrackerParamsStreamingOwnership.AlreadyActive,
                ),
                command = TrackerParamsStreamingCommand.NoOp,
            )
        }

        val requestedIds = baselineIds + trackerId
        val ownership = if (baselineIds.isEmpty()) {
            TrackerParamsStreamingOwnership.StartedFromIdle
        } else {
            TrackerParamsStreamingOwnership.ExpandedExistingStream
        }
        return TrackerParamsStreamingResolution(
            session = TrackerParamsStreamingSession(
                trackerId = trackerId,
                requestedTrackerIds = requestedIds,
                baselineTrackerIds = baselineIds,
                ownership = ownership,
            ),
            command = TrackerParamsStreamingCommand.Start(
                trackerIds = requestedIds,
                trackerName = input.trackerName?.trim()?.ifBlank { null },
            ),
        )
    }

    fun resolveStop(input: TrackerParamsStreamingStopInput): TrackerParamsStreamingCommand {
        val currentIds = if (input.liveStreamRunning) {
            cleanIds(input.activeTrackerIds)
        } else {
            emptySet()
        }
        return when (input.session.ownership) {
            TrackerParamsStreamingOwnership.NoOp,
            TrackerParamsStreamingOwnership.AlreadyActive -> TrackerParamsStreamingCommand.NoOp

            TrackerParamsStreamingOwnership.StartedFromIdle -> {
                if (currentIds.isEmpty() || currentIds == input.session.requestedTrackerIds) {
                    TrackerParamsStreamingCommand.Stop
                } else {
                    TrackerParamsStreamingCommand.NoOp
                }
            }

            TrackerParamsStreamingOwnership.ExpandedExistingStream -> {
                if (currentIds != input.session.requestedTrackerIds) {
                    TrackerParamsStreamingCommand.NoOp
                } else if (input.session.baselineTrackerIds.isEmpty()) {
                    TrackerParamsStreamingCommand.Stop
                } else {
                    TrackerParamsStreamingCommand.Start(
                        trackerIds = input.session.baselineTrackerIds,
                        trackerName = null,
                    )
                }
            }
        }
    }

    private fun cleanIds(ids: Set<String>): Set<String> {
        return ids.mapNotNull { it.trim().takeIf(String::isNotEmpty) }.toSet()
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
        execute(resolution.command)
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
        execute(command)
    }

    private fun execute(command: TrackerParamsStreamingCommand) {
        when (command) {
            is TrackerParamsStreamingCommand.Start -> {
                MapStreamingServiceHelper.startStreaming(
                    context = appContext,
                    trackerIds = command.trackerIds,
                    trackerName = command.trackerName,
                )
            }
            TrackerParamsStreamingCommand.Stop -> {
                MapStreamingServiceHelper.stopStreaming(appContext)
            }
            TrackerParamsStreamingCommand.NoOp -> Unit
        }
    }
}
