package com.geovault.tracker.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.tracker.AppError
import com.geovault.tracker.GroupPatchRequest
import com.geovault.tracker.MapVisibilityRequest
import com.geovault.tracker.MapVisibilityResponse
import com.geovault.tracker.R
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.TrackerCreateRequest
import com.geovault.tracker.TrackerSettingsRequest
import com.geovault.tracker.data.GroupManagementRepository
import com.geovault.tracker.data.TrackerManagementRepository
import com.geovault.tracker.di.TrackerAppServices
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class TrackersGroupsViewModel(application: Application) : AndroidViewModel(application) {

    private val trackerRepository: TrackerManagementRepository =
        TrackerAppServices.from(application).trackerManagementRepository()
    private val groupRepository: GroupManagementRepository =
        TrackerAppServices.from(application).groupManagementRepository()

    private val _uiState = MutableStateFlow(TrackersGroupsUiState())
    val uiState: StateFlow<TrackersGroupsUiState> = _uiState.asStateFlow()

    fun setSubTab(tab: TrackersGroupsSubTab) {
        _uiState.update { it.copy(subTab = tab) }
    }

    fun clearUserMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }

    fun openCreateTrackerDialog() {
        _uiState.update { it.copy(dialog = TrackersGroupsDialog.CreateTracker()) }
    }

    fun openCreateGroupDialog() {
        _uiState.update { it.copy(dialog = TrackersGroupsDialog.CreateGroup()) }
    }

    fun openEditTrackerDialog(tracker: com.geovault.tracker.Tracker) {
        if (!OwnershipActionPolicy.canEditTracker(tracker)) return
        _uiState.update { it.copy(dialog = TrackersGroupsDialog.EditTracker(tracker, tracker.name)) }
    }

    fun openEditGroupDialog(group: com.geovault.tracker.Group) {
        if (!OwnershipActionPolicy.canEditGroup(group)) return
        _uiState.update { it.copy(dialog = TrackersGroupsDialog.EditGroup(group, group.name)) }
    }

    fun dismissDialog() {
        _uiState.update { it.copy(dialog = TrackersGroupsDialog.Hidden) }
    }

    fun updateCreateTrackerDraft(name: String, color: String) {
        val d = _uiState.value.dialog
        if (d is TrackersGroupsDialog.CreateTracker) {
            _uiState.update { it.copy(dialog = d.copy(nameDraft = name, colorDraft = color)) }
        }
    }

    fun updateCreateGroupDraft(name: String) {
        val d = _uiState.value.dialog
        if (d is TrackersGroupsDialog.CreateGroup) {
            _uiState.update { it.copy(dialog = d.copy(nameDraft = name)) }
        }
    }

    fun updateEditTrackerDraft(name: String) {
        val d = _uiState.value.dialog
        if (d is TrackersGroupsDialog.EditTracker) {
            _uiState.update { it.copy(dialog = d.copy(nameDraft = name)) }
        }
    }

    fun updateEditGroupDraft(name: String) {
        val d = _uiState.value.dialog
        if (d is TrackersGroupsDialog.EditGroup) {
            _uiState.update { it.copy(dialog = d.copy(nameDraft = name)) }
        }
    }

    fun refreshAll() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, userMessage = null) }
            val tDef = async { trackerRepository.loadTrackers(forceRefresh = true) }
            val gDef = async { groupRepository.loadGroups(forceRefresh = true) }
            val vDef = async { trackerRepository.loadMapVisibility(forceRefresh = true) }
            val tr = tDef.await()
            val gr = gDef.await()
            val vr = vDef.await()
            val err = firstError(tr, gr, vr)?.let(::appErrorMessage)
            _uiState.update { s ->
                s.copy(
                    isLoading = false,
                    trackers = tr.successDataOr(s.trackers),
                    groups = gr.successDataOr(s.groups),
                    mapVisibility = vr.successDataOr(s.mapVisibility),
                    userMessage = err,
                )
            }
        }
    }

    fun submitCreateTracker() {
        val d = _uiState.value.dialog as? TrackersGroupsDialog.CreateTracker ?: return
        val name = d.nameDraft.trim()
        if (name.isEmpty()) {
            _uiState.update { it.copy(userMessage = getApplication<Application>().getString(R.string.trackers_validation_name_required)) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, userMessage = null) }
            val color = d.colorDraft.trim().ifEmpty { null }
            when (val r = trackerRepository.createTracker(TrackerCreateRequest(name = name, color = color))) {
                is RepositoryResult.Success -> {
                    dismissDialog()
                    refreshAll()
                }
                is RepositoryResult.Failure -> {
                    _uiState.update { it.copy(isLoading = false, userMessage = appErrorMessage(r.error)) }
                }
            }
        }
    }

    fun submitCreateGroup() {
        val d = _uiState.value.dialog as? TrackersGroupsDialog.CreateGroup ?: return
        val name = d.nameDraft.trim()
        if (name.isEmpty()) {
            _uiState.update { it.copy(userMessage = getApplication<Application>().getString(R.string.trackers_validation_name_required)) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, userMessage = null) }
            when (val r = groupRepository.createGroup(name)) {
                is RepositoryResult.Success -> {
                    dismissDialog()
                    refreshAll()
                }
                is RepositoryResult.Failure -> {
                    _uiState.update { it.copy(isLoading = false, userMessage = appErrorMessage(r.error)) }
                }
            }
        }
    }

    fun submitEditTracker() {
        val d = _uiState.value.dialog as? TrackersGroupsDialog.EditTracker ?: return
        val name = d.nameDraft.trim()
        if (name.isEmpty()) {
            _uiState.update { it.copy(userMessage = getApplication<Application>().getString(R.string.trackers_validation_name_required)) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, userMessage = null) }
            when (
                val r = trackerRepository.updateTrackerSettings(
                    trackerId = d.tracker.id,
                    request = TrackerSettingsRequest(name = name),
                )
            ) {
                is RepositoryResult.Success -> {
                    dismissDialog()
                    refreshAll()
                }
                is RepositoryResult.Failure -> {
                    _uiState.update { it.copy(isLoading = false, userMessage = appErrorMessage(r.error)) }
                }
            }
        }
    }

    fun submitEditGroup() {
        val d = _uiState.value.dialog as? TrackersGroupsDialog.EditGroup ?: return
        val name = d.nameDraft.trim()
        if (name.isEmpty()) {
            _uiState.update { it.copy(userMessage = getApplication<Application>().getString(R.string.trackers_validation_name_required)) }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, userMessage = null) }
            when (val r = groupRepository.patchGroup(d.group.id, GroupPatchRequest(name = name))) {
                is RepositoryResult.Success -> {
                    dismissDialog()
                    refreshAll()
                }
                is RepositoryResult.Failure -> {
                    _uiState.update { it.copy(isLoading = false, userMessage = appErrorMessage(r.error)) }
                }
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

    fun leaveTracker(tracker: com.geovault.tracker.Tracker) {
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

internal fun toggleTrackerInVisibility(current: MapVisibilityResponse, trackerId: String): MapVisibilityRequest {
    val hidden = current.hidden_track_ids.toMutableSet()
    if (hidden.contains(trackerId)) hidden.remove(trackerId) else hidden.add(trackerId)
    return MapVisibilityRequest(
        hidden_track_ids = hidden.toList(),
        hidden_group_ids = current.hidden_group_ids,
    )
}

internal fun toggleGroupInVisibility(current: MapVisibilityResponse, groupId: String): MapVisibilityRequest {
    val hidden = current.hidden_group_ids.toMutableSet()
    if (hidden.contains(groupId)) hidden.remove(groupId) else hidden.add(groupId)
    return MapVisibilityRequest(
        hidden_track_ids = current.hidden_track_ids,
        hidden_group_ids = hidden.toList(),
    )
}
