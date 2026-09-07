package com.geovault.uploader.domain

import android.net.Uri
import com.geovault.common.net.GeoVaultApiFailure

sealed interface ImportUploadOutcome {
    data object Success : ImportUploadOutcome
    data class Failed(val failure: GeoVaultApiFailure) : ImportUploadOutcome
    data object Cancelled : ImportUploadOutcome
}

interface ImportFileUploader {
    suspend fun upload(uri: Uri, finalFilename: String, generation: Long): ImportUploadOutcome
    fun cancelActiveUpload(generation: Long)
}
