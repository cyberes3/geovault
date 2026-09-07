package com.geovault.tracker.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.common.coroutines.runSuspendCatching
import com.geovault.common.net.GeoVaultApiFailure
import com.geovault.tracker.AvailableToAddResponse
import com.geovault.tracker.Group
import com.geovault.tracker.MapVisibilityResponse
import com.geovault.tracker.R
import com.geovault.tracker.Tracker
import com.geovault.tracker.data.GroupManagementRepository
import com.geovault.tracker.data.TrackerApiFailureMessages
import com.geovault.tracker.data.TrackerBootstrapOutcome
import com.geovault.tracker.data.TrackerManagementRepository
import com.geovault.tracker.data.TrackerSessionWarmup
import com.geovault.tracker.di.TrackerAppServices
import com.geovault.tracker.services.TrackingRuntimeStateStore
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.IOException

class SharedViewModel(application: Application) : AndroidViewModel(application) {

    private val trackerRepository: TrackerManagementRepository =
        TrackerAppServices.from(application).trackerManagementRepository()
    private val groupRepository: GroupManagementRepository =
        TrackerAppServices.from(application).groupManagementRepository()
    private val addRemoveCoordinator = TrackerAddRemoveCoordinator(
        trackerRepository = trackerRepository,
        groupRepository = groupRepository,
    )
    private val sessionWarmup: TrackerSessionWarmup =
        TrackerAppServices.from(application).trackerSessionWarmup()
    private val stateStore = TrackerAppServices.from(application).trackerManagementStateStore()

    private val _uiState = MutableStateFlow(SharedUiState())
    val uiState: StateFlow<SharedUiState> = _uiState.asStateFlow()

    private val _snackbarEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val snackbarEvents: SharedFlow<String> = _snackbarEvents

    /** Successful remove/leave from Shared tracker edit — shell should close that sub-view. */
    private val _dismissSharedTrackerEditId = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val dismissSharedTrackerEditId: SharedFlow<String> = _dismissSharedTrackerEditId

    /** Successful leave from Shared group edit — shell should close that sub-view. */
    private val _dismissSharedGroupEditId = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val dismissSharedGroupEditId: SharedFlow<String> = _dismissSharedGroupEditId
    private var refreshInFlightJob: Job? = null
    private var refreshPending: Boolean = false
    private var refreshPendingFeedbackMessage: String? = null
    private var discoveryLoadJob: Job? = null

    init {
        viewModelScope.launch {
            stateStore.trackers.collectLatest { trackers ->
                _uiState.update {
                    it.copy(
                        trackers = trackers,
                        selectedTrackerId = selectedTrackerId(),
                    )
                }
            }
        }
        viewModelScope.launch {
            stateStore.groups.collectLatest { groups ->
                _uiState.update {
                    it.copy(
                        groups = groups,
                        selectedTrackerId = selectedTrackerId(),
                    )
                }
            }
        }
        viewModelScope.launch {
            stateStore.mapVisibility.collectLatest { mapVisibility ->
                _uiState.update {
                    it.copy(
                        mapVisibility = mapVisibility,
                        selectedTrackerId = selectedTrackerId(),
                    )
                }
            }
        }
    }

    fun showSharedList() {
        _uiState.update { it.copy(viewMode = SharedViewMode.SHARED_LIST) }
    }

    fun updateSharedListQuery(value: String) {
        _uiState.update { it.copy(sharedListQuery = value) }
    }

    fun clearSharedListQuery() {
        _uiState.update { it.copy(sharedListQuery = "") }
    }

    fun showDiscoverOverlay() {
        ensureSharedBootstrapIfNotLoadedYet()
        _uiState.update { current ->
            current.copy(
                viewMode = SharedViewMode.DISCOVER_OVERLAY,
                discoverMode = DiscoverOverlayMode.ON_MY_MAP,
                isLoading = current.isLoading || current.availableToAdd == null,
                retainedIncomingTrackers = emptyMap(),
                retainedIncomingGroups = emptyMap(),
            )
        }
        ensureDiscoveryDataLoaded()
    }

    fun showPublicOverlay() {
        ensureSharedBootstrapIfNotLoadedYet()
        _uiState.update { current ->
            current.copy(
                viewMode = SharedViewMode.PUBLIC_OVERLAY,
                isLoading = current.isLoading || current.availableToAdd == null,
                retainedPublicTrackers = emptyMap(),
                retainedPublicGroups = emptyMap(),
            )
        }
        ensureDiscoveryDataLoaded()
    }

    fun openFromNavigationSubTab(tab: SharedSubTab) {
        when (tab) {
            SharedSubTab.SHARED -> showSharedList()
            SharedSubTab.DISCOVER -> showDiscoverOverlay()
            SharedSubTab.PUBLIC -> showPublicOverlay()
        }
    }

    fun setDiscoverOverlayMode(mode: DiscoverOverlayMode) {
        _uiState.update { current ->
            val clearQueries = current.discoverMode != mode
            current.copy(
                discoverMode = mode,
                discoverOnMapQuery = if (clearQueries) "" else current.discoverOnMapQuery,
                discoverIncomingQuery = if (clearQueries) "" else current.discoverIncomingQuery,
            )
        }
    }

    fun updateDiscoverOnMapQuery(value: String) {
        _uiState.update { it.copy(discoverOnMapQuery = value) }
    }

    fun updateDiscoverIncomingQuery(value: String) {
        _uiState.update { it.copy(discoverIncomingQuery = value) }
    }

    fun updatePublicQuery(value: String) {
        _uiState.update { it.copy(publicQuery = value) }
    }

    private fun startPendingMutation(
        key: String,
        phase: SharedMutationPhase,
        optimisticApply: (SharedUiState) -> SharedUiState = { it },
    ): Boolean {
        var started = false
        _uiState.update { state ->
            if (state.pendingOps.containsKey(key)) {
                state
            } else {
                started = true
                val optimistic = optimisticApply(state)
                optimistic.copy(pendingOps = optimistic.pendingOps + (key to phase))
            }
        }
        return started
    }

    private fun clearPendingMutation(key: String) {
        _uiState.update { state -> state.copy(pendingOps = state.pendingOps - key) }
    }

    private fun optimisticTrackerForId(state: SharedUiState, trackerId: String): Tracker {
        val fromExisting = state.trackers.firstOrNull { it.id == trackerId }
        if (fromExisting != null) return fromExisting
        val fromIncoming = state.availableToAdd
            ?.shared_with_me
            .orEmpty()
            .firstOrNull { it.id == trackerId }
        val fromPublic = state.availableToAdd
            ?.public
            .orEmpty()
            .firstOrNull { it.id == trackerId }
        val fromAvailable = fromIncoming ?: fromPublic
        return Tracker(
            id = trackerId,
            name = fromAvailable?.name ?: trackerId,
            color = fromAvailable?.color,
            owner_email = fromAvailable?.owner_email,
            is_owner = false,
            visibility = if (fromPublic != null && fromIncoming == null) "public" else "shared",
        )
    }

    private fun incomingTrackerForId(state: SharedUiState, trackerId: String) =
        state.availableToAdd?.shared_with_me.orEmpty().firstOrNull { it.id == trackerId }

    private fun incomingGroupForId(state: SharedUiState, groupId: String) =
        state.availableToAdd?.shared_with_me_groups.orEmpty().firstOrNull { it.id == groupId }

    private fun publicTrackerForId(state: SharedUiState, trackerId: String) =
        state.availableToAdd?.public.orEmpty().firstOrNull { it.id == trackerId }

    private fun publicGroupForId(state: SharedUiState, groupId: String) =
        state.availableToAdd?.public_groups.orEmpty().firstOrNull { it.id == groupId }

    private suspend fun refreshStateFromServerSerialized(feedbackMessage: String?) {
        if (!feedbackMessage.isNullOrBlank()) {
            refreshPendingFeedbackMessage = feedbackMessage
        }
        refreshPending = true
        while (refreshPending) {
            val runningJob = refreshInFlightJob
            if (runningJob?.isActive == true) {
                runningJob.join()
                continue
            }
            refreshPending = false
            val nextFeedbackMessage = refreshPendingFeedbackMessage
            refreshPendingFeedbackMessage = null
            refreshInFlightJob = viewModelScope.launch {
                refreshStateFromServer(feedbackMessage = nextFeedbackMessage)
            }
            refreshInFlightJob?.join()
        }
    }

    private fun mutationKeyEditTrackerUnsubscribe(trackerId: String): String = "edit-tracker-unsubscribe-$trackerId"
    private fun mutationKeyEditTrackerLeaveShare(trackerId: String): String = "edit-tracker-leave-share-$trackerId"
    private fun mutationKeyEditGroupLeave(groupId: String): String = "edit-group-leave-$groupId"

    fun editTrackerUnsubscribePendingKey(trackerId: String): String = mutationKeyEditTrackerUnsubscribe(trackerId)
    fun editTrackerLeaveSharePendingKey(trackerId: String): String = mutationKeyEditTrackerLeaveShare(trackerId)
    fun editGroupLeavePendingKey(groupId: String): String = mutationKeyEditGroupLeave(groupId)

    private fun performSharedMutation(operation: SharedAddRemoveOperation) {
        var startResult: SharedMutationStartResult? = null
        val incomingTrackersSnapshot = if (operation is SharedAddRemoveOperation.IncomingGroupAccept) {
            _uiState.value.incomingTrackers
        } else {
            emptyList()
        }
        _uiState.update { state ->
            val result = addRemoveCoordinator.beginSharedMutation(
                state = state,
                operation = operation,
                optimisticTrackerResolver = { trackerId -> optimisticTrackerForId(state, trackerId) },
                incomingTrackerResolver = { trackerId -> incomingTrackerForId(state, trackerId) },
                incomingGroupResolver = { groupId -> incomingGroupForId(state, groupId) },
                publicTrackerResolver = { trackerId -> publicTrackerForId(state, trackerId) },
                publicGroupResolver = { groupId -> publicGroupForId(state, groupId) },
            )
            if (result.started) startResult = result
            result.state
        }
        val started = startResult ?: return
        viewModelScope.launch {
            when (operation) {
                is SharedAddRemoveOperation.IncomingGroupAccept -> {
                    try {
                        val group = addRemoveCoordinator.executeIncomingGroupAccept(operation.groupId)
                        val overlapCount = countOverlappingIncomingShares(
                            incomingTrackersSnapshot,
                            group.track_ids,
                        )
                        refreshStateFromServerSerialized(
                            feedbackMessage = resolveAlsoAcceptedSharesMessage(overlapCount),
                        )
                        _uiState.update { state -> addRemoveCoordinator.applySuccess(state, operation) }
                    } catch (e: GeoVaultApiFailure) {
                        _uiState.update { state -> addRemoveCoordinator.applyFailure(state, operation) }
                        emitSnackbar(apiFailureMessage(e))
                    }
                }
                else -> {
                    try {
                        addRemoveCoordinator.executeSharedMutation(
                            operation = operation,
                            trackerResolver = { trackerId ->
                                _uiState.value.trackers.firstOrNull { it.id == trackerId }
                            },
                        )
                        refreshStateFromServerSerialized(feedbackMessage = null)
                        _uiState.update { state -> addRemoveCoordinator.applySuccess(state, operation) }
                    } catch (e: GeoVaultApiFailure) {
                        _uiState.update { state -> addRemoveCoordinator.applyFailure(state, operation) }
                        emitSnackbar(apiFailureMessage(e))
                    }
                }
            }
            _uiState.update { state -> addRemoveCoordinator.clearPendingMutation(state, started.key) }
        }
    }

    private fun resolveAlsoAcceptedSharesMessage(overlapCount: Int): String? {
        if (overlapCount <= 0) return null
        return getApplication<Application>().resources.getQuantityString(
            R.plurals.shared_group_also_accepted_shares,
            overlapCount,
            overlapCount,
        )
    }

    fun requestIncomingGroupAccept(groupId: String) {
        performSharedMutation(SharedAddRemoveOperation.IncomingGroupAccept(groupId))
    }

    fun requestIncomingTrackerAdd(trackerId: String) {
        performSharedMutation(SharedAddRemoveOperation.IncomingTrackerAdd(trackerId))
    }

    fun requestPublicTrackerAdd(trackerId: String) {
        performSharedMutation(SharedAddRemoveOperation.PublicTrackerAdd(trackerId))
    }

    fun requestPublicTrackerRemove(trackerId: String) {
        performSharedMutation(SharedAddRemoveOperation.PublicTrackerRemove(trackerId))
    }

    fun requestDiscoverOnMapTrackerRemove(trackerId: String) {
        performSharedMutation(SharedAddRemoveOperation.DiscoverOnMapTrackerRemove(trackerId))
    }

    fun requestDiscoverOnMapGroupRemove(groupId: String) {
        performSharedMutation(SharedAddRemoveOperation.DiscoverOnMapGroupRemove(groupId))
    }

    fun requestEditSharedTrackerUnsubscribe(trackerId: String) {
        val key = mutationKeyEditTrackerUnsubscribe(trackerId)
        if (!startPendingMutation(key, SharedMutationPhase.PENDING_REMOVE)) return
        viewModelScope.launch {
            try {
                executeTrackerTransition(
                    SharedTrackerTransitionCommand(
                        trackerId = trackerId,
                        action = SharedTrackerTransitionAction.Unsubscribe,
                    )
                )
                // No forced refresh: repository mutation updates the state store immediately,
                // and Shared UI collects that stream for in-place list updates.
                _dismissSharedTrackerEditId.tryEmit(trackerId)
            } catch (e: GeoVaultApiFailure) {
                emitSnackbar(apiFailureMessage(e))
            }
            clearPendingMutation(key)
        }
    }

    fun requestEditSharedTrackerLeaveShare(trackerId: String) {
        val key = mutationKeyEditTrackerLeaveShare(trackerId)
        if (!startPendingMutation(key, SharedMutationPhase.PENDING_REMOVE)) return
        viewModelScope.launch {
            try {
                executeTrackerTransition(
                    SharedTrackerTransitionCommand(
                        trackerId = trackerId,
                        action = SharedTrackerTransitionAction.LeaveShare,
                    )
                )
                // No forced refresh: repository mutation updates the state store immediately,
                // and Shared UI collects that stream for in-place list updates.
                _dismissSharedTrackerEditId.tryEmit(trackerId)
            } catch (e: GeoVaultApiFailure) {
                emitSnackbar(apiFailureMessage(e))
            }
            clearPendingMutation(key)
        }
    }

    fun requestEditSharedGroupLeave(groupId: String) {
        val key = mutationKeyEditGroupLeave(groupId)
        if (!startPendingMutation(key, SharedMutationPhase.PENDING_REMOVE)) return
        viewModelScope.launch {
            try {
                executeGroupTransition(SharedOwnershipTransitionPolicy.forGroupLeave(groupId))
                _dismissSharedGroupEditId.tryEmit(groupId)
                refreshStateFromServerSerialized(feedbackMessage = null)
            } catch (e: GeoVaultApiFailure) {
                emitSnackbar(apiFailureMessage(e))
            }
            clearPendingMutation(key)
        }
    }

    fun requestPublicGroupAdd(groupId: String) {
        val normalizedGroupId = groupId.trim()
        if (normalizedGroupId.isEmpty()) return
        performSharedMutation(SharedAddRemoveOperation.PublicGroupAdd(normalizedGroupId))
    }

    fun requestPublicGroupRemove(groupId: String) {
        val normalizedGroupId = groupId.trim()
        if (normalizedGroupId.isEmpty()) return
        performSharedMutation(SharedAddRemoveOperation.PublicGroupRemove(normalizedGroupId))
    }

    fun refreshAll() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val snapshot = loadSharedSnapshot(forceRefresh = true)
            _uiState.update { current ->
                applySnapshot(
                    base = current,
                    snapshot = snapshot,
                )
            }
            emitSnackbar(snapshot.errorMessage)
        }
    }

    /** Clears shell bootstrap UI gates after sign-out (see MainScreen auth LaunchedEffect). */
    fun resetSurfacePreloadAfterSignOut() {
        _uiState.update {
            it.copy(
                availableToAdd = null,
                hasCompletedInitialLoad = false,
                isLoading = false,
            )
        }
    }

    /** Runs launch bootstrap when discover/public overlays need store data but shell has not loaded yet. */
    private fun ensureSharedBootstrapIfNotLoadedYet() {
        val current = _uiState.value
        if (current.isLoading || current.hasCompletedInitialLoad) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val outcome = sessionWarmup.runLaunchWarmup()
            _uiState.update { state ->
                state.copy(
                    isLoading = false,
                    hasCompletedInitialLoad = true,
                )
            }
            if (!outcome.isServerAccessible) {
                emitSnackbar(apiFailureMessage(networkApiFailure()))
            }
        }
    }

    /** Shell shows loading before [MainScreenViewModel.runAuthenticatedLaunchBootstrap]. */
    fun beginShellBootstrapUi() {
        _uiState.update { it.copy(isLoading = true) }
    }

    /** Shell applies outcome after shared session bootstrap completes. */
    fun completeShellBootstrapUi(outcome: TrackerBootstrapOutcome) {
        _uiState.update {
            it.copy(
                isLoading = false,
                hasCompletedInitialLoad = true,
            )
        }
        if (!outcome.isServerAccessible) {
            emitSnackbar(apiFailureMessage(networkApiFailure()))
        } else {
            ensureDiscoveryDataLoaded(showLoading = false)
        }
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

    private fun toggleMapVisibility(target: MapVisibilityToggleTarget) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
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
                    _uiState.update { it.copy(isLoading = false) }
                    emitSnackbar(apiFailureMessage(result.error))
                }
            }
        }
    }

    fun leaveTracker(tracker: Tracker) {
        val command = SharedOwnershipTransitionPolicy.forTrackerLeave(tracker) ?: return
        runTrackerTransition(
            command = command,
        )
    }

    /** Reject incoming direct share without subscribing. */
    fun leaveIncomingShare(trackerId: String) {
        runTrackerTransition(SharedOwnershipTransitionPolicy.forIncomingTrackerReject(trackerId))
    }

    /**
     * Remove all subscriptions for tracks in this shared group (user stays in group until [leaveGroup]).
     * This only affects tracker subscriptions; membership is managed independently via [leaveGroup].
     */
    fun unsubscribeAllTracksInGroup(trackIds: List<String>) {
        unsubscribeAllTracksInGroup(trackIds, onSuccess = {}, onSettled = {})
    }

    fun unsubscribeAllTracksInGroup(
        trackIds: List<String>,
        onSuccess: () -> Unit = {},
        onSettled: () -> Unit = {},
    ) {
        val normalizedIds = SharedBulkMutationCoordinator.normalizeIds(trackIds)
        if (normalizedIds.isEmpty()) {
            onSettled()
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            var firstFailure: GeoVaultApiFailure? = null
            val outcome = SharedBulkMutationCoordinator.run(normalizedIds) { id ->
                try {
                    trackerRepository.unsubscribeTracker(id)
                    true
                } catch (e: GeoVaultApiFailure) {
                    if (firstFailure == null) firstFailure = e
                    false
                }
            }
            refreshStateFromServer(feedbackMessage = resolveBulkUnsubscribeMessage(outcome, firstFailure))
            if (outcome.failedCount == 0 && outcome.succeededCount > 0) {
                onSuccess()
            }
            onSettled()
        }
    }

    private suspend fun loadSharedSnapshot(forceRefresh: Boolean): SharedLoadSnapshot {
        return coroutineScope {
            val tDef = async { runSuspendCatching { trackerRepository.loadTrackers(forceRefresh = forceRefresh) } }
            val gDef = async { runSuspendCatching { groupRepository.loadGroups(forceRefresh = forceRefresh) } }
            val aDef = async { runSuspendCatching { trackerRepository.loadAvailableToAdd(forceRefresh = forceRefresh) } }
            val vDef = async { runSuspendCatching { trackerRepository.loadMapVisibility(forceRefresh = forceRefresh) } }
            val tr = tDef.await()
            val gr = gDef.await()
            val ar = aDef.await()
            val vr = vDef.await()
            SharedLoadSnapshot(
                trackers = tr,
                groups = gr,
                availableToAdd = ar,
                mapVisibility = vr,
                errorMessage = firstApiFailure(listOf(tr, gr, ar, vr))?.let(::apiFailureMessage)
            )
        }
    }

    private fun runTrackerTransition(
        command: SharedTrackerTransitionCommand,
        onSuccess: () -> Unit = {},
        onFailure: () -> Unit = {},
        onSettled: () -> Unit = {},
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                executeTrackerTransition(command)
                refreshStateFromServer(feedbackMessage = null)
                onSuccess()
            } catch (e: GeoVaultApiFailure) {
                _uiState.update { it.copy(isLoading = false) }
                emitSnackbar(apiFailureMessage(e))
                onFailure()
            }
            onSettled()
        }
    }

    private suspend fun executeTrackerTransition(
        command: SharedTrackerTransitionCommand
    ) {
        when (command.action) {
            SharedTrackerTransitionAction.Subscribe ->
                trackerRepository.subscribeTracker(command.trackerId)
            SharedTrackerTransitionAction.Unsubscribe ->
                trackerRepository.unsubscribeTracker(command.trackerId)
            SharedTrackerTransitionAction.LeaveShare ->
                trackerRepository.leaveShareWithMe(command.trackerId)
        }
    }

    private suspend fun executeGroupTransition(
        command: SharedGroupTransitionCommand
    ) {
        when (command.action) {
            SharedGroupTransitionAction.AcceptShare ->
                groupRepository.acceptGroupShare(command.groupId)
            SharedGroupTransitionAction.LeaveGroup ->
                groupRepository.leaveGroup(command.groupId)
        }
    }

    /**
     * Mutation outcomes are surfaced to users without reserving inline UI slots.
     * State stays deterministic and one-shot snackbar events report outcomes/errors.
     */
    private suspend fun refreshStateFromServer(feedbackMessage: String?) {
        val snapshot = loadSharedSnapshot(forceRefresh = true)
        _uiState.update { current ->
            applySnapshot(
                base = current,
                snapshot = snapshot,
            )
        }
        emitSnackbar(feedbackMessage ?: snapshot.errorMessage)
    }

    private fun applySnapshot(
        base: SharedUiState,
        snapshot: SharedLoadSnapshot,
    ): SharedUiState {
        val next = base.copy(
            isLoading = false,
            trackers = snapshot.trackers.getOrDefault(base.trackers),
            groups = snapshot.groups.getOrDefault(base.groups),
            availableToAdd = snapshot.availableToAdd.getOrNull() ?: base.availableToAdd,
            mapVisibility = snapshot.mapVisibility.getOrNull() ?: base.mapVisibility,
            hasCompletedInitialLoad = true,
            selectedTrackerId = selectedTrackerId(),
        )
        return next
    }

    private fun selectedTrackerId(): String =
        TrackingRuntimeStateStore.state.value.selectedTrackerId.trim()

    private fun resolveBulkUnsubscribeMessage(
        outcome: SharedBulkMutationOutcome,
        firstFailure: GeoVaultApiFailure?
    ): String {
        return when (SharedViewModelContracts.resolveBulkFeedbackKind(outcome)) {
            SharedBulkFeedbackKind.SUCCESS ->
                getApplication<Application>().getString(
                    R.string.shared_bulk_unsubscribe_success,
                    outcome.succeededCount
                )
            SharedBulkFeedbackKind.PARTIAL_FAILURE ->
                getApplication<Application>().getString(
                    R.string.shared_bulk_unsubscribe_partial_failure,
                    outcome.succeededCount,
                    outcome.failedCount
                )
            SharedBulkFeedbackKind.FULL_FAILURE -> apiFailureMessage(firstFailure ?: unknownApiFailure())
        }
    }

    private fun firstApiFailure(results: List<Result<*>>): GeoVaultApiFailure? {
        for (r in results) {
            val error = r.exceptionOrNull() ?: continue
            if (error !is GeoVaultApiFailure) throw error
            return error
        }
        return null
    }

    private fun apiFailureMessage(failure: GeoVaultApiFailure): String =
        TrackerApiFailureMessages.format(getApplication(), failure)

    private fun networkApiFailure(): GeoVaultApiFailure =
        GeoVaultApiFailure.fromThrowable(IOException())

    private fun unknownApiFailure(): GeoVaultApiFailure =
        GeoVaultApiFailure(httpCode = null, serverMessage = null)

    private fun emitSnackbar(message: String?) {
        if (message.isNullOrBlank()) return
        _snackbarEvents.tryEmit(message)
    }

    private fun ensureDiscoveryDataLoaded(showLoading: Boolean = true) {
        if (_uiState.value.availableToAdd != null || discoveryLoadJob?.isActive == true) return
        discoveryLoadJob = viewModelScope.launch {
            if (showLoading) {
                _uiState.update { it.copy(isLoading = true) }
            }
            try {
                val available = trackerRepository.loadAvailableToAdd(forceRefresh = false)
                _uiState.update {
                    it.copy(
                        isLoading = if (showLoading || it.viewMode != SharedViewMode.SHARED_LIST) {
                            false
                        } else {
                            it.isLoading
                        },
                        availableToAdd = available,
                    )
                }
            } catch (e: GeoVaultApiFailure) {
                if (showLoading || _uiState.value.viewMode != SharedViewMode.SHARED_LIST) {
                    _uiState.update { it.copy(isLoading = false) }
                }
                emitSnackbar(apiFailureMessage(e))
            }
        }
    }

    private data class SharedLoadSnapshot(
        val trackers: Result<List<Tracker>>,
        val groups: Result<List<Group>>,
        val availableToAdd: Result<AvailableToAddResponse>,
        val mapVisibility: Result<MapVisibilityResponse>,
        val errorMessage: String?
    )
}
