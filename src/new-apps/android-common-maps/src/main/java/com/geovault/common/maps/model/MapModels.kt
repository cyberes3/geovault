package com.geovault.common.maps.model

import kotlinx.serialization.Serializable

@Serializable
data class TileClientConfig(
    val mapTilerApiKey: String? = null,
)

@Serializable
data class TileSource(
    val id: String,
    val name: String,
    val kind: String,
    val url: String? = null,
)

@Serializable
data class TileSourceResponse(
    val options: List<String> = emptyList(),
    val sources: List<TileSource> = emptyList(),
    val clients: TileClientConfig? = null,
)

object MapSourceIds {
    const val STREET = "street"
    const val SATELLITE = "satellite"
    const val TOPO = "topo"
}
