package com.geovault.tracker.fragments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.Tracker
import com.geovault.tracker.data.TrackerListRepository
import com.geovault.tracker.data.TrackerManagementStateStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class TrackersListUiState(
    val isLoading: Boolean = false,
    val trackers: List<Tracker> = emptyList(),
    val isEmpty: Boolean = false,
    val errorMessage: String? = null
)

@HiltViewModel
class TrackersListViewModel @Inject constructor(
    private val trackerListRepository: TrackerListRepository,
    private val stateStore: TrackerManagementStateStore
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        TrackersListUiState(
            trackers = stateStore.trackers.value,
            isEmpty = stateStore.trackers.value.isEmpty()
        )
    )
    val uiState: StateFlow<TrackersListUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            stateStore.trackers.collectLatest { trackers ->
                setCached(trackers)
            }
        }
    }

    fun setCached(trackers: List<Tracker>) {
        _uiState.value = _uiState.value.copy(
            trackers = trackers,
            isEmpty = trackers.isEmpty()
        )
    }

    fun load(forceRefresh: Boolean = false, showLoading: Boolean = true) {
        if (showLoading) {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        }
        viewModelScope.launch {
            val result = trackerListRepository.loadTrackers(forceRefresh = forceRefresh)
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

