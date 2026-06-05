package com.geovault.tracker.aar

import com.geovault.tracker.sensor.ActivityHint

/**
 * Holds the expiry timestamp for the active AAR motion hint.
 *
 * [hintUntilMs] is `@Volatile` because [ActivityRecognitionHintBridge.onTransition] writes it
 * on the main thread (receiver delivery) while [currentHint] is read on Dispatchers.Default
 * (ingest scope) and the main thread (motion tick).
 */
internal class ActivityRecognitionHintStore {

    @Volatile
    private var hintUntilMs: Long = 0L

    fun setHint(untilMs: Long) {
        hintUntilMs = untilMs
    }

    fun clear() {
        hintUntilMs = 0L
    }

    fun currentHint(nowMs: Long): ActivityHint? {
        return if (hintUntilMs > 0L && nowMs < hintUntilMs) ActivityHint else null
    }
}
