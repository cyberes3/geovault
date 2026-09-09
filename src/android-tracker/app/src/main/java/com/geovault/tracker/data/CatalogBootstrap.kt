package com.geovault.tracker.data

import com.geovault.common.concurrent.SingleFlightGate
import com.geovault.common.concurrent.TimeWindowedSingleFlight
import com.geovault.common.coroutines.runSuspendCatching
import com.geovault.common.net.GeoVaultApiFailure
import com.geovault.tracker.Tracker
import com.geovault.tracker.history.TrunkFetchOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

data class TrackerBootstrapOutcome(
    val isServerAccessible: Boolean,
    val geometryFailure: GeoVaultApiFailure? = null,
    val committedNewDegrade: Boolean = false,
)

interface TrackerBootstrapDataSource {
    suspend fun loadTrackers(forceRefresh: Boolean)
    suspend fun loadGroups(forceRefresh: Boolean)
    suspend fun loadMapVisibility(forceRefresh: Boolean)
}

class RepositoryTrackerBootstrapDataSource(
    private val trackerRepository: TrackerManagementRepository,
    private val groupRepository: GroupManagementRepository,
) : TrackerBootstrapDataSource {
    override suspend fun loadTrackers(forceRefresh: Boolean) {
        trackerRepository.loadTrackers(forceRefresh = forceRefresh)
    }

    override suspend fun loadGroups(forceRefresh: Boolean) {
        groupRepository.loadGroups(forceRefresh = forceRefresh)
    }

    override suspend fun loadMapVisibility(forceRefresh: Boolean) {
        trackerRepository.loadMapVisibility(forceRefresh = forceRefresh)
    }
}

class CatalogBootstrap(
    private val dataSource: TrackerBootstrapDataSource,
    scope: CoroutineScope,
    resumeDedupeWindowMs: Long = 4_000L,
    nowMsProvider: () -> Long = { System.currentTimeMillis() },
    private val catalogTrackers: () -> List<Tracker> = { emptyList() },
    private val fetchCatalogGeometry: (suspend (List<Tracker>) -> TrunkFetchOutcome)? = null,
) {
    private val launchGate = SingleFlightGate<String, TrackerBootstrapOutcome>(scope)
    private val resumeFlight = TimeWindowedSingleFlight<String, TrackerBootstrapOutcome>(
        scope = scope,
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
        return resumeFlight.run(RESUME_CACHE_KEY) {
            refresh(forceRefresh = true)
        }
    }

    fun resetLaunchState() {
        launchCompleted = false
        lastLaunchOutcome = null
        resumeFlight.clear()
    }

    fun resetForSignedOutSession() = resetLaunchState()

    suspend fun runLaunchWarmup(): TrackerBootstrapOutcome = refreshForLaunch()

    suspend fun runResumeWarmup(): TrackerBootstrapOutcome = refreshForResume()

    suspend fun refreshGeometry(): TrunkFetchOutcome {
        val fetch = fetchCatalogGeometry ?: return TrunkFetchOutcome(failure = null, committedNewDegrade = false)
        return fetch(catalogTrackers())
    }

    private suspend fun refresh(forceRefresh: Boolean): TrackerBootstrapOutcome {
        val startupResults = coroutineScope {
            val trackersDef = async { runSuspendCatching { dataSource.loadTrackers(forceRefresh = forceRefresh) } }
            val groupsDef = async { runSuspendCatching { dataSource.loadGroups(forceRefresh = forceRefresh) } }
            val mapVisibilityDef = async { runSuspendCatching { dataSource.loadMapVisibility(forceRefresh = forceRefresh) } }
            listOf(
                trackersDef.await(),
                groupsDef.await(),
                mapVisibilityDef.await(),
            )
        }
        val geometry = refreshGeometry()
        return TrackerBootstrapOutcome(
            isServerAccessible = startupResults.any { it.isSuccess },
            geometryFailure = geometry.failure,
            committedNewDegrade = geometry.committedNewDegrade,
        )
    }

    private companion object {
        const val RESUME_CACHE_KEY = "resume"
    }
}
