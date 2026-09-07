package com.geovault.uploader.domain

import com.geovault.common.files.GeoVaultFilename

object FilenamePolicy {
    private const val UPLOAD_SUFFIX = "_android_upload"

    fun withOptionalSuffix(filename: String, addSuffix: Boolean): String {
        if (!addSuffix) return filename
        val (base, ext) = GeoVaultFilename.splitBaseAndExtension(filename)
        return if (ext.isNotEmpty()) {
            "${base}${UPLOAD_SUFFIX}.$ext"
        } else {
            "${filename}${UPLOAD_SUFFIX}"
        }
    }
}
