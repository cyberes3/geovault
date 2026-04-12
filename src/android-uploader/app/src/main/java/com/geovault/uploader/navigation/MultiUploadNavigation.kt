package com.geovault.uploader.navigation

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.geovault.uploader.MultiUploadActivity

object MultiUploadNavigation {
    private const val EXTRA_REJECTED_FILE_NAMES = "rejected_file_names"

    fun createIntent(
        context: Context,
        supportedUris: List<Uri>,
        rejectedFileNames: List<String>
    ): Intent {
        return Intent(context, MultiUploadActivity::class.java).apply {
            action = Intent.ACTION_SEND_MULTIPLE
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(supportedUris))
            if (rejectedFileNames.isNotEmpty()) {
                putStringArrayListExtra(EXTRA_REJECTED_FILE_NAMES, ArrayList(rejectedFileNames))
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun readRejectedFileNames(intent: Intent?): List<String> {
        return intent
            ?.getStringArrayListExtra(EXTRA_REJECTED_FILE_NAMES)
            ?.toList()
            .orEmpty()
    }
}
