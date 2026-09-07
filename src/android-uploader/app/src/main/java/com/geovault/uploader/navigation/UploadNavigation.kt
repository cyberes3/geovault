package com.geovault.uploader.navigation

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.geovault.common.files.GeoVaultFileRef
import com.geovault.common.intent.GeoVaultIncomingFileIntents
import com.geovault.uploader.MultiUploadActivity

object UploadNavigation {
    const val EXTRA_REJECTED_FILE_NAMES = "rejected_file_names"
    const val EXTRA_INGEST_SOURCE = "ingest_source"

    fun createIntent(
        context: Context,
        supportedUris: List<Uri>,
        rejectedFileNames: List<String> = emptyList(),
        source: GeoVaultFileRef.Source = GeoVaultFileRef.Source.Picker,
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
                    EXTRA_REJECTED_FILE_NAMES,
                    ArrayList(rejectedFileNames),
                )
            }
            putExtra(EXTRA_INGEST_SOURCE, source.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun readSource(intent: Intent?): GeoVaultFileRef.Source {
        val raw = intent?.getStringExtra(EXTRA_INGEST_SOURCE).orEmpty()
        return runCatching { GeoVaultFileRef.Source.valueOf(raw) }
            .getOrDefault(GeoVaultFileRef.Source.Intent)
    }

    fun readRejectedFileNames(intent: Intent?): List<String> {
        return intent?.getStringArrayListExtra(EXTRA_REJECTED_FILE_NAMES).orEmpty()
    }

    fun urisFrom(intent: Intent?): List<Uri> = GeoVaultIncomingFileIntents.urisFrom(intent)
}
