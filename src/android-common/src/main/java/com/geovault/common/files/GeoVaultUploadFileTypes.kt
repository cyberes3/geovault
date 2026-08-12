package com.geovault.common.files

object GeoVaultUploadFileTypes {
    val catalog: GeoVaultFileTypeCatalog = GeoVaultFileTypeCatalog(
        listOf(
            GeoVaultFileType(
                extension = "kml",
                mimeTypes = setOf("application/vnd.google-earth.kml+xml"),
            ),
            GeoVaultFileType(
                extension = "kmz",
                mimeTypes = setOf("application/vnd.google-earth.kmz"),
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
