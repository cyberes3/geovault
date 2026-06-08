package com.geovault.tracker.location

enum class StationaryPauseEligibilityReason(val telemetryValue: String) {
    ALLOWED("allowed"),
    STATIONARY_POLICY_NOT_READY("stationary_policy_not_ready"),
    STALE_LOCAL_POINT("stale_local_point"),
    FALLBACK_PENDING("fallback_pending"),
    PROVIDER_UNAVAILABLE("provider_unavailable"),
}

data class StationaryPauseEligibilityDecision(
    val shouldPause: Boolean,
    val reason: StationaryPauseEligibilityReason,
)

/**
 * Service-layer gate on top of [com.geovault.tracker.TrackingLocationPolicy.stationaryUpdate].
 *
 * The stationary policy may prove stillness, but GPS should only sleep when
 * the persisted local trail is fresh and no recovery path is pending.
 *
 * The [sensorFusionHighConfidence] bypass exempts the staleness check when
 * barometer/IMU evidence independently confirms the device has not moved.
 * That signal is GPS-independent, so it remains valid even when the last
 * committed point is old.
 */
object StationaryPauseEligibilityPolicy {
    fun evaluate(
        stationaryPolicyWantsPause: Boolean,
        localPointFresh: Boolean,
        fallbackPending: Boolean,
        providerAvailable: Boolean,
        sensorFusionHighConfidence: Boolean = false,
    ): StationaryPauseEligibilityDecision {
        if (!stationaryPolicyWantsPause) {
            return StationaryPauseEligibilityDecision(
                shouldPause = false,
                reason = StationaryPauseEligibilityReason.STATIONARY_POLICY_NOT_READY,
            )
        }
        if (!providerAvailable) {
            return StationaryPauseEligibilityDecision(
                shouldPause = false,
                reason = StationaryPauseEligibilityReason.PROVIDER_UNAVAILABLE,
            )
        }
        if (!localPointFresh && !sensorFusionHighConfidence) {
            return StationaryPauseEligibilityDecision(
                shouldPause = false,
                reason = StationaryPauseEligibilityReason.STALE_LOCAL_POINT,
            )
        }
        if (fallbackPending) {
            return StationaryPauseEligibilityDecision(
                shouldPause = false,
                reason = StationaryPauseEligibilityReason.FALLBACK_PENDING,
            )
        }
        return StationaryPauseEligibilityDecision(
            shouldPause = true,
            reason = StationaryPauseEligibilityReason.ALLOWED,
        )
    }
}
