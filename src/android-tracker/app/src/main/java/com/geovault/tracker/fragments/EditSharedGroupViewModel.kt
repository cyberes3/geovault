package com.geovault.tracker.fragments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.tracker.MapVisibilityRequest
import com.geovault.tracker.MapVisibilityResponse
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

data class EditSharedGroupUiState(
    val isLoading: Boolean = false,
    val mapVisibility: MapVisibilityResponse? = null,
    val pendingHiddenGroupId: String? = null,
    val errorMessage: String? = null,
    val didLeave: Boolean = false
)

@HiltViewModel
class EditSharedGroupViewModel @Inject constructor(
    private val trackerRepository: TrackerManagementRepository,
    private val groupRepository: GroupManagementRepository
) : ViewModel() {
    companion object {
        const val VISIBILITY_PERSISTENCE_MISMATCH = "group_visibility_persistence_mismatch"
    }

    private val _uiState = MutableStateFlow(EditSharedGroupUiState())
    val uiState: StateFlow<EditSharedGroupUiState> = _uiState.asStateFlow()

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

    fun setHidden(groupId: String, hidden: Boolean) {
        val current = _uiState.value.mapVisibility ?: return
        val updated = if (hidden) {
            current.hidden_group_ids + groupId
        } else {
            current.hidden_group_ids.filterNot { it == groupId }
        }.distinct()
        val optimistic = current.copy(hidden_group_ids = updated)
        _uiState.update {
            it.copy(
                mapVisibility = optimistic,
                pendingHiddenGroupId = groupId,
                errorMessage = null
            )
        }
        viewModelScope.launch {
            when (
                val result = trackerRepository.patchMapVisibility(
                    MapVisibilityRequest(hidden_group_ids = updated)
                )
            ) {
                is RepositoryResult.Success -> {
                    when (val refreshed = trackerRepository.loadMapVisibility(forceRefresh = true)) {
                        is RepositoryResult.Success -> {
                            val persistedHidden = refreshed.data.hidden_group_ids.contains(groupId)
                            if (persistedHidden != hidden) {
                                _uiState.update {
                                    it.copy(
                                        mapVisibility = refreshed.data,
                                        pendingHiddenGroupId = null,
                                        errorMessage = VISIBILITY_PERSISTENCE_MISMATCH
                                    )
                                }
                                return@launch
                            }
                            _uiState.update {
                                it.copy(
                                    mapVisibility = refreshed.data,
                                    pendingHiddenGroupId = null,
                                    errorMessage = null
                                )
                            }
                        }
                        is RepositoryResult.Failure -> {
                            _uiState.update {
                                it.copy(
                                    pendingHiddenGroupId = null,
                                    errorMessage = VISIBILITY_PERSISTENCE_MISMATCH
                                )
                            }
                        }
                    }
                }
                is RepositoryResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            pendingHiddenGroupId = null,
                            errorMessage = result.error.toString()
                        )
                    }
                    load()
                }
            }
        }
    }

    fun leaveGroup(groupId: String) {
        viewModelScope.launch {
            when (val result = groupRepository.leaveGroup(groupId)) {
                is RepositoryResult.Success -> _uiState.update { it.copy(didLeave = true, errorMessage = null) }
                is RepositoryResult.Failure -> _uiState.update { it.copy(errorMessage = result.error.toString()) }
            }
        }
    }

    fun consumeError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
