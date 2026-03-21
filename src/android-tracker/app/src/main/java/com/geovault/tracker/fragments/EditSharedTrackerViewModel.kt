package com.geovault.tracker.fragments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.tracker.MapVisibilityRequest
import com.geovault.tracker.MapVisibilityResponse
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
    val isLoading: Boolean = false,
    val mapVisibility: MapVisibilityResponse? = null,
    val pendingHiddenTrackerId: String? = null,
    val errorMessage: String? = null,
    val didLeave: Boolean = false
)

@HiltViewModel
class EditSharedTrackerViewModel @Inject constructor(
    private val trackerRepository: TrackerManagementRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(EditSharedTrackerUiState())
    val uiState: StateFlow<EditSharedTrackerUiState> = _uiState.asStateFlow()

    fun load() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            when (val result = trackerRepository.loadMapVisibility(forceRefresh = true)) {
                is RepositoryResult.Success -> {
                    _uiState.update { it.copy(isLoading = false, mapVisibility = result.data) }
                }
                is RepositoryResult.Failure -> {
                    _uiState.update { it.copy(isLoading = false, errorMessage = result.error.toString()) }
                }
            }
        }
    }

    fun setHidden(trackerId: String, hidden: Boolean) {
        val current = _uiState.value.mapVisibility ?: return
        val updated = if (hidden) {
            current.hidden_track_ids + trackerId
        } else {
            current.hidden_track_ids.filterNot { it == trackerId }
        }.distinct()
        val optimistic = current.copy(hidden_track_ids = updated)
        _uiState.update {
            it.copy(
                mapVisibility = optimistic,
                pendingHiddenTrackerId = trackerId,
                errorMessage = null
            )
        }
        viewModelScope.launch {
            when (
                val result = trackerRepository.patchMapVisibility(
                    MapVisibilityRequest(hidden_track_ids = updated)
                )
            ) {
                is RepositoryResult.Success -> {
                    _uiState.update {
                        it.copy(
                            mapVisibility = result.data,
                            pendingHiddenTrackerId = null,
                            errorMessage = null
                        )
                    }
                }
                is RepositoryResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            pendingHiddenTrackerId = null,
                            errorMessage = result.error.toString()
                        )
                    }
                    load()
                }
            }
        }
    }

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
}
