package com.geovault.tracker.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.tracker.AppError
import com.geovault.tracker.R
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.Tracker
import com.geovault.tracker.data.GroupManagementRepository
import com.geovault.tracker.data.TrackerBootstrapOutcome
import com.geovault.tracker.data.TrackerManagementRepository
import com.geovault.tracker.data.TrackerSessionBootstrap
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

class SharedViewModel(application: Application) : AndroidViewModel(application) {

    private val trackerRepository: TrackerManagementRepository =
        TrackerAppServices.from(application).trackerManagementRepository()
    private val groupRepository: GroupManagementRepository =
        TrackerAppServices.from(application).groupManagementRepository()
    private val addRemoveCoordinator = TrackerAddRemoveCoordinator(
        trackerRepository = trackerRepository,
        groupRepository = groupRepository,
    )
    private val sessionBootstrap: TrackerSessionBootstrap =
        TrackerAppServices.from(application).trackerSessionBootstrap()
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
            when (
                val result = addRemoveCoordinator.executeSharedMutation(
                    operation = operation,
                    trackerResolver = { trackerId ->
                        _uiState.value.trackers.firstOrNull { it.id == trackerId }
                    },
                )
            ) {
                is RepositoryResult.Success -> {
                    refreshStateFromServerSerialized(feedbackMessage = null)
                    _uiState.update { state -> addRemoveCoordinator.applySuccess(state, operation) }
                }
                is RepositoryResult.Failure -> {
                    _uiState.update { state -> addRemoveCoordinator.applyFailure(state, operation) }
                    emitSnackbar(appErrorMessage(result.error))
                }
            }
            _uiState.update { state -> addRemoveCoordinator.clearPendingMutation(state, started.key) }
        }
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
            when (
                val result = executeTrackerTransition(
                    SharedTrackerTransitionCommand(
                        trackerId = trackerId,
                        action = SharedTrackerTransitionAction.Unsubscribe,
                    )
                )
            ) {
                // No forced refresh: repository mutation updates the state store immediately,
                // and Shared UI collects that stream for in-place list updates.
                is RepositoryResult.Success -> {
                    _dismissSharedTrackerEditId.tryEmit(trackerId)
                }
                is RepositoryResult.Failure -> emitSnackbar(appErrorMessage(result.error))
            }
            clearPendingMutation(key)
        }
    }

    fun requestEditSharedTrackerLeaveShare(trackerId: String) {
        val key = mutationKeyEditTrackerLeaveShare(trackerId)
        if (!startPendingMutation(key, SharedMutationPhase.PENDING_REMOVE)) return
        viewModelScope.launch {
            when (
                val result = executeTrackerTransition(
                    SharedTrackerTransitionCommand(
                        trackerId = trackerId,
                        action = SharedTrackerTransitionAction.LeaveShare,
                    )
                )
            ) {
                // No forced refresh: repository mutation updates the state store immediately,
                // and Shared UI collects that stream for in-place list updates.
                is RepositoryResult.Success -> {
                    _dismissSharedTrackerEditId.tryEmit(trackerId)
                }
                is RepositoryResult.Failure -> emitSnackbar(appErrorMessage(result.error))
            }
            clearPendingMutation(key)
        }
    }

    fun requestEditSharedGroupLeave(groupId: String) {
        val key = mutationKeyEditGroupLeave(groupId)
        if (!startPendingMutation(key, SharedMutationPhase.PENDING_REMOVE)) return
        viewModelScope.launch {
            when (val result = executeGroupTransition(SharedOwnershipTransitionPolicy.forGroupLeave(groupId))) {
                is RepositoryResult.Success -> {
                    _dismissSharedGroupEditId.tryEmit(groupId)
                    refreshStateFromServerSerialized(feedbackMessage = null)
                }
                is RepositoryResult.Failure -> emitSnackbar(appErrorMessage(result.error))
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
            val outcome = sessionBootstrap.runLaunchBootstrap()
            _uiState.update { state ->
                state.copy(
                    isLoading = false,
                    hasCompletedInitialLoad = true,
                )
            }
            if (!outcome.isServerAccessible) {
                emitSnackbar(appErrorMessage(AppError.Network))
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
            emitSnackbar(appErrorMessage(AppError.Network))
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
                    emitSnackbar(appErrorMessage(result.error))
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
            refreshStateFromServer(feedbackMessage = resolveBulkUnsubscribeMessage(outcome, firstFailure))
            if (outcome.failedCount == 0 && outcome.succeededCount > 0) {
                onSuccess()
            }
            onSettled()
        }
    }

    private suspend fun loadSharedSnapshot(forceRefresh: Boolean): SharedLoadSnapshot {
        return coroutineScope {
            val tDef = async { trackerRepository.loadTrackers(forceRefresh = forceRefresh) }
            val gDef = async { groupRepository.loadGroups(forceRefresh = forceRefresh) }
            val aDef = async { trackerRepository.loadAvailableToAdd(forceRefresh = forceRefresh) }
            val vDef = async { trackerRepository.loadMapVisibility(forceRefresh = forceRefresh) }
            val tr = tDef.await()
            val gr = gDef.await()
            val ar = aDef.await()
            val vr = vDef.await()
            SharedLoadSnapshot(
                trackersResult = tr,
                groupsResult = gr,
                availableToAddResult = ar,
                mapVisibilityResult = vr,
                errorMessage = firstError(tr, gr, ar, vr)?.let(::appErrorMessage)
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
            when (val result = executeTrackerTransition(command)) {
                is RepositoryResult.Success -> {
                    refreshStateFromServer(feedbackMessage = null)
                    onSuccess()
                }
                is RepositoryResult.Failure -> {
                    _uiState.update { it.copy(isLoading = false) }
                    emitSnackbar(appErrorMessage(result.error))
                    onFailure()
                }
            }
            onSettled()
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
            trackers = snapshot.trackersResult.successDataOr(base.trackers),
            groups = snapshot.groupsResult.successDataOr(base.groups),
            availableToAdd = snapshot.availableToAddResult.successDataOr(base.availableToAdd),
            mapVisibility = snapshot.mapVisibilityResult.successDataOr(base.mapVisibility),
            hasCompletedInitialLoad = true,
            selectedTrackerId = selectedTrackerId(),
        )
        return next
    }

    private fun selectedTrackerId(): String =
        TrackingRuntimeStateStore.state.value.selectedTrackerId.trim()

    private fun resolveBulkUnsubscribeMessage(
        outcome: SharedBulkMutationOutcome,
        firstFailure: AppError?
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
            SharedBulkFeedbackKind.FULL_FAILURE -> appErrorMessage(firstFailure ?: AppError.Unknown)
        }
    }

    private fun <T> RepositoryResult<T>.successDataOr(fallback: T): T =
        when (this) {
            is RepositoryResult.Success -> data
            is RepositoryResult.Failure -> fallback
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
            when (val result = trackerRepository.loadAvailableToAdd(forceRefresh = false)) {
                is RepositoryResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = if (showLoading || it.viewMode != SharedViewMode.SHARED_LIST) {
                                false
                            } else {
                                it.isLoading
                            },
                            availableToAdd = result.data,
                        )
                    }
                }
                is RepositoryResult.Failure -> {
                    if (showLoading || _uiState.value.viewMode != SharedViewMode.SHARED_LIST) {
                        _uiState.update { it.copy(isLoading = false) }
                    }
                    emitSnackbar(appErrorMessage(result.error))
                }
            }
        }
    }

    private data class SharedLoadSnapshot(
        val trackersResult: RepositoryResult<List<Tracker>>,
        val groupsResult: RepositoryResult<List<com.geovault.tracker.Group>>,
        val availableToAddResult: RepositoryResult<com.geovault.tracker.AvailableToAddResponse>,
        val mapVisibilityResult: RepositoryResult<com.geovault.tracker.MapVisibilityResponse>,
        val errorMessage: String?
    )
}
