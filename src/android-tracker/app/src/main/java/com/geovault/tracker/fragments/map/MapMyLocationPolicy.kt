package com.geovault.tracker.fragments.map

internal data class MyLocationPolicyInput(
    val trackingRunning: Boolean,
    val showMyLocationEnabledIntent: Boolean,
    val isSelectedDefaultTracker: Boolean,
    val gpsLockRequested: Boolean,
    val trackerOrLiveLockActive: Boolean
)

internal data class MyLocationPolicyDecision(
    val myLocationModeActive: Boolean,
    val effectiveGpsLockActive: Boolean,
    val shouldEnablePuck: Boolean,
    val shouldTrackGpsCamera: Boolean,
    val shouldShowButton: Boolean
)

internal object MapMyLocationPolicy {
    fun compute(input: MyLocationPolicyInput): MyLocationPolicyDecision {
        val myLocationModeActive = input.showMyLocationEnabledIntent && !input.isSelectedDefaultTracker
        val effectiveGpsLockActive = input.gpsLockRequested && !input.trackerOrLiveLockActive
        val shouldEnablePuck = !input.trackingRunning && myLocationModeActive
        val shouldTrackGpsCamera = shouldEnablePuck && effectiveGpsLockActive
        val shouldShowButton = !input.trackingRunning && !input.isSelectedDefaultTracker
        return MyLocationPolicyDecision(
            myLocationModeActive = myLocationModeActive,
            effectiveGpsLockActive = effectiveGpsLockActive,
            shouldEnablePuck = shouldEnablePuck,
            shouldTrackGpsCamera = shouldTrackGpsCamera,
            shouldShowButton = shouldShowButton
        )
    }
}
