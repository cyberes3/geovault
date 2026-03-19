package com.geovault.tracker.fragments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.tracker.AppError
import com.geovault.tracker.Group
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.Tracker
import com.geovault.tracker.data.GroupManagementRepository
import com.geovault.tracker.data.TrackerManagementRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GroupActionsUiState(
    val isLoading: Boolean = false,
    val group: Group? = null,
    val trackers: List<Tracker> = emptyList(),
    val errorMessage: String? = null
)

@HiltViewModel
class GroupActionsViewModel @Inject constructor(
    private val groupRepository: GroupManagementRepository,
    private val trackerRepository: TrackerManagementRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(GroupActionsUiState())
    val uiState: StateFlow<GroupActionsUiState> = _uiState.asStateFlow()

    fun load(groupId: String) {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val groupResult = groupRepository.loadGroup(groupId)
            val trackersResult = trackerRepository.loadTrackers(forceRefresh = false)
            if (groupResult is RepositoryResult.Success && trackersResult is RepositoryResult.Success) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        group = groupResult.data,
                        trackers = trackersResult.data,
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
                            else -> AppError.Unknown.toString()
                        }
                    )
                }
            }
        }
    }
}
