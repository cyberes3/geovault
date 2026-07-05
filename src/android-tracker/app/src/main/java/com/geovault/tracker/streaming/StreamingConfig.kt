package com.geovault.tracker.streaming

import java.util.concurrent.TimeUnit

/**
 * Single source of truth for every streaming-domain timeout/TTL/backoff constant. Values are
 * tracker-domain-specific (not extracted to android-common) but were previously scattered as
 * magic numbers across [LiveTrackStreamingService], [LiveStreamSubscriptionRepository], and the
 * remote-point admission policies. Centralizing them here means every threshold that shapes
 * "does streaming look broken" behavior is documented and testable in one place.
 */
object StreamingConfig {
    /** OkHttp read timeout for the live-track websocket; also the outer bound OkHttp itself would use to notice a truly dead socket. */
    val webSocketReadTimeoutMs: Long = TimeUnit.SECONDS.toMillis(90)

    /** OkHttp ping interval; keeps NAT/proxy connections alive and gives fast onFailure signal. */
    val webSocketPingIntervalMs: Long = TimeUnit.SECONDS.toMillis(30)

    val webSocketWriteTimeoutMs: Long = TimeUnit.SECONDS.toMillis(10)
    val webSocketConnectTimeoutMs: Long = TimeUnit.SECONDS.toMillis(15)

    /**
     * [StreamingSessionGuard] treats a REUSE-eligible session as stale if no socket activity
     * (message or ping pong) has been observed for longer than this while nominally RUNNING.
     */
    val sessionStaleAfterMs: Long = TimeUnit.SECONDS.toMillis(45)

    /**
     * Liveness watchdog poll interval while a session is nominally RUNNING. Half of
     * [sessionStaleAfterMs] so staleness is detected within one extra tick of the threshold.
     */
    val livenessWatchdogIntervalMs: Long = TimeUnit.SECONDS.toMillis(20)

    /** Base/never-longer-than bounds for the transient-failure exponential backoff. */
    val transientRetryBaseDelayMs: Long = TimeUnit.SECONDS.toMillis(3)
    val transientRetryMaxDelayMs: Long = TimeUnit.SECONDS.toMillis(60)

    /** Jitter fraction (+/-) applied to every computed reconnect delay to avoid synchronized reconnect storms. */
    const val retryJitterFraction: Double = 0.2

    /** Fixed delay before retrying an AUTH failure (token likely needs a refresh cycle to settle). */
    val authRetryDelayMs: Long = TimeUnit.SECONDS.toMillis(30)

    /** Hard cap on consecutive AUTH failures before escalating to PERMANENT. */
    const val maxAuthRetryAttempts: Int = 3

    /**
     * Hard ceiling on total wall-clock time spent retrying TRANSIENT failures before escalating
     * to a user-visible terminal state, so a permanently-unreachable server doesn't retry a
     * background service forever.
     */
    val maxTransientRetryDurationMs: Long = TimeUnit.MINUTES.toMillis(30)

    /**
     * Remote points are only rejected as stale if older than this relative to wall-clock now.
     * Long enough to tolerate real clock skew and reconnect catch-up backlogs (see
     * [reconnectFreshnessGraceMs]) without masking a genuinely stuck stream indefinitely.
     */
    val remoteFreshnessTtlMs: Long = TimeUnit.MINUTES.toMillis(30)

    /**
     * After a (re)connect, freshness rejection is suspended for this long so a replayed
     * catch-up backlog (every point older than [remoteFreshnessTtlMs] by construction) is not
     * silently dropped. Steady-state freshness rejection resumes once the grace window elapses.
     */
    val reconnectFreshnessGraceMs: Long = TimeUnit.MINUTES.toMillis(2)

    /** Safety margin for points that appear to be from the future (clock skew tolerance). */
    val maxFutureSkewMs: Long = TimeUnit.MINUTES.toMillis(5)

    /**
     * How long a bootstrap seed (persisted pre-process-death targets, applied before the first
     * owner has expressed a real lease) stays eligible to keep a restored session alive. Bounds
     * the "ghost session no owner ever claims" case to a short, deterministic window instead of
     * pinning a stale session forever.
     */
    val bootstrapGraceMs: Long = TimeUnit.SECONDS.toMillis(10)

    /** Debounce window before a lease change is dispatched to the service, absorbing rapid tracker-scrolling churn. */
    val dispatchDebounceMs: Long = 350L

    /**
     * Delay before [com.geovault.common.coroutines.launchSupervisedCollector] restarts one of
     * [com.geovault.tracker.map.MapStreamingSubsystem]'s always-on collectors after it throws.
     * Short enough that a transient one-off exception doesn't leave the map visibly stalled, but
     * long enough that a collector which fails immediately on every restart (a real, persistent
     * bug) doesn't spin-loop.
     */
    val collectorRestartDelayMs: Long = TimeUnit.SECONDS.toMillis(2)

    /** How often [com.geovault.tracker.map.MapStreamingSubsystem] logs a streaming heartbeat while a subscription is wanted. */
    val heartbeatIntervalMs: Long = TimeUnit.SECONDS.toMillis(60)

    /**
     * How long the streaming connection must be continuously unhealthy (not [ConnectionPhase.RUNNING])
     * while a subscription is wanted, with a usable network present, before
     * [com.geovault.tracker.presentation.StreamingBatteryOptimizationHintPolicy] suggests the user
     * check their OEM's battery-optimization settings.
     */
    val batteryOptimizationHintUnhealthyThresholdMs: Long = TimeUnit.MINUTES.toMillis(3)

    /**
     * A single geometry reload network fetch (see [com.geovault.tracker.map.MapTrailReloadSubsystem])
     * taking at least this long is only diagnostically interesting -- worth a breadcrumb -- when it
     * happens while a recording session is active for the same tracker, since that is exactly the
     * "stalled local map" failure mode this plan set out to catch. Comfortably below the harder
     * [com.geovault.tracker.presentation.MapGeometryReloadCircuitBreaker.NETWORK_TIMEOUT_MS] cutoff so
     * it fires as an early warning rather than only alongside an outright timeout failure.
     */
    val reloadNetworkSlowDuringRecordingThresholdMs: Long = TimeUnit.SECONDS.toMillis(5)
}
