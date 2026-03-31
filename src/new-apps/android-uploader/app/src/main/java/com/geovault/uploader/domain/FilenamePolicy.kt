package com.geovault.uploader.domain

object FilenamePolicy {
    private const val UPLOAD_SUFFIX = "_android_upload"

    fun splitFilename(filename: String): Pair<String, String> {
        val lastDot = filename.lastIndexOf('.')
        return if (lastDot > 0 && lastDot < filename.length - 1) {
            filename.substring(0, lastDot) to filename.substring(lastDot + 1)
        } else {
            filename to ""
        }
    }

    fun withOptionalSuffix(filename: String, addSuffix: Boolean): String {
        if (!addSuffix) return filename
        val (base, ext) = splitFilename(filename)
        return if (ext.isNotEmpty()) {
            "${base}${UPLOAD_SUFFIX}.$ext"
        } else {
            "${filename}${UPLOAD_SUFFIX}"
        }
    }

    fun isSupportedImportType(filename: String): Boolean {
        val extension = splitFilename(filename).second.lowercase()
        return extension in setOf("kmz", "kml", "gpx")
    }
}
