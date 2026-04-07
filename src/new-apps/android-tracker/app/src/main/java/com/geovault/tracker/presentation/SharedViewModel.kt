package com.geovault.tracker.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.tracker.AppError
import com.geovault.tracker.R
import com.geovault.tracker.RepositoryResult
import com.geovault.tracker.Tracker
import com.geovault.tracker.data.GroupManagementRepository
import com.geovault.tracker.data.TrackerManagementRepository
import com.geovault.tracker.di.TrackerAppServices
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SharedViewModel(application: Application) : AndroidViewModel(application) {

    private val trackerRepository: TrackerManagementRepository =
        TrackerAppServices.from(application).trackerManagementRepository()
    private val groupRepository: GroupManagementRepository =
        TrackerAppServices.from(application).groupManagementRepository()

    private val _uiState = MutableStateFlow(SharedUiState())
    val uiState: StateFlow<SharedUiState> = _uiState.asStateFlow()

    private val _snackbarEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val snackbarEvents: SharedFlow<String> = _snackbarEvents
    private val refreshMutex = Mutex()

    fun showSharedList() {
        _uiState.update { it.copy(viewMode = SharedViewMode.SHARED_LIST) }
    }

    fun showDiscoverOverlay() {
        preloadSharedSurface()
        _uiState.update { current ->
            current.copy(
                viewMode = SharedViewMode.DISCOVER_OVERLAY,
                discoverMode = DiscoverOverlayMode.ON_MY_MAP,
                isLoading = current.isLoading || current.availableToAdd == null,
            )
        }
        ensureDiscoveryDataLoaded()
    }

    fun showPublicOverlay() {
        preloadSharedSurface()
        _uiState.update { current ->
            current.copy(
                viewMode = SharedViewMode.PUBLIC_OVERLAY,
                isLoading = current.isLoading || current.availableToAdd == null,
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
        val fromAvailable = state.availableToAdd
            ?.shared_with_me
            .orEmpty()
            .firstOrNull { it.id == trackerId }
            ?: state.availableToAdd
                ?.public
                .orEmpty()
                .firstOrNull { it.id == trackerId }
        return Tracker(
            id = trackerId,
            name = fromAvailable?.name ?: trackerId,
            color = fromAvailable?.color,
            owner_email = fromAvailable?.owner_email,
            is_owner = false,
            visibility = "shared",
        )
    }

    private suspend fun refreshStateFromServerSerialized(feedbackMessage: String?) {
        refreshMutex.withLock {
            refreshStateFromServer(feedbackMessage = feedbackMessage)
        }
    }

    private fun mutationKeyIncomingGroup(groupId: String): String = "incoming-group-$groupId"
    private fun mutationKeyIncomingTrackerAdd(trackerId: String): String = "incoming-tracker-$trackerId"
    private fun mutationKeyPublicTrackerAdd(trackerId: String): String = "public-tracker-$trackerId"
    private fun mutationKeyPublicTrackerRemove(trackerId: String): String = "public-remove-tracker-$trackerId"
    private fun mutationKeyPublicGroupAdd(trackIds: List<String>): String =
        "public-group-${trackIds.sorted().joinToString(separator = ",")}"
    private fun mutationKeyPublicGroupRemove(trackIds: List<String>): String =
        "public-remove-group-${trackIds.sorted().joinToString(separator = ",")}"
    private fun mutationKeyDiscoverOnMapTrackerRemove(trackerId: String): String = "discover-remove-tracker-$trackerId"
    private fun mutationKeyDiscoverOnMapGroupRemove(groupId: String): String = "discover-remove-group-$groupId"
    private fun mutationKeyEditTrackerUnsubscribe(trackerId: String): String = "edit-tracker-unsubscribe-$trackerId"
    private fun mutationKeyEditTrackerLeaveShare(trackerId: String): String = "edit-tracker-leave-share-$trackerId"
    private fun mutationKeyEditGroupLeave(groupId: String): String = "edit-group-leave-$groupId"

    fun editTrackerUnsubscribePendingKey(trackerId: String): String = mutationKeyEditTrackerUnsubscribe(trackerId)
    fun editTrackerLeaveSharePendingKey(trackerId: String): String = mutationKeyEditTrackerLeaveShare(trackerId)
    fun editGroupLeavePendingKey(groupId: String): String = mutationKeyEditGroupLeave(groupId)

    fun requestIncomingGroupAccept(groupId: String) {
        val key = mutationKeyIncomingGroup(groupId)
        if (!startPendingMutation(key, SharedMutationPhase.PENDING_ADD)) return
        viewModelScope.launch {
            when (val result = executeGroupTransition(SharedOwnershipTransitionPolicy.forGroupAccept(groupId))) {
                is RepositoryResult.Success -> refreshStateFromServerSerialized(feedbackMessage = null)
                is RepositoryResult.Failure -> {
                    emitSnackbar(appErrorMessage(result.error))
                }
            }
            clearPendingMutation(key)
        }
    }

    fun requestIncomingTrackerAdd(trackerId: String) {
        val key = mutationKeyIncomingTrackerAdd(trackerId)
        if (!startPendingMutation(key, SharedMutationPhase.PENDING_ADD) { state ->
                state.copy(
                    optimisticTrackerAdds = state.optimisticTrackerAdds + (trackerId to optimisticTrackerForId(state, trackerId)),
                    optimisticTrackerRemovals = state.optimisticTrackerRemovals - trackerId,
                    optimisticDiscoverOnMapRemovals = state.optimisticDiscoverOnMapRemovals - trackerId,
                )
            }
        ) return
        viewModelScope.launch {
            when (val result = executeTrackerTransition(SharedOwnershipTransitionPolicy.forIncomingTrackerSubscribe(trackerId))) {
                is RepositoryResult.Success -> {
                    refreshStateFromServerSerialized(feedbackMessage = null)
                    _uiState.update { it.copy(optimisticTrackerAdds = it.optimisticTrackerAdds - trackerId) }
                }
                is RepositoryResult.Failure -> {
                    _uiState.update { it.copy(optimisticTrackerAdds = it.optimisticTrackerAdds - trackerId) }
                    emitSnackbar(appErrorMessage(result.error))
                }
            }
            clearPendingMutation(key)
        }
    }

    fun requestPublicTrackerAdd(trackerId: String) {
        val key = mutationKeyPublicTrackerAdd(trackerId)
        if (!startPendingMutation(key, SharedMutationPhase.PENDING_ADD) { state ->
                state.copy(
                    optimisticTrackerAdds = state.optimisticTrackerAdds + (trackerId to optimisticTrackerForId(state, trackerId)),
                    optimisticTrackerRemovals = state.optimisticTrackerRemovals - trackerId,
                )
            }
        ) return
        viewModelScope.launch {
            when (val result = executeTrackerTransition(SharedOwnershipTransitionPolicy.forPublicTrackerSubscribe(trackerId))) {
                is RepositoryResult.Success -> {
                    refreshStateFromServerSerialized(feedbackMessage = null)
                    _uiState.update { it.copy(optimisticTrackerAdds = it.optimisticTrackerAdds - trackerId) }
                }
                is RepositoryResult.Failure -> {
                    _uiState.update { it.copy(optimisticTrackerAdds = it.optimisticTrackerAdds - trackerId) }
                    emitSnackbar(appErrorMessage(result.error))
                }
            }
            clearPendingMutation(key)
        }
    }

    fun requestPublicTrackerRemove(trackerId: String) {
        val key = mutationKeyPublicTrackerRemove(trackerId)
        if (!startPendingMutation(key, SharedMutationPhase.PENDING_REMOVE) { state ->
                state.copy(
                    optimisticTrackerAdds = state.optimisticTrackerAdds - trackerId,
                    optimisticTrackerRemovals = state.optimisticTrackerRemovals + trackerId,
                )
            }
        ) return
        viewModelScope.launch {
            when (val result = executeTrackerTransition(
                SharedTrackerTransitionCommand(
                    trackerId = trackerId,
                    action = SharedTrackerTransitionAction.Unsubscribe,
                )
            )) {
                is RepositoryResult.Success -> {
                    refreshStateFromServerSerialized(feedbackMessage = null)
                    _uiState.update { it.copy(optimisticTrackerRemovals = it.optimisticTrackerRemovals - trackerId) }
                }
                is RepositoryResult.Failure -> {
                    _uiState.update { it.copy(optimisticTrackerRemovals = it.optimisticTrackerRemovals - trackerId) }
                    emitSnackbar(appErrorMessage(result.error))
                }
            }
            clearPendingMutation(key)
        }
    }

    fun requestDiscoverOnMapTrackerRemove(trackerId: String) {
        val tracker = _uiState.value.trackers.firstOrNull { it.id == trackerId } ?: return
        val key = mutationKeyDiscoverOnMapTrackerRemove(trackerId)
        if (!startPendingMutation(key, SharedMutationPhase.PENDING_REMOVE) { state ->
                state.copy(
                    optimisticTrackerAdds = state.optimisticTrackerAdds - trackerId,
                    optimisticTrackerRemovals = state.optimisticTrackerRemovals + trackerId,
                    optimisticDiscoverOnMapRemovals = state.optimisticDiscoverOnMapRemovals + trackerId,
                )
            }
        ) return
        viewModelScope.launch {
            val command = SharedOwnershipTransitionPolicy.forTrackerLeave(tracker) ?: return@launch
            when (val result = executeTrackerTransition(command)) {
                is RepositoryResult.Success -> {
                    refreshStateFromServerSerialized(feedbackMessage = null)
                    _uiState.update {
                        it.copy(
                            optimisticTrackerRemovals = it.optimisticTrackerRemovals - trackerId,
                            optimisticDiscoverOnMapRemovals = it.optimisticDiscoverOnMapRemovals - trackerId,
                        )
                    }
                }
                is RepositoryResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            optimisticTrackerRemovals = it.optimisticTrackerRemovals - trackerId,
                            optimisticDiscoverOnMapRemovals = it.optimisticDiscoverOnMapRemovals - trackerId,
                        )
                    }
                    emitSnackbar(appErrorMessage(result.error))
                }
            }
            clearPendingMutation(key)
        }
    }

    fun requestDiscoverOnMapGroupRemove(groupId: String) {
        val key = mutationKeyDiscoverOnMapGroupRemove(groupId)
        if (!startPendingMutation(key, SharedMutationPhase.PENDING_REMOVE)) return
        viewModelScope.launch {
            when (val result = executeGroupTransition(SharedOwnershipTransitionPolicy.forGroupLeave(groupId))) {
                is RepositoryResult.Success -> refreshStateFromServerSerialized(feedbackMessage = null)
                is RepositoryResult.Failure -> emitSnackbar(appErrorMessage(result.error))
            }
            clearPendingMutation(key)
        }
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
                is RepositoryResult.Success -> refreshStateFromServerSerialized(feedbackMessage = null)
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
                is RepositoryResult.Success -> refreshStateFromServerSerialized(feedbackMessage = null)
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
                is RepositoryResult.Success -> refreshStateFromServerSerialized(feedbackMessage = null)
                is RepositoryResult.Failure -> emitSnackbar(appErrorMessage(result.error))
            }
            clearPendingMutation(key)
        }
    }

    fun requestPublicGroupAdd(trackIds: List<String>) {
        val normalizedIds = SharedBulkMutationCoordinator.normalizeIds(trackIds)
        if (normalizedIds.isEmpty()) return
        val key = mutationKeyPublicGroupAdd(normalizedIds)
        if (!startPendingMutation(key, SharedMutationPhase.PENDING_ADD) { state ->
                val optimisticAdds = normalizedIds.associateWith { id -> optimisticTrackerForId(state, id) }
                state.copy(
                    optimisticTrackerAdds = state.optimisticTrackerAdds + optimisticAdds,
                    optimisticTrackerRemovals = state.optimisticTrackerRemovals - normalizedIds.toSet(),
                )
            }
        ) return
        viewModelScope.launch {
            var firstFailure: AppError? = null
            val outcome = SharedBulkMutationCoordinator.run(normalizedIds) { id ->
                when (val r = trackerRepository.subscribeTracker(id)) {
                    is RepositoryResult.Success -> true
                    is RepositoryResult.Failure -> {
                        if (firstFailure == null) firstFailure = r.error
                        false
                    }
                }
            }
            refreshStateFromServerSerialized(resolveBulkSubscribeMessage(outcome, firstFailure))
            _uiState.update { it.copy(optimisticTrackerAdds = it.optimisticTrackerAdds - normalizedIds.toSet()) }
            clearPendingMutation(key)
        }
    }

    fun requestPublicGroupRemove(trackIds: List<String>) {
        val normalizedIds = SharedBulkMutationCoordinator.normalizeIds(trackIds)
        if (normalizedIds.isEmpty()) return
        val key = mutationKeyPublicGroupRemove(normalizedIds)
        if (!startPendingMutation(key, SharedMutationPhase.PENDING_REMOVE) { state ->
                state.copy(
                    optimisticTrackerAdds = state.optimisticTrackerAdds - normalizedIds.toSet(),
                    optimisticTrackerRemovals = state.optimisticTrackerRemovals + normalizedIds.toSet(),
                )
            }
        ) return
        viewModelScope.launch {
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
            refreshStateFromServerSerialized(resolveBulkUnsubscribeMessage(outcome, firstFailure))
            _uiState.update { it.copy(optimisticTrackerRemovals = it.optimisticTrackerRemovals - normalizedIds.toSet()) }
            clearPendingMutation(key)
        }
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

    fun preloadSharedSurface() {
        val current = _uiState.value
        if (current.isLoading || current.hasCompletedInitialLoad) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val trackersDef = async { trackerRepository.loadTrackers(forceRefresh = false) }
            val groupsDef = async { groupRepository.loadGroups(forceRefresh = false) }
            val visibilityDef = async { trackerRepository.loadMapVisibility(forceRefresh = false) }
            val trackersResult = trackersDef.await()
            val groupsResult = groupsDef.await()
            val visibilityResult = visibilityDef.await()
            _uiState.update { state ->
                state.copy(
                    isLoading = false,
                    trackers = trackersResult.successDataOr(state.trackers),
                    groups = groupsResult.successDataOr(state.groups),
                    mapVisibility = visibilityResult.successDataOr(state.mapVisibility),
                    hasCompletedInitialLoad = true,
                )
            }
            emitSnackbar(firstError(trackersResult, groupsResult, visibilityResult)?.let(::appErrorMessage))
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
        leaveTracker(tracker, onSuccess = {}, onFailure = {}, onSettled = {})
    }

    fun leaveTracker(
        tracker: Tracker,
        onSuccess: () -> Unit = {},
        onFailure: () -> Unit = {},
        onSettled: () -> Unit = {},
    ) {
        val command = SharedOwnershipTransitionPolicy.forTrackerLeave(tracker) ?: return
        runTrackerTransition(
            command = command,
            onSuccess = onSuccess,
            onFailure = onFailure,
            onSettled = onSettled,
        )
    }

    fun unsubscribeTracker(trackerId: String) {
        runTrackerTransition(
            SharedTrackerTransitionCommand(
                trackerId = trackerId,
                action = SharedTrackerTransitionAction.Unsubscribe
            )
        )
    }

    fun leaveTrackerShare(trackerId: String) {
        runTrackerTransition(
            SharedTrackerTransitionCommand(
                trackerId = trackerId,
                action = SharedTrackerTransitionAction.LeaveShare
            )
        )
    }

    fun leaveGroup(groupId: String) {
        runGroupTransition(SharedOwnershipTransitionPolicy.forGroupLeave(groupId))
    }

    fun acceptGroupShare(groupId: String, onSettled: () -> Unit = {}) {
        runGroupTransition(
            command = SharedOwnershipTransitionPolicy.forGroupAccept(groupId),
            onSettled = onSettled,
        )
    }

    /** Incoming shared tracker: add to my trackers (subscribe). */
    fun subscribeIncomingTracker(
        trackerId: String,
        onSuccess: () -> Unit = {},
        onFailure: () -> Unit = {},
        onSettled: () -> Unit = {},
    ) {
        runTrackerTransition(
            command = SharedOwnershipTransitionPolicy.forIncomingTrackerSubscribe(trackerId),
            onSuccess = onSuccess,
            onFailure = onFailure,
            onSettled = onSettled,
        )
    }

    /** Reject incoming direct share without subscribing. */
    fun leaveIncomingShare(trackerId: String) {
        runTrackerTransition(SharedOwnershipTransitionPolicy.forIncomingTrackerReject(trackerId))
    }

    fun subscribePublicTracker(trackerId: String) {
        subscribePublicTracker(trackerId, onSuccess = {}, onSettled = {})
    }

    fun subscribePublicTracker(
        trackerId: String,
        onSuccess: () -> Unit = {},
        onFailure: () -> Unit = {},
        onSettled: () -> Unit = {},
    ) {
        runTrackerTransition(
            command = SharedOwnershipTransitionPolicy.forPublicTrackerSubscribe(trackerId),
            onSuccess = onSuccess,
            onFailure = onFailure,
            onSettled = onSettled,
        )
    }

    /**
     * Subscribe to every addable track in a public group.
     * Mutations are applied per-track; state is always refreshed afterwards to match server truth.
     */
    fun subscribePublicGroup(trackIds: List<String>) {
        subscribePublicGroup(trackIds, onSuccess = {}, onSettled = {})
    }

    fun subscribePublicGroup(
        trackIds: List<String>,
        onSuccess: () -> Unit = {},
        onSettled: () -> Unit = {},
    ) {
        val normalizedIds = SharedBulkMutationCoordinator.normalizeIds(trackIds)
        if (normalizedIds.isEmpty()) {
            emitSnackbar(getApplication<Application>().getString(R.string.shared_error_public_group_no_tracks))
            onSettled()
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            var firstFailure: AppError? = null
            val outcome = SharedBulkMutationCoordinator.run(normalizedIds) { id ->
                when (val r = trackerRepository.subscribeTracker(id)) {
                    is RepositoryResult.Success -> true
                    is RepositoryResult.Failure -> {
                        if (firstFailure == null) firstFailure = r.error
                        false
                    }
                }
            }
            refreshStateFromServer(feedbackMessage = resolveBulkSubscribeMessage(outcome, firstFailure))
            if (outcome.failedCount == 0 && outcome.succeededCount > 0) {
                onSuccess()
            }
            onSettled()
        }
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

    fun unsubscribeTracker(
        trackerId: String,
        onSuccess: () -> Unit = {},
        onSettled: () -> Unit = {},
    ) {
        runTrackerTransition(
            command = SharedTrackerTransitionCommand(
                trackerId = trackerId,
                action = SharedTrackerTransitionAction.Unsubscribe,
            ),
            onSuccess = onSuccess,
            onSettled = onSettled,
        )
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

    private fun runGroupTransition(
        command: SharedGroupTransitionCommand,
        onSettled: () -> Unit = {},
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            when (val result = executeGroupTransition(command)) {
                is RepositoryResult.Success -> refreshStateFromServer(feedbackMessage = null)
                is RepositoryResult.Failure -> {
                    _uiState.update { it.copy(isLoading = false) }
                    emitSnackbar(appErrorMessage(result.error))
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
        )
        return next
    }

    private fun resolveBulkSubscribeMessage(
        outcome: SharedBulkMutationOutcome,
        firstFailure: AppError?
    ): String {
        return when (SharedViewModelContracts.resolveBulkFeedbackKind(outcome)) {
            SharedBulkFeedbackKind.SUCCESS ->
                getApplication<Application>().getString(
                    R.string.shared_bulk_subscribe_success,
                    outcome.succeededCount
                )
            SharedBulkFeedbackKind.PARTIAL_FAILURE ->
                getApplication<Application>().getString(
                    R.string.shared_bulk_subscribe_partial_failure,
                    outcome.succeededCount,
                    outcome.failedCount
                )
            SharedBulkFeedbackKind.FULL_FAILURE -> appErrorMessage(firstFailure ?: AppError.Unknown)
        }
    }

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

    private fun ensureDiscoveryDataLoaded() {
        if (_uiState.value.availableToAdd != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            when (val result = trackerRepository.loadAvailableToAdd(forceRefresh = false)) {
                is RepositoryResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            availableToAdd = result.data,
                        )
                    }
                }
                is RepositoryResult.Failure -> {
                    _uiState.update { it.copy(isLoading = false) }
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
