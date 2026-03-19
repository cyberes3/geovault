package com.geovault.tracker.startup

import android.content.Context
import com.geovault.common.GeovaultAuthManager
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.Tracker
import com.geovault.tracker.data.TrackerManagementRepository
import javax.inject.Inject
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class StartupRefreshInput(
    val selectedTrackerId: String,
    val savedTab: Int
)

data class StartupRefreshResult(
    val serverAccessible: Boolean,
    val selectedTrackerForMap: Tracker?
)

interface StartupRefreshGateway {
    suspend fun fetchUserStatus(context: Context): String?
    suspend fun fetchTrackers(context: Context, forceRefresh: Boolean): List<Tracker>?
    suspend fun fetchSelectedTracker(context: Context, trackerId: String): Tracker?
    suspend fun refreshSelectedTrackerGeometry(context: Context, trackerId: String, allData: Boolean): Tracker?
}

class RepositoryStartupRefreshGateway @Inject constructor(
    private val trackerManagementRepository: TrackerManagementRepository
) : StartupRefreshGateway {
    override suspend fun fetchUserStatus(context: Context): String? =
        suspendCancellableCoroutine { continuation ->
            GeovaultAuthManager.fetchUserStatus(context) { email ->
                continuation.resume(email)
            }
        }

    override suspend fun fetchTrackers(context: Context, forceRefresh: Boolean): List<Tracker>? =
        when (val result = trackerManagementRepository.loadTrackers(forceRefresh = forceRefresh)) {
            is RepositoryResult.Success -> result.data
            is RepositoryResult.Failure -> null
        }

    override suspend fun fetchSelectedTracker(context: Context, trackerId: String): Tracker? =
        when (val result = trackerManagementRepository.loadTracker(trackerId)) {
            is RepositoryResult.Success -> result.data
            is RepositoryResult.Failure -> null
        }

    override suspend fun refreshSelectedTrackerGeometry(
        context: Context,
        trackerId: String,
        allData: Boolean
    ): Tracker? = when (val result = trackerManagementRepository.loadTrackerGeometry(trackerId, allData = allData)) {
        is RepositoryResult.Success -> result.data
        is RepositoryResult.Failure -> null
    }
}

class StartupRefreshOrchestrator(
    private val gateway: StartupRefreshGateway
) {
    suspend fun run(context: Context, input: StartupRefreshInput): StartupRefreshResult {
        // Keep startup refresh deterministic: exactly one user-status and trackers fetch.
        gateway.fetchUserStatus(context)
        val trackers = gateway.fetchTrackers(context, forceRefresh = true)
        val selectedTrackerId = input.selectedTrackerId
        if (selectedTrackerId.isNotBlank()) {
            coroutineScope {
                launch { gateway.fetchSelectedTracker(context, selectedTrackerId) }
                launch { gateway.refreshSelectedTrackerGeometry(context, selectedTrackerId, allData = true) }
            }
        }
        val selectedTrackerForMap = if (input.savedTab == 1 && selectedTrackerId.isNotBlank()) {
            trackers?.find { it.id == selectedTrackerId }
        } else {
            null
        }
        return StartupRefreshResult(
            serverAccessible = trackers != null,
            selectedTrackerForMap = selectedTrackerForMap
        )
    }
}
