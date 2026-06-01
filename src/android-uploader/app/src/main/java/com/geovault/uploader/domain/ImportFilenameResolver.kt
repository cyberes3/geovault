package com.geovault.uploader.domain

import android.net.Uri

fun interface ImportFilenameResolver {
    fun filenameFromUri(uri: Uri): String
}
