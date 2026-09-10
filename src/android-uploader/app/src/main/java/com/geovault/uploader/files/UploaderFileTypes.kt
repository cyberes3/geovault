package com.geovault.uploader.files

import com.geovault.common.files.GeoVaultFileTypeCatalog
import com.geovault.common.files.GeoVaultStandardFileTypes

object UploaderFileTypes {
    val catalog: GeoVaultFileTypeCatalog = GeoVaultFileTypeCatalog(
        listOf(
            GeoVaultStandardFileTypes.kml,
            GeoVaultStandardFileTypes.kmz,
            GeoVaultStandardFileTypes.gpx,
        ),
    )

    val pickerMimeTypes: Array<String> get() = catalog.pickerMimeTypes
}
