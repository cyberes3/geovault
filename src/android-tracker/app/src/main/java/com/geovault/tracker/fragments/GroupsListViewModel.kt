package com.geovault.tracker.fragments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.tracker.Group
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.data.GroupManagementRepository
import com.geovault.tracker.data.TrackerManagementStateStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GroupsListUiState(
    val isLoading: Boolean = false,
    val groups: List<Group> = emptyList(),
    val createdGroup: Group? = null,
    val errorMessage: String? = null
)

@HiltViewModel
class GroupsListViewModel @Inject constructor(
    private val groupRepository: GroupManagementRepository,
    private val stateStore: TrackerManagementStateStore
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        GroupsListUiState(groups = mapMyGroups(stateStore.groups.value))
    )
    val uiState: StateFlow<GroupsListUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            stateStore.groups.collectLatest { groups ->
                _uiState.update { it.copy(groups = mapMyGroups(groups)) }
            }
        }
    }

    fun load(forceRefresh: Boolean = false) {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            when (val result = groupRepository.loadGroups(forceRefresh = forceRefresh)) {
                is RepositoryResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            groups = mapMyGroups(result.data),
                            errorMessage = null
                        )
                    }
                }
                is RepositoryResult.Failure -> {
                    _uiState.update { it.copy(isLoading = false, errorMessage = result.error.toString()) }
                }
            }
        }
    }

    fun createGroup(name: String) {
        if (name.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Group name is required") }
            return
        }
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            when (val result = groupRepository.createGroup(name.trim())) {
                is RepositoryResult.Success -> {
                    _uiState.update { current ->
                        current.copy(
                            isLoading = false,
                            createdGroup = result.data,
                            errorMessage = null
                        )
                    }
                }
                is RepositoryResult.Failure -> {
                    _uiState.update { it.copy(isLoading = false, errorMessage = result.error.toString()) }
                }
            }
        }
    }

    fun consumeCreatedGroup() {
        _uiState.update { it.copy(createdGroup = null) }
    }

    private fun mapMyGroups(groups: List<Group>): List<Group> {
        return groups
            .filter { it.is_owner == true && it.hidden != true }
            .sortedBy { it.name.lowercase() }
    }
}
