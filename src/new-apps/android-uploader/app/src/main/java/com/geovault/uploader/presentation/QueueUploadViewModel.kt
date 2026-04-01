package com.geovault.uploader.presentation

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geovault.common.NaturalSort
import com.geovault.uploader.data.FileMetadataRepository
import com.geovault.uploader.data.UploaderPreferences
import com.geovault.uploader.data.UploadRepository
import com.geovault.uploader.domain.FilenamePolicy
import com.geovault.uploader.domain.QueueUploadStateMachine
import com.geovault.uploader.model.FileQueueItem
import com.geovault.uploader.model.FileStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

data class QueueUploadState(
    val items: List<FileQueueItem> = emptyList(),
    val isUploading: Boolean = false,
    /** After the user cancels a batch upload, queue rows stay non-removable until a new upload starts. */
    val uploadCancelled: Boolean = false,
    val progressCurrent: Int = 0,
    val progressMax: Int = 0,
    val statusMessage: String = "",
    val fileCountLabel: String = "0 files"
)

class QueueUploadViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val metadata = FileMetadataRepository(appContext.contentResolver)
    private val prefs = UploaderPreferences.getInstance(appContext)
    private val uploader = UploadRepository(appContext, appContext.contentResolver)

    private val _state = MutableStateFlow(QueueUploadState())
    val state: StateFlow<QueueUploadState> = _state.asStateFlow()

    private var cancelled = false

    fun initialize(intent: Intent?) {
        val items = when (intent?.action) {
            Intent.ACTION_SEND_MULTIPLE -> {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty().map(::buildItem)
            }
            Intent.ACTION_SEND -> {
                val uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                if (uri != null) listOf(buildItem(uri)) else emptyList()
            }
            else -> emptyList()
        }.sortedWith(NaturalSort.naturalOrderBy { it.filename.lowercase(Locale.getDefault()) })
        _state.value = _state.value.copy(
            items = items,
            fileCountLabel = "${items.size} file${if (items.size != 1) "s" else ""}",
            uploadCancelled = false
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
        val n = items.size
        _state.value = _state.value.copy(
            items = items,
            fileCountLabel = "$n file${if (n != 1) "s" else ""}"
        )
    }

    fun cancelUpload() {
        cancelled = true
        uploader.cancelActiveUpload()
        _state.value = QueueUploadStateMachine.cancelUpload(_state.value)
    }

    fun startUpload() {
        if (_state.value.isUploading) return
        cancelled = false
        _state.value = _state.value.copy(uploadCancelled = false)
        viewModelScope.launch {
            val allItems = _state.value.items.toMutableList()
            val validIndexes = allItems.indices.filter { idx ->
                FilenamePolicy.isSupportedImportType(allItems[idx].filename)
            }
            _state.value = QueueUploadStateMachine.startUpload(_state.value, validIndexes.size)
            if (validIndexes.isEmpty()) {
                _state.value = _state.value.copy(isUploading = false)
                return@launch
            }

            var succeeded = 0
            var failed = 0
            validIndexes.forEachIndexed { progress, index ->
                if (cancelled) return@forEachIndexed
                allItems[index] = allItems[index].copy(status = FileStatus.UPLOADING, errorMessage = null)
                _state.value = QueueUploadStateMachine.onProgress(
                    state = _state.value,
                    items = allItems.toList(),
                    progressCurrent = progress,
                    progressMax = validIndexes.size
                )
                val finalName = FilenamePolicy.withOptionalSuffix(allItems[index].filename, prefs.isSuffixEnabled())
                val result = uploader.upload(allItems[index].uri, finalName)
                allItems[index] = if (result.success) {
                    succeeded++
                    allItems[index].copy(status = FileStatus.SUCCESS, errorMessage = null)
                } else {
                    failed++
                    allItems[index].copy(
                        status = FileStatus.ERROR,
                        errorMessage = result.errorMessage ?: "Upload failed"
                    )
                }
                _state.value = _state.value.copy(
                    items = allItems.toList(),
                    progressCurrent = progress + 1
                )
            }
            _state.value = QueueUploadStateMachine.finishUpload(
                state = _state.value,
                items = allItems,
                succeeded = succeeded,
                failed = failed,
                cancelled = cancelled
            )
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
            modifiedAtMs = modifiedAt
        )
    }
}
