package com.geovault.tracker.data

import com.geovault.common.concurrent.SingleFlightGate
import com.geovault.common.concurrent.TimeWindowedCache
import com.geovault.tracker.RepositoryResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

data class TrackerBootstrapOutcome(
    val isServerAccessible: Boolean,
)

interface TrackerBootstrapDataSource {
    suspend fun loadTrackers(forceRefresh: Boolean): RepositoryResult<*>
    suspend fun loadGroups(forceRefresh: Boolean): RepositoryResult<*>
    suspend fun loadMapVisibility(forceRefresh: Boolean): RepositoryResult<*>
}

class RepositoryTrackerBootstrapDataSource(
    private val trackerRepository: TrackerManagementRepository,
    private val groupRepository: GroupManagementRepository,
) : TrackerBootstrapDataSource {
    override suspend fun loadTrackers(forceRefresh: Boolean): RepositoryResult<*> =
        trackerRepository.loadTrackers(forceRefresh = forceRefresh)

    override suspend fun loadGroups(forceRefresh: Boolean): RepositoryResult<*> =
        groupRepository.loadGroups(forceRefresh = forceRefresh)

    override suspend fun loadMapVisibility(forceRefresh: Boolean): RepositoryResult<*> =
        trackerRepository.loadMapVisibility(forceRefresh = forceRefresh)
}

class TrackerBootstrapOrchestrator(
    private val dataSource: TrackerBootstrapDataSource,
    resumeDedupeWindowMs: Long = 4_000L,
    nowMsProvider: () -> Long = { System.currentTimeMillis() },
) {
    private val launchGate = SingleFlightGate<String, TrackerBootstrapOutcome>()
    private val resumeGate = SingleFlightGate<String, TrackerBootstrapOutcome>()
    private val resumeCache = TimeWindowedCache<String, TrackerBootstrapOutcome>(
        windowMs = resumeDedupeWindowMs,
        nowMsProvider = nowMsProvider,
    )

    @Volatile
    private var launchCompleted = false
    @Volatile
    private var lastLaunchOutcome: TrackerBootstrapOutcome? = null

    suspend fun refreshForLaunch(): TrackerBootstrapOutcome {
        val cachedLaunchOutcome = lastLaunchOutcome
        if (launchCompleted && cachedLaunchOutcome != null) {
            return cachedLaunchOutcome
        }
        return launchGate.run("launch") {
            if (launchCompleted) {
                return@run lastLaunchOutcome ?: refresh(forceRefresh = true)
            }
            refresh(
                forceRefresh = true,
            ).also { outcome ->
                lastLaunchOutcome = outcome
                launchCompleted = true
            }
        }
    }

    suspend fun refreshForResume(): TrackerBootstrapOutcome {
        resumeCache.get(RESUME_CACHE_KEY)?.let { return it }
        return resumeGate.run("resume") {
            resumeCache.get(RESUME_CACHE_KEY)?.let { return@run it }
            refresh(
                forceRefresh = true,
            ).also { outcome ->
                resumeCache.put(RESUME_CACHE_KEY, outcome)
            }
        }
    }

    fun resetLaunchState() {
        launchCompleted = false
        lastLaunchOutcome = null
        resumeCache.clear()
    }

    private suspend fun refresh(forceRefresh: Boolean): TrackerBootstrapOutcome {
        val startupResults = coroutineScope {
            val trackersDef = async { dataSource.loadTrackers(forceRefresh = forceRefresh) }
            val groupsDef = async { dataSource.loadGroups(forceRefresh = forceRefresh) }
            val mapVisibilityDef = async { dataSource.loadMapVisibility(forceRefresh = forceRefresh) }
            val coreResults = listOf(
                trackersDef.await(),
                groupsDef.await(),
                mapVisibilityDef.await(),
            )
            coreResults
        }
        return TrackerBootstrapOutcome(
            isServerAccessible = startupResults.any { it is RepositoryResult.Success },
        )
    }

    private companion object {
        const val RESUME_CACHE_KEY = "resume"
    }
}
