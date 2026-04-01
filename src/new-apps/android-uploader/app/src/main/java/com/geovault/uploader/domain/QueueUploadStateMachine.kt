package com.geovault.uploader.domain

import com.geovault.common.messages.GeoVaultUploadMessageFormatter
import com.geovault.uploader.model.FileQueueItem
import com.geovault.uploader.presentation.QueueUploadState

object QueueUploadStateMachine {
    fun startUpload(state: QueueUploadState, validCount: Int): QueueUploadState {
        return state.copy(
            isUploading = true,
            uploadCancelled = false,
            progressCurrent = 0,
            progressMax = validCount,
            statusMessage = if (validCount == 0) "No valid files to upload" else ""
        )
    }

    fun cancelUpload(state: QueueUploadState): QueueUploadState {
        return state.copy(
            isUploading = false,
            uploadCancelled = true,
            progressCurrent = 0,
            progressMax = 0,
            statusMessage = "Upload cancelled"
        )
    }

    fun onProgress(
        state: QueueUploadState,
        items: List<FileQueueItem>,
        progressCurrent: Int,
        progressMax: Int
    ): QueueUploadState {
        return state.copy(
            items = items,
            progressCurrent = progressCurrent,
            progressMax = progressMax,
            statusMessage = if (progressMax > 0) {
                GeoVaultUploadMessageFormatter.uploadProgress(progressCurrent + 1, progressMax)
            } else {
                state.statusMessage
            }
        )
    }

    fun finishUpload(
        state: QueueUploadState,
        items: List<FileQueueItem>,
        succeeded: Int,
        failed: Int,
        cancelled: Boolean
    ): QueueUploadState {
        return state.copy(
            items = items,
            isUploading = false,
            progressCurrent = if (cancelled) 0 else state.progressCurrent,
            progressMax = if (cancelled) 0 else state.progressMax,
            statusMessage = GeoVaultUploadMessageFormatter.uploadSummary(
                succeeded = succeeded,
                failed = failed,
                cancelled = cancelled
            )
        )
    }
}
