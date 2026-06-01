package com.geovault.uploader.domain

import android.net.Uri
import com.geovault.uploader.model.ImportUploadOutcome

interface ImportFileUploader {
    suspend fun warmAccessToken(): ImportUploadOutcome?
    suspend fun upload(uri: Uri, finalFilename: String): ImportUploadOutcome
    fun cancelActiveUpload()
}
