package com.geovault.tracker.map

import com.geovault.tracker.presentation.TrackerMapTrailReloadReason

/**
 * PENDING-RELOAD-FIT: replaces a raw `Boolean` flag (`pendingFitAfterReload`) with a type whose
 * invariants are enforced internally instead of left to callers to remember.
 *
 * Bugs this closes structurally, not just by convention:
 *  - STALE-COMMIT FIT-FLAG LEAK: a flag armed by one server-fetching reload could previously be
 *    consumed by a later, completely unrelated reload (including a non-server-fetching one),
 *    producing a spurious camera re-fit disconnected from what actually triggered it. A
 *    non-fetching [TrackerMapTrailReloadReason] can now never arm or consume this at all, and the
 *    single [arm] call site sits immediately before the reload it belongs to actually fetches
 *    (see `MapTrailReloadSubsystem.reloadTrailFromDatabase`), so an early-return guard/skip
 *    upstream of that point can never leave a stale arm behind for some other reload to consume.
 *  - STREAMING-START LOCK FIGHT: a reload landing while a map lock is already claiming the
 *    camera (e.g. the selection lock `StreamRosterResolver` engages the instant a stream starts)
 *    used to fire an unconditional full-extent fit through this same flag, fighting the lock
 *    that just engaged. [consumeIfLanded]'s `anyLockActive` parameter blocks consumption while a
 *    lock is active, leaving the reactive precedence-driven directive to frame the camera
 *    correctly instead.
 *  - POST-GESTURE SNAP: a fetch armed before the user started panning/zooming could previously
 *    still land and fire a full-extent fit afterward -- the resulting directive is minted with
 *    the *current* (post-gesture) generation, so the camera consumer's own staleness check can't
 *    catch it. [arm] now stamps the camera generation active at arm time, and [consumeIfLanded]
 *    refuses to fire (though it still disarms) once that generation has moved on.
 */
internal class PendingReloadCameraFit {
    private var armed = false
    private var armedGeneration: Long = -1L

    internal fun arm(reason: TrackerMapTrailReloadReason, generation: Long) {
        if (!reason.allowServerHistoryFetch) return
        armed = true
        armedGeneration = generation
    }

    internal fun disarm(reason: TrackerMapTrailReloadReason) {
        if (reason.allowServerHistoryFetch) armed = false
    }

    /**
     * Returns `true` (and disarms) only when: this reason is allowed to have armed it, it is
     * currently armed, the just-committed reload actually produced data, no map lock is
     * currently claiming the camera, and no user gesture has started a new camera generation
     * since [arm] was called. Callers should fire their explicit fit only when this returns
     * `true`.
     *
     * Disarming happens whenever a data-bearing landing for the armed reason occurs, regardless
     * of whether the lock or generation check ends up blocking the fit -- once this reload has
     * had its shot, the arm is spent and must never be picked up by a later, unrelated reload.
     */
    internal fun consumeIfLanded(
        reason: TrackerMapTrailReloadReason,
        hasData: Boolean,
        anyLockActive: Boolean,
        currentGeneration: Long,
    ): Boolean {
        if (!reason.allowServerHistoryFetch || !armed || !hasData) return false
        armed = false
        return !anyLockActive && currentGeneration == armedGeneration
    }
}
