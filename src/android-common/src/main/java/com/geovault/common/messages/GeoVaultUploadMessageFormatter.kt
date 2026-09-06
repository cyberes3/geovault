package com.geovault.common.messages

object GeoVaultUploadMessageFormatter {
    fun validationConnected(): String {
        return "Connected to GeoVault.\n\nShare a file to upload it or choose one using the button below."
    }

    fun validationUnauthorized(): String {
        return "Unauthorized.\n\nReconnect in Settings."
    }

    fun validationNotFound(): String {
        return "Not found.\n\nCheck your server URL."
    }

    fun validationRequestFailed(code: Int): String {
        return "Request failed ($code)"
    }

    fun validationConnectionFailed(message: String): String {
        return "$message\n\nCheck your server URL and network connection."
    }

    fun uploadProgress(currentIndex: Int, totalCount: Int): String {
        return "Uploading $currentIndex/$totalCount..."
    }

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

    fun uploadSummary(succeeded: Int, failed: Int, cancelled: Boolean): String {
        return if (cancelled) {
            "Upload cancelled"
        } else if (failed == 0) {
            "All $succeeded files uploaded successfully!"
        } else if (succeeded == 0) {
            "All $failed files failed to upload"
        } else {
            "Upload complete: $succeeded succeeded, $failed failed"
        }
    }
}
