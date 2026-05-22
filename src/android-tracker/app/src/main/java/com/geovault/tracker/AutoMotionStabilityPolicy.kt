package com.geovault.tracker

import com.geovault.tracker.policy.TrackPointRejectReason

internal object AutoMotionStabilityPolicy {
    fun shouldSuppressFastLock(
        rejectReason: TrackPointRejectReason?,
        nowMs: Long,
        lastMotionEvidenceAtMs: Long,
        lastModeChangedAtMs: Long,
        windowMs: Long,
    ): Boolean {
        val isTransientReject = rejectReason == TrackPointRejectReason.BAD_ACCURACY ||
            rejectReason == TrackPointRejectReason.STALE
        if (!isTransientReject) return false
        return isWithinWindow(nowMs, lastMotionEvidenceAtMs, windowMs) ||
            isWithinWindow(nowMs, lastModeChangedAtMs, windowMs)
    }

    fun shouldDebounceLocationRequestReapply(
        reason: String,
        nowMs: Long,
        lastAppliedAtMs: Long,
        debounceMs: Long,
    ): Boolean {
        if (lastAppliedAtMs <= 0L) return false
        val nonCriticalAutoModeRequest = reason.startsWith("auto_mode_") ||
            reason == "elasticity_update" ||
            reason.startsWith("elasticity_reset_auto_mode_changed")
        if (!nonCriticalAutoModeRequest) return false
        return nowMs - lastAppliedAtMs in 0 until debounceMs
    }

    private fun isWithinWindow(nowMs: Long, eventAtMs: Long, windowMs: Long): Boolean {
        if (eventAtMs <= 0L) return false
        return nowMs - eventAtMs in 0..windowMs
    }
}
