package com.geovault.tracker.fragments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerCreateRequest
import com.geovault.tracker.data.TrackerManagementRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NewTrackerUiState(
    val isSaving: Boolean = false,
    val createdTracker: Tracker? = null,
    val errorMessage: String? = null
)

@HiltViewModel
class NewTrackerViewModel @Inject constructor(
    private val trackerRepository: TrackerManagementRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(NewTrackerUiState())
    val uiState: StateFlow<NewTrackerUiState> = _uiState.asStateFlow()

    fun createTracker(name: String, color: String?) {
        if (name.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Name is required") }
            return
        }
        _uiState.update { it.copy(isSaving = true, errorMessage = null, createdTracker = null) }
        viewModelScope.launch {
            when (val result = trackerRepository.createTracker(TrackerCreateRequest(name.trim(), color))) {
                is RepositoryResult.Success -> {
                    _uiState.update {
                        it.copy(isSaving = false, createdTracker = result.data, errorMessage = null)
                    }
                }
                is RepositoryResult.Failure -> {
                    _uiState.update {
                        it.copy(isSaving = false, errorMessage = result.error.toString())
                    }
                }
            }
        }
    }

    fun consumeCreatedTracker() {
        _uiState.update { it.copy(createdTracker = null) }
    }
}
