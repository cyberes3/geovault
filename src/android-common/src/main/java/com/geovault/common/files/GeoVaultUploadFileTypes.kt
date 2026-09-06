package com.geovault.common.files

object GeoVaultUploadFileTypes {
    const val MIME_KML = "application/vnd.google-earth.kml+xml"
    const val MIME_KMZ = "application/vnd.google-earth.kmz"

    val catalog: GeoVaultFileTypeCatalog = GeoVaultFileTypeCatalog(
        listOf(
            GeoVaultFileType(
                extension = "kml",
                mimeTypes = setOf(MIME_KML),
            ),
            GeoVaultFileType(
                extension = "kmz",
                mimeTypes = setOf(MIME_KMZ),
            ),
            GeoVaultFileType(
                extension = "gpx",
                mimeTypes = setOf(
                    "application/gpx+xml",
                    "application/xml",
                    "text/xml",
                ),
            ),
        ),
    )

    val supportedExtensions: Set<String> get() = catalog.extensions

    val supportedMimeTypes: Array<String> get() = catalog.mimeTypes

    fun isSupportedFilename(filename: String): Boolean = catalog.isSupportedFilename(filename)
}
