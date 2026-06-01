package com.geovault.tracker.positioning.recovery

import com.geovault.tracker.positioning.PositioningRuntime

internal class RecoverySubsystem(rt: PositioningRuntime) {
    val fastLock = FastGpsLockSubsystem(rt)
    val fallback = LowAccuracyFallbackSubsystem(rt)
    val pausedFreshness = PausedFreshnessSubsystem(rt)
    val jobs = RecoveryJobsSubsystem(rt)
}
