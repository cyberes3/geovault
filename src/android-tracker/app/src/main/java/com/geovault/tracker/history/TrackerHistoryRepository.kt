package com.geovault.tracker.history

import com.geovault.common.logging.GeoVaultCaptureLog
import com.geovault.tracker.db.QueuedLocation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TrackerHistoryRepository(
    private val sourceStore: TrackerHistorySourceStore = TrackerHistorySourceStore(),
    private val deferralWatchdog: TrackerHistoryDeferralWatchdog = TrackerHistoryDeferralWatchdog(),
) {
    private val _snapshots = MutableStateFlow<Map<TrackerHistoryKey, TrackerHistorySnapshot>>(emptyMap())
    val snapshots: StateFlow<Map<TrackerHistoryKey, TrackerHistorySnapshot>> = _snapshots.asStateFlow()

    private val lastTrunkFetchedAtMsByTracker = mutableMapOf<String, Long>()

    // SYNCHRONIZED READ: every mutator of `lastTrunkFetchedAtMsByTracker` (a plain, non-thread-safe
    // `mutableMapOf`) is `@Synchronized` on this instance, but this getter previously was not.
    // Besides the missing JMM visibility guarantee (a reader could observe a stale value from
    // another thread indefinitely), reading a plain `LinkedHashMap` concurrently with an
    // unsynchronized write is undefined behavior for the map's own internal structure, not just
    // for the value returned -- worth guarding even though writes are comparatively rare.
    @Synchronized
    fun lastTrunkFetchedAtMs(trackerId: String): Long? {
        return lastTrunkFetchedAtMsByTracker[trackerId.trim()]
    }

    @Synchronized
    fun reset() {
        sourceStore.clearAll()
        _snapshots.value = emptyMap()
        lastTrunkFetchedAtMsByTracker.clear()
        deferralWatchdog.reset()
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
        val prepared = TrackerHistoryActiveSessionPolicy.prepareTrunkForCommit(
            batch = batch,
            activeSessionStartMs = activeSessionStartMs,
        )
        val trunkBatch = when (prepared) {
            is TrackerHistoryTrunkPrepareResult.Reject -> {
                GeoVaultCaptureLog.w(
                    TAG,
                    "map_update history_trunk_ignored tracker=${batch.normalizedTrackerId} " +
                        "window=${batch.window.normalizedKey} reason=${prepared.reason} pts=${batch.points.size} " +
                        "session=${activeSessionStartMs ?: -1}",
                )
                return fallbackForKey(
                    key = TrackerHistoryKey(batch.normalizedTrackerId, batch.window),
                    reason = prepared.reason,
                    nowMs = nowMs,
                )
            }
            is TrackerHistoryTrunkPrepareResult.Commit -> {
                if (prepared.clipped) {
                    GeoVaultCaptureLog.i(
                        TAG,
                        "map_update history_trunk_clipped tracker=${batch.normalizedTrackerId} " +
                            "window=${batch.window.normalizedKey} before=${batch.points.size} " +
                            "after=${prepared.batch.points.size} session=${activeSessionStartMs ?: -1}",
                    )
                }
                prepared.batch
            }
        }
        sourceStore.putTrunk(trunkBatch)
        lastTrunkFetchedAtMsByTracker[trunkBatch.normalizedTrackerId] = nowMs
        if (trunkBatch.sourceKind == TrackerHistorySourceKind.FILTERED_SERVER_TRUNK && !trunkBatch.complete) {
            GeoVaultCaptureLog.i(
                TAG,
                "map_update history_trunk_truncated tracker=${trunkBatch.normalizedTrackerId} " +
                    "window=${trunkBatch.window.normalizedKey} points=${trunkBatch.points.size}",
            )
        }
        val key = TrackerHistoryKey(trunkBatch.normalizedTrackerId, trunkBatch.window)
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
        deferralWatchdog.forget(trackerId)
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

    /**
     * Overlay points already in the source store that the published snapshot does not include
     * (typically [empty_snapshot_deferred]). Draw must still paint these.
     */
    @Synchronized
    fun unpublishedOverlayQueuedLocations(key: TrackerHistoryKey): List<QueuedLocation> {
        val publishedKeys = _snapshots.value[key]?.points?.map { it.key }?.toSet().orEmpty()
        return sourceStore.overlays(key)
            .flatMap { it.points }
            .filter { it.key !in publishedKeys }
            .sortedBy { it.timestampMs }
            .map { it.toQueuedLocation() }
    }

    @Synchronized
    fun composeAndPublish(
        key: TrackerHistoryKey,
        activeSessionStartMs: Long?,
        nowMs: Long = System.currentTimeMillis(),
        forceCommitEmpty: Boolean = false,
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
                forceCommitEmpty = forceCommitEmpty || deferralWatchdog.shouldForceCommit(key),
            ),
        )
        if (result.committed) {
            deferralWatchdog.onCommitted(key)
            _snapshots.value = _snapshots.value + (key to result.snapshot)
            if (result.snapshot.trunk.isNotEmpty()) {
                sourceStore.releaseClearBoundary(key.normalizedTrackerId)
            }
        } else if (result.reason == "empty_snapshot_deferred") {
            deferralWatchdog.onDeferred(key)
            TrackerHistoryDiagnostics.logComposeDeferred(
                trackerId = key.normalizedTrackerId,
                window = key.window.normalizedKey,
                previousPoints = previous?.points?.size ?: 0,
            )
        }
        return result
    }

    /**
     * IDLE-ROLLING-WINDOW STALENESS: a "last N hours"-style filter is only re-evaluated inside
     * [composeAndPublish], which only runs when a trunk/overlay commit or clear happens. If a
     * tracker goes idle (no new points) while such a window is displayed, the composed snapshot
     * keeps whatever set of points fell inside the window at the *last* compose time forever —
     * points that have since aged out of the window stay visibly included instead of dropping
     * off as time passes. Call this periodically (and on resume) so every rolling-window key
     * gets re-filtered against the current [nowMs] even with no new data. [activeSessionStartMsFor]
     * only matters for the (rare) rolling-window key that also belongs to the tracker currently
     * being locally recorded, since that is the only case a rolling-window compose still consults
     * an active session id (to gate local live/queue overlay eligibility) — pass a resolver rather
     * than a single value so this repository does not need to know which tracker that is.
     *
     * Passes `forceCommitEmpty = true` unconditionally: unlike a data-arrival compose, there is no
     * transient race for [TrackerHistoryDeferralWatchdog] to protect against here — this call
     * itself *is* the deliberate, periodic re-derivation, driven purely by wall-clock passage, so
     * a newly-empty result should always land immediately rather than waiting out the watchdog's
     * consecutive-deferral threshold first.
     *
     * Returns the keys whose composed points actually changed, so the caller can decide whether a
     * render republish is warranted.
     */
    @Synchronized
    fun recomputeStaleRollingWindows(
        nowMs: Long = System.currentTimeMillis(),
        activeSessionStartMsFor: (String) -> Long? = { null },
    ): List<TrackerHistoryKey> {
        val rollingKeys = _snapshots.value.keys.filter { it.window.isRolling }
        if (rollingKeys.isEmpty()) return emptyList()
        val changedKeys = mutableListOf<TrackerHistoryKey>()
        for (key in rollingKeys) {
            val previousPoints = _snapshots.value[key]?.points
            val result = composeAndPublish(
                key = key,
                activeSessionStartMs = activeSessionStartMsFor(key.normalizedTrackerId),
                nowMs = nowMs,
                forceCommitEmpty = true,
            )
            if (result.committed && result.snapshot.points != previousPoints) {
                changedKeys += key
            }
        }
        return changedKeys
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
