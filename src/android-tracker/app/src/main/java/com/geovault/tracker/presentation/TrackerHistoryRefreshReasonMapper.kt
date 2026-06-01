package com.geovault.tracker.presentation

import com.geovault.tracker.history.TrackerHistoryRefreshCause

internal object TrackerHistoryRefreshReasonMapper {
    fun toRefreshCause(reason: TrackerMapTrailReloadReason): TrackerHistoryRefreshCause {
        return when (reason) {
            TrackerMapTrailReloadReason.MapContextChange,
            TrackerMapTrailReloadReason.ExplicitTrackerLoad -> TrackerHistoryRefreshCause.TrackerSwitch

            TrackerMapTrailReloadReason.StreamingStart -> TrackerHistoryRefreshCause.ModeSwitch
            TrackerMapTrailReloadReason.RestoreSelectedAfterStreaming -> TrackerHistoryRefreshCause.Resume
            TrackerMapTrailReloadReason.RecentDataWindowChanged -> TrackerHistoryRefreshCause.WindowChanged
            TrackerMapTrailReloadReason.HistoryCleared -> TrackerHistoryRefreshCause.HistoryCleared
            TrackerMapTrailReloadReason.RosterChanged -> TrackerHistoryRefreshCause.RosterChanged
            TrackerMapTrailReloadReason.GenericMapRefresh,
            TrackerMapTrailReloadReason.MetadataMapRefresh -> TrackerHistoryRefreshCause.CosmeticTick
        }
    }
}
