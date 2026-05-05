package com.geovault.tracker.presentation

import android.content.Context
import com.geovault.tracker.MapStreamingServiceHelper

internal enum class LiveTrackStreamingOwner {
    Map,
    Params,
}

internal data class LiveTrackStreamingTargetRequest(
    val trackerIds: Set<String>,
    val trackerName: String?,
    val locallyRecordedTrackerId: String?,
)

internal object LiveTrackStreamingTargetCoordinator {
    private var mapRequest: LiveTrackStreamingTargetRequest? = null
    private var paramsRequest: LiveTrackStreamingTargetRequest? = null
    private var lastAppliedIds: Set<String> = emptySet()
    private var lastAppliedName: String? = null
    private var hasApplied = false

    @Synchronized
    fun replaceRequest(
        context: Context,
        owner: LiveTrackStreamingOwner,
        request: LiveTrackStreamingTargetRequest?,
    ) {
        when (owner) {
            LiveTrackStreamingOwner.Map -> mapRequest = request
            LiveTrackStreamingOwner.Params -> paramsRequest = request
        }
        reconcile(context)
    }

    @Synchronized
    fun clearAll(context: Context) {
        mapRequest = null
        paramsRequest = null
        apply(context, emptySet(), null)
    }

    private fun reconcile(context: Context) {
        val requests = listOfNotNull(mapRequest, paramsRequest)
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
        apply(context, trackerIds, trackerName)
    }

    private fun apply(context: Context, trackerIds: Set<String>, trackerName: String?) {
        if (hasApplied && trackerIds == lastAppliedIds && trackerName == lastAppliedName) return
        hasApplied = true
        lastAppliedIds = trackerIds
        lastAppliedName = trackerName
        if (trackerIds.isEmpty()) {
            MapStreamingServiceHelper.stopStreaming(context)
        } else {
            MapStreamingServiceHelper.startStreaming(
                context = context,
                trackerIds = trackerIds,
                trackerName = trackerName,
            )
        }
    }
}
