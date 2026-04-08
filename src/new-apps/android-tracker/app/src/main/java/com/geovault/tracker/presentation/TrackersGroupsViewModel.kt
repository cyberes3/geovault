package com.geovault.tracker.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.tracker.AppError
import com.geovault.tracker.Group
import com.geovault.tracker.R
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.SelectedTrackerPrefs
import com.geovault.tracker.SelectedTrackerManager
import com.geovault.tracker.TrackerCreateRequest
import com.geovault.tracker.TrackerRecentDataWindowOptions
import com.geovault.tracker.TrackerSettingsRequest
import com.geovault.tracker.Tracker
import com.geovault.tracker.UserItem
import com.geovault.tracker.data.GroupManagementRepository
import com.geovault.tracker.data.TrackerManagementRepository
import com.geovault.tracker.di.TrackerAppServices
import com.geovault.common.NaturalSort
import java.util.Locale
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class TrackersGroupsViewModel(application: Application) : AndroidViewModel(application) {

    private val trackerRepository: TrackerManagementRepository =
        TrackerAppServices.from(application).trackerManagementRepository()
    private val groupRepository: GroupManagementRepository =
        TrackerAppServices.from(application).groupManagementRepository()
    private val stateStore = TrackerAppServices.from(application).trackerManagementStateStore()

    private val _uiState = MutableStateFlow(
        TrackersGroupsUiState(
            trackers = stateStore.trackers.value,
            groups = stateStore.groups.value,
            mapVisibility = stateStore.mapVisibility.value,
        )
    )
    val uiState: StateFlow<TrackersGroupsUiState> = _uiState.asStateFlow()

    private val _kmlExportEvents = MutableSharedFlow<TrackerKmlExportEvent>(extraBufferCapacity = 1)
    val kmlExportEvents: SharedFlow<TrackerKmlExportEvent> = _kmlExportEvents.asSharedFlow()
    private val _toastEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val toastEvents: SharedFlow<String> = _toastEvents.asSharedFlow()

    private var openEditTrackerJob: Job? = null
    private var editTrackerWorldShareJob: Job? = null
    private var editGroupWorldShareJob: Job? = null

    init {
        viewModelScope.launch {
            stateStore.trackers.collectLatest { trackers ->
                _uiState.update { it.copy(trackers = trackers) }
            }
        }
        viewModelScope.launch {
            stateStore.groups.collectLatest { groups ->
                _uiState.update { it.copy(groups = groups) }
            }
        }
        viewModelScope.launch {
            stateStore.mapVisibility.collectLatest { mapVisibility ->
                _uiState.update { it.copy(mapVisibility = mapVisibility) }
            }
        }
        preloadTrackersSurface()
    }

    fun preloadTrackersSurface() {
        val state = _uiState.value
        if (state.isLoading || state.hasCompletedInitialLoad) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    isPullRefreshing = false,
                    userMessage = null,
                )
            }
            refreshStateFromServer(
                userMessage = null,
                forceRefresh = true,
            )
        }
    }

    fun setSubTab(tab: TrackersGroupsSubTab) {
        _uiState.update { it.copy(subTab = tab) }
    }

    fun clearUserMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }

    fun postUserMessage(message: String) {
        _uiState.update { it.copy(userMessage = message) }
    }

    fun exportTrackerKml(trackerId: String, trackerDisplayName: String) {
        if (_uiState.value.isKmlExportLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isKmlExportLoading = true) }
            when (val result = trackerRepository.fetchTrackerKml(trackerId)) {
                is RepositoryResult.Success -> {
                    _uiState.update { it.copy(isKmlExportLoading = false) }
                    val base = sanitizeKmlBaseFileName(trackerDisplayName)
                    _kmlExportEvents.emit(TrackerKmlExportEvent(result.data, base))
                }
                is RepositoryResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            isKmlExportLoading = false,
                            userMessage = getApplication<Application>().getString(R.string.trackers_kml_fetch_failed),
                        )
                    }
                }
            }
        }
    }

    fun openCreateTrackerDialog() {
        _uiState.update { it.copy(dialog = TrackersGroupsDialog.CreateTracker()) }
    }

    fun openCreateGroupDialog() {
        _uiState.update { it.copy(dialog = TrackersGroupsDialog.CreateGroup()) }
    }

    fun openEditTrackerDialog(tracker: com.geovault.tracker.Tracker) {
        if (!OwnershipActionPolicy.canEditTracker(tracker)) return
        openEditTrackerJob?.cancel()
        refreshShareRecipientSuggestions()
        val fallbackTracker = _uiState.value.trackers.firstOrNull { it.id == tracker.id } ?: tracker
        _uiState.update {
            it.copy(
                dialog = TrackersGroupsDialog.EditTrackerLoading(
                    trackerId = fallbackTracker.id,
                    trackerName = fallbackTracker.name,
                ),
            )
        }
        openEditTrackerJob = viewModelScope.launch {
            val loadResult = trackerRepository.loadTracker(fallbackTracker.id)
            val trackerForDialog = when (loadResult) {
                is RepositoryResult.Success -> loadResult.data
                is RepositoryResult.Failure -> fallbackTracker
            }
            val selectedTrackerId = SelectedTrackerPrefs.selectedTrackerId(getApplication())
            _uiState.update {
                val loading = it.dialog as? TrackersGroupsDialog.EditTrackerLoading ?: return@update it
                if (loading.trackerId != fallbackTracker.id) return@update it
                it.copy(
                    dialog = toEditTrackerDialog(trackerForDialog, selectedTrackerId),
                    userMessage = if (loadResult is RepositoryResult.Failure) {
                        getApplication<Application>().getString(
                            R.string.trackers_failed_to_load_tracker_details,
                        )
                    } else {
                        it.userMessage
                    },
                )
            }
            val activeEdit = _uiState.value.dialog as? TrackersGroupsDialog.EditTracker
            if (activeEdit?.tracker?.id == trackerForDialog.id) {
                bootstrapEditTrackerWorldShareUrlIfNeeded(trackerForDialog)
            }
        }
    }

    fun openEditGroupDialog(group: com.geovault.tracker.Group) {
        val trackIds = group.track_ids.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        _uiState.update {
            it.copy(
                dialog = TrackersGroupsDialog.EditGroup(
                    group = group,
                    nameDraft = group.name,
                    visibilityDraft = GroupShareVisibility.fromApiValue(group.visibility),
                    sharedEmailsDraft = group.shared_with_emails.orEmpty().joinToString(", "),
                    worldShareEnabledDraft = !group.world_share_id.isNullOrBlank() ||
                        !group.world_share_url.isNullOrBlank(),
                    worldShareUrlDraft = group.world_share_url,
                    hiddenDraft = group.hidden == true,
                    memberTrackIds = trackIds,
                    initialMemberTrackIds = trackIds,
                )
            )
        }
        if (group.isOwner()) {
            refreshShareRecipientSuggestions()
            refreshTrackersForPicker()
        }
    }

    fun dismissDialog() {
        openEditTrackerJob?.cancel()
        openEditTrackerJob = null
        editTrackerWorldShareJob?.cancel()
        editTrackerWorldShareJob = null
        editGroupWorldShareJob?.cancel()
        editGroupWorldShareJob = null
        _uiState.update { it.copy(dialog = TrackersGroupsDialog.Hidden) }
    }

    fun updateCreateTrackerDraft(name: String, color: String) {
        val d = _uiState.value.dialog
        if (d is TrackersGroupsDialog.CreateTracker) {
            _uiState.update {
                it.copy(
                    dialog = d.copy(
                        nameDraft = name,
                        colorDraft = color
                    )
                )
            }
        }
    }

    fun updateCreateGroupDraft(name: String) {
        val d = _uiState.value.dialog
        if (d is TrackersGroupsDialog.CreateGroup) {
            _uiState.update { it.copy(dialog = d.copy(nameDraft = name)) }
        }
    }

    fun updateEditTrackerDraft(name: String) {
        val d = _uiState.value.dialog
        if (d is TrackersGroupsDialog.EditTracker) {
            _uiState.update { it.copy(dialog = d.copy(nameDraft = name)) }
        }
    }

    fun updateEditTrackerColorDraft(color: String) {
        val d = _uiState.value.dialog
        if (d is TrackersGroupsDialog.EditTracker) {
            _uiState.update { it.copy(dialog = d.copy(colorDraft = color)) }
        }
    }

    fun updateCreateTrackerSetAsSelected(setAsSelected: Boolean) {
        val d = _uiState.value.dialog
        if (d is TrackersGroupsDialog.CreateTracker) {
            _uiState.update { it.copy(dialog = d.copy(setAsSelectedTracker = setAsSelected)) }
        }
    }

    fun updateEditTrackerSetAsSelected(setAsSelected: Boolean) {
        val d = _uiState.value.dialog
        if (d is TrackersGroupsDialog.EditTracker) {
            _uiState.update {
                it.copy(
                    dialog = d.copy(
                        setAsSelectedTracker = setAsSelected,
                        hiddenDraft = if (setAsSelected) false else d.hiddenDraft,
                    )
                )
            }
        }
    }

    fun updateEditTrackerHidden(hidden: Boolean) {
        val d = _uiState.value.dialog
        if (d is TrackersGroupsDialog.EditTracker) {
            val coercedHidden = if (d.setAsSelectedTracker) false else hidden
            _uiState.update { it.copy(dialog = d.copy(hiddenDraft = coercedHidden)) }
        }
    }

    fun updateEditTrackerRecentDataWindow(value: String) {
        val d = _uiState.value.dialog
        if (d is TrackersGroupsDialog.EditTracker) {
            _uiState.update { it.copy(dialog = d.copy(recentDataWindowDraft = value)) }
        }
    }

    fun updateEditTrackerVisibility(visibility: TrackerShareVisibility) {
        val d = _uiState.value.dialog
        if (d is TrackersGroupsDialog.EditTracker) {
            editTrackerWorldShareJob?.cancel()
            editTrackerWorldShareJob = null
            _uiState.update { it.copy(dialog = d.copy(visibilityDraft = visibility)) }
        }
    }

    fun updateEditTrackerSharedEmails(emails: String) {
        val d = _uiState.value.dialog
        if (d is TrackersGroupsDialog.EditTracker) {
            _uiState.update { it.copy(dialog = d.copy(sharedEmailsDraft = emails)) }
        }
    }

    fun toggleEditTrackerSharedEmailSelection(email: String) {
        val d = _uiState.value.dialog
        if (d is TrackersGroupsDialog.EditTracker) {
            _uiState.update {
                it.copy(
                    dialog = d.copy(
                        sharedEmailsDraft = SharedRecipientSelectionPolicy.toggle(d.sharedEmailsDraft, email)
                    )
                )
            }
        }
    }

    fun updateEditTrackerWorldShareEnabled(enabled: Boolean) {
        val d = _uiState.value.dialog as? TrackersGroupsDialog.EditTracker ?: return
        val visibilityForWorldShare = when (d.visibilityDraft) {
            TrackerShareVisibility.PRIVATE -> return
            TrackerShareVisibility.SHARED,
            TrackerShareVisibility.PUBLIC -> d.visibilityDraft
        }
        val trackerId = d.tracker.id
        editTrackerWorldShareJob?.cancel()
        if (enabled) {
            editTrackerWorldShareJob = viewModelScope.launch {
                _uiState.update {
                    val cur = it.dialog as? TrackersGroupsDialog.EditTracker ?: return@update it
                    if (cur.tracker.id != trackerId) return@update it
                    it.copy(
                        dialog = cur.copy(
                            worldShareEnabledDraft = true,
                            isWorldShareLinkLoading = true,
                        ),
                    )
                }
                when (
                    val result = trackerRepository.updateTrackerSettings(
                        trackerId = trackerId,
                        request = TrackerSettingsRequest(
                            visibility = visibilityForWorldShare.apiValue,
                            world_share_enabled = true,
                        ),
                        publishToStore = true,
                    )
                ) {
                    is RepositoryResult.Success -> {
                        val t = result.data
                        _uiState.update {
                            val cur = it.dialog as? TrackersGroupsDialog.EditTracker ?: return@update it
                            if (cur.tracker.id != trackerId) return@update it
                            it.copy(
                                dialog = cur.copy(
                                    tracker = t,
                                    isWorldShareLinkLoading = false,
                                    visibilityDraft = TrackerShareVisibility.fromApiValue(t.visibility),
                                    worldShareEnabledDraft =
                                        !t.world_share_id.isNullOrBlank() ||
                                            !t.world_share_url.isNullOrBlank(),
                                    worldShareUrlDraft = t.world_share_url,
                                ),
                            )
                        }
                    }
                    is RepositoryResult.Failure -> {
                        _uiState.update {
                            val cur = it.dialog as? TrackersGroupsDialog.EditTracker ?: return@update it
                            if (cur.tracker.id != trackerId) return@update it
                            it.copy(
                                dialog = cur.copy(
                                    isWorldShareLinkLoading = false,
                                    worldShareEnabledDraft = false,
                                ),
                                userMessage = getApplication<Application>().getString(
                                    R.string.trackers_failed_to_enable_world_share,
                                ),
                            )
                        }
                    }
                }
            }
        } else {
            editTrackerWorldShareJob = viewModelScope.launch {
                _uiState.update {
                    val cur = it.dialog as? TrackersGroupsDialog.EditTracker ?: return@update it
                    if (cur.tracker.id != trackerId) return@update it
                    it.copy(dialog = cur.copy(isWorldShareLinkLoading = true))
                }
                when (
                    val result = trackerRepository.updateTrackerSettings(
                        trackerId = trackerId,
                        request = TrackerSettingsRequest(world_share_enabled = false),
                        publishToStore = true,
                    )
                ) {
                    is RepositoryResult.Success -> {
                        val t = result.data
                        _uiState.update {
                            val cur = it.dialog as? TrackersGroupsDialog.EditTracker ?: return@update it
                            if (cur.tracker.id != trackerId) return@update it
                            it.copy(
                                dialog = cur.copy(
                                    tracker = t,
                                    isWorldShareLinkLoading = false,
                                    worldShareEnabledDraft = false,
                                    worldShareUrlDraft = null,
                                    shareParamsWithWorldDraft = false,
                                ),
                            )
                        }
                    }
                    is RepositoryResult.Failure -> {
                        _uiState.update {
                            val cur = it.dialog as? TrackersGroupsDialog.EditTracker ?: return@update it
                            if (cur.tracker.id != trackerId) return@update it
                            it.copy(
                                dialog = cur.copy(isWorldShareLinkLoading = false),
                                userMessage = getApplication<Application>().getString(
                                    R.string.trackers_failed_to_disable_world_share,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun bootstrapEditTrackerWorldShareUrlIfNeeded(tracker: Tracker) {
        val visibilityForWorldShare = tracker.shareVisibilityForEditing()
        val enabledOnServer =
            !tracker.world_share_id.isNullOrBlank() || !tracker.world_share_url.isNullOrBlank()
        if (
            visibilityForWorldShare == TrackerShareVisibility.PRIVATE ||
            !enabledOnServer ||
            !tracker.world_share_url.isNullOrBlank()
        ) return
        val trackerId = tracker.id
        editTrackerWorldShareJob?.cancel()
        editTrackerWorldShareJob = viewModelScope.launch {
            _uiState.update {
                val cur = it.dialog as? TrackersGroupsDialog.EditTracker ?: return@update it
                if (cur.tracker.id != trackerId) return@update it
                it.copy(dialog = cur.copy(isWorldShareLinkLoading = true))
            }
            when (
                val result = trackerRepository.updateTrackerSettings(
                    trackerId = trackerId,
                    request = TrackerSettingsRequest(
                        visibility = visibilityForWorldShare.apiValue,
                        world_share_enabled = true,
                    ),
                    publishToStore = true,
                )
            ) {
                is RepositoryResult.Success -> {
                    val t = result.data
                    _uiState.update {
                        val cur = it.dialog as? TrackersGroupsDialog.EditTracker ?: return@update it
                        if (cur.tracker.id != trackerId) return@update it
                        it.copy(
                            dialog = cur.copy(
                                tracker = t,
                                isWorldShareLinkLoading = false,
                                visibilityDraft = TrackerShareVisibility.fromApiValue(t.visibility),
                                worldShareUrlDraft = t.world_share_url,
                            ),
                        )
                    }
                }
                is RepositoryResult.Failure -> {
                    _uiState.update {
                        val cur = it.dialog as? TrackersGroupsDialog.EditTracker ?: return@update it
                        if (cur.tracker.id != trackerId) return@update it
                        it.copy(
                            dialog = cur.copy(isWorldShareLinkLoading = false),
                            userMessage = getApplication<Application>().getString(
                                R.string.trackers_failed_to_fetch_world_share_link,
                            ),
                        )
                    }
                }
            }
        }
    }

    fun updateEditTrackerShareParamsWithRecipients(enabled: Boolean) {
        val d = _uiState.value.dialog
        if (d is TrackersGroupsDialog.EditTracker) {
            _uiState.update { it.copy(dialog = d.copy(shareParamsWithRecipientsDraft = enabled)) }
        }
    }

    fun updateEditTrackerAllowGroupReshare(enabled: Boolean) {
        val d = _uiState.value.dialog
        if (d is TrackersGroupsDialog.EditTracker) {
            _uiState.update { it.copy(dialog = d.copy(allowGroupReshareDraft = enabled)) }
        }
    }

    fun updateEditTrackerShareParamsWithWorld(enabled: Boolean) {
        val d = _uiState.value.dialog
        if (d is TrackersGroupsDialog.EditTracker) {
            _uiState.update { it.copy(dialog = d.copy(shareParamsWithWorldDraft = enabled)) }
        }
    }

    fun updateEditGroupDraft(name: String) {
        val d = _uiState.value.dialog
        if (d is TrackersGroupsDialog.EditGroup) {
            _uiState.update { it.copy(dialog = d.copy(nameDraft = name)) }
        }
    }

    fun updateEditGroupVisibility(visibility: GroupShareVisibility) {
        val d = _uiState.value.dialog
        if (d is TrackersGroupsDialog.EditGroup) {
            _uiState.update { it.copy(dialog = d.copy(visibilityDraft = visibility)) }
        }
    }

    fun updateEditGroupSharedEmails(emails: String) {
        val d = _uiState.value.dialog
        if (d is TrackersGroupsDialog.EditGroup) {
            _uiState.update { it.copy(dialog = d.copy(sharedEmailsDraft = emails)) }
        }
    }

    fun toggleEditGroupSharedEmailSelection(email: String) {
        val d = _uiState.value.dialog
        if (d is TrackersGroupsDialog.EditGroup) {
            _uiState.update {
                it.copy(
                    dialog = d.copy(
                        sharedEmailsDraft = SharedRecipientSelectionPolicy.toggle(d.sharedEmailsDraft, email)
                    )
                )
            }
        }
    }

    fun updateEditGroupWorldShareEnabled(enabled: Boolean) {
        val d = _uiState.value.dialog
        if (d is TrackersGroupsDialog.EditGroup) {
            _uiState.update { it.copy(dialog = d.copy(worldShareEnabledDraft = enabled)) }
        }
    }

    fun updateEditGroupHidden(hidden: Boolean) {
        val d = _uiState.value.dialog
        if (d is TrackersGroupsDialog.EditGroup) {
            _uiState.update { it.copy(dialog = d.copy(hiddenDraft = hidden)) }
        }
    }

    fun addGroupDraftTracker(trackerId: String) {
        val d = _uiState.value.dialog as? TrackersGroupsDialog.EditGroup ?: return
        if (trackerId in d.memberTrackIds) return
        _uiState.update { it.copy(dialog = d.copy(memberTrackIds = d.memberTrackIds + trackerId)) }
    }

    fun removeGroupDraftTracker(trackerId: String) {
        val d = _uiState.value.dialog as? TrackersGroupsDialog.EditGroup ?: return
        if (trackerId !in d.memberTrackIds) return
        _uiState.update { it.copy(dialog = d.copy(memberTrackIds = d.memberTrackIds - trackerId)) }
    }

    fun updateGroupDraftTrackers(trackerIds: Set<String>) {
        val d = _uiState.value.dialog as? TrackersGroupsDialog.EditGroup ?: return
        _uiState.update { it.copy(dialog = d.copy(memberTrackIds = trackerIds)) }
    }

    fun recordImmediateTrackerAdd(trackerId: String) {
        val d = _uiState.value.dialog as? TrackersGroupsDialog.EditGroup ?: return
        _uiState.update {
            it.copy(
                dialog = d.copy(
                    memberTrackIds = d.memberTrackIds + trackerId,
                    initialMemberTrackIds = d.initialMemberTrackIds + trackerId,
                )
            )
        }
    }

    fun toggleGroupWorldShare() {
        val d = _uiState.value.dialog as? TrackersGroupsDialog.EditGroup ?: return
        if (d.visibilityDraft == GroupShareVisibility.PRIVATE) return
        val groupId = d.group.id
        val enabling = !d.worldShareEnabledDraft
        editGroupWorldShareJob?.cancel()
        editGroupWorldShareJob = viewModelScope.launch {
            _uiState.update {
                val cur = it.dialog as? TrackersGroupsDialog.EditGroup ?: return@update it
                if (cur.group.id != groupId) return@update it
                it.copy(
                    dialog = cur.copy(
                        worldShareEnabledDraft = enabling,
                        isWorldShareLinkLoading = true,
                    ),
                )
            }
            val request = com.geovault.tracker.GroupPatchRequest(world_share_enabled = enabling)
            when (val result = groupRepository.patchGroup(groupId, request, publishToStore = true)) {
                is RepositoryResult.Success -> {
                    val g = result.data
                    _uiState.update {
                        val cur = it.dialog as? TrackersGroupsDialog.EditGroup ?: return@update it
                        if (cur.group.id != groupId) return@update it
                        it.copy(
                            dialog = cur.copy(
                                group = g,
                                isWorldShareLinkLoading = false,
                                worldShareEnabledDraft = !g.world_share_id.isNullOrBlank() ||
                                    !g.world_share_url.isNullOrBlank(),
                                worldShareUrlDraft = g.world_share_url,
                            ),
                        )
                    }
                }
                is RepositoryResult.Failure -> {
                    _uiState.update {
                        val cur = it.dialog as? TrackersGroupsDialog.EditGroup ?: return@update it
                        if (cur.group.id != groupId) return@update it
                        it.copy(
                            dialog = cur.copy(
                                worldShareEnabledDraft = !enabling,
                                isWorldShareLinkLoading = false,
                            ),
                            userMessage = getApplication<Application>().getString(
                                if (enabling) R.string.trackers_failed_to_enable_world_share
                                else R.string.trackers_failed_to_disable_world_share,
                            ),
                        )
                    }
                }
            }
        }
    }

    fun leaveGroupFromEditor(groupId: String) {
        runGroupTransition(SharedOwnershipTransitionPolicy.forGroupLeave(groupId))
        dismissDialog()
    }

    fun refreshAll(asPullRefresh: Boolean = false) {
        viewModelScope.launch {
            val forceRefresh = asPullRefresh || !_uiState.value.hasCompletedInitialLoad
            _uiState.update {
                it.copy(
                    isLoading = !asPullRefresh,
                    isPullRefreshing = asPullRefresh,
                    userMessage = null,
                )
            }
            refreshStateFromServer(
                userMessage = null,
                forceRefresh = forceRefresh
            )
        }
    }

    fun refreshTrackersForPicker() {
        viewModelScope.launch {
            _uiState.update { it.copy(isPickerRefreshing = true) }
            val result = trackerRepository.loadTrackers(forceRefresh = true)
            _uiState.update { current ->
                current.copy(
                    isPickerRefreshing = false,
                    trackers = when (result) {
                        is RepositoryResult.Success -> result.data
                        is RepositoryResult.Failure -> current.trackers
                    },
                )
            }
        }
    }

    fun refreshShareRecipientSuggestions() {
        viewModelScope.launch {
            _uiState.update { it.copy(isShareRecipientSuggestionsLoading = true) }
            when (val loaded = trackerRepository.loadUsers()) {
                is RepositoryResult.Success -> {
                    val distinctUsers = loaded.data.users
                        .map { user -> UserItem(id = user.id, email = user.email.trim()) }
                        .filter { it.email.isNotEmpty() }
                        .distinctBy { it.email.lowercase(Locale.getDefault()) }
                        .sortedWith(
                            NaturalSort.naturalOrderBy { it.email.lowercase(Locale.getDefault()) }
                        )
                    _uiState.update {
                        it.copy(
                            isShareRecipientSuggestionsLoading = false,
                            shareRecipientUsers = distinctUsers,
                            shareRecipientSuggestions = distinctUsers
                                .map { user -> user.email.trim().lowercase(Locale.getDefault()) }
                                .filter { it.isNotEmpty() },
                        )
                    }
                }
                is RepositoryResult.Failure -> {
                    _uiState.update { it.copy(isShareRecipientSuggestionsLoading = false) }
                }
            }
        }
    }

    fun submitCreateTracker() {
        val d = _uiState.value.dialog as? TrackersGroupsDialog.CreateTracker ?: return
        val name = d.nameDraft.trim()
        if (name.isEmpty()) {
            _uiState.update { it.copy(userMessage = getApplication<Application>().getString(R.string.trackers_validation_name_required)) }
            return
        }
        val color = d.colorDraft.trim().ifEmpty { null }
        runMutationAndRefresh(
            mutation = {
                trackerRepository.createTracker(TrackerCreateRequest(name = name, color = color))
            },
            onSuccess = { createdTracker ->
                if (d.setAsSelectedTracker) {
                    SelectedTrackerManager.setSelectedTracker(
                        context = getApplication(),
                        trackerId = createdTracker.id,
                        trackerName = createdTracker.name,
                        restartTrackingIfRunning = true
                    )
                }
                dismissDialog()
            }
        )
    }

    fun submitCreateGroup() {
        val d = _uiState.value.dialog as? TrackersGroupsDialog.CreateGroup ?: return
        val name = d.nameDraft.trim()
        if (name.isEmpty()) {
            _uiState.update { it.copy(userMessage = getApplication<Application>().getString(R.string.trackers_validation_name_required)) }
            return
        }
        runMutationAndRefresh(
            mutation = { groupRepository.createGroup(name) },
            onSuccess = {
                dismissDialog()
            }
        )
    }

    fun submitEditTracker() {
        val d = _uiState.value.dialog as? TrackersGroupsDialog.EditTracker ?: return
        val name = d.nameDraft.trim()
        if (name.isEmpty()) {
            _uiState.update { it.copy(userMessage = getApplication<Application>().getString(R.string.trackers_validation_name_required)) }
            return
        }
        val sharingDraft = TrackerSharingDraft(
            visibility = d.visibilityDraft,
            sharedEmailsInput = d.sharedEmailsDraft,
            worldShareEnabled = d.worldShareEnabledDraft
        )
        val sharingValidation = TrackerSharingSettingsPolicy.validate(sharingDraft)
        val app = getApplication<Application>()
        val recentResolved = TrackerRecentDataWindowOptions.resolveValueFromInput(
            context = app,
            rawInput = d.recentDataWindowDraft,
        )
        if (recentResolved == null) {
            _uiState.update {
                it.copy(userMessage = app.getString(R.string.trackers_edit_invalid_recent_data))
            }
            return
        }
        runMutationAndRefresh(
            mutation = {
                trackerRepository.updateTrackerSettings(
                    trackerId = d.tracker.id,
                    request = TrackerSettingsRequest(
                        name = name,
                        color = d.colorDraft.trim().ifBlank { null },
                        recent_data_window = recentResolved,
                        visibility = d.visibilityDraft.apiValue,
                        share_params_with_recipients = d.shareParamsWithRecipientsDraft,
                        share_params_with_world = d.visibilityDraft != TrackerShareVisibility.PRIVATE &&
                            d.worldShareEnabledDraft && d.shareParamsWithWorldDraft,
                        shared_with_emails = if (d.visibilityDraft == TrackerShareVisibility.SHARED) {
                            sharingValidation.normalizedEmails
                        } else {
                            null
                        },
                        world_share_enabled = d.visibilityDraft != TrackerShareVisibility.PRIVATE &&
                            d.worldShareEnabledDraft,
                        hidden = if (d.setAsSelectedTracker) false else d.hiddenDraft,
                        allow_group_reshare = d.allowGroupReshareDraft,
                    ),
                )
            },
            onSuccess = {
                val app = getApplication<Application>()
                val selectedTrackerId = SelectedTrackerPrefs.selectedTrackerId(app)
                if (d.setAsSelectedTracker) {
                    SelectedTrackerManager.setSelectedTracker(
                        context = app,
                        trackerId = d.tracker.id,
                        trackerName = name,
                        restartTrackingIfRunning = true
                    )
                } else if (selectedTrackerId == d.tracker.id) {
                    SelectedTrackerManager.clearSelectedTrackerAndInvalidateCaches(app)
                }
                SelectedTrackerManager.updateSelectedTrackerNameIfSelected(
                    context = app,
                    trackerId = d.tracker.id,
                    trackerName = name
                )
                _toastEvents.tryEmit(app.getString(R.string.trackers_saved_successfully))
                dismissDialog()
            }
        )
    }

    fun submitEditGroup() {
        val d = _uiState.value.dialog as? TrackersGroupsDialog.EditGroup ?: return
        val name = d.nameDraft.trim()
        if (name.isEmpty()) {
            _uiState.update { it.copy(userMessage = getApplication<Application>().getString(R.string.trackers_validation_name_required)) }
            return
        }
        val sharingDraft = GroupSharingDraft(
            visibility = d.visibilityDraft,
            sharedEmailsInput = d.sharedEmailsDraft,
            worldShareEnabled = d.worldShareEnabledDraft
        )
        val addTrackIds = d.memberTrackIds.minus(d.initialMemberTrackIds).toList()
        val removeTrackIds = d.initialMemberTrackIds.minus(d.memberTrackIds).toList()
        runMutationAndRefresh(
            mutation = {
                groupRepository.patchGroup(
                    d.group.id,
                    GroupSharingSettingsPolicy.buildPatchRequest(
                        name = name,
                        sharingDraft = sharingDraft,
                        hidden = d.hiddenDraft,
                        addTrackIds = addTrackIds,
                        removeTrackIds = removeTrackIds,
                    )
                )
            },
            onSuccess = {
                _toastEvents.tryEmit(
                    getApplication<Application>().getString(R.string.trackers_saved_successfully)
                )
                dismissDialog()
            }
        )
    }

    fun toggleTrackerHiddenOnMap(trackerId: String) {
        toggleMapVisibility(
            MapVisibilityToggleTarget(
                id = trackerId,
                type = MapVisibilityToggleEntityType.Tracker
            )
        )
    }

    fun toggleGroupHiddenOnMap(groupId: String) {
        toggleMapVisibility(
            MapVisibilityToggleTarget(
                id = groupId,
                type = MapVisibilityToggleEntityType.Group
            )
        )
    }

    fun leaveTracker(tracker: com.geovault.tracker.Tracker) {
        val command = SharedOwnershipTransitionPolicy.forTrackerLeave(tracker) ?: return
        runTrackerTransition(command)
    }

    fun leaveGroup(groupId: String) {
        runGroupTransition(SharedOwnershipTransitionPolicy.forGroupLeave(groupId))
    }

    fun acceptGroupShare(groupId: String) {
        runGroupTransition(SharedOwnershipTransitionPolicy.forGroupAccept(groupId))
    }

    fun unsubscribeAllTracksInGroup(trackIds: List<String>) {
        val normalizedIds = SharedBulkMutationCoordinator.normalizeIds(trackIds)
        if (normalizedIds.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, isPullRefreshing = false, userMessage = null) }
            var firstFailure: AppError? = null
            val outcome = SharedBulkMutationCoordinator.run(normalizedIds) { id ->
                when (val r = trackerRepository.unsubscribeTracker(id)) {
                    is RepositoryResult.Success -> true
                    is RepositoryResult.Failure -> {
                        if (firstFailure == null) firstFailure = r.error
                        false
                    }
                }
            }
            refreshStateFromServer(
                userMessage = resolveBulkUnsubscribeMessage(outcome, firstFailure),
                forceRefresh = true
            )
        }
    }

    fun clearTrackerHistory(trackerId: String) {
        runMutationAndRefresh(
            mutation = { trackerRepository.clearTrackerHistory(trackerId) },
            successMessage = getApplication<Application>().getString(R.string.trackers_history_cleared)
        )
    }

    fun deleteTracker(trackerId: String) {
        runMutationAndRefresh(
            mutation = { trackerRepository.deleteTracker(trackerId) },
            onSuccess = {
                val app = getApplication<Application>()
                if (SelectedTrackerPrefs.selectedTrackerId(app) == trackerId) {
                    SelectedTrackerManager.clearSelectedTrackerAndInvalidateCaches(app)
                }
            }
            ,
            successMessage = getApplication<Application>().getString(R.string.trackers_deleted)
        )
    }

    fun deleteGroup(groupId: String) {
        runMutationAndRefresh(
            mutation = { groupRepository.deleteGroup(groupId) },
            successMessage = getApplication<Application>().getString(R.string.groups_deleted)
        )
    }

    fun syncGroupTrackMembership(
        groupId: String,
        currentTrackerIds: Set<String>,
        targetTrackerIds: Set<String>,
    ) {
        val syncPlan = GroupMembershipSyncPolicy.plan(
            currentTrackerIds = currentTrackerIds,
            targetTrackerIds = targetTrackerIds,
        )
        if (syncPlan.isNoOp) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, isPullRefreshing = false, userMessage = null) }
            var firstFailure: AppError? = null
            val outcome = GroupMembershipMutationCoordinator.run(
                plan = syncPlan,
                removeTrackerFromGroup = { trackId ->
                    when (val result = groupRepository.removeGroupTrack(groupId, trackId)) {
                        is RepositoryResult.Success -> true
                        is RepositoryResult.Failure -> {
                            if (firstFailure == null) firstFailure = result.error
                            false
                        }
                    }
                },
                addTrackerToGroup = { trackId ->
                    when (val result = groupRepository.addGroupTrack(groupId, trackId)) {
                        is RepositoryResult.Success -> true
                        is RepositoryResult.Failure -> {
                            if (firstFailure == null) firstFailure = result.error
                            false
                        }
                    }
                }
            )
            refreshStateFromServer(
                userMessage = resolveGroupMembershipMessage(outcome, firstFailure),
                forceRefresh = true
            )
        }
    }

    fun addTrackerToGroup(groupId: String, trackerId: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            var shouldRunMutation = false
            _uiState.update { state ->
                val (started, updatedIds) = TrackersGroupAddMutationPolicy.tryBegin(
                    addingTrackerIds = state.addingTrackerIds,
                    trackerId = trackerId,
                )
                if (started) {
                    shouldRunMutation = true
                    state.copy(addingTrackerIds = updatedIds)
                } else {
                    state
                }
            }
            if (!shouldRunMutation) return@launch

            try {
                when (val result = groupRepository.addGroupTrack(groupId, trackerId)) {
                    is RepositoryResult.Success -> onSuccess()
                    is RepositoryResult.Failure -> _toastEvents.emit(appErrorMessage(result.error))
                }
            } finally {
                _uiState.update { state ->
                    state.copy(
                        addingTrackerIds = TrackersGroupAddMutationPolicy.settle(
                            addingTrackerIds = state.addingTrackerIds,
                            trackerId = trackerId,
                        )
                    )
                }
            }
        }
    }

    private fun <T> RepositoryResult<T>.successDataOr(fallback: T): T =
        when (this) {
            is RepositoryResult.Success -> data
            is RepositoryResult.Failure -> fallback
        }

    private suspend fun loadTrackersGroupsSnapshot(forceRefresh: Boolean): TrackersGroupsLoadSnapshot {
        return coroutineScope {
            val tDef = async { trackerRepository.loadTrackers(forceRefresh = forceRefresh) }
            val gDef = async { groupRepository.loadGroups(forceRefresh = forceRefresh) }
            val vDef = async { trackerRepository.loadMapVisibility(forceRefresh = forceRefresh) }
            val tr = tDef.await()
            val gr = gDef.await()
            val vr = vDef.await()
            TrackersGroupsLoadSnapshot(
                trackersResult = tr,
                groupsResult = gr,
                mapVisibilityResult = vr,
                errorMessage = firstError(tr, gr, vr)?.let(::appErrorMessage),
            )
        }
    }

    private fun applyTrackersGroupsSnapshot(
        base: TrackersGroupsUiState,
        snapshot: TrackersGroupsLoadSnapshot,
        userMessageOverride: String?,
    ): TrackersGroupsUiState {
        return base.copy(
            isLoading = false,
            isPullRefreshing = false,
            hasCompletedInitialLoad = true,
            trackers = snapshot.trackersResult.successDataOr(base.trackers),
            groups = snapshot.groupsResult.successDataOr(base.groups),
            mapVisibility = snapshot.mapVisibilityResult.successDataOr(base.mapVisibility),
            userMessage = userMessageOverride,
        )
    }

    private fun runTrackerTransition(command: SharedTrackerTransitionCommand) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, isPullRefreshing = false, userMessage = null) }
            when (val result = executeTrackerTransition(command)) {
                is RepositoryResult.Success -> refreshStateFromServer(
                    userMessage = null,
                    forceRefresh = true
                )
                is RepositoryResult.Failure -> {
                    _uiState.update { it.copy(isLoading = false, isPullRefreshing = false, userMessage = appErrorMessage(result.error)) }
                }
            }
        }
    }

    private fun toggleMapVisibility(target: MapVisibilityToggleTarget) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, isPullRefreshing = false, userMessage = null) }
            when (
                val result = MapVisibilityMutationCoordinator.toggle(
                    current = _uiState.value.mapVisibility,
                    target = target,
                    loadVisibility = { trackerRepository.loadMapVisibility(forceRefresh = true) },
                    patchVisibility = { request -> trackerRepository.patchMapVisibility(request) }
                )
            ) {
                is MapVisibilityMutationResult.Success -> {
                    _uiState.update { it.copy(isLoading = false, mapVisibility = result.visibility) }
                }
                is MapVisibilityMutationResult.Failure -> {
                    _uiState.update { it.copy(isLoading = false, isPullRefreshing = false, userMessage = appErrorMessage(result.error)) }
                }
            }
        }
    }

    private fun runGroupTransition(command: SharedGroupTransitionCommand) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, isPullRefreshing = false, userMessage = null) }
            when (val result = executeGroupTransition(command)) {
                is RepositoryResult.Success -> refreshStateFromServer(
                    userMessage = null,
                    forceRefresh = true
                )
                is RepositoryResult.Failure -> {
                    _uiState.update { it.copy(isLoading = false, isPullRefreshing = false, userMessage = appErrorMessage(result.error)) }
                }
            }
        }
    }

    private suspend fun executeTrackerTransition(
        command: SharedTrackerTransitionCommand
    ): RepositoryResult<Unit> {
        return when (command.action) {
            SharedTrackerTransitionAction.Subscribe -> trackerRepository
                .subscribeTracker(command.trackerId)
                .mapToUnit()
            SharedTrackerTransitionAction.Unsubscribe -> trackerRepository.unsubscribeTracker(command.trackerId)
            SharedTrackerTransitionAction.LeaveShare -> trackerRepository.leaveShareWithMe(command.trackerId)
        }
    }

    private suspend fun executeGroupTransition(
        command: SharedGroupTransitionCommand
    ): RepositoryResult<Unit> {
        return when (command.action) {
            SharedGroupTransitionAction.AcceptShare -> groupRepository
                .acceptGroupShare(command.groupId)
                .mapToUnit()
            SharedGroupTransitionAction.LeaveGroup -> groupRepository.leaveGroup(command.groupId)
        }
    }

    private suspend fun refreshStateFromServer(
        userMessage: String?,
        forceRefresh: Boolean,
    ) {
        val snapshot = loadTrackersGroupsSnapshot(forceRefresh = forceRefresh)
        _uiState.update { current ->
            applyTrackersGroupsSnapshot(
                base = current,
                snapshot = snapshot,
                userMessageOverride = userMessage ?: snapshot.errorMessage
            )
        }
    }

    private fun <T> RepositoryResult<T>.mapToUnit(): RepositoryResult<Unit> {
        return when (this) {
            is RepositoryResult.Success -> RepositoryResult.Success(Unit)
            is RepositoryResult.Failure -> RepositoryResult.Failure(error)
        }
    }

    private fun firstError(vararg results: RepositoryResult<*>): AppError? {
        for (r in results) {
            if (r is RepositoryResult.Failure) return r.error
        }
        return null
    }

    private fun resolveGroupMembershipMessage(
        outcome: GroupMembershipMutationOutcome,
        firstFailure: AppError?
    ): String {
        return when {
            outcome.failedCount == 0 -> getApplication<Application>().getString(R.string.groups_membership_updated)
            outcome.hasAnySuccess -> getApplication<Application>().getString(
                R.string.groups_membership_partial_update,
                outcome.succeededCount,
                outcome.failedCount
            )
            else -> appErrorMessage(firstFailure ?: AppError.Unknown)
        }
    }

    private fun resolveBulkUnsubscribeMessage(
        outcome: SharedBulkMutationOutcome,
        firstFailure: AppError?
    ): String {
        return when {
            outcome.failedCount == 0 ->
                getApplication<Application>().getString(
                    R.string.shared_bulk_unsubscribe_success,
                    outcome.succeededCount
                )
            outcome.hasAnySuccess ->
                getApplication<Application>().getString(
                    R.string.shared_bulk_unsubscribe_partial_failure,
                    outcome.succeededCount,
                    outcome.failedCount
                )
            else -> appErrorMessage(firstFailure ?: AppError.Unknown)
        }
    }

    private fun <T> runMutationAndRefresh(
        mutation: suspend () -> RepositoryResult<T>,
        onSuccess: suspend (T) -> Unit = {},
        successMessage: String? = null,
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, isPullRefreshing = false, userMessage = null) }
            when (val result = TrackersGroupsMutationCoordinator.run(mutation)) {
                is TrackersGroupsMutationResult.Success -> {
                    onSuccess(result.data)
                    refreshStateFromServer(
                        userMessage = successMessage,
                        forceRefresh = true
                    )
                }
                is TrackersGroupsMutationResult.Failure -> {
                    _uiState.update { it.copy(isLoading = false, isPullRefreshing = false, userMessage = appErrorMessage(result.error)) }
                }
            }
        }
    }

    private fun sanitizeKmlBaseFileName(displayName: String): String =
        displayName.map { c -> if (c.isLetterOrDigit() || c in " -_") c else "" }
            .joinToString("")
            .take(40)
            .ifEmpty { "track" }

    private fun appErrorMessage(error: AppError): String {
        val ctx = getApplication<Application>()
        return when (error) {
            AppError.MissingServerUrl -> ctx.getString(R.string.trackers_error_missing_server)
            AppError.Network -> ctx.getString(R.string.trackers_error_network)
            AppError.Unauthorized -> ctx.getString(R.string.trackers_error_unauthorized)
            AppError.NotFound -> ctx.getString(R.string.trackers_error_not_found)
            is AppError.Server -> ctx.getString(R.string.trackers_error_server, error.code)
            is AppError.Validation -> error.message?.takeIf { it.isNotBlank() }
                ?: ctx.getString(R.string.trackers_error_validation)
            AppError.Unknown -> ctx.getString(R.string.trackers_error_unknown)
        }
    }

    private data class TrackersGroupsLoadSnapshot(
        val trackersResult: RepositoryResult<List<com.geovault.tracker.Tracker>>,
        val groupsResult: RepositoryResult<List<Group>>,
        val mapVisibilityResult: RepositoryResult<com.geovault.tracker.MapVisibilityResponse>,
        val errorMessage: String?,
    )
}

data class TrackerKmlExportEvent(val bytes: ByteArray, val fileBaseName: String)

private fun Tracker.settingString(key: String): String {
    return (settings?.get(key) as? String).orEmpty()
}

private fun Tracker.settingBoolean(key: String): Boolean {
    return (settings?.get(key) as? Boolean) == true
}

private fun toEditTrackerDialog(
    tracker: Tracker,
    selectedTrackerId: String?,
): TrackersGroupsDialog.EditTracker {
    return TrackersGroupsDialog.EditTracker(
        tracker = tracker,
        nameDraft = tracker.name,
        colorDraft = tracker.color.orEmpty(),
        setAsSelectedTracker = selectedTrackerId == tracker.id,
        hiddenDraft = tracker.settingBoolean("hidden"),
        recentDataWindowDraft = tracker.settingString("recent_data_window").ifBlank { "all" },
        visibilityDraft = tracker.shareVisibilityForEditing(),
        sharedEmailsDraft = tracker.shared_with_emails.orEmpty().joinToString(", "),
        shareParamsWithRecipientsDraft = tracker.share_params_with_recipients == true,
        allowGroupReshareDraft = tracker.settingBoolean("allow_group_reshare"),
        worldShareEnabledDraft = !tracker.world_share_id.isNullOrBlank() ||
            !tracker.world_share_url.isNullOrBlank(),
        shareParamsWithWorldDraft = tracker.share_params_with_world == true,
        worldShareUrlDraft = tracker.world_share_url,
        isWorldShareLinkLoading = false,
    )
}
