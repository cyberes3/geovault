package com.geovault.tracker.history

enum class TrackerHistoryRefreshCause {
    TrackerSwitch,
    ModeSwitch,
    WindowChanged,
    ColdStart,
    Resume,
    HistoryCleared,
    UploadSuccess,
    RosterChanged,
    PeriodicRecording,
    CosmeticTick,
    LivePoint,
}

data class TrackerHistoryRefreshInput(
    val cause: TrackerHistoryRefreshCause,
    val nowMs: Long,
    val lastTrunkFetchedAtMs: Long?,
    /** When set, stale checks use this tracker's last trunk fetch instead of a global timestamp. */
    val trackerIdForStaleCheck: String? = null,
    val isRecording: Boolean = false,
    val visibleRowsUploaded: Boolean = false,
    val staleAfterMs: Long = DEFAULT_STALE_AFTER_MS,
) {
    companion object {
        const val DEFAULT_STALE_AFTER_MS = 60_000L
    }
}

data class TrackerHistoryRefreshDecision(
    val shouldRefresh: Boolean,
    val reason: String,
)

object TrackerHistoryRefreshPolicy {
    fun resolve(input: TrackerHistoryRefreshInput): TrackerHistoryRefreshDecision {
        return when (input.cause) {
            TrackerHistoryRefreshCause.TrackerSwitch,
            TrackerHistoryRefreshCause.ModeSwitch,
            TrackerHistoryRefreshCause.WindowChanged,
            TrackerHistoryRefreshCause.ColdStart,
            TrackerHistoryRefreshCause.HistoryCleared,
            TrackerHistoryRefreshCause.RosterChanged -> TrackerHistoryRefreshDecision(true, input.cause.name)

            TrackerHistoryRefreshCause.UploadSuccess -> TrackerHistoryRefreshDecision(
                shouldRefresh = input.visibleRowsUploaded,
                reason = if (input.visibleRowsUploaded) "visible_upload" else "no_visible_rows",
            )

            TrackerHistoryRefreshCause.Resume,
            TrackerHistoryRefreshCause.PeriodicRecording -> {
                val last = input.lastTrunkFetchedAtMs
                val stale = last == null || input.nowMs - last >= input.staleAfterMs
                TrackerHistoryRefreshDecision(stale, if (stale) "stale_trunk" else "fresh_trunk")
            }

            TrackerHistoryRefreshCause.CosmeticTick,
            TrackerHistoryRefreshCause.LivePoint -> TrackerHistoryRefreshDecision(false, "overlay_or_cosmetic_only")
        }
    }
}
