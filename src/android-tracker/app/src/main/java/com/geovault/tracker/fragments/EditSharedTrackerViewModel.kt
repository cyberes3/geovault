package com.geovault.tracker.fragments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.data.TrackerManagementRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EditSharedTrackerUiState(
    val errorMessage: String? = null,
    val didLeave: Boolean = false
)

@HiltViewModel
class EditSharedTrackerViewModel @Inject constructor(
    private val trackerRepository: TrackerManagementRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(EditSharedTrackerUiState())
    val uiState: StateFlow<EditSharedTrackerUiState> = _uiState.asStateFlow()

    fun unsubscribe(trackerId: String) {
        viewModelScope.launch {
            when (val result = trackerRepository.unsubscribeTracker(trackerId)) {
                is RepositoryResult.Success -> _uiState.update { it.copy(didLeave = true, errorMessage = null) }
                is RepositoryResult.Failure -> _uiState.update { it.copy(errorMessage = result.error.toString()) }
            }
        }
    }

    fun leaveShared(trackerId: String) {
        viewModelScope.launch {
            when (val result = trackerRepository.leaveShareWithMe(trackerId)) {
                is RepositoryResult.Success -> _uiState.update { it.copy(didLeave = true, errorMessage = null) }
                is RepositoryResult.Failure -> _uiState.update { it.copy(errorMessage = result.error.toString()) }
            }
        }
    }

    fun consumeError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
