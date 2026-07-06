package com.geovault.tracker.map

import com.geovault.tracker.presentation.TrackerMapTrailReloadReason

/**
 * PENDING-RELOAD-FIT: replaces a raw `Boolean` flag (`pendingFitAfterReload`) with a type whose
 * invariants are enforced internally instead of left to callers to remember.
 *
 * Two bugs this closes structurally, not just by convention:
 *  - STALE-COMMIT FIT-FLAG LEAK: a flag armed by one server-fetching reload could previously be
 *    consumed by a later, completely unrelated reload (including a non-server-fetching one),
 *    producing a spurious camera re-fit disconnected from what actually triggered it. A
 *    non-fetching [TrackerMapTrailReloadReason] can now never arm or consume this at all.
 *  - STREAMING-START LOCK FIGHT: a reload landing while a map lock is already claiming the
 *    camera (e.g. the selection lock `StreamRosterResolver` engages the instant a stream starts)
 *    used to fire an unconditional full-extent fit through this same flag, fighting the lock
 *    that just engaged. [consumeIfLanded]'s `anyLockActive` parameter blocks consumption while a
 *    lock is active, leaving the reactive precedence-driven directive to frame the camera
 *    correctly instead.
 */
internal class PendingReloadCameraFit {
    private var armed = false

    fun arm(reason: TrackerMapTrailReloadReason) {
        if (reason.allowServerHistoryFetch) armed = true
    }

    fun disarm(reason: TrackerMapTrailReloadReason) {
        if (reason.allowServerHistoryFetch) armed = false
    }

    /**
     * Returns `true` (and disarms) only when: this reason is allowed to have armed it, it is
     * currently armed, the just-committed reload actually produced data, and no map lock is
     * currently claiming the camera. Callers should fire their explicit fit only when this
     * returns `true`.
     */
    fun consumeIfLanded(
        reason: TrackerMapTrailReloadReason,
        hasData: Boolean,
        anyLockActive: Boolean,
    ): Boolean {
        if (!reason.allowServerHistoryFetch || !armed || !hasData || anyLockActive) return false
        armed = false
        return true
    }
}
