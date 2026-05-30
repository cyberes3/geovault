package com.geovault.tracker.settings

import com.geovault.common.logging.GeoVaultCaptureLog
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class TrackerSettingsRepositoryImpl(
    private val dataStore: TrackerSettingsDataStore,
    private val writePolicy: TrackerSettingsWritePolicy
) : TrackerSettingsRepository {

    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initializationComplete = CompletableDeferred<Unit>()
    private val commandQueue = Channel<SettingsCommand>(capacity = Channel.UNLIMITED)
    private val opSequence = AtomicLong(0L)
    private val state = MutableStateFlow(TrackerSettingsState.loading())

    init {
        logEvent("repo_init", "startup")
        repoScope.launch { observeStore() }
        repoScope.launch { processCommands() }
    }

    override fun isReady(): Boolean = state.value.isReady

    override fun getState(): TrackerSettingsState = state.value

    override fun observeState(): Flow<TrackerSettingsState> = state.asStateFlow()

    override fun getSettings(): TrackerSettings = state.value.settings

    override fun observeSettings(): Flow<TrackerSettings> = state.asStateFlow().map { it.settings }

    override fun wasTrackingBeforeExit(): Boolean = state.value.wasTrackingBeforeExit

    override fun dumpDebugState(reason: String) {
        val current = state.value
        logEvent(
            name = "debug_dump",
            reason = reason,
            opId = null,
            extra = "loadState=${current.loadState} schema=${current.schemaVersion} revision=${current.revision} wasTrackingBeforeExit=${current.wasTrackingBeforeExit} settings=${settingsSummary(current.settings)}"
        )
        repoScope.launch {
            runCatching { dataStore.readRecord() }
                .onSuccess { record ->
                    logEvent(
                        name = "debug_dump_durable",
                        reason = reason,
                        opId = null,
                        extra = "schema=${record.schemaVersion} wasTrackingBeforeExit=${record.wasTrackingBeforeExit} settings=${settingsSummary(record.settings)}"
                    )
                }
                .onFailure { error ->
                    GeoVaultCaptureLog.e(TAG, "settings_event name=debug_dump_durable_failed reason=$reason", error)
                }
        }
    }

    override fun setSendExtendedData(enabled: Boolean) =
        enqueueMutation("set_send_extended_data") { it.copy(sendExtendedData = enabled) }

    override fun setSignificantDataOnly(enabled: Boolean) =
        enqueueMutation("set_significant_data_only") { it.copy(significantDataOnly = enabled) }

    override fun setLowAccuracyFallbackEnabled(enabled: Boolean) =
        enqueueMutation("set_low_accuracy_fallback_enabled") { it.copy(lowAccuracyFallbackEnabled = enabled) }

    override fun setLowAccuracyFallbackTimeoutSec(value: Long) =
        enqueueMutation("set_low_accuracy_fallback_timeout_sec") { it.copy(lowAccuracyFallbackTimeoutSec = value) }

    override fun setStartOnBoot(enabled: Boolean) =
        enqueueMutation("set_start_on_boot") { it.copy(startOnBoot = enabled) }

    override fun setStartTrackingOnLaunch(enabled: Boolean) =
        enqueueMutation("set_start_tracking_on_launch") { it.copy(startTrackingOnLaunch = enabled) }

    override fun setKeepScreenOnWhileViewingMap(enabled: Boolean) =
        enqueueMutation("set_keep_screen_on_while_viewing_map") { it.copy(keepScreenOnWhileViewingMap = enabled) }

    override fun setGroupModeFitOnlyActiveTrackers(enabled: Boolean) =
        enqueueMutation("set_group_mode_fit_only_active_trackers") { it.copy(groupModeFitOnlyActiveTrackers = enabled) }

    override fun setWasTrackingBeforeExit(value: Boolean) {
        val opId = nextOpId()
        enqueueCommand(
            SettingsCommand(
                name = "set_was_tracking_before_exit",
                opId = opId,
                operation = {
                    dataStore.updateRecord(reason = "set_was_tracking_before_exit") { current ->
                        current.copy(wasTrackingBeforeExit = value)
                    }
                }
            )
        )
    }

    override fun clearWasTrackingBeforeExit() {
        val opId = nextOpId()
        enqueueCommand(
            SettingsCommand(
                name = "clear_was_tracking_before_exit",
                opId = opId,
                operation = {
                    dataStore.updateRecord(reason = "clear_was_tracking_before_exit") { current ->
                        current.copy(wasTrackingBeforeExit = false)
                    }
                }
            )
        )
    }

    private fun enqueueMutation(
        name: String,
        transform: (TrackerSettings) -> TrackerSettings
    ) {
        val opId = nextOpId()
        enqueueCommand(
            SettingsCommand(
                name = name,
                opId = opId,
                operation = {
                    dataStore.updateRecord(reason = name) { current ->
                        val nextSettings = writePolicy.sanitize(transform(current.settings))
                        current.copy(settings = nextSettings)
                    }
                }
            )
        )
    }

    private fun enqueueCommand(command: SettingsCommand) {
        logEvent(name = "intent_received", reason = command.name, opId = command.opId)
        val queued = commandQueue.trySend(command)
        if (queued.isFailure) {
            logEvent(name = "intent_rejected", reason = command.name, opId = command.opId)
        } else {
            logEvent(name = "intent_enqueued", reason = command.name, opId = command.opId)
        }
    }

    private suspend fun processCommands() {
        initializationComplete.await()
        for (command in commandQueue) {
            logEvent(name = "command_apply_begin", reason = command.name, opId = command.opId)
            runCatching { command.operation() }
                .onSuccess {
                    logEvent(name = "command_persisted", reason = command.name, opId = command.opId)
                }
                .onFailure { error ->
                    logEvent(name = "command_failed", reason = command.name, opId = command.opId)
                    GeoVaultCaptureLog.e(TAG, "settings_event name=command_failed reason=${command.name} opId=${command.opId}", error)
                }
        }
    }

    private suspend fun observeStore() {
        runCatching {
            initializeStorage()
            dataStore.observeRecord().collect { record ->
                val normalized = writePolicy.sanitize(record.settings)
                val nextState = TrackerSettingsState(
                    loadState = TrackerSettingsLoadState.Ready,
                    settings = normalized,
                    wasTrackingBeforeExit = record.wasTrackingBeforeExit,
                    schemaVersion = record.schemaVersion,
                    revision = state.value.revision + 1L
                )
                state.value = nextState
                logEvent(
                    name = "state_observed",
                    reason = "datastore_record",
                    extra = "loadState=${nextState.loadState} schema=${nextState.schemaVersion} revision=${nextState.revision} wasTrackingBeforeExit=${nextState.wasTrackingBeforeExit} settings=${settingsSummary(nextState.settings)}"
                )
                if (!initializationComplete.isCompleted) {
                    initializationComplete.complete(Unit)
                    logEvent(name = "ready_transition", reason = "first_observation")
                }
            }
        }.onFailure { error ->
            state.value = state.value.copy(
                loadState = TrackerSettingsLoadState.Error,
                revision = state.value.revision + 1L
            )
            if (!initializationComplete.isCompleted) {
                initializationComplete.complete(Unit)
            }
            GeoVaultCaptureLog.e(TAG, "settings_event name=observer_failed reason=observe_store", error)
        }
    }

    private suspend fun initializeStorage() {
        val record = dataStore.readRecord()
        logEvent(
            name = "initialize_storage",
            reason = "schema_check",
            extra = "currentSchema=${record.schemaVersion} requiredSchema=${TrackerSettingsDefaults.schemaVersion}"
        )
        if (record.schemaVersion == TrackerSettingsDefaults.schemaVersion) return
        dataStore.resetToDefaults(TrackerSettingsDefaults.schemaVersion)
        logEvent(
            name = "initialize_storage_reset_applied",
            reason = "schema_mismatch",
            extra = "previousSchema=${record.schemaVersion} newSchema=${TrackerSettingsDefaults.schemaVersion}"
        )
    }

    private fun nextOpId(): Long = opSequence.incrementAndGet()

    private fun logEvent(
        name: String,
        reason: String,
        opId: Long? = null,
        extra: String = ""
    ) {
        val opPart = if (opId == null) "" else " opId=$opId"
        val extraPart = if (extra.isBlank()) "" else " $extra"
        GeoVaultCaptureLog.i(TAG, "settings_event name=$name reason=$reason$opPart$extraPart")
    }

    private fun settingsSummary(settings: TrackerSettings): String {
        return "startOnBoot=${settings.startOnBoot},startOnLaunch=${settings.startTrackingOnLaunch},extended=${settings.sendExtendedData},sigMotion=${settings.significantDataOnly},lowAccFallback=${settings.lowAccuracyFallbackEnabled},keepScreenOn=${settings.keepScreenOnWhileViewingMap},groupFitActiveOnly=${settings.groupModeFitOnlyActiveTrackers},lowAccTimeout=${settings.lowAccuracyFallbackTimeoutSec}"
    }

    private data class SettingsCommand(
        val name: String,
        val opId: Long,
        val operation: suspend () -> Unit
    )

    companion object {
        private const val TAG = "TrackerSettingsRepo"
    }
}
