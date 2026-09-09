package com.geovault.common.ui.files

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

data class GeoVaultCreateDocumentRequest(
    val suggestedName: String,
    val mimeType: String,
)

/**
 * SAF [Intent.ACTION_CREATE_DOCUMENT] launcher that takes a per-launch MIME type.
 * Use this when one screen exports several formats; [ActivityResultContracts.CreateDocument]
 * binds MIME at construction time.
 */
class GeoVaultCreateDocumentWithMimeContract :
    ActivityResultContract<GeoVaultCreateDocumentRequest, Uri?>() {

    override fun createIntent(context: Context, input: GeoVaultCreateDocumentRequest): Intent {
        return Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.mimeType)
            .putExtra(Intent.EXTRA_TITLE, input.suggestedName)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
        return intent.takeIf { resultCode == Activity.RESULT_OK }?.data
    }
}
