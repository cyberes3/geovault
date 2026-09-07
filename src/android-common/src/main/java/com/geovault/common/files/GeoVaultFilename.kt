package com.geovault.common.files

object GeoVaultFilename {
    fun splitBaseAndExtension(filename: String): Pair<String, String> {
        val trimmed = filename.trim()
        val dot = trimmed.lastIndexOf('.')
        if (dot <= 0 || dot == trimmed.lastIndex) {
            return trimmed to ""
        }
        return trimmed.substring(0, dot) to trimmed.substring(dot + 1)
    }
}
