package com.geovault.common.files

object GeoVaultUploadFileTypes {
    val supportedExtensions: Set<String> = setOf("kml", "kmz", "gpx")

    val supportedMimeTypes: Array<String> = arrayOf(
        "application/vnd.google-earth.kml+xml",
        "application/vnd.google-earth.kmz",
        "application/gpx+xml",
        "application/xml",
        "text/xml"
    )

    fun isSupportedFilename(filename: String): Boolean {
        val extension = filename.substringAfterLast('.', "").lowercase()
        return extension in supportedExtensions
    }
}
