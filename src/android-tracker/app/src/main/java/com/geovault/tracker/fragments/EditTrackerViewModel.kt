package com.geovault.tracker.fragments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerSettingsRequest
import com.geovault.tracker.UserItem
import com.geovault.tracker.data.TrackerManagementRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class EditTrackerPhase {
    Loading,
    Ready,
    Saving,
    Saved
}

data class EditTrackerFormState(
    val trackerId: String = "",
    val name: String = "",
    val color: String = "",
    val isDefaultTrack: Boolean = false,
    val recentDataWindow: String = "",
    val visibility: String = "private",
    val sharedWithEmails: List<String> = emptyList(),
    val shareParamsWithRecipients: Boolean = false,
    val allowGroupReshare: Boolean = false,
    val shareParamsWithWorld: Boolean = false,
    val worldShareEnabled: Boolean = false,
    val worldShareUrl: String? = null,
    val hiddenInList: Boolean = false,
    val isOwner: Boolean = false
) {
    fun toRequest(): TrackerSettingsRequest {
        return TrackerSettingsRequest(
            name = name.trim(),
            color = color.trim().ifBlank { null },
            recent_data_window = recentDataWindow.ifBlank { null },
            visibility = if (isOwner) visibility else null,
            share_params_with_recipients = if (isOwner) shareParamsWithRecipients else null,
            share_params_with_world = if (isOwner) shareParamsWithWorld else null,
            shared_with_emails = if (isOwner && visibility == "shared") sharedWithEmails else null,
            world_share_enabled = if (isOwner) worldShareEnabled else null,
            allow_group_reshare = if (isOwner) allowGroupReshare else null,
            hidden_in_list = hiddenInList
        )
    }
}

data class EditTrackerUiState(
    val phase: EditTrackerPhase = EditTrackerPhase.Loading,
    val form: EditTrackerFormState = EditTrackerFormState(),
    val initialSnapshot: EditTrackerFormState? = null,
    val users: List<UserItem> = emptyList(),
    val didDelete: Boolean = false,
    val didClearHistory: Boolean = false,
    val kmlBytes: ByteArray? = null,
    val errorMessage: String? = null
)

@HiltViewModel
class EditTrackerViewModel @Inject constructor(
    private val trackerRepository: TrackerManagementRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(EditTrackerUiState())
    val uiState: StateFlow<EditTrackerUiState> = _uiState.asStateFlow()

    private fun toFormState(
        tracker: Tracker,
        defaultColorHex: String,
        isDefaultTrack: Boolean
    ): EditTrackerFormState {
        val recentDataWindow = (tracker.settings?.get("recent_data_window") as? String) ?: ""
        val visibility = tracker.visibility ?: "private"
        val allowReshare = (tracker.settings as? Map<*, *>)?.get("allow_group_reshare") == true
        val worldShareEnabled = !tracker.world_share_url.isNullOrBlank()
        val color = tracker.color ?: defaultColorHex
        return EditTrackerFormState(
            trackerId = tracker.id,
            name = tracker.name,
            color = color,
            isDefaultTrack = isDefaultTrack,
            recentDataWindow = recentDataWindow,
            visibility = visibility,
            sharedWithEmails = tracker.shared_with_emails ?: emptyList(),
            shareParamsWithRecipients = tracker.share_params_with_recipients == true,
            allowGroupReshare = allowReshare,
            shareParamsWithWorld = tracker.share_params_with_world == true,
            worldShareEnabled = worldShareEnabled,
            worldShareUrl = tracker.world_share_url,
            hiddenInList = (tracker.settings?.get("hidden_in_list") as? Boolean) == true,
            isOwner = tracker.isOwner()
        )
    }

    fun bindInitialTracker(
        tracker: Tracker,
        defaultColorHex: String,
        isDefaultTrack: Boolean
    ) {
        if (_uiState.value.initialSnapshot != null) return
        val form = toFormState(tracker, defaultColorHex, isDefaultTrack)
        _uiState.update {
            it.copy(
                phase = EditTrackerPhase.Ready,
                form = form,
                initialSnapshot = form,
                errorMessage = null
            )
        }
    }

    fun load(trackerId: String) {
        _uiState.update { it.copy(phase = EditTrackerPhase.Loading, errorMessage = null) }
        viewModelScope.launch {
            val trackerResult = trackerRepository.loadTracker(trackerId)
            val usersResult = trackerRepository.loadUsers()
            if (trackerResult is RepositoryResult.Success) {
                val prior = _uiState.value
                val defaultColor = prior.form.color.ifBlank { "#1E88E5" }
                val form = toFormState(
                    tracker = trackerResult.data,
                    defaultColorHex = defaultColor,
                    isDefaultTrack = prior.form.isDefaultTrack
                )
                _uiState.update {
                    it.copy(
                        phase = EditTrackerPhase.Ready,
                        form = form,
                        initialSnapshot = it.initialSnapshot ?: form,
                        users = if (usersResult is RepositoryResult.Success) usersResult.data.users else emptyList(),
                        errorMessage = null
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        phase = EditTrackerPhase.Ready,
                        errorMessage = (trackerResult as RepositoryResult.Failure).error.toString()
                    )
                }
            }
        }
    }

    fun onNameChanged(value: String) = _uiState.update { it.copy(form = it.form.copy(name = value)) }
    fun onColorChanged(value: String) = _uiState.update { it.copy(form = it.form.copy(color = value)) }
    fun onRecentDataWindowChanged(value: String) = _uiState.update { it.copy(form = it.form.copy(recentDataWindow = value)) }
    fun onDefaultTrackChanged(value: Boolean) = _uiState.update { it.copy(form = it.form.copy(isDefaultTrack = value)) }
    fun onHiddenInListChanged(value: Boolean) = _uiState.update { it.copy(form = it.form.copy(hiddenInList = value)) }
    fun onVisibilityChanged(value: String) = _uiState.update { it.copy(form = it.form.copy(visibility = value)) }
    fun onSharedWithEmailsChanged(value: List<String>) = _uiState.update { it.copy(form = it.form.copy(sharedWithEmails = value)) }
    fun onShareParamsRecipientsChanged(value: Boolean) = _uiState.update { it.copy(form = it.form.copy(shareParamsWithRecipients = value)) }
    fun onAllowGroupReshareChanged(value: Boolean) = _uiState.update { it.copy(form = it.form.copy(allowGroupReshare = value)) }
    fun onShareParamsWorldChanged(value: Boolean) = _uiState.update { it.copy(form = it.form.copy(shareParamsWithWorld = value)) }
    fun onWorldShareEnabledChanged(value: Boolean) = _uiState.update { it.copy(form = it.form.copy(worldShareEnabled = value)) }

    fun save() {
        val trackerId = _uiState.value.form.trackerId
        if (trackerId.isBlank()) return
        val request = _uiState.value.form.toRequest()
        _uiState.update { it.copy(phase = EditTrackerPhase.Saving, errorMessage = null) }
        viewModelScope.launch {
            when (val result = trackerRepository.updateTrackerSettings(trackerId, request, publishToStore = false)) {
                is RepositoryResult.Success -> {
                    val prior = _uiState.value
                    val form = toFormState(
                        tracker = result.data,
                        defaultColorHex = prior.form.color.ifBlank { "#1E88E5" },
                        isDefaultTrack = prior.form.isDefaultTrack
                    )
                    _uiState.update {
                        it.copy(
                            phase = EditTrackerPhase.Saved,
                            form = form,
                            initialSnapshot = form,
                            errorMessage = null
                        )
                    }
                }
                is RepositoryResult.Failure -> {
                    _uiState.update { it.copy(phase = EditTrackerPhase.Ready, errorMessage = result.error.toString()) }
                }
            }
        }
    }

    fun enableWorldShare() {
        val trackerId = _uiState.value.form.trackerId
        if (trackerId.isBlank()) return
        _uiState.update { it.copy(phase = EditTrackerPhase.Saving, errorMessage = null) }
        viewModelScope.launch {
            when (
                val result = trackerRepository.updateTrackerSettings(
                    trackerId = trackerId,
                    request = TrackerSettingsRequest(world_share_enabled = true),
                    publishToStore = false
                )
            ) {
                is RepositoryResult.Success -> {
                    _uiState.update {
                        it.copy(
                            phase = EditTrackerPhase.Ready,
                            form = it.form.copy(
                                worldShareEnabled = true,
                                worldShareUrl = result.data.world_share_url
                            ),
                            errorMessage = null
                        )
                    }
                }
                is RepositoryResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            phase = EditTrackerPhase.Ready,
                            form = it.form.copy(worldShareEnabled = false),
                            errorMessage = result.error.toString()
                        )
                    }
                }
            }
        }
    }

    fun hasUnsavedChanges(): Boolean {
        val state = _uiState.value
        val initial = state.initialSnapshot ?: return false
        return state.form != initial
    }

    fun deleteTracker(trackerId: String) {
        viewModelScope.launch {
            when (val result = trackerRepository.deleteTracker(trackerId)) {
                is RepositoryResult.Success -> _uiState.update { it.copy(didDelete = true, errorMessage = null) }
                is RepositoryResult.Failure -> _uiState.update { it.copy(errorMessage = result.error.toString()) }
            }
        }
    }

    fun clearTrackerHistory(trackerId: String) {
        viewModelScope.launch {
            when (val result = trackerRepository.clearTrackerHistory(trackerId)) {
                is RepositoryResult.Success -> _uiState.update { it.copy(didClearHistory = true, errorMessage = null) }
                is RepositoryResult.Failure -> _uiState.update { it.copy(errorMessage = result.error.toString()) }
            }
        }
    }

    fun exportKml(trackerId: String) {
        viewModelScope.launch {
            when (val result = trackerRepository.fetchTrackerKml(trackerId)) {
                is RepositoryResult.Success -> _uiState.update { it.copy(kmlBytes = result.data, errorMessage = null) }
                is RepositoryResult.Failure -> _uiState.update { it.copy(errorMessage = result.error.toString()) }
            }
        }
    }

    fun consumeKml() {
        _uiState.update { it.copy(kmlBytes = null) }
    }

    fun consumeDelete() {
        _uiState.update { it.copy(didDelete = false) }
    }

    fun consumeHistoryCleared() {
        _uiState.update { it.copy(didClearHistory = false) }
    }
}
