package com.geovault.tracker.fragments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.data.GroupManagementRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EditSharedGroupUiState(
    val errorMessage: String? = null,
    val didLeave: Boolean = false
)

@HiltViewModel
class EditSharedGroupViewModel @Inject constructor(
    private val groupRepository: GroupManagementRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(EditSharedGroupUiState())
    val uiState: StateFlow<EditSharedGroupUiState> = _uiState.asStateFlow()

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
