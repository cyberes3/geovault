package com.geovault.tracker.fragments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.tracker.AppError
import com.geovault.tracker.Group
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.Tracker
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

data class AddGroupTrackersUiState(
    val isLoading: Boolean = false,
    val group: Group? = null,
    val candidates: List<Tracker> = emptyList(),
    val errorMessage: String? = null
)

@HiltViewModel
class AddGroupTrackersViewModel @Inject constructor(
    private val groupRepository: GroupManagementRepository,
    private val trackerRepository: TrackerManagementRepository,
    private val eligibilityUseCase: GroupTrackerEligibilityUseCase
) : ViewModel() {
    private val _uiState = MutableStateFlow(AddGroupTrackersUiState())
    val uiState: StateFlow<AddGroupTrackersUiState> = _uiState.asStateFlow()

    fun load(groupId: String) {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val groupResult = groupRepository.loadGroup(groupId)
            val trackersResult = trackerRepository.loadTrackers(forceRefresh = false)
            if (groupResult is RepositoryResult.Success && trackersResult is RepositoryResult.Success) {
                val candidates = eligibilityUseCase
                    .addableTrackers(trackersResult.data, groupResult.data)
                    .filter { it.canAdd }
                    .map { it.tracker }
                    .sortedBy { it.name.lowercase() }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        group = groupResult.data,
                        candidates = candidates,
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

    fun addTracker(groupId: String, trackerId: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            when (val result = groupRepository.addGroupTrack(groupId, trackerId)) {
                is RepositoryResult.Success -> {
                    val allTrackers = trackerRepository.loadTrackers(forceRefresh = false)
                    val candidates = if (allTrackers is RepositoryResult.Success) {
                        eligibilityUseCase
                            .addableTrackers(allTrackers.data, result.data)
                            .filter { it.canAdd }
                            .map { it.tracker }
                            .sortedBy { it.name.lowercase() }
                    } else {
                        _uiState.value.candidates.filterNot { it.id == trackerId }
                    }
                    _uiState.update {
                        it.copy(group = result.data, candidates = candidates, errorMessage = null)
                    }
                    onResult(true)
                }
                is RepositoryResult.Failure -> {
                    _uiState.update { it.copy(errorMessage = result.error.toString()) }
                    onResult(false)
                }
            }
        }
    }
}
