package com.geovault.tracker.fragments.map

internal data class LockResumePolicyInput(
    val followLockEnabled: Boolean,
    val hasLockTarget: Boolean,
    val hasTrackPoint: Boolean,
    val showMyLocationEnabled: Boolean,
    val gpsLocationLockActive: Boolean,
    val liveActiveFitEnabled: Boolean,
    val liveActiveFitAvailable: Boolean,
    val trackerOrLiveLockActive: Boolean
)

internal data class LockResumePolicyDecision(
    val shouldRecenterFollowLock: Boolean,
    val shouldRecenterGpsLock: Boolean,
    val shouldReapplyLiveLock: Boolean
)

internal object MapLockResumePolicy {
    fun decide(input: LockResumePolicyInput): LockResumePolicyDecision {
        val shouldRecenterFollowLock =
            input.followLockEnabled && (input.hasLockTarget || input.hasTrackPoint)
        val shouldRecenterGpsLock =
            input.showMyLocationEnabled && input.gpsLocationLockActive && !input.trackerOrLiveLockActive
        val shouldReapplyLiveLock =
            input.liveActiveFitEnabled && input.liveActiveFitAvailable && !input.followLockEnabled
        return LockResumePolicyDecision(
            shouldRecenterFollowLock = shouldRecenterFollowLock,
            shouldRecenterGpsLock = shouldRecenterGpsLock,
            shouldReapplyLiveLock = shouldReapplyLiveLock
        )
    }
}
