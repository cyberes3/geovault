package com.geovault.tracker.presentation

import android.content.Context
import com.geovault.tracker.MapStreamingServiceHelper
import com.geovault.tracker.MapStreamingStartResult
import com.geovault.tracker.MapStreamingStopResult
import com.geovault.tracker.policy.StreamingTargetPolicy
import com.geovault.tracker.policy.StreamingTargetPolicyInput
import com.geovault.tracker.services.LiveStreamRuntimeStateStore
import com.geovault.tracker.services.StreamingHealth
import com.geovault.tracker.services.StreamingIntent

internal enum class LiveTrackStreamingOwner {
    Map,
    Params,
}

internal data class LiveTrackStreamingTargetRequest(
    val trackerIds: Set<String>,
    val trackerName: String?,
    val locallyRecordedTrackerId: String?,
)

internal data class StreamingLeaseSet(
    val mapRequest: LiveTrackStreamingTargetRequest?,
    val paramsRequest: LiveTrackStreamingTargetRequest?,
)

internal data class StreamingSubscriptionPlan(
    val trackerIds: Set<String>,
    val trackerName: String?,
) {
    val shouldRunService: Boolean
        get() = trackerIds.isNotEmpty()
}

internal sealed class StreamingSubscriptionApplyResult {
    data object Applied : StreamingSubscriptionApplyResult()
    data class Failed(val reason: String) : StreamingSubscriptionApplyResult()
}

internal interface LiveTrackStreamingServiceGateway {
    fun startStreaming(context: Context, trackerIds: Set<String>, trackerName: String?): MapStreamingStartResult
    fun stopStreaming(context: Context): MapStreamingStopResult
}

/**
 * Merges independent streaming intents from Map and Tracker Params into one foreground
 * subscription: `trackerIds` from each owner are unioned after trimming, then the union of all
 * `locallyRecordedTrackerId`s is stripped so the actively-recorded tracker (whose live GPS feed
 * is the local source of truth) never round-trips through the websocket. Either owner may
 * replace its half with `null`, dropping it from the merge. The notification name is retained
 * only when the merged set has exactly one id.
 *
 * Apply-layer dedupe ([lastAppliedIds]) avoids duplicate `ACTION_START`; [resetApplyGate] must
 * be invoked when reconciliation decides the same ids must be pushed again after a transient
 * service failure (the failure paths inside this coordinator do this automatically).
 */
internal object LiveTrackStreamingTargetCoordinator {
    private var mapRequest: LiveTrackStreamingTargetRequest? = null
    private var paramsRequest: LiveTrackStreamingTargetRequest? = null
    private var lastAppliedIds: Set<String> = emptySet()
    private var lastAppliedName: String? = null
    private var hasApplied = false
    private var serviceGateway: LiveTrackStreamingServiceGateway = DefaultLiveTrackStreamingServiceGateway

    /** Clears the last-applied fingerprint so the next [replaceRequest]/[reconcile] issues start/stop to the helper again if needed. */
    @Synchronized
    fun resetApplyGate() {
        hasApplied = false
        lastAppliedIds = emptySet()
        lastAppliedName = null
    }

    @Synchronized
    internal fun resetForTests(serviceGateway: LiveTrackStreamingServiceGateway? = null) {
        mapRequest = null
        paramsRequest = null
        resetApplyGate()
        this.serviceGateway = serviceGateway ?: DefaultLiveTrackStreamingServiceGateway
    }

    @Synchronized
    fun replaceRequest(
        context: Context,
        owner: LiveTrackStreamingOwner,
        request: LiveTrackStreamingTargetRequest?,
    ): StreamingSubscriptionApplyResult {
        when (owner) {
            LiveTrackStreamingOwner.Map -> mapRequest = request
            LiveTrackStreamingOwner.Params -> paramsRequest = request
        }
        return reconcile(context)
    }

    @Synchronized
    fun clearAll(context: Context): StreamingSubscriptionApplyResult {
        mapRequest = null
        paramsRequest = null
        resetApplyGate()
        return apply(context, emptySet(), null)
    }

    @Synchronized
    fun clearInMemoryRequests() {
        mapRequest = null
        paramsRequest = null
        resetApplyGate()
    }

    private fun reconcile(context: Context): StreamingSubscriptionApplyResult {
        val plan = resolveSubscriptionPlan(
            StreamingLeaseSet(
                mapRequest = mapRequest,
                paramsRequest = paramsRequest,
            )
        )
        return apply(context, plan.trackerIds, plan.trackerName)
    }

    internal fun resolveSubscriptionPlan(leases: StreamingLeaseSet): StreamingSubscriptionPlan {
        val requests = listOfNotNull(leases.mapRequest, leases.paramsRequest)
        val locallyRecordedIds = requests.mapNotNull { it.locallyRecordedTrackerId }
        val trackerIds = StreamingTargetPolicy.remoteSubscriptionTargets(
            StreamingTargetPolicyInput(
                requestedTrackerIds = requests.flatMap { it.trackerIds },
                locallyRecordedTrackerIds = locallyRecordedIds,
            )
        )
        val trackerName = if (trackerIds.size == 1) {
            requests.firstNotNullOfOrNull { request ->
                request.trackerName?.trim()?.ifBlank { null }
            }
        } else {
            null
        }
        return StreamingSubscriptionPlan(
            trackerIds = trackerIds,
            trackerName = trackerName,
        )
    }

    private fun apply(
        context: Context,
        trackerIds: Set<String>,
        trackerName: String?,
    ): StreamingSubscriptionApplyResult {
        if (hasApplied && trackerIds == lastAppliedIds && trackerName == lastAppliedName) {
            return StreamingSubscriptionApplyResult.Applied
        }
        if (trackerIds.isEmpty()) {
            return applyStop(context, trackerIds, trackerName)
        } else {
            return when (val result = serviceGateway.startStreaming(
                context = context,
                trackerIds = trackerIds,
                trackerName = trackerName,
            )) {
                is MapStreamingStartResult.Started -> {
                    hasApplied = true
                    lastAppliedIds = result.trackerIds
                    lastAppliedName = trackerName
                    StreamingSubscriptionApplyResult.Applied
                }
                is MapStreamingStartResult.Failed -> {
                    val stopResult = serviceGateway.stopStreaming(context)
                    resetApplyGate()
                    val failureReason = when (stopResult) {
                        MapStreamingStopResult.Stopped -> result.reason
                        is MapStreamingStopResult.Failed -> "${result.reason}; stop_failed:${stopResult.reason}"
                    }
                    // STREAM-STATE-MACHINE: when the start is rejected we treat the failure as
                    // transient by default; the caller drove an explicit start so the user/app
                    // intent stays Wanted unless the bundled cleanup-stop succeeded, in which case
                    // we collapse to Idle/Stopped to keep the snapshot consistent with the empty
                    // active set.
                    val stoppedCleanly = stopResult == MapStreamingStopResult.Stopped
                    LiveStreamRuntimeStateStore.update { previous ->
                        previous.copy(
                            intent = if (stoppedCleanly) StreamingIntent.Idle else previous.intent,
                            health = if (stoppedCleanly) StreamingHealth.Stopped else StreamingHealth.FailedTransient,
                            activeTrackerIds = if (stoppedCleanly) emptySet() else previous.activeTrackerIds,
                            failureReason = failureReason,
                        )
                    }
                    StreamingSubscriptionApplyResult.Failed(failureReason)
                }
            }
        }
    }

    private fun applyStop(
        context: Context,
        trackerIds: Set<String>,
        trackerName: String?,
    ): StreamingSubscriptionApplyResult {
        return when (val stopResult = serviceGateway.stopStreaming(context)) {
            MapStreamingStopResult.Stopped -> {
                hasApplied = true
                lastAppliedIds = trackerIds
                lastAppliedName = trackerName
                StreamingSubscriptionApplyResult.Applied
            }
            is MapStreamingStopResult.Failed -> {
                resetApplyGate()
                // STREAM-STATE-MACHINE: stop failed -> the user/app wanted to stop but the
                // service didn't acknowledge. Surface that as a transient failure with intent
                // Idle (we are no longer trying to subscribe) so reconcile can attempt cleanup
                // again on the next tick without resurrecting the old target set.
                LiveStreamRuntimeStateStore.update { previous ->
                    previous.copy(
                        intent = StreamingIntent.Idle,
                        health = StreamingHealth.FailedTransient,
                        failureReason = stopResult.reason,
                    )
                }
                StreamingSubscriptionApplyResult.Failed(stopResult.reason)
            }
        }
    }

    private object DefaultLiveTrackStreamingServiceGateway : LiveTrackStreamingServiceGateway {
        override fun startStreaming(
            context: Context,
            trackerIds: Set<String>,
            trackerName: String?
        ): MapStreamingStartResult {
            return MapStreamingServiceHelper.startStreaming(context, trackerIds, trackerName)
        }

        override fun stopStreaming(context: Context): MapStreamingStopResult {
            return MapStreamingServiceHelper.stopStreaming(context)
        }
    }
}
