package com.geovault.tracker.tracking

object TrackingServiceConstants {
    const val TAG = "TrackingService"
    const val NOTIFICATION_ID = 101
    const val CHANNEL_ID = "tracker_service"
    const val SESSION_STATS_UPDATE = "com.geovault.tracker.SESSION_STATS_UPDATE"

    const val FALLBACK_TRANSITION_TRACK_ID = "fallback_transition"
    const val MAX_QUEUE_SIZE = 5000
    const val MAX_QUEUE_AGE_MS = 7L * 24L * 60L * 60L * 1000L
    const val RETRY_JITTER_MS = 10_000L
    const val LOCATION_REQUEST_REAPPLY_RETRY_MS = 10_000L
    const val FIX_DELIVERY_WATCHDOG_INTERVAL_MS = 30_000L
    const val FIX_DELIVERY_STALE_MS = 90_000L
    const val RECOVERY_HEARTBEAT_INTERVAL_MS = 15_000L
    const val MAX_BATCHES_PER_PUSH = 10

    const val EXTRAS_KEY_LOW_ACCURACY_FALLBACK = "low_accuracy_fallback"
    const val EXTRAS_KEY_FALLBACK_SOURCE_PROVIDER = "fallback_source_provider"
    const val EXTRAS_KEY_FRESHNESS_RECOVERY = "freshness_recovery"
    const val EXTRAS_KEY_FRESHNESS_RECOVERY_SOURCE_PROVIDER = "freshness_recovery_source_provider"
    const val EXTRAS_KEY_MANUAL_SEND = "manual_send"
    const val EXTRA_LOCATION_UPDATES = "extra_location_updates"

    const val FALLBACK_REJECT_SUMMARY_INTERVAL_MS = 30_000L
    const val PAUSED_FRESHNESS_MAX_POOR_ACCURACY_FIXES = 3
    // Covers typical fused-GPS inter-fix spacing while cap-evidence streak is pending.
    const val AUTO_MOTION_CAP_EVIDENCE_STREAK_PRESERVE_WINDOW_MS = 45_000L
    const val AUTO_MOTION_REQUEST_REAPPLY_DEBOUNCE_MS = 10_000L
    const val MOTION_HINT_FLOOR_MPS = 1.0f

    const val AAR_SCRUTINY_WINDOW_MS = 45_000L
    const val AAR_SCRUTINY_INTERVAL_SEC = 2L
}
