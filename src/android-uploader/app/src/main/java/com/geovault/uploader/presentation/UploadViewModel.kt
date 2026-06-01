package com.geovault.uploader.presentation

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.common.NaturalSort
import com.geovault.uploader.di.UploaderAppServices
import com.geovault.uploader.domain.FilenamePolicy
import com.geovault.uploader.domain.ImportUploadQueue
import com.geovault.uploader.domain.QueueUploadStateMachine
import com.geovault.uploader.domain.ShareIntentParser
import com.geovault.uploader.model.FileQueueItem
import com.geovault.uploader.model.FileStatus
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class QueueUploadState(
    val items: List<FileQueueItem> = emptyList(),
    val isUploading: Boolean = false,
    val uploadCancelled: Boolean = false,
    val progressCurrent: Int = 0,
    val progressMax: Int = 0,
    val statusMessage: String = "",
    val fileCountLabel: String = "0 files"
)

class UploadViewModel(
    application: Application,
    services: UploaderAppServices,
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application,
        UploaderAppServices.from(application)
    )

    private val metadata = services.fileMetadataRepository
    private val prefs = services.uploaderPreferences
    private val importUploadQueue: ImportUploadQueue = services.importUploadQueue

    private val _state = MutableStateFlow(QueueUploadState())
    val state: StateFlow<QueueUploadState> = _state.asStateFlow()

    private var uploadJob: Job? = null

    fun initialize(intent: Intent?) {
        uploadJob?.cancel()
        uploadJob = null
        val payload = ShareIntentParser.parse(intent)
        val items = payload.uris.map(::buildItem).sortedWith(
            NaturalSort.naturalOrderBy { it.filename.lowercase(Locale.getDefault()) }
        )
        _state.value = QueueUploadState(
            items = items,
            fileCountLabel = fileCountLabel(items.size),
            uploadCancelled = false,
        )
    }

    fun rename(index: Int, baseName: String) {
        if (_state.value.uploadCancelled) return
        val items = _state.value.items.toMutableList()
        if (index !in items.indices) return
        val original = items[index]
        if (original.status != FileStatus.PENDING) return
        val (_, ext) = FilenamePolicy.splitFilename(original.filename)
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

    private fun buildItem(uri: Uri): FileQueueItem {
        val filename = metadata.filenameFromUri(uri)
        val size = metadata.fileSizeFromUri(uri)
        val modifiedAt = metadata.fileModifiedAtFromUri(uri)
        if (!FilenamePolicy.isSupportedImportType(filename)) {
            return FileQueueItem(
                uri = uri,
                filename = filename,
                sizeBytes = size,
                modifiedAtMs = modifiedAt,
                status = FileStatus.ERROR,
                errorMessage = "Invalid file type. Only KMZ, KML, and GPX files are allowed."
            )
        }
        return FileQueueItem(
            uri = uri,
            filename = filename,
            sizeBytes = size,
            modifiedAtMs = modifiedAt,
        )
    }

    private fun fileCountLabel(count: Int): String {
        return "$count file${if (count != 1) "s" else ""}"
    }
}
