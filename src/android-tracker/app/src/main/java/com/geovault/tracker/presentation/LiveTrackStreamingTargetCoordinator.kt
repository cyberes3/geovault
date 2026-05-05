package com.geovault.tracker.presentation

import android.content.Context
import com.geovault.tracker.MapStreamingServiceHelper
import com.geovault.tracker.MapStreamingStartResult
import com.geovault.tracker.MapStreamingStopResult
import com.geovault.tracker.location.TrackingLifecycleState
import com.geovault.tracker.services.LiveStreamRuntimeStateStore

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
 * Merges independent streaming intents from Map and Tracker Params into one foreground subscription:
 * `trackerIds` from each owner are unioned after trimming; any id marked as [LiveTrackStreamingTargetRequest.locallyRecordedTrackerId]
 * (device is recording that tracker) is stripped so locals never appear on the websocket. Either owner may replace its half with `null`,
 * dropping it from merge. Naming for the notification is retained only when the merged set has exactly one id.
 *
 * Apply-layer dedupe ([lastAppliedIds]) avoids duplicate `ACTION_START`; [resetApplyGate] must be invoked when reconciliation decides the
 * same ids must be pushed again after a transient service failure ([LiveTrackStreamingReconciler.invalidateDedupe]).
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
        val locallyRecordedIds = requests
            .mapNotNull { it.locallyRecordedTrackerId?.trim()?.takeIf(String::isNotEmpty) }
            .toSet()
        val trackerIds = requests
            .flatMap { it.trackerIds }
            .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            .filterNot { it in locallyRecordedIds }
            .toSet()
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
                    LiveStreamRuntimeStateStore.update {
                        val stopped = stopResult == MapStreamingStopResult.Stopped
                        it.copy(
                            isRunning = if (stopped) false else it.isRunning,
                            lifecycleState = TrackingLifecycleState.FAILED,
                            activeTrackerIds = if (stopped) emptySet() else it.activeTrackerIds,
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
                LiveStreamRuntimeStateStore.update {
                    it.copy(
                        lifecycleState = TrackingLifecycleState.FAILED,
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
