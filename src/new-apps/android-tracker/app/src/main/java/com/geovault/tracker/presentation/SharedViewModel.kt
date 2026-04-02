package com.geovault.tracker.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.tracker.AppError
import com.geovault.tracker.R
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.Tracker
import com.geovault.tracker.data.GroupManagementRepository
import com.geovault.tracker.data.TrackerManagementRepository
import com.geovault.tracker.di.TrackerAppServices
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SharedViewModel(application: Application) : AndroidViewModel(application) {

    private val trackerRepository: TrackerManagementRepository =
        TrackerAppServices.from(application).trackerManagementRepository()
    private val groupRepository: GroupManagementRepository =
        TrackerAppServices.from(application).groupManagementRepository()

    private val _uiState = MutableStateFlow(SharedUiState())
    val uiState: StateFlow<SharedUiState> = _uiState.asStateFlow()

    fun setSubTab(tab: SharedSubTab) {
        _uiState.update { it.copy(subTab = tab) }
    }

    fun clearUserMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }

    fun refreshAll() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    userMessage = null,
                )
            }
            val tDef = async { trackerRepository.loadTrackers(forceRefresh = true) }
            val gDef = async { groupRepository.loadGroups(forceRefresh = true) }
            val aDef = async { trackerRepository.loadAvailableToAdd(forceRefresh = true) }
            val vDef = async { trackerRepository.loadMapVisibility(forceRefresh = true) }
            val tr = tDef.await()
            val gr = gDef.await()
            val ar = aDef.await()
            val vr = vDef.await()
            val err = firstError(tr, gr, ar, vr)?.let(::appErrorMessage)
            _uiState.update { s ->
                s.copy(
                    isLoading = false,
                    trackers = tr.successDataOr(s.trackers),
                    groups = gr.successDataOr(s.groups),
                    availableToAdd = ar.successDataOr(s.availableToAdd),
                    mapVisibility = vr.successDataOr(s.mapVisibility),
                    userMessage = err,
                    hasCompletedInitialLoad = true,
                )
            }
        }
    }

    fun toggleTrackerHiddenOnMap(trackerId: String) {
        viewModelScope.launch {
            val base = _uiState.value.mapVisibility ?: when (val r = trackerRepository.loadMapVisibility(forceRefresh = true)) {
                is RepositoryResult.Success -> r.data
                is RepositoryResult.Failure -> {
                    _uiState.update { it.copy(userMessage = appErrorMessage(r.error)) }
                    return@launch
                }
            }
            val req = toggleTrackerInVisibility(base, trackerId)
            _uiState.update { it.copy(isLoading = true, userMessage = null) }
            when (val r = trackerRepository.patchMapVisibility(req)) {
                is RepositoryResult.Success -> {
                    _uiState.update { it.copy(isLoading = false, mapVisibility = r.data) }
                }
                is RepositoryResult.Failure -> {
                    _uiState.update { it.copy(isLoading = false, userMessage = appErrorMessage(r.error)) }
                }
            }
        }
    }

    fun toggleGroupHiddenOnMap(groupId: String) {
        viewModelScope.launch {
            val base = _uiState.value.mapVisibility ?: when (val r = trackerRepository.loadMapVisibility(forceRefresh = true)) {
                is RepositoryResult.Success -> r.data
                is RepositoryResult.Failure -> {
                    _uiState.update { it.copy(userMessage = appErrorMessage(r.error)) }
                    return@launch
                }
            }
            val req = toggleGroupInVisibility(base, groupId)
            _uiState.update { it.copy(isLoading = true, userMessage = null) }
            when (val r = trackerRepository.patchMapVisibility(req)) {
                is RepositoryResult.Success -> {
                    _uiState.update { it.copy(isLoading = false, mapVisibility = r.data) }
                }
                is RepositoryResult.Failure -> {
                    _uiState.update { it.copy(isLoading = false, userMessage = appErrorMessage(r.error)) }
                }
            }
        }
    }

    fun leaveTracker(tracker: Tracker) {
        val kind = OwnershipActionPolicy.trackerLeaveKind(tracker) ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, userMessage = null) }
            val result: RepositoryResult<Unit> = when (kind) {
                TrackerLeaveKind.Unsubscribe -> trackerRepository.unsubscribeTracker(tracker.id)
                TrackerLeaveKind.LeaveShare -> trackerRepository.leaveShareWithMe(tracker.id)
            }
            when (result) {
                is RepositoryResult.Success -> refreshAll()
                is RepositoryResult.Failure -> {
                    _uiState.update { it.copy(isLoading = false, userMessage = appErrorMessage(result.error)) }
                }
            }
        }
    }

    fun leaveGroup(groupId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, userMessage = null) }
            when (val r = groupRepository.leaveGroup(groupId)) {
                is RepositoryResult.Success -> refreshAll()
                is RepositoryResult.Failure -> {
                    _uiState.update { it.copy(isLoading = false, userMessage = appErrorMessage(r.error)) }
                }
            }
        }
    }

    fun acceptGroupShare(groupId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, userMessage = null) }
            when (val r = groupRepository.acceptGroupShare(groupId)) {
                is RepositoryResult.Success -> refreshAll()
                is RepositoryResult.Failure -> {
                    _uiState.update { it.copy(isLoading = false, userMessage = appErrorMessage(r.error)) }
                }
            }
        }
    }

    /** Incoming shared tracker: add to my trackers (subscribe). */
    fun subscribeIncomingTracker(trackerId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, userMessage = null) }
            when (val r = trackerRepository.subscribeTracker(trackerId)) {
                is RepositoryResult.Success -> refreshAll()
                is RepositoryResult.Failure -> {
                    _uiState.update { it.copy(isLoading = false, userMessage = appErrorMessage(r.error)) }
                }
            }
        }
    }

    /** Reject incoming direct share without subscribing. */
    fun leaveIncomingShare(trackerId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, userMessage = null) }
            when (val r = trackerRepository.leaveShareWithMe(trackerId)) {
                is RepositoryResult.Success -> refreshAll()
                is RepositoryResult.Failure -> {
                    _uiState.update { it.copy(isLoading = false, userMessage = appErrorMessage(r.error)) }
                }
            }
        }
    }

    fun subscribePublicTracker(trackerId: String) {
        subscribeIncomingTracker(trackerId)
    }

    /**
     * Subscribe to every addable track in a public group (legacy Discover modal behavior).
     * TODO: Backend may change; if partial subscribe succeeds, we refresh anyway to stay consistent with server state.
     */
    fun subscribePublicGroup(trackIds: List<String>) {
        if (trackIds.isEmpty()) {
            _uiState.update {
                it.copy(userMessage = getApplication<Application>().getString(R.string.shared_error_public_group_no_tracks))
            }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, userMessage = null) }
            for (id in trackIds) {
                when (val r = trackerRepository.subscribeTracker(id)) {
                    is RepositoryResult.Success -> Unit
                    is RepositoryResult.Failure -> {
                        _uiState.update { it.copy(isLoading = false, userMessage = appErrorMessage(r.error)) }
                        return@launch
                    }
                }
            }
            refreshAll()
        }
    }

    /**
     * Remove all subscriptions for tracks in this shared group (user stays in group until [leaveGroup]).
     * TODO: Legacy web offered both "unsubscribe all" and "leave group"; confirm Android parity for membership vs subscriptions.
     */
    fun unsubscribeAllTracksInGroup(trackIds: List<String>) {
        if (trackIds.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, userMessage = null) }
            for (id in trackIds) {
                when (val r = trackerRepository.unsubscribeTracker(id)) {
                    is RepositoryResult.Success -> Unit
                    is RepositoryResult.Failure -> {
                        _uiState.update { it.copy(isLoading = false, userMessage = appErrorMessage(r.error)) }
                        return@launch
                    }
                }
            }
            refreshAll()
        }
    }

    private fun <T> RepositoryResult<T>.successDataOr(fallback: T): T =
        when (this) {
            is RepositoryResult.Success -> data
            is RepositoryResult.Failure -> fallback
        }

    private fun firstError(vararg results: RepositoryResult<*>): AppError? {
        for (r in results) {
            if (r is RepositoryResult.Failure) return r.error
        }
        return null
    }

    private fun appErrorMessage(error: AppError): String {
        val ctx = getApplication<Application>()
        return when (error) {
            AppError.MissingServerUrl -> ctx.getString(R.string.trackers_error_missing_server)
            AppError.Network -> ctx.getString(R.string.trackers_error_network)
            AppError.Unauthorized -> ctx.getString(R.string.trackers_error_unauthorized)
            AppError.NotFound -> ctx.getString(R.string.trackers_error_not_found)
            is AppError.Server -> ctx.getString(R.string.trackers_error_server, error.code)
            is AppError.Validation -> error.message?.takeIf { it.isNotBlank() }
                ?: ctx.getString(R.string.trackers_error_validation)
            AppError.Unknown -> ctx.getString(R.string.trackers_error_unknown)
        }
    }
}
