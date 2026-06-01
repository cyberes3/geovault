package com.geovault.uploader.domain

import com.geovault.uploader.model.FileQueueItem
import com.geovault.uploader.model.FileStatus
import com.geovault.uploader.model.ImportUploadOutcome
import com.geovault.uploader.presentation.QueueUploadState
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

class ImportUploadQueue(
    private val uploader: ImportFileUploader,
) {
    suspend fun runBatch(
        state: QueueUploadState,
        suffixEnabled: Boolean,
        onState: (QueueUploadState) -> Unit,
    ): QueueUploadState {
        val allItems = state.items.toMutableList()
        val validIndexes = allItems.indices.filter { idx ->
            FilenamePolicy.isSupportedImportType(allItems[idx].filename)
        }
        var current = QueueUploadStateMachine.startUpload(state, validIndexes.size)
        onState(current)
        if (validIndexes.isEmpty()) {
            return current.copy(isUploading = false)
        }

        when (val warmFailure = uploader.warmAccessToken()) {
            is ImportUploadOutcome.Failed -> {
                return current.copy(isUploading = false, statusMessage = warmFailure.message)
            }
            ImportUploadOutcome.Cancelled -> {
                return QueueUploadStateMachine.cancelUpload(current)
            }
            null -> Unit
            ImportUploadOutcome.Success -> Unit
        }

        var succeeded = 0
        var failed = 0
        var cancelled = false

        for ((progress, index) in validIndexes.withIndex()) {
            coroutineContext.ensureActive()
            allItems[index] = allItems[index].copy(status = FileStatus.UPLOADING, errorMessage = null)
            current = QueueUploadStateMachine.onProgress(
                state = current,
                items = allItems.toList(),
                progressCurrent = progress,
                progressMax = validIndexes.size,
            )
            onState(current)

            val finalName = FilenamePolicy.withOptionalSuffix(allItems[index].filename, suffixEnabled)
            when (val outcome = uploader.upload(allItems[index].uri, finalName)) {
                ImportUploadOutcome.Success -> {
                    succeeded++
                    allItems[index] = allItems[index].copy(status = FileStatus.SUCCESS, errorMessage = null)
                }
                is ImportUploadOutcome.Failed -> {
                    failed++
                    allItems[index] = allItems[index].copy(
                        status = FileStatus.ERROR,
                        errorMessage = outcome.message,
                    )
                }
                ImportUploadOutcome.Cancelled -> {
                    cancelled = true
                    allItems[index] = allItems[index].copy(
                        status = FileStatus.PENDING,
                        errorMessage = null,
                    )
                    current = QueueUploadStateMachine.cancelUpload(
                        current.copy(items = allItems.toList())
                    )
                    onState(current)
                    return current
                }
            }
            current = current.copy(
                items = allItems.toList(),
                progressCurrent = progress + 1,
            )
            onState(current)
        }

        current = QueueUploadStateMachine.finishUpload(
            state = current,
            items = allItems,
            succeeded = succeeded,
            failed = failed,
            cancelled = cancelled,
        )
        onState(current)
        return current
    }

    fun cancelActiveUpload() {
        uploader.cancelActiveUpload()
    }
}
