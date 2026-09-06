package com.geovault.tracker.presentation

import com.geovault.common.concurrent.SingleFlightGate
import com.geovault.common.concurrent.TimeWindowedCache
import com.geovault.tracker.RepositoryResult
import kotlinx.coroutines.CoroutineScope

/**
 * Dedupes bursty map warmup requests for a short session window.
 *
 * This keeps launch/resume stabilization from re-hitting the same endpoints
 * multiple times while preserving fresh fetches once the window expires.
 */
class TrackerMapSessionRequestDeduper(
    scope: CoroutineScope,
    dedupeWindowMs: Long = 4_000L,
    nowMsProvider: () -> Long = { System.currentTimeMillis() },
) {
    private val resultCache = TimeWindowedCache<String, Any>(windowMs = dedupeWindowMs, nowMsProvider = nowMsProvider)
    private val gate = SingleFlightGate<String, Any>(scope)

    suspend fun <T : Any> loadOnce(
        key: String,
        loader: suspend () -> RepositoryResult<T>
    ): RepositoryResult<T> {
        resultCache.get(key)?.let { cached ->
            @Suppress("UNCHECKED_CAST")
            return RepositoryResult.Success(cached as T)
        }
        @Suppress("UNCHECKED_CAST")
        return gate.run(key) {
            resultCache.get(key)?.let { rechecked ->
                return@run RepositoryResult.Success(rechecked) as Any
            }
            when (val result = loader()) {
                is RepositoryResult.Success -> {
                    resultCache.put(key, result.data as Any)
                    result as Any
                }
                is RepositoryResult.Failure -> result as Any
            }
        } as RepositoryResult<T>
    }

    suspend fun clear() {
        resultCache.clear()
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
        resultCache.invalidateWhere { it.referencesTrackerId(id) }
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
