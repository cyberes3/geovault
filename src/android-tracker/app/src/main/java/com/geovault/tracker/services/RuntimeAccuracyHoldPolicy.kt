package com.geovault.tracker.services

/**
 * Decides what `lastAccuracyMeters` should appear in the runtime snapshot for a newly arrived fix.
 *
 * GPS hardware briefly emits a few high-uncertainty fixes when coming out of doze / screen-off.
 * Surfacing those raw values to the UI causes the accuracy indicator and home readout to flash
 * red even though the recording filter rejects them. The hold policy: if a *good* fix was seen
 * within [ACCURACY_HOLD_GRACE_MS], a subsequent bad fix is held back and the last good value is
 * kept on display. After the grace expires the raw value is surfaced so genuine degradations
 * still reach the user.
 *
 * The grace window intentionally spans multiple normal tracking intervals. On the biking/driving
 * profiles we commonly receive fixes about every 20 seconds, so a shorter hold can expire between
 * healthy fixes and fail to cover the next screen-wake blip.
 *
 * The policy only decides what to display; raw `location.accuracy` is still passed unchanged into
 * the location ingest / filter pipeline.
 */
object RuntimeAccuracyHoldPolicy {
    const val ACCURACY_HOLD_GRACE_MS = 60_000L

    data class Result(
        val displayedAccuracyMeters: Float?,
        val lastGoodAccuracyMeters: Float?,
        val lastGoodAccuracyAtElapsedMs: Long,
        val heldLastGoodAccuracy: Boolean,
    )

    @JvmStatic
    fun next(
        previous: TrackingRuntimeSnapshot,
        incomingAccuracyMeters: Float?,
        effectiveAccuracyThresholdMeters: Float,
        nowElapsedMs: Long,
    ): Result {
        val isGood = incomingAccuracyMeters != null &&
            !incomingAccuracyMeters.isNaN() &&
            incomingAccuracyMeters <= effectiveAccuracyThresholdMeters
        if (isGood) {
            return Result(
                displayedAccuracyMeters = incomingAccuracyMeters,
                lastGoodAccuracyMeters = incomingAccuracyMeters,
                lastGoodAccuracyAtElapsedMs = nowElapsedMs,
                heldLastGoodAccuracy = false,
            )
        }
        val lastGoodAccuracy = previous.lastGoodAccuracyMeters
        val lastGoodAt = previous.lastGoodAccuracyAtElapsedMs
        val withinGrace = lastGoodAccuracy != null &&
            lastGoodAt > 0L &&
            nowElapsedMs - lastGoodAt <= ACCURACY_HOLD_GRACE_MS
        val displayed = if (withinGrace) lastGoodAccuracy else incomingAccuracyMeters
        return Result(
            displayedAccuracyMeters = displayed,
            lastGoodAccuracyMeters = lastGoodAccuracy,
            lastGoodAccuracyAtElapsedMs = lastGoodAt,
            heldLastGoodAccuracy = withinGrace,
        )
    }
}
