package com.geovault.uploader.navigation

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.geovault.uploader.MultiUploadActivity
import com.geovault.uploader.domain.ShareIntentParser

object UploadNavigation {
    fun createIntent(
        context: Context,
        supportedUris: List<Uri>,
        rejectedFileNames: List<String>,
    ): Intent {
        return Intent(context, MultiUploadActivity::class.java).apply {
            action = if (supportedUris.size == 1) {
                Intent.ACTION_SEND
            } else {
                Intent.ACTION_SEND_MULTIPLE
            }
            if (supportedUris.size == 1) {
                putExtra(Intent.EXTRA_STREAM, supportedUris.first())
            } else {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(supportedUris))
            }
            if (rejectedFileNames.isNotEmpty()) {
                putStringArrayListExtra(
                    ShareIntentParser.EXTRA_REJECTED_FILE_NAMES,
                    ArrayList(rejectedFileNames),
                )
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun readRejectedFileNames(intent: Intent?): List<String> {
        return ShareIntentParser.parse(intent).rejectedFileNames
    }
}
