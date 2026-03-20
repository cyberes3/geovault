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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
                val mergedTrackers = resolveGroupTrackers(groupResult.data, trackersResult.data)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        group = groupResult.data,
                        trackers = mergedTrackers,
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

    private suspend fun resolveGroupTrackers(group: Group, allTrackers: List<Tracker>): List<Tracker> {
        val trackIds = group.track_ids.orEmpty()
        if (trackIds.isEmpty()) return allTrackers

        val existingIds = allTrackers.mapTo(mutableSetOf()) { it.id }
        val missingIds = trackIds.filter { it !in existingIds }.distinct()
        if (missingIds.isEmpty()) return allTrackers

        val fetched = coroutineScope {
            missingIds.map { trackerId ->
                async {
                    when (val result = trackerRepository.loadTracker(trackerId)) {
                        is RepositoryResult.Success -> result.data
                        is RepositoryResult.Failure -> null
                    }
                }
            }.awaitAll().filterNotNull()
        }
        if (fetched.isEmpty()) return allTrackers
        return (allTrackers + fetched).distinctBy { it.id }
    }
}
