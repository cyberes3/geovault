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
    val status: FileStatus = FileStatus.PENDING,
    val errorMessage: String? = null
)

data class UploadResult(
    val success: Boolean,
    val statusCode: Int? = null,
    val errorMessage: String? = null
)
