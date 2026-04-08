package com.geovault.tracker.presentation

import com.geovault.tracker.RepositoryResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

data class MainStartupRefreshOutcome(
    val isServerAccessible: Boolean,
    val selectedTrackerPrefetchAttempted: Boolean,
)

object MainStartupRefreshCoordinator {
    suspend fun run(
        selectedTrackerId: String,
        loadTrackers: suspend () -> RepositoryResult<*>,
        loadGroups: suspend () -> RepositoryResult<*>,
        loadMapVisibility: suspend () -> RepositoryResult<*>,
        loadTracker: suspend (String) -> RepositoryResult<*>,
        loadTrackerGeometry: suspend (String) -> RepositoryResult<*>,
        loadTrackerCoordinates: suspend (String) -> RepositoryResult<*>,
    ): MainStartupRefreshOutcome {
        val prefetchAttempted = selectedTrackerId.isNotBlank()
        val serverAccessible = coroutineScope {
            val trackersDef = async { loadTrackers() }
            val groupsDef = async { loadGroups() }
            val visibilityDef = async { loadMapVisibility() }
            val selectedTrackerPrefetchDefs = if (prefetchAttempted) {
                listOf(
                    async { loadTracker(selectedTrackerId) },
                    async { loadTrackerGeometry(selectedTrackerId) },
                    async { loadTrackerCoordinates(selectedTrackerId) },
                )
            } else {
                emptyList()
            }
            val startupResults = listOf(
                trackersDef.await(),
                groupsDef.await(),
                visibilityDef.await(),
            )
            selectedTrackerPrefetchDefs.awaitAll()
            startupResults.any { it is RepositoryResult.Success }
        }
        return MainStartupRefreshOutcome(
            isServerAccessible = serverAccessible,
            selectedTrackerPrefetchAttempted = prefetchAttempted
        )
    }
}
