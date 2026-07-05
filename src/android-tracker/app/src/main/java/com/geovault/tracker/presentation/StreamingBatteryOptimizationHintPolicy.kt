package com.geovault.tracker.presentation

import com.geovault.tracker.streaming.StreamingConfig

/**
 * Decides whether to surface a "your device may be killing the streaming connection in the
 * background -- disable battery optimization" hint on the map.
 *
 * Streaming has no equivalent of tracking's `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` prompt (see
 * [com.geovault.tracker.location.TrackingPermissionGate]), so without this a user whose OEM
 * silently kills the socket in the background sees only "streaming looks stuck" capture-log
 * breadcrumbs with no actionable next step in the app itself.
 *
 * Deliberately conservative about false positives: only fires when a subscription is actually
 * wanted, the connection has been unhealthy continuously for
 * [StreamingConfig.batteryOptimizationHintUnhealthyThresholdMs], a usable network is present (so
 * this isn't confused with "no internet, nothing to do with battery optimization"), and the
 * exemption isn't already granted (so the hint clears itself the moment the user takes the
 * suggested action, without waiting for the connection to actually recover).
 */
object StreamingBatteryOptimizationHintPolicy {
    fun shouldShowHint(
        wantsSubscription: Boolean,
        connectionHealthy: Boolean,
        unhealthySinceMs: Long?,
        nowMs: Long,
        hasUsableNetwork: Boolean,
        hasBatteryOptimizationExemption: Boolean,
    ): Boolean {
        if (!wantsSubscription || connectionHealthy || hasBatteryOptimizationExemption || !hasUsableNetwork) {
            return false
        }
        val unhealthySince = unhealthySinceMs ?: return false
        return nowMs - unhealthySince >= StreamingConfig.batteryOptimizationHintUnhealthyThresholdMs
    }
}
