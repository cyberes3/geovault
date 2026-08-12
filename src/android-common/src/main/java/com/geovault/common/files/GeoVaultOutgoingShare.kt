package com.geovault.common.files

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * Shared outbound share / open helpers. Each app keeps its own [FileProvider] under
 * `${packageName}.fileprovider`; this object only writes cache files and builds Intents.
 */
object GeoVaultOutgoingShare {
    const val DEFAULT_CACHE_SUBDIR = "exports"
    const val DEFAULT_FILE_PROVIDER_SUFFIX = ".fileprovider"

    fun fileProviderAuthority(context: Context): String =
        "${context.packageName}$DEFAULT_FILE_PROVIDER_SUFFIX"

    fun writeCacheFile(
        context: Context,
        fileName: String,
        bytes: ByteArray,
        cacheSubdir: String = DEFAULT_CACHE_SUBDIR,
    ): File {
        val dir = File(context.cacheDir, cacheSubdir).apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeBytes(bytes)
        return file
    }

    fun createFileShareIntent(contentUri: Uri, mimeType: String): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun createTextShareIntent(text: String): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
    }

    fun createViewIntent(contentUri: Uri, mimeType: String, newTask: Boolean): Intent {
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (newTask) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun shareBytes(
        context: Context,
        bytes: ByteArray,
        fileName: String,
        mimeType: String,
        chooserTitle: String = "Share",
        cacheSubdir: String = DEFAULT_CACHE_SUBDIR,
    ) {
        runCatching {
            val file = writeCacheFile(context, fileName, bytes, cacheSubdir)
            shareFileUri(context, file, mimeType, chooserTitle)
        }.onFailure {
            Toast.makeText(context, "Share failed", Toast.LENGTH_SHORT).show()
        }
    }

    fun shareFile(
        context: Context,
        source: File,
        fileName: String,
        mimeType: String,
        chooserTitle: String = "Share",
        cacheSubdir: String = DEFAULT_CACHE_SUBDIR,
    ) {
        runCatching {
            val bytes = source.readBytes()
            shareBytes(context, bytes, fileName, mimeType, chooserTitle, cacheSubdir)
        }.onFailure {
            Toast.makeText(context, "Share failed", Toast.LENGTH_SHORT).show()
        }
    }

    fun shareText(context: Context, text: String, chooserTitle: String? = null) {
        val share = createTextShareIntent(text)
        context.startActivity(Intent.createChooser(share, chooserTitle))
    }

    fun viewFile(context: Context, file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(context, fileProviderAuthority(context), file)
        val newTask = context !is Activity
        context.startActivity(createViewIntent(uri, mimeType, newTask))
    }

    private fun shareFileUri(
        context: Context,
        file: File,
        mimeType: String,
        chooserTitle: String,
    ) {
        val uri = FileProvider.getUriForFile(context, fileProviderAuthority(context), file)
        val share = createFileShareIntent(uri, mimeType)
        context.startActivity(Intent.createChooser(share, chooserTitle))
    }
}
