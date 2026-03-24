package com.geovault.tracker.fragments

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerSettingsRequest
import com.geovault.tracker.UserItem
import com.geovault.tracker.data.TrackerManagementRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    val hidden: Boolean = false,
    val isOwner: Boolean = false
) {
    private fun normalizedRecentDataWindowForRequest(): String? {
        val normalized = recentDataWindow.trim()
        return when {
            normalized.isEmpty() -> RecentDataWindowOptions.VALUE_ALL
            normalized == RecentDataWindowOptions.VALUE_ALL -> RecentDataWindowOptions.VALUE_ALL
            else -> normalized
        }
    }

    fun toRequest(): TrackerSettingsRequest {
        return TrackerSettingsRequest(
            name = name.trim(),
            color = color.trim().ifBlank { null },
            recent_data_window = normalizedRecentDataWindowForRequest(),
            visibility = if (isOwner) visibility else null,
            share_params_with_recipients = if (isOwner) shareParamsWithRecipients else null,
            share_params_with_world = if (isOwner) shareParamsWithWorld else null,
            shared_with_emails = if (isOwner && visibility == "shared") sharedWithEmails else null,
            world_share_enabled = if (isOwner) worldShareEnabled else null,
            allow_group_reshare = if (isOwner) allowGroupReshare else null,
            hidden = if (isDefaultTrack) false else hidden
        )
    }

    fun toRecentDataWindowOnlyRequest(): TrackerSettingsRequest =
        TrackerSettingsRequest(recent_data_window = normalizedRecentDataWindowForRequest())
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
    companion object {
        private const val TAG = "EditTrackerViewModel"
        const val SAVE_PERSISTENCE_MISMATCH = "save_persistence_mismatch"
        private const val RECENT_DATA_WINDOW_PERSIST_DEBOUNCE_MS = 500L
    }

    private val _uiState = MutableStateFlow(EditTrackerUiState())
    val uiState: StateFlow<EditTrackerUiState> = _uiState.asStateFlow()

    private val _recentDataWindowPersisted = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val recentDataWindowPersisted: SharedFlow<String> = _recentDataWindowPersisted.asSharedFlow()

    private val _recentDataWindowPersistFailed = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val recentDataWindowPersistFailed: SharedFlow<Unit> = _recentDataWindowPersistFailed.asSharedFlow()

    private var recentDataWindowPersistJob: Job? = null

    private data class PersistedSnapshot(
        val name: String,
        val color: String?,
        val recentDataWindow: String?,
        val hidden: Boolean,
        val visibility: String?,
        val shareParamsWithRecipients: Boolean?,
        val shareParamsWithWorld: Boolean?,
        val allowGroupReshare: Boolean?,
        val sharedWithEmails: List<String>?
    )

    private fun requestSnapshot(form: EditTrackerFormState): PersistedSnapshot {
        val normalizedRecentDataWindow = form.recentDataWindow.trim()
        return PersistedSnapshot(
            name = form.name.trim(),
            color = form.color.trim().ifBlank { null },
            recentDataWindow = normalizedRecentDataWindow
                .takeIf { it.isNotBlank() && it != RecentDataWindowOptions.VALUE_ALL },
            hidden = if (form.isDefaultTrack) false else form.hidden,
            visibility = if (form.isOwner) form.visibility else null,
            shareParamsWithRecipients = if (form.isOwner) form.shareParamsWithRecipients else null,
            shareParamsWithWorld = if (form.isOwner) form.shareParamsWithWorld else null,
            allowGroupReshare = if (form.isOwner) form.allowGroupReshare else null,
            sharedWithEmails = if (form.isOwner && form.visibility == "shared") {
                form.sharedWithEmails.map { it.trim() }.filter { it.isNotBlank() }.sorted()
            } else {
                null
            }
        )
    }

    private fun trackerSnapshot(tracker: Tracker): PersistedSnapshot {
        val settings = tracker.settings
        val normalizedRecentDataWindow = (settings?.get("recent_data_window") as? String)?.trim()
        return PersistedSnapshot(
            name = tracker.name.trim(),
            color = tracker.color?.trim()?.ifBlank { null },
            recentDataWindow = normalizedRecentDataWindow
                .takeIf { !it.isNullOrBlank() && it != RecentDataWindowOptions.VALUE_ALL },
            hidden = (settings?.get("hidden") as? Boolean) == true,
            visibility = if (tracker.isOwner()) tracker.visibility else null,
            shareParamsWithRecipients = if (tracker.isOwner()) tracker.share_params_with_recipients == true else null,
            shareParamsWithWorld = if (tracker.isOwner()) tracker.share_params_with_world == true else null,
            allowGroupReshare = if (tracker.isOwner()) (settings?.get("allow_group_reshare") == true) else null,
            sharedWithEmails = if (tracker.isOwner() && tracker.visibility == "shared") {
                tracker.shared_with_emails.orEmpty().map { it.trim() }.filter { it.isNotBlank() }.sorted()
            } else {
                null
            }
        )
    }

    private fun snapshotDiff(expected: PersistedSnapshot, actual: PersistedSnapshot): String {
        val differences = mutableListOf<String>()
        if (expected.name != actual.name) differences += "name(expected=${expected.name}, actual=${actual.name})"
        if (expected.color != actual.color) differences += "color(expected=${expected.color}, actual=${actual.color})"
        if (expected.recentDataWindow != actual.recentDataWindow) {
            differences += "recentDataWindow(expected=${expected.recentDataWindow}, actual=${actual.recentDataWindow})"
        }
        if (expected.hidden != actual.hidden) {
            differences += "hidden(expected=${expected.hidden}, actual=${actual.hidden})"
        }
        if (expected.visibility != actual.visibility) {
            differences += "visibility(expected=${expected.visibility}, actual=${actual.visibility})"
        }
        if (expected.shareParamsWithRecipients != actual.shareParamsWithRecipients) {
            differences += "shareParamsWithRecipients(expected=${expected.shareParamsWithRecipients}, actual=${actual.shareParamsWithRecipients})"
        }
        if (expected.shareParamsWithWorld != actual.shareParamsWithWorld) {
            differences += "shareParamsWithWorld(expected=${expected.shareParamsWithWorld}, actual=${actual.shareParamsWithWorld})"
        }
        if (expected.allowGroupReshare != actual.allowGroupReshare) {
            differences += "allowGroupReshare(expected=${expected.allowGroupReshare}, actual=${actual.allowGroupReshare})"
        }
        if (expected.sharedWithEmails != actual.sharedWithEmails) {
            differences += "sharedWithEmails(expected=${expected.sharedWithEmails}, actual=${actual.sharedWithEmails})"
        }
        return if (differences.isEmpty()) "none" else differences.joinToString(separator = "; ")
    }

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
        val serverHidden = (tracker.settings?.get("hidden") as? Boolean) == true
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
            hidden = if (isDefaultTrack) false else serverHidden,
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

    fun queueRecentDataWindowPersist() {
        recentDataWindowPersistJob?.cancel()
        recentDataWindowPersistJob = viewModelScope.launch {
            delay(RECENT_DATA_WINDOW_PERSIST_DEBOUNCE_MS)
            val state = _uiState.value
            if (state.phase != EditTrackerPhase.Ready) return@launch
            val form = state.form
            if (!form.isOwner || form.trackerId.isBlank()) return@launch
            val initial = state.initialSnapshot ?: return@launch
            val targetRecent = requestSnapshot(form).recentDataWindow
            val initialRecent = requestSnapshot(initial).recentDataWindow
            if (targetRecent == initialRecent) return@launch
            val trackerId = form.trackerId
            val request = form.toRecentDataWindowOnlyRequest()
            Log.d(TAG, "Persisting recent_data_window trackerId=$trackerId request=$request")
            when (val result = trackerRepository.updateTrackerSettings(trackerId, request, publishToStore = false)) {
                is RepositoryResult.Success -> {
                    val updatedRecent =
                        (result.data.settings?.get("recent_data_window") as? String) ?: ""
                    _uiState.update { st ->
                        val snap = st.initialSnapshot ?: return@update st
                        st.copy(
                            form = st.form.copy(recentDataWindow = updatedRecent),
                            initialSnapshot = snap.copy(recentDataWindow = updatedRecent),
                            errorMessage = null
                        )
                    }
                    _recentDataWindowPersisted.emit(result.data.id)
                }
                is RepositoryResult.Failure -> {
                    Log.e(TAG, "Recent data window persist failed trackerId=$trackerId error=${result.error}")
                    _recentDataWindowPersistFailed.emit(Unit)
                }
            }
        }
    }

    fun onDefaultTrackChanged(value: Boolean) = _uiState.update {
        val f = it.form
        it.copy(
            form = f.copy(
                isDefaultTrack = value,
                hidden = if (value) false else f.hidden
            )
        )
    }

    fun onHiddenChanged(value: Boolean) = _uiState.update {
        val f = it.form
        if (f.isDefaultTrack && value) return@update it
        it.copy(form = f.copy(hidden = value))
    }
    fun onVisibilityChanged(value: String) = _uiState.update { it.copy(form = it.form.copy(visibility = value)) }
    fun onSharedWithEmailsChanged(value: List<String>) = _uiState.update { it.copy(form = it.form.copy(sharedWithEmails = value)) }
    fun onShareParamsRecipientsChanged(value: Boolean) = _uiState.update { it.copy(form = it.form.copy(shareParamsWithRecipients = value)) }
    fun onAllowGroupReshareChanged(value: Boolean) = _uiState.update { it.copy(form = it.form.copy(allowGroupReshare = value)) }
    fun onShareParamsWorldChanged(value: Boolean) = _uiState.update { it.copy(form = it.form.copy(shareParamsWithWorld = value)) }
    fun onWorldShareEnabledChanged(value: Boolean) = _uiState.update { it.copy(form = it.form.copy(worldShareEnabled = value)) }

    fun save() {
        recentDataWindowPersistJob?.cancel()
        recentDataWindowPersistJob = null
        val trackerId = _uiState.value.form.trackerId
        if (trackerId.isBlank()) return
        val form = _uiState.value.form
        val request = form.toRequest()
        val requestedSnapshot = requestSnapshot(form)
        Log.d(
            TAG,
            "Saving tracker settings trackerId=$trackerId requestSnapshot=$requestedSnapshot request=$request"
        )
        _uiState.update { it.copy(phase = EditTrackerPhase.Saving, errorMessage = null) }
        viewModelScope.launch {
            when (val result = trackerRepository.updateTrackerSettings(trackerId, request, publishToStore = false)) {
                is RepositoryResult.Success -> {
                    Log.d(TAG, "Tracker settings API save succeeded trackerId=$trackerId")
                    when (val persisted = trackerRepository.loadTracker(trackerId)) {
                        is RepositoryResult.Success -> {
                            val persistedSnapshot = trackerSnapshot(persisted.data)
                            if (persistedSnapshot != requestedSnapshot) {
                                Log.e(
                                    TAG,
                                    "Save persistence mismatch trackerId=$trackerId diff=${snapshotDiff(requestedSnapshot, persistedSnapshot)} requested=$requestedSnapshot persisted=$persistedSnapshot"
                                )
                                _uiState.update {
                                    it.copy(
                                        phase = EditTrackerPhase.Ready,
                                        errorMessage = SAVE_PERSISTENCE_MISMATCH
                                    )
                                }
                                return@launch
                            }
                            val prior = _uiState.value
                            val refreshedForm = toFormState(
                                tracker = persisted.data,
                                defaultColorHex = prior.form.color.ifBlank { "#1E88E5" },
                                isDefaultTrack = prior.form.isDefaultTrack
                            )
                            _uiState.update {
                                it.copy(
                                    phase = EditTrackerPhase.Saved,
                                    form = refreshedForm,
                                    initialSnapshot = refreshedForm,
                                    errorMessage = null
                                )
                            }
                        }
                        is RepositoryResult.Failure -> {
                            Log.e(
                                TAG,
                                "Tracker settings reload failed after save trackerId=$trackerId error=${persisted.error}"
                            )
                            _uiState.update {
                                it.copy(
                                    phase = EditTrackerPhase.Ready,
                                    errorMessage = SAVE_PERSISTENCE_MISMATCH
                                )
                            }
                        }
                    }
                }
                is RepositoryResult.Failure -> {
                    Log.e(
                        TAG,
                        "Tracker settings API save failed trackerId=$trackerId error=${result.error}"
                    )
                    _uiState.update { it.copy(phase = EditTrackerPhase.Ready, errorMessage = result.error.toString()) }
                }
            }
        }
    }

    fun enableWorldShare(trackerId: String? = null) {
        val resolvedTrackerId = trackerId?.takeIf { it.isNotBlank() } ?: _uiState.value.form.trackerId
        if (resolvedTrackerId.isBlank()) return
        _uiState.update { it.copy(phase = EditTrackerPhase.Saving, errorMessage = null) }
        viewModelScope.launch {
            when (
                val result = trackerRepository.updateTrackerSettings(
                    trackerId = resolvedTrackerId,
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

    fun disableWorldShare(trackerId: String? = null) {
        val resolvedTrackerId = trackerId?.takeIf { it.isNotBlank() } ?: _uiState.value.form.trackerId
        if (resolvedTrackerId.isBlank()) return
        _uiState.update { it.copy(phase = EditTrackerPhase.Saving, errorMessage = null) }
        viewModelScope.launch {
            when (
                val result = trackerRepository.updateTrackerSettings(
                    trackerId = resolvedTrackerId,
                    request = TrackerSettingsRequest(world_share_enabled = false),
                    publishToStore = false
                )
            ) {
                is RepositoryResult.Success -> {
                    _uiState.update {
                        it.copy(
                            phase = EditTrackerPhase.Ready,
                            form = it.form.copy(
                                worldShareEnabled = false,
                                worldShareUrl = null
                            ),
                            errorMessage = null
                        )
                    }
                }
                is RepositoryResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            phase = EditTrackerPhase.Ready,
                            form = it.form.copy(worldShareEnabled = true),
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

    fun hasUnsavedChangesExcludingRecentDataWindow(): Boolean {
        val state = _uiState.value
        val initial = state.initialSnapshot ?: return false
        val normalizedForm = state.form.copy(recentDataWindow = initial.recentDataWindow)
        return normalizedForm != initial
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

    override fun onCleared() {
        recentDataWindowPersistJob?.cancel()
        super.onCleared()
    }
}
