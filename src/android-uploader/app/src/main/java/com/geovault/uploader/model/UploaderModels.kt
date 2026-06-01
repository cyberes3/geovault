package com.geovault.uploader.model

import android.net.Uri

enum class FileStatus {
    PENDING,
    UPLOADING,
    SUCCESS,
    ERROR
}

data class FileQueueItem(
    val uri: Uri,
    val filename: String,
    val sizeBytes: Long,
    val modifiedAtMs: Long? = null,
    val status: FileStatus = FileStatus.PENDING,
    val errorMessage: String? = null
)

sealed class ImportUploadOutcome {
    data object Success : ImportUploadOutcome()
    data class Failed(val message: String) : ImportUploadOutcome()
    data object Cancelled : ImportUploadOutcome()
}
