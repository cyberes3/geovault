package com.geovault.uploader.presentation

object UploaderMessageFormatter {
    fun validationConnected(): String {
        return "Connected to GeoVault.\n\nShare a file to upload it or choose one using the button below."
    }

    fun validationUnauthorized(): String {
        return "Unauthorized.\n\nReconnect in Settings."
    }

    fun validationConnectionFailed(message: String): String {
        return "$message\n\nCheck your server URL and network connection."
    }

    fun uploadProgress(currentIndex: Int, totalCount: Int): String {
        return "Uploading $currentIndex/$totalCount..."
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
