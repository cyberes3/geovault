package com.geovault.tracker.history

/**
 * Tracks consecutive `empty_snapshot_deferred` compose outcomes per [TrackerHistoryKey] and
 * decides when [TrackerHistoryRepository] should force the next compose to commit instead of
 * deferring again.
 *
 * `empty_snapshot_deferred` (see [TrackerHistoryAssembler]) exists to absorb a single transient
 * race — e.g. session-start resolution lagging one commit behind a burst of overlay points —
 * without flashing the map to empty. But if compose keeps landing on empty for the same key for
 * an unrelated, non-transient reason (a genuinely stuck session/window mismatch), deferring
 * forever freezes the map on stale points with nothing to ever trigger a recovery. Once a key
 * has deferred [FORCE_COMMIT_AFTER] times in a row, the next compose for it is forced through
 * regardless of outcome — trading one visible "goes empty" flash for guaranteed self-recovery.
 */
class TrackerHistoryDeferralWatchdog {
    private val consecutiveDeferrals = mutableMapOf<TrackerHistoryKey, Int>()

    fun shouldForceCommit(key: TrackerHistoryKey): Boolean {
        return (consecutiveDeferrals[key] ?: 0) >= FORCE_COMMIT_AFTER
    }

    fun onDeferred(key: TrackerHistoryKey) {
        consecutiveDeferrals[key] = (consecutiveDeferrals[key] ?: 0) + 1
    }

    fun onCommitted(key: TrackerHistoryKey) {
        if (consecutiveDeferrals.isNotEmpty()) consecutiveDeferrals.remove(key)
    }

    fun forget(trackerId: String) {
        if (consecutiveDeferrals.isEmpty()) return
        consecutiveDeferrals.keys.retainAll { it.normalizedTrackerId != trackerId }
    }

    fun reset() {
        consecutiveDeferrals.clear()
    }

    companion object {
        const val FORCE_COMMIT_AFTER = 3
    }
}
