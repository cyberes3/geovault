package com.geovault.tracker.fragments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.tracker.Group
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.Tracker
import com.geovault.tracker.data.GroupManagementRepository
import com.geovault.tracker.data.TrackerManagementStateStore
import com.geovault.tracker.data.TrackerManagementRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SharedTrackersData(
    val sharedGroups: List<Group> = emptyList(),
    val sharedTrackers: List<Tracker> = emptyList()
)

data class SharedTrackersUiState(
    val isLoading: Boolean = false,
    val data: SharedTrackersData = SharedTrackersData(),
    val errorMessage: String? = null
)

@HiltViewModel
class SharedTrackersViewModel @Inject constructor(
    private val trackerManagementRepository: TrackerManagementRepository,
    private val groupManagementRepository: GroupManagementRepository,
    private val trackerManagementStateStore: TrackerManagementStateStore,
    private val sharedSurfaceFilterUseCase: SharedSurfaceFilterUseCase
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        SharedTrackersUiState(
            data = computeSharedData(
                groups = trackerManagementStateStore.groups.value,
                trackers = trackerManagementStateStore.trackers.value
            )
        )
    )
    val uiState: StateFlow<SharedTrackersUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                trackerManagementStateStore.groups,
                trackerManagementStateStore.trackers
            ) { groups, trackers ->
                computeSharedData(groups = groups, trackers = trackers)
            }.collect { data ->
                _uiState.update { current ->
                    current.copy(data = data)
                }
            }
        }
    }

    fun preload() {
        viewModelScope.launch {
            trackerManagementRepository.loadAvailableToAdd(forceRefresh = false)
            groupManagementRepository.loadGroups(forceRefresh = false)
            trackerManagementRepository.loadTrackers(forceRefresh = false)
        }
    }

    fun refresh(forceRefresh: Boolean, showLoading: Boolean = true) {
        if (showLoading) {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        } else {
            _uiState.update { it.copy(errorMessage = null) }
        }
        viewModelScope.launch {
            val groupsResult = groupManagementRepository.loadGroups(forceRefresh = forceRefresh)
            val trackersResult = trackerManagementRepository.loadTrackers(forceRefresh = forceRefresh)

            val error = when {
                groupsResult is RepositoryResult.Failure -> groupsResult.error.toString()
                trackersResult is RepositoryResult.Failure -> trackersResult.error.toString()
                else -> null
            }

            _uiState.update {
                it.copy(
                    isLoading = false,
                    errorMessage = error
                )
            }
        }
    }

    private fun computeSharedData(
        groups: List<Group>,
        trackers: List<Tracker>
    ): SharedTrackersData {
        val filtered = sharedSurfaceFilterUseCase.filter(groups = groups, trackers = trackers)
        return SharedTrackersData(
            sharedGroups = filtered.sharedGroups,
            sharedTrackers = filtered.sharedTrackers
        )
    }
}
