package com.geovault.common.files

import android.net.Uri

/**
 * Result of partitioning a list of content URIs by whether their display names match a
 * [GeoVaultFileTypeCatalog].
 */
data class GeoVaultIncomingFileClassification(
    val supported: List<Uri>,
    val rejectedFileNames: List<String>,
)
