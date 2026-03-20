package com.geovault.tracker.fragments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerSettingsRequest
import com.geovault.tracker.UserItem
import com.geovault.tracker.data.TrackerManagementRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EditTrackerUiState(
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val tracker: Tracker? = null,
    val users: List<UserItem> = emptyList(),
    val didDelete: Boolean = false,
    val kmlBytes: ByteArray? = null,
    val errorMessage: String? = null
)

@HiltViewModel
class EditTrackerViewModel @Inject constructor(
    private val trackerRepository: TrackerManagementRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(EditTrackerUiState())
    val uiState: StateFlow<EditTrackerUiState> = _uiState.asStateFlow()

    fun load(trackerId: String) {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val trackerResult = trackerRepository.loadTracker(trackerId)
            val usersResult = trackerRepository.loadUsers()
            if (trackerResult is RepositoryResult.Success) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        tracker = trackerResult.data,
                        users = if (usersResult is RepositoryResult.Success) usersResult.data.users else emptyList(),
                        errorMessage = null
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = (trackerResult as RepositoryResult.Failure).error.toString()
                    )
                }
            }
        }
    }

    fun save(trackerId: String, request: TrackerSettingsRequest) {
        _uiState.update { it.copy(isSaving = true, errorMessage = null) }
        viewModelScope.launch {
            when (val result = trackerRepository.updateTrackerSettings(trackerId, request)) {
                is RepositoryResult.Success -> {
                    _uiState.update { it.copy(isSaving = false, tracker = result.data, errorMessage = null) }
                }
                is RepositoryResult.Failure -> {
                    _uiState.update { it.copy(isSaving = false, errorMessage = result.error.toString()) }
                }
            }
        }
    }

    fun deleteTracker(trackerId: String) {
        viewModelScope.launch {
            when (val result = trackerRepository.deleteTracker(trackerId)) {
                is RepositoryResult.Success -> _uiState.update { it.copy(didDelete = true, errorMessage = null) }
                is RepositoryResult.Failure -> _uiState.update { it.copy(errorMessage = result.error.toString()) }
            }
        }
    }

    fun exportKml(trackerId: String) {
        viewModelScope.launch {
            when (val result = trackerRepository.fetchTrackerKml(trackerId)) {
                is RepositoryResult.Success -> _uiState.update { it.copy(kmlBytes = result.data, errorMessage = null) }
                is RepositoryResult.Failure -> _uiState.update { it.copy(errorMessage = result.error.toString()) }
            }
        }
    }

    fun consumeKml() {
        _uiState.update { it.copy(kmlBytes = null) }
    }
}
