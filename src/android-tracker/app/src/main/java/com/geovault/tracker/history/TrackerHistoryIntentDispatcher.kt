package com.geovault.tracker.history

sealed interface TrackerHistoryIntent {
    data class CommitTrunk(
        val batch: TrackerHistorySourceBatch,
        val activeSessionStartMs: Long?,
        val nowMs: Long = System.currentTimeMillis(),
    ) : TrackerHistoryIntent

    data class CommitOverlay(
        val batch: TrackerHistorySourceBatch,
        val activeSessionStartMs: Long?,
        val nowMs: Long = System.currentTimeMillis(),
    ) : TrackerHistoryIntent

    data class Clear(
        val boundary: TrackerHistoryClearBoundary,
        val window: TrackerHistoryWindow,
        val nowMs: Long = System.currentTimeMillis(),
    ) : TrackerHistoryIntent
}

class TrackerHistoryIntentDispatcher(
    private val repository: TrackerHistoryRepository,
) {
    fun dispatch(intent: TrackerHistoryIntent): TrackerHistoryTransactionResult {
        val result = when (intent) {
            is TrackerHistoryIntent.CommitTrunk -> {
                TrackerHistoryDiagnostics.logIntent("CommitTrunk", batch = intent.batch)
                repository.commitTrunk(
                    batch = intent.batch,
                    activeSessionStartMs = intent.activeSessionStartMs,
                    nowMs = intent.nowMs,
                )
            }
            is TrackerHistoryIntent.CommitOverlay -> {
                val batch = intent.batch
                if (batch.sourceKind == TrackerHistorySourceKind.RUNTIME_HEAD ||
                    batch.sourceKind == TrackerHistorySourceKind.LOCAL_LIVE ||
                    batch.sourceKind == TrackerHistorySourceKind.REMOTE_STREAM
                ) {
                    // High-frequency paths: log via throttled tx line only.
                } else {
                    TrackerHistoryDiagnostics.logIntent("CommitOverlay", batch = batch)
                }
                repository.commitOverlay(
                    batch = batch,
                    activeSessionStartMs = intent.activeSessionStartMs,
                    nowMs = intent.nowMs,
                )
            }
            is TrackerHistoryIntent.Clear -> {
                TrackerHistoryDiagnostics.logIntent(
                    intent = "Clear",
                    boundary = intent.boundary,
                    window = intent.window,
                )
                repository.clearHistory(
                    boundary = intent.boundary,
                    window = intent.window,
                    nowMs = intent.nowMs,
                )
            }
        }
        val intentLabel = when (intent) {
            is TrackerHistoryIntent.CommitTrunk -> "CommitTrunk"
            is TrackerHistoryIntent.CommitOverlay -> "CommitOverlay"
            is TrackerHistoryIntent.Clear -> "Clear"
        }
        val batch = (intent as? TrackerHistoryIntent.CommitTrunk)?.batch
            ?: (intent as? TrackerHistoryIntent.CommitOverlay)?.batch
        if (intent is TrackerHistoryIntent.CommitOverlay && batch != null) {
            TrackerHistoryDiagnostics.logOverlayCommitThrottled(
                sourceKind = batch.sourceKind,
                trackerId = batch.normalizedTrackerId,
                window = batch.window.normalizedKey,
                pointCount = batch.points.size,
                committed = result.committed,
                reason = result.reason,
            )
        } else {
            TrackerHistoryDiagnostics.logTransaction(intentLabel, result, batch)
        }
        return result
    }
}
