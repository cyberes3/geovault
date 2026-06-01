package com.geovault.tracker.history

import com.geovault.common.logging.GeoVaultCaptureLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TrackerHistoryRepository(
    private val sourceStore: TrackerHistorySourceStore = TrackerHistorySourceStore(),
) {
    private val _snapshots = MutableStateFlow<Map<TrackerHistoryKey, TrackerHistorySnapshot>>(emptyMap())
    val snapshots: StateFlow<Map<TrackerHistoryKey, TrackerHistorySnapshot>> = _snapshots.asStateFlow()

    private val lastTrunkFetchedAtMsByTracker = mutableMapOf<String, Long>()

    fun lastTrunkFetchedAtMs(trackerId: String): Long? {
        return lastTrunkFetchedAtMsByTracker[trackerId.trim()]
    }

    @Synchronized
    fun reset() {
        sourceStore.clearAll()
        _snapshots.value = emptyMap()
        lastTrunkFetchedAtMsByTracker.clear()
        GeoVaultCaptureLog.i(TAG, "map_update history_repository_reset")
    }

    companion object {
        private const val TAG = "TrackerHistoryRepository"
    }

    @Synchronized
    fun commitTrunk(
        batch: TrackerHistorySourceBatch,
        activeSessionStartMs: Long?,
        nowMs: Long = System.currentTimeMillis(),
    ): TrackerHistoryTransactionResult {
        TrackerHistoryActiveSessionPolicy.staleTrunkReason(
            batch = batch,
            activeSessionStartMs = activeSessionStartMs,
        )?.let { reason ->
            GeoVaultCaptureLog.w(
                TAG,
                "map_update history_trunk_ignored tracker=${batch.normalizedTrackerId} " +
                    "window=${batch.window.normalizedKey} reason=$reason pts=${batch.points.size} " +
                    "session=${activeSessionStartMs ?: -1}",
            )
            return fallbackForKey(
                key = TrackerHistoryKey(batch.normalizedTrackerId, batch.window),
                reason = reason,
                nowMs = nowMs,
            )
        }
        sourceStore.putTrunk(batch)
        lastTrunkFetchedAtMsByTracker[batch.normalizedTrackerId] = nowMs
        if (batch.sourceKind == TrackerHistorySourceKind.FILTERED_SERVER_TRUNK && !batch.complete) {
            GeoVaultCaptureLog.i(
                TAG,
                "map_update history_trunk_truncated tracker=${batch.normalizedTrackerId} " +
                    "window=${batch.window.normalizedKey} points=${batch.points.size}",
            )
        }
        val key = TrackerHistoryKey(batch.normalizedTrackerId, batch.window)
        return composeAndPublish(key = key, activeSessionStartMs = activeSessionStartMs, nowMs = nowMs)
    }

    @Synchronized
    fun commitOverlay(
        batch: TrackerHistorySourceBatch,
        activeSessionStartMs: Long?,
        nowMs: Long = System.currentTimeMillis(),
    ): TrackerHistoryTransactionResult {
        sourceStore.putOverlay(batch)
        val key = TrackerHistoryKey(batch.normalizedTrackerId, batch.window)
        return composeAndPublish(key = key, activeSessionStartMs = activeSessionStartMs, nowMs = nowMs)
    }

    @Synchronized
    fun clearHistory(
        boundary: TrackerHistoryClearBoundary,
        window: TrackerHistoryWindow,
        nowMs: Long = System.currentTimeMillis(),
    ): TrackerHistoryTransactionResult {
        val trackerId = boundary.trackerId.trim()
        sourceStore.setClearBoundary(boundary)
        val staleKeys = _snapshots.value.keys.filter { it.normalizedTrackerId == trackerId }
        if (staleKeys.isNotEmpty()) {
            _snapshots.value = _snapshots.value.filterKeys { it.normalizedTrackerId != trackerId }
            GeoVaultCaptureLog.i(
                TAG,
                "map_update history_clear_purge_snapshots tracker=$trackerId removed_keys=" +
                    staleKeys.map { it.window.normalizedKey }.sorted(),
            )
        }
        val key = TrackerHistoryKey(trackerId, window)
        return composeAndPublish(
            key = key,
            activeSessionStartMs = boundary.activeSessionStartMs,
            nowMs = nowMs,
        )
    }

    @Synchronized
    fun snapshotFor(key: TrackerHistoryKey): TrackerHistorySnapshot? {
        return _snapshots.value[key]
    }

    @Synchronized
    fun composeAndPublish(
        key: TrackerHistoryKey,
        activeSessionStartMs: Long?,
        nowMs: Long = System.currentTimeMillis(),
    ): TrackerHistoryTransactionResult {
        val previous = _snapshots.value[key]
        val trunk = sourceStore.trunk(key)
        val result = TrackerHistoryAssembler.compose(
            TrackerHistoryComposeInput(
                key = key,
                trunk = trunk,
                overlayBatches = sourceStore.overlays(key),
                sessionContext = TrackerHistorySessionContext(
                    activeSessionStartMs = activeSessionStartMs,
                    window = key.window,
                    skipRenderWindowFilter = trunk?.skipRenderWindowFilter == true,
                ),
                clearBoundary = sourceStore.clearBoundary(key.normalizedTrackerId),
                nowMs = nowMs,
                previousSnapshot = previous,
            ),
        )
        if (result.committed) {
            _snapshots.value = _snapshots.value + (key to result.snapshot)
            if (result.snapshot.trunk.isNotEmpty()) {
                sourceStore.releaseClearBoundary(key.normalizedTrackerId)
            }
        } else if (result.reason == "empty_snapshot_deferred") {
            TrackerHistoryDiagnostics.logComposeDeferred(
                trackerId = key.normalizedTrackerId,
                window = key.window.normalizedKey,
                previousPoints = previous?.points?.size ?: 0,
            )
        }
        return result
    }

    private fun fallbackForKey(
        key: TrackerHistoryKey,
        reason: String,
        nowMs: Long,
    ): TrackerHistoryTransactionResult {
        val previous = _snapshots.value[key] ?: TrackerHistorySnapshot(
            key = key,
            trunk = emptyList(),
            overlay = emptyList(),
            points = emptyList(),
            committedAtMs = nowMs,
            generation = nowMs,
        )
        return TrackerHistoryTransactionResult(
            snapshot = previous,
            committed = false,
            reason = reason,
        )
    }
}
