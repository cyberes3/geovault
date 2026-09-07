package com.geovault.uploader.presentation

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.common.files.GeoVaultFilename
import com.geovault.common.files.GeoVaultOpenableUriMetadata
import com.geovault.common.sort.NaturalSort
import com.geovault.common.update.GeoVaultAppUpdatePromptBinding
import com.geovault.common.update.VersionCheckResult
import com.geovault.uploader.di.UploaderAppServices
import com.geovault.uploader.domain.ImportUploadQueue
import com.geovault.uploader.domain.QueueUploadStateMachine
import com.geovault.uploader.model.FileQueueItem
import com.geovault.uploader.model.FileStatus
import com.geovault.uploader.navigation.UploadNavigation
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.geovault.common.auth.GeoVaultAccountUiState

data class QueueUploadState(
    val items: List<FileQueueItem> = emptyList(),
    val isUploading: Boolean = false,
    val uploadCancelled: Boolean = false,
    val progressCurrent: Int = 0,
    val progressMax: Int = 0,
    val statusMessage: String = "",
    val fileCountLabel: String = "0 files",
    val rejectedFileNames: List<String> = emptyList(),
    val updateAvailable: VersionCheckResult.UpdateAvailable? = null,
)

class UploadViewModel(
    application: Application,
    services: UploaderAppServices,
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application,
        UploaderAppServices.from(application)
    )

    private val metadata: GeoVaultOpenableUriMetadata = services.openableUriMetadata
    private val fileIngest = services.fileIngest
    private val prefs = services.uploaderPreferences
    private val importUploadQueue: ImportUploadQueue = services.importUploadQueue
    private val updatePromptBinding = GeoVaultAppUpdatePromptBinding(services.updateCoordinator())

    private val _state = MutableStateFlow(QueueUploadState())
    val state: StateFlow<QueueUploadState> = _state.asStateFlow()

    private var uploadJob: Job? = null

    init {
        updatePromptBinding.collect(viewModelScope) { prompt ->
            _state.update { it.copy(updateAvailable = prompt) }
        }
    }

    fun onAccountStateChanged(accountState: GeoVaultAccountUiState) {
        if (accountState.isLoggedIn) {
            updatePromptBinding.onAuthenticated(viewModelScope)
        } else {
            updatePromptBinding.onSignedOut()
        }
    }

    fun clearUpdateAvailable() {
        updatePromptBinding.dismissPrompt()
    }

    fun initialize(intent: Intent?) {
        uploadJob?.cancel()
        uploadJob = null
        val payloadUris = UploadNavigation.urisFrom(intent)
        val ingested = fileIngest.ingest(payloadUris, UploadNavigation.readSource(intent))
        val items = ingested.accepted.map(::buildItem).sortedWith(
            NaturalSort.byName(Locale.getDefault()) { it.filename }
        )
        _state.value = QueueUploadState(
            items = items,
            fileCountLabel = fileCountLabel(items.size),
            uploadCancelled = false,
            rejectedFileNames = ingested.rejectedFileNames,
            updateAvailable = _state.value.updateAvailable,
        )
    }

    fun rename(index: Int, baseName: String) {
        if (_state.value.uploadCancelled) return
        val items = _state.value.items.toMutableList()
        if (index !in items.indices) return
        val original = items[index]
        if (original.status != FileStatus.PENDING) return
        val (_, ext) = GeoVaultFilename.splitBaseAndExtension(original.filename)
        val updatedName = if (ext.isNotEmpty()) "$baseName.$ext" else baseName
        items[index] = original.copy(filename = updatedName)
        _state.value = _state.value.copy(items = items)
    }

    fun removeItemAt(index: Int) {
        if (_state.value.isUploading || _state.value.uploadCancelled) return
        val items = _state.value.items.toMutableList()
        if (index !in items.indices) return
        if (items[index].status != FileStatus.PENDING) return
        items.removeAt(index)
        _state.value = _state.value.copy(
            items = items,
            fileCountLabel = fileCountLabel(items.size),
        )
    }

    fun cancelUpload() {
        uploadJob?.cancel()
        importUploadQueue.cancelActiveUpload()
        _state.value = QueueUploadStateMachine.cancelUpload(_state.value)
    }

    fun startUpload() {
        if (_state.value.isUploading) return
        uploadJob?.cancel()
        _state.value = _state.value.copy(uploadCancelled = false)
        uploadJob = viewModelScope.launch {
            try {
                importUploadQueue.runBatch(
                    state = _state.value,
                    suffixEnabled = prefs.isSuffixEnabled(),
                    onState = { _state.value = it },
                )
            } catch (_: CancellationException) {
                _state.value = QueueUploadStateMachine.cancelUpload(_state.value)
            } finally {
                uploadJob = null
            }
        }
    }

    private fun buildItem(ref: com.geovault.common.files.GeoVaultFileRef): FileQueueItem {
        return FileQueueItem(
            uri = ref.uri,
            filename = ref.displayName,
            sizeBytes = ref.sizeBytes,
            modifiedAtMs = metadata.lastModifiedMillis(ref.uri),
        )
    }

    private fun fileCountLabel(count: Int): String {
        return "$count file${if (count != 1) "s" else ""}"
    }
}
