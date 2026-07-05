package com.geovault.tracker.streaming

import android.content.Context
import com.geovault.tracker.MapStreamingServiceHelper
import com.geovault.tracker.MapStreamingStartResult
import com.geovault.tracker.MapStreamingStopResult

/**
 * Thin seam between [LiveStreamSubscriptionRepository] and the real Android service surface
 * ([MapStreamingServiceHelper] + its SharedPreferences-backed persisted-target read), so the
 * repository's dispatch/bootstrap logic can be unit-tested without a Robolectric service.
 */
internal interface LiveStreamServicePort {
    fun startStreaming(context: Context, trackerIds: Set<String>, trackerName: String?): MapStreamingStartResult
    fun stopStreaming(context: Context): MapStreamingStopResult

    /** Targets the service persisted from its last successful start, surviving process death. */
    fun persistedTargets(context: Context): Pair<Set<String>, String?>
}

internal object DefaultLiveStreamServicePort : LiveStreamServicePort {
    override fun startStreaming(context: Context, trackerIds: Set<String>, trackerName: String?): MapStreamingStartResult {
        return MapStreamingServiceHelper.startStreaming(context, trackerIds, trackerName)
    }

    override fun stopStreaming(context: Context): MapStreamingStopResult {
        return MapStreamingServiceHelper.stopStreaming(context)
    }

    override fun persistedTargets(context: Context): Pair<Set<String>, String?> {
        return MapStreamingServiceHelper.persistedTargets(context)
    }
}
