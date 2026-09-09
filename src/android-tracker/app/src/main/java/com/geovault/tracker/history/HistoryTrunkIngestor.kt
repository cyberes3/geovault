package com.geovault.tracker.history

import com.geovault.common.net.GeoVaultApiFailure
import com.geovault.tracker.Tracker
import com.geovault.tracker.db.QueuedLocation

data class TrunkFetchOutcome(
    val failure: GeoVaultApiFailure?,
    val committedNewDegrade: Boolean,
)

/**
 * Only compose write owner. Catalog fetch, user refresh, and launch preload write trunks
 * here. Map recomposes from published snapshots.
 */
class HistoryTrunkIngestor(
    private val dispatcher: TrackerHistoryIntentDispatcher,
    private val repository: TrackerHistoryRepository,
) {
    fun commitTrunk(intent: TrackerHistoryIntent.CommitTrunk): TrackerHistoryTransactionResult {
        return dispatcher.dispatch(intent)
    }

    suspend fun fetchCatalog(
        catalogTrackers: List<Tracker>,
        loadGeometry: suspend (List<String>) -> List<Tracker>,
        activeSessionStartMsFor: (String) -> Long?,
    ): TrunkFetchOutcome {
        return fetchAndCommit(
            trackerIds = catalogTrackers.map { it.id },
            catalogTrackers = catalogTrackers,
            loadGeometry = loadGeometry,
            activeSessionStartMsFor = activeSessionStartMsFor,
        )
    }

    suspend fun fetchAndCommit(
        trackerIds: Collection<String>,
        catalogTrackers: List<Tracker>,
        loadGeometry: suspend (List<String>) -> List<Tracker>,
        activeSessionStartMsFor: (String) -> Long?,
    ): TrunkFetchOutcome {
        val ids = trackerIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (ids.isEmpty()) return TrunkFetchOutcome(failure = null, committedNewDegrade = false)
        return try {
            val loaded = loadGeometry(ids)
            val loadedIds = loaded.map { it.id.trim() }.filter { it.isNotEmpty() }.toSet()
            loaded.forEach { tracker ->
                commitTrunk(
                    TrackerHistoryIntent.CommitTrunk(
                        batch = TrackerHistorySourceAdapters.filteredServerTrunk(tracker),
                        activeSessionStartMs = activeSessionStartMsFor(tracker.id),
                    ),
                )
            }
            var committedNewDegrade = false
            ids.filter { it !in loadedIds }.forEach { missingId ->
                if (commitDegradedIfNoAuthoritativeTrunk(
                        trackerId = missingId,
                        catalogTrackers = catalogTrackers,
                        activeSessionStartMsFor = activeSessionStartMsFor,
                    )
                ) {
                    committedNewDegrade = true
                }
            }
            TrunkFetchOutcome(failure = null, committedNewDegrade = committedNewDegrade)
        } catch (e: GeoVaultApiFailure) {
            var committedNewDegrade = false
            ids.forEach { trackerId ->
                if (commitDegradedIfNoAuthoritativeTrunk(
                        trackerId = trackerId,
                        catalogTrackers = catalogTrackers,
                        activeSessionStartMsFor = activeSessionStartMsFor,
                    )
                ) {
                    committedNewDegrade = true
                }
            }
            TrunkFetchOutcome(failure = e, committedNewDegrade = committedNewDegrade)
        }
    }

    fun recompose(
        key: TrackerHistoryKey,
        activeSessionStartMs: Long?,
        nowMs: Long = System.currentTimeMillis(),
        forceCommitEmpty: Boolean = false,
    ): TrackerHistoryTransactionResult {
        return repository.composeAndPublish(
            key = key,
            activeSessionStartMs = activeSessionStartMs,
            nowMs = nowMs,
            forceCommitEmpty = forceCommitEmpty,
        )
    }

    fun unpublishedOverlay(key: TrackerHistoryKey): List<QueuedLocation> {
        return repository.unpublishedOverlay(key)
    }

    private fun commitDegradedIfNoAuthoritativeTrunk(
        trackerId: String,
        catalogTrackers: List<Tracker>,
        activeSessionStartMsFor: (String) -> Long?,
    ): Boolean {
        val tracker = catalogTrackers.firstOrNull { it.id.trim() == trackerId }
        val window = TrackerHistoryWindowResolver.fromTracker(tracker)
        val key = TrackerHistoryKey(trackerId, window)
        val existing = repository.snapshotFor(key)
        if (existing != null && existing.trunk.isNotEmpty() && !existing.degradedLocalOnly) {
            return false
        }
        val hadSnapshot = existing != null
        commitTrunk(
            TrackerHistoryIntent.CommitTrunk(
                batch = TrackerHistorySourceAdapters.degradedLocalOnlyTrunk(
                    trackerId = trackerId,
                    window = window,
                    queuedLocations = emptyList(),
                ),
                activeSessionStartMs = activeSessionStartMsFor(trackerId),
            ),
        )
        return !hadSnapshot
    }
}
