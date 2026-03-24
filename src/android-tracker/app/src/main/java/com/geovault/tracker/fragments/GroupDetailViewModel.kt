package com.geovault.tracker.fragments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.tracker.AppError
import com.geovault.tracker.Group
import com.geovault.tracker.GroupPatchRequest
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.Tracker
import com.geovault.tracker.UserItem
import com.geovault.tracker.data.GroupManagementRepository
import com.geovault.tracker.data.GroupTrackerEligibilityUseCase
import com.geovault.tracker.data.TrackerManagementStateStore
import com.geovault.tracker.data.TrackerManagementRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class GroupDetailPhase {
    Loading,
    Ready,
    Saving,
    Saved,
    Deleting,
    Deleted
}

data class GroupDetailFormState(
    val groupId: String = "",
    val name: String = "",
    val visibility: String = "private",
    val sharedWithEmails: List<String> = emptyList(),
    val worldShareEnabled: Boolean = false,
    val worldShareUrl: String? = null,
    val hiddenInList: Boolean = false
) {
    fun toRequest(
        addTrackIds: List<String>,
        removeTrackIds: List<String>
    ): GroupPatchRequest {
        return GroupPatchRequest(
            name = name.trim(),
            hidden_in_list = hiddenInList,
            visibility = visibility,
            shared_with_emails = if (visibility == "shared") sharedWithEmails else null,
            world_share_enabled = worldShareEnabled,
            add_track_ids = addTrackIds,
            remove_track_ids = removeTrackIds
        )
    }
}

data class GroupDetailUiState(
    val phase: GroupDetailPhase = GroupDetailPhase.Loading,
    val group: Group? = null,
    val form: GroupDetailFormState = GroupDetailFormState(),
    val initialSnapshot: GroupDetailFormState? = null,
    val initialTrackIds: Set<String> = emptySet(),
    val draftTrackIds: Set<String> = emptySet(),
    val allTrackers: List<Tracker> = emptyList(),
    val draftGroupTrackers: List<Tracker> = emptyList(),
    val addableTrackers: List<Tracker> = emptyList(),
    val users: List<UserItem> = emptyList(),
    val errorMessage: String? = null
)

@HiltViewModel
class GroupDetailViewModel @Inject constructor(
    private val groupRepository: GroupManagementRepository,
    private val trackerRepository: TrackerManagementRepository,
    private val eligibilityUseCase: GroupTrackerEligibilityUseCase,
    private val stateStore: TrackerManagementStateStore
) : ViewModel() {
    companion object {
        const val SAVE_PERSISTENCE_MISMATCH = "group_save_persistence_mismatch"
    }

    private val _uiState = MutableStateFlow(GroupDetailUiState())
    val uiState: StateFlow<GroupDetailUiState> = _uiState.asStateFlow()
    private var currentGroupId: String? = null

    private data class PersistedSnapshot(
        val name: String,
        val visibility: String,
        val sharedWithEmails: List<String>,
        val hiddenInList: Boolean,
        val trackIds: Set<String>
    )

    init {
        viewModelScope.launch {
            stateStore.groups.collect { groups ->
                val groupId = currentGroupId ?: return@collect
                val updatedGroup = groups.firstOrNull { it.id == groupId } ?: return@collect
                _uiState.update { current ->
                    if (current.phase != GroupDetailPhase.Ready) return@update current
                    val hasUnsavedMembershipChanges = current.draftTrackIds != current.initialTrackIds
                    val trackIds = if (hasUnsavedMembershipChanges) current.draftTrackIds else updatedGroup.track_ids.orEmpty().toSet()
                    val updatedInitialTrackIds = if (hasUnsavedMembershipChanges) current.initialTrackIds else trackIds
                    val derived = deriveDraftLists(
                        baseGroup = updatedGroup,
                        allTrackers = current.allTrackers,
                        draftTrackIds = trackIds
                    )
                    current.copy(
                        group = updatedGroup,
                        initialTrackIds = updatedInitialTrackIds,
                        draftTrackIds = trackIds,
                        draftGroupTrackers = derived.groupTrackers,
                        addableTrackers = derived.addableTrackers
                    )
                }
            }
        }
    }

    private fun toFormState(group: Group): GroupDetailFormState {
        return GroupDetailFormState(
            groupId = group.id,
            name = group.name,
            visibility = group.visibility ?: "private",
            sharedWithEmails = group.shared_with_emails ?: emptyList(),
            worldShareEnabled = !group.world_share_id.isNullOrBlank(),
            worldShareUrl = group.world_share_url,
            hiddenInList = group.hidden_in_list == true
        )
    }

    private fun requestSnapshot(state: GroupDetailUiState): PersistedSnapshot {
        val form = state.form
        val normalizedShared = if (form.visibility == "shared") {
            form.sharedWithEmails
        } else {
            emptyList()
        }.map { it.trim() }.filter { it.isNotBlank() }.sorted()
        return PersistedSnapshot(
            name = form.name.trim(),
            visibility = form.visibility,
            sharedWithEmails = normalizedShared,
            hiddenInList = form.hiddenInList,
            trackIds = state.draftTrackIds
        )
    }

    private fun groupSnapshot(group: Group): PersistedSnapshot {
        val visibility = group.visibility ?: "private"
        val normalizedShared = if (visibility == "shared") {
            group.shared_with_emails.orEmpty()
        } else {
            emptyList()
        }.map { it.trim() }.filter { it.isNotBlank() }.sorted()
        return PersistedSnapshot(
            name = group.name.trim(),
            visibility = visibility,
            sharedWithEmails = normalizedShared,
            hiddenInList = group.hidden_in_list == true,
            trackIds = group.track_ids.orEmpty().toSet()
        )
    }

    fun load(groupId: String) {
        currentGroupId = groupId
        _uiState.update { it.copy(phase = GroupDetailPhase.Loading, errorMessage = null) }
        viewModelScope.launch {
            val groupResult = groupRepository.loadGroup(groupId)
            val trackersResult = trackerRepository.loadTrackers(forceRefresh = false)
            val usersResult = trackerRepository.loadUsers()
            if (groupResult is RepositoryResult.Success && trackersResult is RepositoryResult.Success) {
                val initialTrackIds = groupResult.data.track_ids.orEmpty().toSet()
                val derived = deriveDraftLists(
                    baseGroup = groupResult.data,
                    allTrackers = trackersResult.data,
                    draftTrackIds = initialTrackIds
                )
                val form = toFormState(groupResult.data)
                _uiState.update {
                    it.copy(
                        phase = GroupDetailPhase.Ready,
                        group = groupResult.data,
                        form = form,
                        initialSnapshot = form,
                        initialTrackIds = initialTrackIds,
                        draftTrackIds = initialTrackIds,
                        allTrackers = trackersResult.data,
                        draftGroupTrackers = derived.groupTrackers,
                        addableTrackers = derived.addableTrackers,
                        users = if (usersResult is RepositoryResult.Success) usersResult.data.users else emptyList(),
                        errorMessage = null
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        phase = GroupDetailPhase.Ready,
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

    fun onNameChanged(value: String) = _uiState.update { it.copy(form = it.form.copy(name = value)) }
    fun onVisibilityChanged(value: String) = _uiState.update { it.copy(form = it.form.copy(visibility = value)) }
    fun onSharedWithEmailsChanged(value: List<String>) = _uiState.update { it.copy(form = it.form.copy(sharedWithEmails = value)) }
    fun onHiddenInListChanged(value: Boolean) = _uiState.update { it.copy(form = it.form.copy(hiddenInList = value)) }
    fun onWorldShareEnabledChanged(value: Boolean) = _uiState.update { it.copy(form = it.form.copy(worldShareEnabled = value)) }
    fun addDraftTracker(id: String) = _uiState.update { state ->
        if (id in state.draftTrackIds) return@update state
        val nextDraftIds = state.draftTrackIds + id
        val baseGroup = state.group ?: return@update state.copy(draftTrackIds = nextDraftIds)
        val derived = deriveDraftLists(baseGroup, state.allTrackers, nextDraftIds)
        state.copy(
            draftTrackIds = nextDraftIds,
            draftGroupTrackers = derived.groupTrackers,
            addableTrackers = derived.addableTrackers
        )
    }

    fun removeDraftTracker(id: String) = _uiState.update { state ->
        if (id !in state.draftTrackIds) return@update state
        val nextDraftIds = state.draftTrackIds - id
        val baseGroup = state.group ?: return@update state.copy(draftTrackIds = nextDraftIds)
        val derived = deriveDraftLists(baseGroup, state.allTrackers, nextDraftIds)
        state.copy(
            draftTrackIds = nextDraftIds,
            draftGroupTrackers = derived.groupTrackers,
            addableTrackers = derived.addableTrackers
        )
    }

    fun discardDraftMembership() = _uiState.update { state ->
        val baseGroup = state.group ?: return@update state
        val canonical = state.initialTrackIds
        val derived = deriveDraftLists(baseGroup, state.allTrackers, canonical)
        state.copy(
            draftTrackIds = canonical,
            draftGroupTrackers = derived.groupTrackers,
            addableTrackers = derived.addableTrackers
        )
    }

    fun saveGroup() {
        val groupId = _uiState.value.form.groupId
        if (groupId.isBlank()) return
        val state = _uiState.value
        val expectedSnapshot = requestSnapshot(state)
        val addTrackIds = state.draftTrackIds.minus(state.initialTrackIds).toList()
        val removeTrackIds = state.initialTrackIds.minus(state.draftTrackIds).toList()
        val request = state.form.toRequest(
            addTrackIds = addTrackIds,
            removeTrackIds = removeTrackIds
        )
        _uiState.update { it.copy(phase = GroupDetailPhase.Saving, errorMessage = null) }
        viewModelScope.launch {
            when (val result = groupRepository.patchGroup(groupId, request, publishToStore = true)) {
                is RepositoryResult.Success -> {
                    when (val persisted = groupRepository.loadGroup(groupId)) {
                        is RepositoryResult.Success -> {
                            if (groupSnapshot(persisted.data) != expectedSnapshot) {
                                _uiState.update {
                                    it.copy(
                                        phase = GroupDetailPhase.Ready,
                                        errorMessage = SAVE_PERSISTENCE_MISMATCH
                                    )
                                }
                                return@launch
                            }
                            val trackers = _uiState.value.allTrackers
                            val savedTrackIds = persisted.data.track_ids.orEmpty().toSet()
                            val derived = deriveDraftLists(
                                baseGroup = persisted.data,
                                allTrackers = trackers,
                                draftTrackIds = savedTrackIds
                            )
                            val form = toFormState(persisted.data)
                            _uiState.update {
                                it.copy(
                                    phase = GroupDetailPhase.Saved,
                                    group = persisted.data,
                                    form = form,
                                    initialSnapshot = form,
                                    initialTrackIds = savedTrackIds,
                                    draftTrackIds = savedTrackIds,
                                    draftGroupTrackers = derived.groupTrackers,
                                    addableTrackers = derived.addableTrackers,
                                    errorMessage = null
                                )
                            }
                        }
                        is RepositoryResult.Failure -> {
                            _uiState.update {
                                it.copy(
                                    phase = GroupDetailPhase.Ready,
                                    errorMessage = SAVE_PERSISTENCE_MISMATCH
                                )
                            }
                        }
                    }
                }
                is RepositoryResult.Failure -> {
                    _uiState.update { it.copy(phase = GroupDetailPhase.Ready, errorMessage = result.error.toString()) }
                }
            }
        }
    }

    fun enableWorldShare() {
        val state = _uiState.value
        val groupId = state.form.groupId
        if (groupId.isBlank()) return
        _uiState.update { it.copy(phase = GroupDetailPhase.Saving, errorMessage = null) }
        viewModelScope.launch {
            when (
                val result = groupRepository.patchGroup(
                    groupId = groupId,
                    request = GroupPatchRequest(world_share_enabled = true),
                    publishToStore = true
                )
            ) {
                is RepositoryResult.Success -> {
                    _uiState.update {
                        it.copy(
                            phase = GroupDetailPhase.Ready,
                            group = result.data,
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
                            phase = GroupDetailPhase.Ready,
                            form = it.form.copy(worldShareEnabled = false),
                            errorMessage = result.error.toString()
                        )
                    }
                }
            }
        }
    }

    fun disableWorldShare() {
        val state = _uiState.value
        val groupId = state.form.groupId
        if (groupId.isBlank()) return
        _uiState.update { it.copy(phase = GroupDetailPhase.Saving, errorMessage = null) }
        viewModelScope.launch {
            when (
                val result = groupRepository.patchGroup(
                    groupId = groupId,
                    request = GroupPatchRequest(world_share_enabled = false),
                    publishToStore = true
                )
            ) {
                is RepositoryResult.Success -> {
                    _uiState.update {
                        it.copy(
                            phase = GroupDetailPhase.Ready,
                            group = result.data,
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
                            phase = GroupDetailPhase.Ready,
                            form = it.form.copy(worldShareEnabled = true),
                            errorMessage = result.error.toString()
                        )
                    }
                }
            }
        }
    }

    fun deleteGroup(groupId: String) {
        _uiState.update { it.copy(phase = GroupDetailPhase.Deleting, errorMessage = null) }
        viewModelScope.launch {
            when (val result = groupRepository.deleteGroup(groupId)) {
                is RepositoryResult.Success -> {
                    _uiState.update { it.copy(phase = GroupDetailPhase.Deleted) }
                }
                is RepositoryResult.Failure -> {
                    _uiState.update { it.copy(phase = GroupDetailPhase.Ready, errorMessage = result.error.toString()) }
                }
            }
        }
    }

    fun hasUnsavedChanges(): Boolean {
        val state = _uiState.value
        val initial = state.initialSnapshot ?: return false
        return state.form != initial || state.draftTrackIds != state.initialTrackIds
    }

    fun hasUnsavedMembershipChanges(): Boolean {
        val state = _uiState.value
        return state.draftTrackIds != state.initialTrackIds
    }

    fun consumeError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    data class DerivedGroupTrackers(
        val groupTrackers: List<Tracker>,
        val addableTrackers: List<Tracker>
    )

    private fun deriveDraftLists(
        baseGroup: Group,
        allTrackers: List<Tracker>,
        draftTrackIds: Set<String>
    ): DerivedGroupTrackers {
        val trackerById = allTrackers.associateBy { it.id }
        val orderedTrackers = draftTrackIds.mapNotNull { trackerById[it] }
        val draftGroup = baseGroup.copy(track_ids = draftTrackIds.toList())
        val addable = eligibilityUseCase
            .addableTrackers(allTrackers, draftGroup)
            .filter { it.canAdd }
            .map { it.tracker }
            .sortedBy { it.name.lowercase() }
        return DerivedGroupTrackers(
            groupTrackers = orderedTrackers,
            addableTrackers = addable
        )
    }
}
