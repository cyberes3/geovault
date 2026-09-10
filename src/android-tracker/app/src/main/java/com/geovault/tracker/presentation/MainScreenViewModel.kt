package com.geovault.tracker.presentation

import android.app.Application
import com.geovault.common.logging.GeoVaultCaptureLog
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.common.auth.GeoVaultAuthSession
import com.geovault.common.auth.GeoVaultAccountUiState
import com.geovault.common.net.GeoVaultConnectivity
import com.geovault.common.update.GeoVaultAndroidReleaseIdentity
import com.geovault.common.update.GeoVaultAppUpdatePromptBinding
import com.geovault.common.update.VersionCheckResult
import com.geovault.tracker.BuildConfig
import com.geovault.tracker.di.TrackerAppServices
import com.geovault.common.net.GeoVaultApiFailureMessages
import com.geovault.tracker.data.TrackerBootstrapOutcome
import com.geovault.tracker.data.CatalogBootstrap
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MainScreenState(
    val isServerAccessible: Boolean = true,
    val updateAvailable: VersionCheckResult.UpdateAvailable? = null,
    val mapRecoveryRequestToken: Long = 0L,
)

class MainScreenViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application
    private val sessionWarmup: CatalogBootstrap =
        TrackerAppServices.from(application).catalogBootstrap()
    private val updatePromptBinding = GeoVaultAppUpdatePromptBinding(
        GeoVaultAndroidReleaseIdentity.Tracker.updateCoordinator(
            application = application,
            localFullCommitSha = { BuildConfig.GIT_COMMIT_SHA },
        )
    )

    private val launchBootstrapMutex = Mutex()
    private val transportProbeMutex = Mutex()
    private var activeLaunchBootstrap: Deferred<TrackerBootstrapOutcome>? = null

    private val _state = MutableStateFlow(MainScreenState())
    val state: StateFlow<MainScreenState> = _state.asStateFlow()

    private val _pendingOpenAllTrackersOnMap = MutableStateFlow(false)
    val pendingOpenAllTrackersOnMap: StateFlow<Boolean> = _pendingOpenAllTrackersOnMap.asStateFlow()

    fun requestOpenAllTrackersOnMapFromIntent() {
        _pendingOpenAllTrackersOnMap.value = true
    }

    fun consumePendingOpenAllTrackersOnMap() {
        _pendingOpenAllTrackersOnMap.value = false
    }
    private var startupRefreshHandled = false
    private var startupRefreshJob: Job? = null
    private var resumeBootstrapJob: Job? = null
    private var isLoggedIn: Boolean = false
    private var configuredServerUrl: String = ""

    private val validatedInternetNotifier =
        GeoVaultConnectivity.RecoveryMonitor(app) {
            viewModelScope.launch {
                val runBootstrap = transportProbeMutex.withLock {
                    if (!isLoggedIn || _state.value.isServerAccessible) {
                        return@withLock false
                    }
                    val ok = measureLaunchTransportReachable()
                    if (!ok) return@withLock false
                    _state.update { it.copy(isServerAccessible = true) }
                    GeoVaultCaptureLog.d(TAG, "transport_probe_validated_network reachable=true")
                    // Launch bootstrap already runs resume-scale I/O; avoid doubling work mid-flight.
                    if (activeLaunchBootstrap?.isActive == true) {
                        GeoVaultCaptureLog.d(TAG, "transport_probe_validated_network skip_resume launch_bootstrap_active")
                        return@withLock false
                    }
                    true
                }
                if (runBootstrap) {
                    emitGeometryFailureIfNeeded(sessionWarmup.runResumeWarmup())
                }
            }
        }

    init {
        updatePromptBinding.collect(viewModelScope) { prompt ->
            _state.update { it.copy(updateAvailable = prompt) }
        }
        viewModelScope.launch {
            state.collect { s ->
                if (isLoggedIn && !s.isServerAccessible) {
                    validatedInternetNotifier.start()
                } else {
                    validatedInternetNotifier.stop()
                }
            }
        }
    }

    fun initialize() {
        launchPostAuthStartupFlowsIfNeeded()
    }

    fun onAccountStateChanged(accountState: GeoVaultAccountUiState) {
        val wasAuthenticated = isLoggedIn
        isLoggedIn = accountState.isLoggedIn
        configuredServerUrl = accountState.serverUrl
        if (wasAuthenticated != isLoggedIn) {
            resetPostAuthStartupState()
        }
        launchPostAuthStartupFlowsIfNeeded()
    }

    fun onHostResumed() {
        launchPostAuthStartupFlowsIfNeeded()
        scheduleResumeBootstrapAfterStartup()
    }

    fun showExternalError(message: String) {
        emitHostMessage(message)
    }

    fun clearUpdateAvailable() {
        updatePromptBinding.dismissPrompt()
    }

    fun requestMapRecoveryAfterStreamingStop() {
        _state.update { it.copy(mapRecoveryRequestToken = it.mapRecoveryRequestToken + 1L) }
    }

    fun consumeMapRecoveryRequest(token: Long) {
        if (_state.value.mapRecoveryRequestToken == token) {
            _state.update { it.copy(mapRecoveryRequestToken = 0L) }
        }
    }

    private fun launchPostAuthStartupFlowsIfNeeded() {
        if (!isLoggedIn) return
        launchStartupRefreshIfNeeded()
        launchVersionCheckIfNeeded()
    }

    private fun launchStartupRefreshIfNeeded() {
        if (startupRefreshHandled || startupRefreshJob?.isActive == true) return
        startupRefreshJob = viewModelScope.launch {
            startupRefreshHandled = true
            runAuthenticatedLaunchBootstrap()
        }
    }

    /**
     * Runs launch-time transport reachability (unauthenticated `/api/health/`) plus launch-scale
     * bootstrap once; concurrent callers await the same work.
     * Safe to call from [com.geovault.tracker.ui.MainScreen] and from internal startup paths.
     */
    suspend fun runAuthenticatedLaunchBootstrap(): TrackerBootstrapOutcome = coroutineScope {
        lateinit var self: Deferred<TrackerBootstrapOutcome>
        val deferred = launchBootstrapMutex.withLock {
            val existing = activeLaunchBootstrap
            if (existing != null && existing.isActive) {
                return@withLock existing
            }
            val created = async {
                try {
                    val transportReachable = measureLaunchTransportReachableExclusive()
                    GeoVaultCaptureLog.d(TAG, "transport_probe_launch reachable=$transportReachable")
                    // Apply immediately so offline overlay / notifier match transport (do not wait for launch I/O).
                    _state.update { it.copy(isServerAccessible = transportReachable) }
                    val outcome = sessionWarmup.runLaunchWarmup()
                    emitGeometryFailureIfNeeded(outcome)
                    outcome
                } finally {
                    launchBootstrapMutex.withLock {
                        if (activeLaunchBootstrap === self) {
                            activeLaunchBootstrap = null
                        }
                    }
                }
            }
            self = created
            activeLaunchBootstrap = created
            created
        }
        deferred.await()
    }

    private fun scheduleResumeBootstrapAfterStartup() {
        if (!isLoggedIn) return
        resumeBootstrapJob?.cancel()
        resumeBootstrapJob = viewModelScope.launch {
            startupRefreshJob?.join()
            logConfiguredServerHost("transport_probe_on_resume_pre")
            if (!_state.value.isServerAccessible) {
                val reachable = measureLaunchTransportReachableExclusive()
                _state.update { it.copy(isServerAccessible = reachable) }
                GeoVaultCaptureLog.d(TAG, "transport_probe_on_resume reachable=$reachable")
            } else {
                // Avoid a flaky probe undoing a validated-network recovery that beat this coroutine.
                GeoVaultCaptureLog.d(TAG, "transport_probe_on_resume skip_probe already_accessible")
            }
            emitGeometryFailureIfNeeded(sessionWarmup.runResumeWarmup())
        }
    }

    /**
     * Unauthenticated GET to `/api/health/` — any HTTP response means the host was reached.
     * Runs once during authenticated launch bootstrap only (see [runAuthenticatedLaunchBootstrap]).
     */
    private suspend fun measureLaunchTransportReachable(): Boolean =
        GeoVaultAuthSession.get().probeServerTransportReachable()

    private suspend fun measureLaunchTransportReachableExclusive(): Boolean =
        transportProbeMutex.withLock { measureLaunchTransportReachable() }

    private fun logConfiguredServerHost(reason: String) {
        val raw = configuredServerUrl.trim()
        val host = runCatching { java.net.URI(raw).host }.getOrNull().orEmpty()
        GeoVaultCaptureLog.d(TAG, "$reason configuredHost=$host len=${raw.length}")
    }

    private fun launchVersionCheckIfNeeded() {
        updatePromptBinding.onAuthenticated(viewModelScope)
    }

    private fun resetPostAuthStartupState() {
        sessionWarmup.resetForSignedOutSession()
        startupRefreshHandled = false
        updatePromptBinding.onSignedOut()
        startupRefreshJob?.cancel()
        startupRefreshJob = null
        resumeBootstrapJob?.cancel()
        resumeBootstrapJob = null
        _state.update {
            it.copy(
                isServerAccessible = true,
                updateAvailable = null,
                mapRecoveryRequestToken = 0L,
            )
        }
    }

    override fun onCleared() {
        validatedInternetNotifier.stop()
        super.onCleared()
    }

    private fun emitGeometryFailureIfNeeded(outcome: TrackerBootstrapOutcome) {
        val failure = outcome.geometryFailure ?: return
        GeoVaultCaptureLog.w(TAG, "Catalog geometry fetch failed error=$failure", failure)
        emitHostMessage(GeoVaultApiFailureMessages.format(failure))
    }

    private fun emitHostMessage(message: String) {
        TrackerAppServices.from(app).uiEffects().emitMessage(message)
    }

    private companion object {
        private const val TAG = "MainScreenViewModel"
    }
}
