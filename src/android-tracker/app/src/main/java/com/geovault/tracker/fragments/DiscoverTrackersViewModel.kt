package com.geovault.tracker.fragments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.tracker.AvailableToAddGroup
import com.geovault.tracker.AvailableToAddItem
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.data.GroupManagementRepository
import com.geovault.tracker.data.TrackerManagementRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DiscoverTrackersUiState(
    val isLoading: Boolean = false,
    val onMyMapTrackers: List<AvailableToAddItem> = emptyList(),
    val onMyMapGroups: List<AvailableToAddGroup> = emptyList(),
    val incomingTrackers: List<AvailableToAddItem> = emptyList(),
    val incomingSharedGroups: List<AvailableToAddGroup> = emptyList(),
    val errorMessage: String? = null
)

@HiltViewModel
class DiscoverTrackersViewModel @Inject constructor(
    private val trackerManagementRepository: TrackerManagementRepository,
    private val groupManagementRepository: GroupManagementRepository,
    private val sharedSurfaceFilterUseCase: SharedSurfaceFilterUseCase
) : ViewModel() {
    private val _uiState = MutableStateFlow(DiscoverTrackersUiState(isLoading = true))
    val uiState: StateFlow<DiscoverTrackersUiState> = _uiState.asStateFlow()

    fun load(forceRefresh: Boolean, showLoading: Boolean = true) {
        if (showLoading) {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        }
        viewModelScope.launch {
            val availableResult = trackerManagementRepository.loadAvailableToAdd(forceRefresh = forceRefresh)
            if (availableResult is RepositoryResult.Failure) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        onMyMapTrackers = emptyList(),
                        onMyMapGroups = emptyList(),
                        incomingTrackers = emptyList(),
                        incomingSharedGroups = emptyList(),
                        errorMessage = availableResult.error.toString()
                    )
                }
                return@launch
            }
            val available = (availableResult as RepositoryResult.Success).data

            val groupsResult = groupManagementRepository.loadGroups(forceRefresh = forceRefresh)
            val trackersResult = trackerManagementRepository.loadTrackers(forceRefresh = forceRefresh)
            val groups = (groupsResult as? RepositoryResult.Success)?.data ?: emptyList()
            val trackers = (trackersResult as? RepositoryResult.Success)?.data ?: emptyList()

            val filtered = sharedSurfaceFilterUseCase.filter(groups = groups, trackers = trackers)
            val onMyMapTrackers = filtered.sharedTrackers
                .sortedWith(compareBy({ it.subscribed_at ?: Long.MAX_VALUE }, { it.name.lowercase() }))
                .map {
                AvailableToAddItem(
                    id = it.id,
                    name = it.name,
                    color = it.color,
                    owner_email = it.owner_email
                )
            }
            val onMyMapGroups = filtered.sharedGroups.map {
                AvailableToAddGroup(
                    id = it.id,
                    name = it.name,
                    owner_email = it.owner_email,
                    track_ids = it.track_ids ?: emptyList()
                )
            }
            // Pending shared groups must never expose per-track items pre-acceptance.
            val incomingSharedGroups = available.shared_with_me_groups.map { it.copy(track_ids = emptyList()) }

            val error = when {
                groupsResult is RepositoryResult.Failure -> groupsResult.error.toString()
                trackersResult is RepositoryResult.Failure -> trackersResult.error.toString()
                else -> null
            }
            _uiState.update {
                it.copy(
                    isLoading = false,
                    onMyMapTrackers = onMyMapTrackers,
                    onMyMapGroups = onMyMapGroups,
                    incomingTrackers = available.shared_with_me,
                    incomingSharedGroups = incomingSharedGroups,
                    errorMessage = error
                )
            }
        }
    }
}
