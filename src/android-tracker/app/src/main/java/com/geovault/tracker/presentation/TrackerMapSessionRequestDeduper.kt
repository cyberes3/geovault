package com.geovault.tracker.presentation

import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.data.SingleFlightRequestGate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Dedupes bursty map warmup requests for a short session window.
 *
 * This keeps launch/resume stabilization from re-hitting the same endpoints
 * multiple times while preserving fresh fetches once the window expires.
 */
class TrackerMapSessionRequestDeduper(
    private val dedupeWindowMs: Long = 4_000L,
    private val nowMsProvider: () -> Long = { System.currentTimeMillis() },
) {
    private data class CacheEntry(
        val loadedAtMs: Long,
        val value: Any,
    )

    private val cacheMutex = Mutex()
    private val resultCache = mutableMapOf<String, CacheEntry>()
    private val gate = SingleFlightRequestGate<String, Any>()

    suspend fun <T : Any> loadOnce(
        key: String,
        loader: suspend () -> RepositoryResult<T>
    ): RepositoryResult<T> {
        val now = nowMsProvider()
        val cached = cacheMutex.withLock { resultCache[key] }
        if (cached != null && now - cached.loadedAtMs <= dedupeWindowMs) {
            @Suppress("UNCHECKED_CAST")
            return RepositoryResult.Success(cached.value as T)
        }
        @Suppress("UNCHECKED_CAST")
        return gate.run(key) {
            val recheckNow = nowMsProvider()
            val rechecked = cacheMutex.withLock { resultCache[key] }
            if (rechecked != null && recheckNow - rechecked.loadedAtMs <= dedupeWindowMs) {
                return@run RepositoryResult.Success(rechecked.value) as Any
            }
            when (val result = loader()) {
                is RepositoryResult.Success -> {
                    cacheMutex.withLock {
                        resultCache[key] = CacheEntry(
                            loadedAtMs = nowMsProvider(),
                            value = result.data as Any
                        )
                    }
                    result as Any
                }
                is RepositoryResult.Failure -> result as Any
            }
        } as RepositoryResult<T>
    }

    suspend fun clear() {
        cacheMutex.withLock {
            resultCache.clear()
        }
    }

    /**
     * Drops any cached geometry whose key references [trackerId]. Used to defeat the dedupe
     * window the moment a per-tracker setting (e.g. `recent_data_window`, `hidden`) changes,
     * so the next reload reaches the server with the new parameters instead of replaying
     * the pre-change response.
     *
     * Matches both the single-tracker key shape (`...:trackerId`) and the multi-tracker key
     * shape (`multi:geometry:id1,id2,...`) where ids are joined by commas. The matching is
     * exact on full id boundaries (start-of-segment or after a comma; end-of-key or before a
     * comma) so a shorter id cannot accidentally invalidate a longer id's entry.
     */
    suspend fun invalidate(trackerId: String) {
        val id = trackerId.trim()
        if (id.isEmpty()) return
        cacheMutex.withLock {
            val toRemove = resultCache.keys.filter { it.referencesTrackerId(id) }
            toRemove.forEach(resultCache::remove)
        }
    }

    private fun String.referencesTrackerId(id: String): Boolean {
        var index = 0
        while (true) {
            val found = indexOf(id, startIndex = index)
            if (found < 0) return false
            val precedingChar = getOrNull(found - 1)
            val followingChar = getOrNull(found + id.length)
            val startsBoundary = precedingChar == null || precedingChar == ':' || precedingChar == ','
            val endsBoundary = followingChar == null || followingChar == ','
            if (startsBoundary && endsBoundary) return true
            index = found + 1
        }
    }
}
