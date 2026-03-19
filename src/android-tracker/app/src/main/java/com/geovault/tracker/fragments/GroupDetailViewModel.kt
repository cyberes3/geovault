package com.geovault.tracker.fragments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

data class GroupDetailUiState(
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val isDeleting: Boolean = false,
    val group: Group? = null,
    val allTrackers: List<Tracker> = emptyList(),
    val addableTrackers: List<Tracker> = emptyList(),
    val users: List<UserItem> = emptyList(),
    val deletedGroupId: String? = null,
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

    fun load(groupId: String) {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val groupResult = groupRepository.loadGroup(groupId)
            val trackersResult = trackerRepository.loadTrackers(forceRefresh = false)
            val usersResult = trackerRepository.loadUsers()
            if (groupResult is RepositoryResult.Success && trackersResult is RepositoryResult.Success) {
                val addable = eligibilityUseCase
                    .addableTrackers(trackersResult.data, groupResult.data)
                    .filter { it.canAdd }
                    .map { it.tracker }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        group = groupResult.data,
                        allTrackers = trackersResult.data,
                        addableTrackers = addable,
                        users = if (usersResult is RepositoryResult.Success) usersResult.data.users else emptyList(),
                        errorMessage = null
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = when {
                            groupResult is RepositoryResult.Failure -> groupResult.error.toString()
                            trackersResult is RepositoryResult.Failure -> trackersResult.error.toString()
                            else -> "Failed to load group"
                        }
                    )
                }
            }
        }
    }

    fun saveGroup(groupId: String, request: GroupPatchRequest) {
        _uiState.update { it.copy(isSaving = true, errorMessage = null) }
        viewModelScope.launch {
            when (val result = groupRepository.patchGroup(groupId, request)) {
                is RepositoryResult.Success -> {
                    val trackers = _uiState.value.allTrackers
                    val addable = eligibilityUseCase
                        .addableTrackers(trackers, result.data)
                        .filter { it.canAdd }
                        .map { it.tracker }
                    _uiState.update {
                        it.copy(isSaving = false, group = result.data, addableTrackers = addable)
                    }
                }
                is RepositoryResult.Failure -> {
                    _uiState.update { it.copy(isSaving = false, errorMessage = result.error.toString()) }
                }
            }
        }
    }

    fun deleteGroup(groupId: String) {
        _uiState.update { it.copy(isDeleting = true, errorMessage = null) }
        viewModelScope.launch {
            when (val result = groupRepository.deleteGroup(groupId)) {
                is RepositoryResult.Success -> {
                    _uiState.update { it.copy(isDeleting = false, deletedGroupId = groupId) }
                }
                is RepositoryResult.Failure -> {
                    _uiState.update { it.copy(isDeleting = false, errorMessage = result.error.toString()) }
                }
            }
        }
    }

    fun consumeDeleted() {
        _uiState.update { it.copy(deletedGroupId = null) }
    }
}
