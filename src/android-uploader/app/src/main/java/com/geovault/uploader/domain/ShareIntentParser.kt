package com.geovault.uploader.domain

import android.content.Intent
import android.net.Uri

data class ShareIntentPayload(
    val uris: List<Uri>,
    val rejectedFileNames: List<String> = emptyList(),
)

object ShareIntentParser {
    const val EXTRA_REJECTED_FILE_NAMES = "rejected_file_names"

    fun parse(intent: Intent?): ShareIntentPayload {
        if (intent == null) return ShareIntentPayload(emptyList())
        val uris = when (intent.action) {
            Intent.ACTION_SEND_MULTIPLE -> {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            }
            Intent.ACTION_SEND -> {
                listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
            }
            else -> emptyList()
        }
        val rejected = intent.getStringArrayListExtra(EXTRA_REJECTED_FILE_NAMES).orEmpty()
        return ShareIntentPayload(uris = uris, rejectedFileNames = rejected)
    }
}
