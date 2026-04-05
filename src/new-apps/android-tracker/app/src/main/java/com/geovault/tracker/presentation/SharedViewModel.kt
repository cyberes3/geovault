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

class SharedViewModel(application: Application) : AndroidViewModel(application) {

    private val trackerRepository: TrackerManagementRepository =
        TrackerAppServices.from(application).trackerManagementRepository()
    private val groupRepository: GroupManagementRepository =
        TrackerAppServices.from(application).groupManagementRepository()

    private val _uiState = MutableStateFlow(SharedUiState())
    val uiState: StateFlow<SharedUiState> = _uiState.asStateFlow()

    private val _snackbarEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val snackbarEvents: SharedFlow<String> = _snackbarEvents

    fun setSubTab(tab: SharedSubTab) {
        _uiState.update { it.copy(subTab = tab) }
    }

    fun updateSharedQuery(value: String) {
        _uiState.update { it.copy(sharedQuery = value) }
    }

    fun updateDiscoverQuery(value: String) {
        _uiState.update { it.copy(discoverQuery = value) }
    }

    fun updatePublicQuery(value: String) {
        _uiState.update { it.copy(publicQuery = value) }
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
        runTrackerTransition(command)
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

    fun acceptGroupShare(groupId: String) {
        runGroupTransition(SharedOwnershipTransitionPolicy.forGroupAccept(groupId))
    }

    /** Incoming shared tracker: add to my trackers (subscribe). */
    fun subscribeIncomingTracker(trackerId: String) {
        runTrackerTransition(SharedOwnershipTransitionPolicy.forIncomingTrackerSubscribe(trackerId))
    }

    /** Reject incoming direct share without subscribing. */
    fun leaveIncomingShare(trackerId: String) {
        runTrackerTransition(SharedOwnershipTransitionPolicy.forIncomingTrackerReject(trackerId))
    }

    fun subscribePublicTracker(trackerId: String) {
        runTrackerTransition(SharedOwnershipTransitionPolicy.forPublicTrackerSubscribe(trackerId))
    }

    /**
     * Subscribe to every addable track in a public group.
     * Mutations are applied per-track; state is always refreshed afterwards to match server truth.
     */
    fun subscribePublicGroup(trackIds: List<String>) {
        val normalizedIds = SharedBulkMutationCoordinator.normalizeIds(trackIds)
        if (normalizedIds.isEmpty()) {
            emitSnackbar(getApplication<Application>().getString(R.string.shared_error_public_group_no_tracks))
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
        }
    }

    /**
     * Remove all subscriptions for tracks in this shared group (user stays in group until [leaveGroup]).
     * This only affects tracker subscriptions; membership is managed independently via [leaveGroup].
     */
    fun unsubscribeAllTracksInGroup(trackIds: List<String>) {
        val normalizedIds = SharedBulkMutationCoordinator.normalizeIds(trackIds)
        if (normalizedIds.isEmpty()) return
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

    private fun runTrackerTransition(command: SharedTrackerTransitionCommand) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            when (val result = executeTrackerTransition(command)) {
                is RepositoryResult.Success -> refreshStateFromServer(feedbackMessage = null)
                is RepositoryResult.Failure -> {
                    _uiState.update { it.copy(isLoading = false) }
                    emitSnackbar(appErrorMessage(result.error))
                }
            }
        }
    }

    private fun runGroupTransition(command: SharedGroupTransitionCommand) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            when (val result = executeGroupTransition(command)) {
                is RepositoryResult.Success -> refreshStateFromServer(feedbackMessage = null)
                is RepositoryResult.Failure -> {
                    _uiState.update { it.copy(isLoading = false) }
                    emitSnackbar(appErrorMessage(result.error))
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

    /**
     * Legacy parity: mutation outcomes should be surfaced to users without reserving inline UI slots.
     * We keep state deterministic and emit one-shot snackbar events for outcomes/errors.
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
        return base.copy(
            isLoading = false,
            trackers = snapshot.trackersResult.successDataOr(base.trackers),
            groups = snapshot.groupsResult.successDataOr(base.groups),
            availableToAdd = snapshot.availableToAddResult.successDataOr(base.availableToAdd),
            mapVisibility = snapshot.mapVisibilityResult.successDataOr(base.mapVisibility),
            hasCompletedInitialLoad = true,
        )
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

    private data class SharedLoadSnapshot(
        val trackersResult: RepositoryResult<List<Tracker>>,
        val groupsResult: RepositoryResult<List<com.geovault.tracker.Group>>,
        val availableToAddResult: RepositoryResult<com.geovault.tracker.AvailableToAddResponse>,
        val mapVisibilityResult: RepositoryResult<com.geovault.tracker.MapVisibilityResponse>,
        val errorMessage: String?
    )
}
