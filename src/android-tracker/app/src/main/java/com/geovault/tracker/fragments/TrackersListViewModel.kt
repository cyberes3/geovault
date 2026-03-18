package com.geovault.tracker.fragments

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.Tracker
import com.geovault.tracker.data.TrackerListRepository
import com.geovault.tracker.data.TrackerRepositoryTrackerListRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TrackersListUiState(
    val isLoading: Boolean = false,
    val trackers: List<Tracker> = emptyList(),
    val isEmpty: Boolean = false,
    val errorMessage: String? = null
)

class TrackersListViewModel(
    private val trackerListRepository: TrackerListRepository = TrackerRepositoryTrackerListRepository()
) : ViewModel() {
    private val _uiState = MutableStateFlow(TrackersListUiState())
    val uiState: StateFlow<TrackersListUiState> = _uiState.asStateFlow()

    fun setCached(trackers: List<Tracker>) {
        _uiState.value = TrackersListUiState(
            isLoading = false,
            trackers = trackers,
            isEmpty = trackers.isEmpty()
        )
    }

    fun load(context: Context, forceRefresh: Boolean = false, showLoading: Boolean = true) {
        if (showLoading) {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        }
        viewModelScope.launch {
            val result = trackerListRepository.loadTrackers(context, forceRefresh)
            _uiState.value = when (result) {
                is RepositoryResult.Success -> {
                    TrackersListUiState(
                        isLoading = false,
                        trackers = result.data,
                        isEmpty = result.data.isEmpty()
                    )
                }
                is RepositoryResult.Failure -> {
                    _uiState.value.copy(
                        isLoading = false,
                        errorMessage = result.error.toString()
                    )
                }
            }
        }
    }
}

