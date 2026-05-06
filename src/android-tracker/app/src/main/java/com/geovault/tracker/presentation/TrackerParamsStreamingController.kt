package com.geovault.tracker.presentation

import android.content.Context
import com.geovault.tracker.policy.StreamingTargetPolicy
import com.geovault.tracker.policy.StreamingTargetPolicyInput
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
    val trackerName: String? = null,
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

internal interface TrackerParamsStreamingLeaseSink {
    fun resetApplyGate()

    fun replaceParamsRequest(context: Context, request: LiveTrackStreamingTargetRequest?)
}

internal object LiveTrackStreamingParamsLeaseSink : TrackerParamsStreamingLeaseSink {
    override fun resetApplyGate() {
        LiveTrackStreamingTargetCoordinator.resetApplyGate()
    }

    override fun replaceParamsRequest(context: Context, request: LiveTrackStreamingTargetRequest?) {
        LiveTrackStreamingTargetCoordinator.replaceRequest(
            context = context,
            owner = LiveTrackStreamingOwner.Params,
            request = request,
        )
    }
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
        if (selectedTrackerId.isNotEmpty() && trackerId == selectedTrackerId) {
            return TrackerParamsStreamingResolution(
                session = TrackerParamsStreamingSession(
                    trackerId = trackerId,
                    trackerName = input.trackerName?.trim()?.ifBlank { null },
                    requestedTrackerIds = emptySet(),
                    baselineTrackerIds = emptySet(),
                    ownership = TrackerParamsStreamingOwnership.NoOp,
                ),
                command = TrackerParamsStreamingCommand.NoOp,
            )
        }
        val activeTrackerIds = input.activeTrackerIds
            .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            .toSet()
        val remoteActiveTrackerIds = StreamingTargetPolicy.remoteSubscriptionTargets(
            StreamingTargetPolicyInput(
                requestedTrackerIds = activeTrackerIds,
                selectedTrackerId = selectedTrackerId,
            )
        )
        if (input.liveStreamRunning && trackerId in remoteActiveTrackerIds) {
            return TrackerParamsStreamingResolution(
                session = TrackerParamsStreamingSession(
                    trackerId = trackerId,
                    trackerName = input.trackerName?.trim()?.ifBlank { null },
                    requestedTrackerIds = emptySet(),
                    baselineTrackerIds = remoteActiveTrackerIds,
                    ownership = TrackerParamsStreamingOwnership.AlreadyActive,
                ),
                command = TrackerParamsStreamingCommand.NoOp,
            )
        }

        return TrackerParamsStreamingResolution(
            session = TrackerParamsStreamingSession(
                trackerId = trackerId,
                trackerName = input.trackerName?.trim()?.ifBlank { null },
                requestedTrackerIds = setOf(trackerId),
                baselineTrackerIds = remoteActiveTrackerIds,
                ownership = if (input.liveStreamRunning && remoteActiveTrackerIds.isNotEmpty()) {
                    TrackerParamsStreamingOwnership.ExpandedExistingStream
                } else {
                    TrackerParamsStreamingOwnership.StartedFromIdle
                },
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

internal class TrackerParamsStreamingController(
    private val appContext: Context,
    private val streamState: StateFlow<LiveStreamRuntimeSnapshot> = LiveStreamRuntimeStateStore.state,
    private val leaseSink: TrackerParamsStreamingLeaseSink = LiveTrackStreamingParamsLeaseSink,
) {
    private var session: TrackerParamsStreamingSession? = null
    private var sessionKey: String? = null
    private var streamFingerprint: String? = null

    fun onScreenStarted(
        trackerId: String,
        trackerName: String?,
        selectedTrackerId: String,
        trackingRunning: Boolean,
        streamSnapshot: LiveStreamRuntimeSnapshot,
    ) {
        val nextSessionKey = "${trackerId.trim()}|${selectedTrackerId.trim()}|$trackingRunning"
        val nextStreamFingerprint = streamFingerprint(streamSnapshot)
        if (sessionKey == nextSessionKey) {
            if (streamFingerprint != nextStreamFingerprint) {
                streamFingerprint = nextStreamFingerprint
                reapplyCurrentRequest(selectedTrackerId, trackingRunning)
            }
            return
        }
        onScreenStopped()

        val resolution = TrackerParamsStreamingPolicy.resolveStart(
            TrackerParamsStreamingStartInput(
                trackerId = trackerId,
                trackerName = trackerName,
                selectedTrackerId = selectedTrackerId,
                trackingRunning = trackingRunning,
                liveStreamRunning = streamSnapshot.hasSubscriptionIntent,
                activeTrackerIds = streamSnapshot.activeTrackerIds,
            )
        )
        session = resolution.session
        sessionKey = nextSessionKey
        streamFingerprint = nextStreamFingerprint
        replaceRequest(resolution.command, selectedTrackerId, trackingRunning)
    }

    fun onScreenStopped() {
        val activeSession = session
        session = null
        sessionKey = null
        streamFingerprint = null
        if (activeSession == null) return
        val snapshot = streamState.value
        val command = TrackerParamsStreamingPolicy.resolveStop(
            TrackerParamsStreamingStopInput(
                session = activeSession,
                liveStreamRunning = snapshot.hasSubscriptionIntent,
                activeTrackerIds = snapshot.activeTrackerIds,
            )
        )
        replaceRequest(command, activeSession.trackerId, false)
    }

    private fun reapplyCurrentRequest(selectedTrackerId: String, trackingRunning: Boolean) {
        val activeSession = session ?: return
        if (activeSession.requestedTrackerIds.isEmpty()) return
        leaseSink.resetApplyGate()
        leaseSink.replaceParamsRequest(
            context = appContext,
            request = LiveTrackStreamingTargetRequest(
                trackerIds = activeSession.requestedTrackerIds,
                trackerName = activeSession.trackerName,
                locallyRecordedTrackerId = selectedTrackerId.takeIf { trackingRunning },
                excludedTrackerIds = StreamingTargetPolicy.selectedTrackerExclusion(selectedTrackerId),
            ),
        )
    }

    private fun streamFingerprint(snapshot: LiveStreamRuntimeSnapshot): String {
        return "${snapshot.hasSubscriptionIntent}|${snapshot.lifecycleState.name}|" +
            snapshot.activeTrackerIds
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .sorted()
                .joinToString(",")
    }

    private fun replaceRequest(
        command: TrackerParamsStreamingCommand,
        selectedTrackerId: String,
        trackingRunning: Boolean,
    ) {
        when (command) {
            is TrackerParamsStreamingCommand.Start -> {
                val locallyRecordedTrackerId = selectedTrackerId.takeIf { trackingRunning }
                leaseSink.replaceParamsRequest(
                    context = appContext,
                    request = LiveTrackStreamingTargetRequest(
                        trackerIds = command.trackerIds,
                        trackerName = command.trackerName,
                        locallyRecordedTrackerId = locallyRecordedTrackerId,
                        excludedTrackerIds = StreamingTargetPolicy.selectedTrackerExclusion(selectedTrackerId),
                    ),
                )
            }
            TrackerParamsStreamingCommand.Stop -> {
                leaseSink.replaceParamsRequest(
                    context = appContext,
                    request = null,
                )
            }
            TrackerParamsStreamingCommand.NoOp -> Unit
        }
    }
}
