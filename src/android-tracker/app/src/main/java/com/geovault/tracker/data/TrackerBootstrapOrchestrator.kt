package com.geovault.tracker.data

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
    private val resumeDedupeWindowMs: Long = 4_000L,
    private val nowMsProvider: () -> Long = { System.currentTimeMillis() },
) {
    private val launchGate = SingleFlightRequestGate<String, TrackerBootstrapOutcome>()
    private val resumeGate = SingleFlightRequestGate<String, TrackerBootstrapOutcome>()

    @Volatile
    private var launchCompleted = false
    @Volatile
    private var lastLaunchOutcome: TrackerBootstrapOutcome? = null
    @Volatile
    private var lastResumeOutcome: TrackerBootstrapOutcome? = null
    @Volatile
    private var lastResumeAtMs: Long = 0L

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
        val cachedOutcome = lastResumeOutcome
        if (cachedOutcome != null && nowMsProvider() - lastResumeAtMs <= resumeDedupeWindowMs) {
            return cachedOutcome
        }
        return resumeGate.run("resume") {
            val recheckedOutcome = lastResumeOutcome
            if (recheckedOutcome != null && nowMsProvider() - lastResumeAtMs <= resumeDedupeWindowMs) {
                return@run recheckedOutcome
            }
            refresh(
                forceRefresh = true,
            ).also { outcome ->
                lastResumeOutcome = outcome
                lastResumeAtMs = nowMsProvider()
            }
        }
    }

    fun resetLaunchState() {
        launchCompleted = false
        lastLaunchOutcome = null
        lastResumeOutcome = null
        lastResumeAtMs = 0L
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
}
