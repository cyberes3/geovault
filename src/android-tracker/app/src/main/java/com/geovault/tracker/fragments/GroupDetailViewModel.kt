package com.geovault.tracker.fragments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.tracker.AppError
import com.geovault.tracker.Group
import com.geovault.tracker.GroupPatchRequest
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.Tracker
import com.geovault.tracker.UserItem
import com.geovault.tracker.data.GroupManagementRepository
import com.geovault.tracker.data.GroupTrackerEligibilityUseCase
import com.geovault.tracker.data.TrackerManagementRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class GroupDetailPhase {
    Loading,
    Ready,
    Saving,
    Saved,
    Deleting,
    Deleted
}

data class GroupDetailFormState(
    val groupId: String = "",
    val name: String = "",
    val visibility: String = "private",
    val sharedWithEmails: List<String> = emptyList(),
    val worldShareEnabled: Boolean = false,
    val worldShareUrl: String? = null,
    val hiddenInList: Boolean = false
) {
    fun toRequest(): GroupPatchRequest {
        return GroupPatchRequest(
            name = name.trim(),
            hidden_in_list = hiddenInList,
            visibility = visibility,
            shared_with_emails = if (visibility == "shared") sharedWithEmails else null,
            world_share_enabled = worldShareEnabled
        )
    }
}

data class GroupDetailUiState(
    val phase: GroupDetailPhase = GroupDetailPhase.Loading,
    val group: Group? = null,
    val form: GroupDetailFormState = GroupDetailFormState(),
    val initialSnapshot: GroupDetailFormState? = null,
    val allTrackers: List<Tracker> = emptyList(),
    val addableTrackers: List<Tracker> = emptyList(),
    val users: List<UserItem> = emptyList(),
    val errorMessage: String? = null
)

@HiltViewModel
class GroupDetailViewModel @Inject constructor(
    private val groupRepository: GroupManagementRepository,
    private val trackerRepository: TrackerManagementRepository,
    private val eligibilityUseCase: GroupTrackerEligibilityUseCase
) : ViewModel() {
    private val _uiState = MutableStateFlow(GroupDetailUiState())
    val uiState: StateFlow<GroupDetailUiState> = _uiState.asStateFlow()

    private fun toFormState(group: Group): GroupDetailFormState {
        return GroupDetailFormState(
            groupId = group.id,
            name = group.name,
            visibility = group.visibility ?: "private",
            sharedWithEmails = group.shared_with_emails ?: emptyList(),
            worldShareEnabled = !group.world_share_id.isNullOrBlank(),
            worldShareUrl = group.world_share_url,
            hiddenInList = group.hidden_in_list == true
        )
    }

    fun load(groupId: String) {
        _uiState.update { it.copy(phase = GroupDetailPhase.Loading, errorMessage = null) }
        viewModelScope.launch {
            val groupResult = groupRepository.loadGroup(groupId)
            val trackersResult = trackerRepository.loadTrackers(forceRefresh = false)
            val usersResult = trackerRepository.loadUsers()
            if (groupResult is RepositoryResult.Success && trackersResult is RepositoryResult.Success) {
                val addable = eligibilityUseCase
                    .addableTrackers(trackersResult.data, groupResult.data)
                    .filter { it.canAdd }
                    .map { it.tracker }
                val form = toFormState(groupResult.data)
                _uiState.update {
                    it.copy(
                        phase = GroupDetailPhase.Ready,
                        group = groupResult.data,
                        form = form,
                        initialSnapshot = it.initialSnapshot ?: form,
                        allTrackers = trackersResult.data,
                        addableTrackers = addable,
                        users = if (usersResult is RepositoryResult.Success) usersResult.data.users else emptyList(),
                        errorMessage = null
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        phase = GroupDetailPhase.Ready,
                        errorMessage = when {
                            groupResult is RepositoryResult.Failure -> groupResult.error.toString()
                            trackersResult is RepositoryResult.Failure -> trackersResult.error.toString()
                            else -> AppError.Unknown.toString()
                        }
                    )
                }
            }
        }
    }

    fun onNameChanged(value: String) = _uiState.update { it.copy(form = it.form.copy(name = value)) }
    fun onVisibilityChanged(value: String) = _uiState.update { it.copy(form = it.form.copy(visibility = value)) }
    fun onSharedWithEmailsChanged(value: List<String>) = _uiState.update { it.copy(form = it.form.copy(sharedWithEmails = value)) }
    fun onHiddenInListChanged(value: Boolean) = _uiState.update { it.copy(form = it.form.copy(hiddenInList = value)) }
    fun onWorldShareEnabledChanged(value: Boolean) = _uiState.update { it.copy(form = it.form.copy(worldShareEnabled = value)) }

    fun saveGroup() {
        val groupId = _uiState.value.form.groupId
        if (groupId.isBlank()) return
        val request = _uiState.value.form.toRequest()
        _uiState.update { it.copy(phase = GroupDetailPhase.Saving, errorMessage = null) }
        viewModelScope.launch {
            when (val result = groupRepository.patchGroup(groupId, request, publishToStore = false)) {
                is RepositoryResult.Success -> {
                    val trackers = _uiState.value.allTrackers
                    val addable = eligibilityUseCase
                        .addableTrackers(trackers, result.data)
                        .filter { it.canAdd }
                        .map { it.tracker }
                    val form = toFormState(result.data)
                    _uiState.update {
                        it.copy(
                            phase = GroupDetailPhase.Saved,
                            group = result.data,
                            form = form,
                            initialSnapshot = form,
                            addableTrackers = addable
                        )
                    }
                }
                is RepositoryResult.Failure -> {
                    _uiState.update { it.copy(phase = GroupDetailPhase.Ready, errorMessage = result.error.toString()) }
                }
            }
        }
    }

    fun enableWorldShare() {
        val state = _uiState.value
        val groupId = state.form.groupId
        if (groupId.isBlank()) return
        _uiState.update { it.copy(phase = GroupDetailPhase.Saving, errorMessage = null) }
        viewModelScope.launch {
            when (
                val result = groupRepository.patchGroup(
                    groupId = groupId,
                    request = GroupPatchRequest(world_share_enabled = true),
                    publishToStore = false
                )
            ) {
                is RepositoryResult.Success -> {
                    _uiState.update {
                        it.copy(
                            phase = GroupDetailPhase.Ready,
                            group = result.data,
                            form = it.form.copy(
                                worldShareEnabled = true,
                                worldShareUrl = result.data.world_share_url
                            ),
                            errorMessage = null
                        )
                    }
                }
                is RepositoryResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            phase = GroupDetailPhase.Ready,
                            form = it.form.copy(worldShareEnabled = false),
                            errorMessage = result.error.toString()
                        )
                    }
                }
            }
        }
    }

    fun deleteGroup(groupId: String) {
        _uiState.update { it.copy(phase = GroupDetailPhase.Deleting, errorMessage = null) }
        viewModelScope.launch {
            when (val result = groupRepository.deleteGroup(groupId)) {
                is RepositoryResult.Success -> {
                    _uiState.update { it.copy(phase = GroupDetailPhase.Deleted) }
                }
                is RepositoryResult.Failure -> {
                    _uiState.update { it.copy(phase = GroupDetailPhase.Ready, errorMessage = result.error.toString()) }
                }
            }
        }
    }

    fun hasUnsavedChanges(): Boolean {
        val state = _uiState.value
        val initial = state.initialSnapshot ?: return false
        return state.form != initial
    }
}
