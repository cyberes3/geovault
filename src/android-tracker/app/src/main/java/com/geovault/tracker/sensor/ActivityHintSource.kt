package com.geovault.tracker.sensor

import android.content.Context

/**
 * The single channel between the self-contained AAR package and the positioning stack.
 *
 * The positioning stack depends only on this interface; all GMS activity-recognition
 * internals, expiry logic, and policy decisions are hidden behind it.
 */
internal interface ActivityHintSource {

    /**
     * Returns [ActivityHint] when a moving-activity transition is live and not expired,
     * null otherwise (no transition received, hint expired, permission denied, GMS error).
     */
    fun currentHint(nowMs: Long): ActivityHint?

    /**
     * Registers GMS activity transition updates. Called after a tracking session starts.
     * A no-op if the [android.Manifest.permission.ACTIVITY_RECOGNITION] permission is
     * missing or GMS registration fails.
     */
    fun start(context: Context, trackId: String, trackingGeneration: Int)

    /**
     * Deregisters GMS updates and clears any active hint. Safe to call multiple times.
     */
    fun stop()
}
