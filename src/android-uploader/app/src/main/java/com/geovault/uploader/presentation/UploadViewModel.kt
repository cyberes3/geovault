package com.geovault.uploader.presentation

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.common.files.GeoVaultFileRef
import com.geovault.common.files.GeoVaultFilename
import com.geovault.common.files.GeoVaultOpenableUriMetadata
import com.geovault.common.intent.GeoVaultIncomingFileIntake
import com.geovault.common.intent.GeoVaultIncomingIntakeResult
import com.geovault.common.intent.GeoVaultShareSession
import com.geovault.common.sort.NaturalSort
import com.geovault.uploader.data.UploaderSettingsStore
import com.geovault.uploader.di.UploaderAppServices
import com.geovault.uploader.domain.ImportFileUploader
import com.geovault.uploader.domain.ImportUploadEngine
import com.geovault.uploader.domain.UploadEvent
import com.geovault.uploader.domain.UploadItem
import com.geovault.uploader.domain.UploadItemId
import com.geovault.uploader.domain.UploadItemState
import com.geovault.uploader.domain.UploadSession
import com.geovault.uploader.domain.UploadSessionPhase
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class UploadItemUi(
    val id: UploadItemId,
    val displayName: String,
    val sizeBytes: Long,
    val modifiedAtMs: Long?,
    val state: UploadItemState,
    val errorMessage: String?,
    val canRename: Boolean,
    val canRemove: Boolean,
)

data class UploadQueueUiState(
    val items: List<UploadItemUi> = emptyList(),
    val showUploadAll: Boolean = false,
    val showCancel: Boolean = false,
    val statusMessage: String = "",
    val progressCurrent: Int = 0,
    val progressMax: Int = 0,
    val fileCountLabel: String = "0 files",
    val rejectedFileNames: List<String> = emptyList(),
    val showRejectedDialog: Boolean = false,
    val incomingCloseReturnsToSender: Boolean = false,
    val isIncomingShareFlow: Boolean = false,
)

class UploadViewModel(
    application: Application,
    private val incomingIntake: GeoVaultIncomingFileIntake,
    private val metadata: GeoVaultOpenableUriMetadata,
    private val settingsStore: UploaderSettingsStore,
    private val engine: ImportUploadEngine,
    private val uploader: ImportFileUploader,
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application,
        UploaderAppServices.from(application),
    )

    constructor(application: Application, services: UploaderAppServices) : this(
        application,
        services.incomingIntake(),
        services.openableUriMetadata(),
        services.settingsStore(),
        services.importUploadEngine(),
        services.uploadRepository(),
    )

    private val session = MutableStateFlow(UploadSession())
    private val rejectedFileNames = MutableStateFlow<List<String>>(emptyList())
    private val showRejectedDialog = MutableStateFlow(false)
    private val incomingCloseReturnsToSender = MutableStateFlow(false)
    private val isIncomingShareFlow = MutableStateFlow(false)

    val state: StateFlow<UploadQueueUiState> = combine(
        session,
        rejectedFileNames,
        showRejectedDialog,
        incomingCloseReturnsToSender,
        isIncomingShareFlow,
    ) { current, rejected, showRejected, keepOpen, shareFlow ->
        toUiState(current, rejected, showRejected, keepOpen, shareFlow)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UploadQueueUiState())

    private var uploadJob: Job? = null

    fun bindIncomingCloseReturnsToSender(value: Boolean) {
        incomingCloseReturnsToSender.value = value
    }

    fun incomingCloseReturnsToSender(): Boolean = incomingCloseReturnsToSender.value

    fun isIncomingShareFlow(): Boolean = isIncomingShareFlow.value

    fun ingestIntent(intent: Intent?, deliveredToRunningInstance: Boolean): Boolean {
        val keepOpen = GeoVaultShareSession.keepHostOpen(deliveredToRunningInstance, intent)
        return when (val result = incomingIntake.ingest(intent)) {
            GeoVaultIncomingIntakeResult.Ignored -> false
            is GeoVaultIncomingIntakeResult.Processed -> {
                incomingCloseReturnsToSender.value = keepOpen || incomingCloseReturnsToSender.value
                isIncomingShareFlow.value = true
                applyProcessed(result)
            }
        }
    }

    fun ingestPickerUris(uris: List<Uri>): Boolean {
        return when (val result = incomingIntake.ingest(uris, GeoVaultFileRef.Source.Picker)) {
            GeoVaultIncomingIntakeResult.Ignored -> false
            is GeoVaultIncomingIntakeResult.Processed -> {
                isIncomingShareFlow.value = false
                applyProcessed(result)
            }
        }
    }

    fun dismissRejectedFilesDialog() {
        showRejectedDialog.value = false
    }

    fun rename(id: UploadItemId, baseName: String) {
        val item = session.value.items[id] ?: return
        val (_, ext) = GeoVaultFilename.splitBaseAndExtension(item.displayName)
        val updatedName = if (ext.isNotEmpty()) "$baseName.$ext" else baseName
        session.value = session.value.reduce(UploadEvent.ItemRenamed(id, updatedName))
    }

    fun removeItem(id: UploadItemId) {
        session.value = session.value.reduce(UploadEvent.ItemRemoved(id))
    }

    fun startUpload() {
        if (session.value.phase is UploadSessionPhase.Running) return
        cancelCurrent(resetState = false)
        val started = session.value
            .copy(generation = session.value.generation + 1)
            .reduce(UploadEvent.BatchRequested)
        session.value = started
        if (started.phase !is UploadSessionPhase.Running) return
        var job: Job? = null
        job = viewModelScope.launch {
            try {
                engine.run(started, settingsStore.snapshot().suffixEnabled).collect { next ->
                    if (next.generation == session.value.generation) {
                        session.value = next
                    }
                }
            } catch (_: CancellationException) {
                if (session.value.generation == started.generation) {
                    session.value = session.value.reduce(UploadEvent.CancelRequested)
                }
            } finally {
                if (uploadJob === job) {
                    uploadJob = null
                }
            }
        }
        uploadJob = job
    }

    fun cancelUpload() {
        cancelCurrent(resetState = true)
    }

    private fun cancelCurrent(resetState: Boolean) {
        val generation = session.value.generation
        val job = uploadJob
        uploadJob = null
        job?.cancel()
        uploader.cancelActiveUpload(generation)
        if (resetState) {
            session.value = session.value.reduce(UploadEvent.CancelRequested)
        }
    }

    private fun applyProcessed(
        result: GeoVaultIncomingIntakeResult.Processed,
    ): Boolean {
        if (session.value.phase is UploadSessionPhase.Running) {
            cancelCurrent(resetState = true)
        }
        val built = result.accepted.map(::buildItem).sortedWith(
            NaturalSort.byName(Locale.getDefault()) { it.displayName },
        )
        session.value = session.value
            .copy(generation = session.value.generation + 1)
            .reduce(UploadEvent.ItemsAppended(built))
        rejectedFileNames.value = result.rejectedFileNames
        showRejectedDialog.value = result.rejectedFileNames.isNotEmpty()
        return session.value.items.isNotEmpty() || result.rejectedFileNames.isNotEmpty()
    }

    private fun buildItem(ref: GeoVaultFileRef): UploadItem {
        return UploadItem(
            id = UploadItemId(UUID.randomUUID().toString()),
            file = ref,
            displayName = ref.displayName,
            sizeBytes = ref.sizeBytes,
            modifiedAtMs = metadata.lastModifiedMillis(ref.uri),
        )
    }

    companion object {
        fun toUiState(
            session: UploadSession,
            rejectedFileNames: List<String>,
            showRejectedDialog: Boolean,
            incomingCloseReturnsToSender: Boolean,
            isIncomingShareFlow: Boolean,
        ): UploadQueueUiState {
            val running = session.phase is UploadSessionPhase.Running
            val work = session.workItems()
            val items = session.itemList().map { item ->
                UploadItemUi(
                    id = item.id,
                    displayName = item.displayName,
                    sizeBytes = item.sizeBytes,
                    modifiedAtMs = item.modifiedAtMs,
                    state = item.state,
                    errorMessage = (item.state as? UploadItemState.Failed)?.let { failed ->
                        UploaderFailureMessages.format(failed.failure)
                    },
                    canRename = !running && item.state is UploadItemState.Queued,
                    canRemove = !running &&
                        (item.state is UploadItemState.Queued || item.state is UploadItemState.Failed),
                )
            }
            val (progressCurrent, progressMax) = when (val phase = session.phase) {
                is UploadSessionPhase.Running -> phase.workCompleted to phase.workTotal
                is UploadSessionPhase.Finished ->
                    (phase.succeeded + phase.failed) to (phase.succeeded + phase.failed)
                UploadSessionPhase.Idle -> 0 to 0
            }
            return UploadQueueUiState(
                items = items,
                showUploadAll = !running && work.isNotEmpty(),
                showCancel = running,
                statusMessage = session.statusMessage,
                progressCurrent = progressCurrent,
                progressMax = progressMax,
                fileCountLabel = fileCountLabel(items.size),
                rejectedFileNames = rejectedFileNames,
                showRejectedDialog = showRejectedDialog,
                incomingCloseReturnsToSender = incomingCloseReturnsToSender,
                isIncomingShareFlow = isIncomingShareFlow,
            )
        }

        fun fileCountLabel(count: Int): String {
            return if (count == 1) "1 File" else "$count Files"
        }
    }
}
