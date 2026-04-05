package com.geovault.tracker.presentation

import com.geovault.tracker.RepositoryResult
import kotlinx.coroutines.async
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
        loadAvailableToAdd: suspend () -> RepositoryResult<*>,
        loadTracker: suspend (String) -> RepositoryResult<*>,
        loadTrackerGeometry: suspend (String) -> RepositoryResult<*>,
    ): MainStartupRefreshOutcome {
        val serverAccessible = coroutineScope {
            val trackersDef = async { loadTrackers() }
            val groupsDef = async { loadGroups() }
            val visibilityDef = async { loadMapVisibility() }
            val addableDef = async { loadAvailableToAdd() }
            val startupResults = listOf(
                trackersDef.await(),
                groupsDef.await(),
                visibilityDef.await(),
                addableDef.await(),
            )
            startupResults.any { it is RepositoryResult.Success }
        }
        val prefetchAttempted = selectedTrackerId.isNotBlank()
        if (prefetchAttempted) {
            coroutineScope {
                async { loadTracker(selectedTrackerId) }
                async { loadTrackerGeometry(selectedTrackerId) }
            }
        }
        return MainStartupRefreshOutcome(
            isServerAccessible = serverAccessible,
            selectedTrackerPrefetchAttempted = prefetchAttempted
        )
    }
}
