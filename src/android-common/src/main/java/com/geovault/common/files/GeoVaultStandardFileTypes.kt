package com.geovault.common.files

/**
 * Shared extension + MIME definitions. Apps compose [GeoVaultFileTypeCatalog]s from these;
 * they do not own a product catalog themselves.
 */
object GeoVaultStandardFileTypes {
    const val MIME_KML = "application/vnd.google-earth.kml+xml"
    const val MIME_KMZ = "application/vnd.google-earth.kmz"
    const val MIME_GPX = "application/gpx+xml"
    const val MIME_JSON = "application/json"
    const val MIME_TXT = "text/plain"
    const val MIME_CSV = "text/csv"

    val kml: GeoVaultFileType = GeoVaultFileType(
        extension = "kml",
        mimeTypes = setOf(MIME_KML),
    )

    val kmz: GeoVaultFileType = GeoVaultFileType(
        extension = "kmz",
        mimeTypes = setOf(MIME_KMZ),
    )

    val gpx: GeoVaultFileType = GeoVaultFileType(
        extension = "gpx",
        mimeTypes = setOf(
            MIME_GPX,
            "application/xml",
            "text/xml",
        ),
    )

    val txt: GeoVaultFileType = GeoVaultFileType(
        extension = "txt",
        mimeTypes = setOf(
            MIME_TXT,
            "text/x-plain",
            "text/txt",
            "text/x-txt",
            "application/txt",
        ),
    )

    val csv: GeoVaultFileType = GeoVaultFileType(
        extension = "csv",
        mimeTypes = setOf(
            MIME_CSV,
            "text/comma-separated-values",
            "text/x-csv",
            "text/x-comma-separated-values",
            "application/csv",
            "application/x-csv",
        ),
    )

    val json: GeoVaultFileType = GeoVaultFileType(
        extension = "json",
        mimeTypes = setOf(MIME_JSON, "text/json"),
    )
}
