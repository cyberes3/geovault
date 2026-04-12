package com.geovault.uploader.data

internal object UploadErrorMessageFormatter {
    fun fromStatusCode(statusCode: Int, serverMessage: String): String {
        val base = when (statusCode) {
            400 -> "Upload failed (400)\nInvalid request. Check your file format."
            401 -> "Upload failed (401)\nAPI key is invalid or expired.\nCheck Settings."
            403 -> "Upload failed (403)\nAccess denied. Check API key permissions."
            404 -> "Upload failed (404)\nServer endpoint not found.\nCheck your server URL in Settings."
            500 -> "Upload failed (500)\nServer error. Try again later."
            else -> "Upload failed ($statusCode)"
        }
        return if (serverMessage.isNotBlank()) "$base\n\n${serverMessage.take(100)}" else base
    }
}
