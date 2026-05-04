package com.geovault.common.maps.model

import kotlinx.serialization.Serializable

@Serializable
data class TileSource(
    val id: String,
    val name: String,
    val type: String,
    val requires_proxy: Boolean = false,
    val needs_hillshade: Boolean = false,
    val client_config: TileClientConfig = TileClientConfig(),
)

@Serializable
data class TileClientConfig(
    val type: String? = null,
    val url: String? = null,
    val style_url: String? = null,
    val tileSize: Int? = null,
    val attribution: String? = null,
)

@Serializable
data class TileSourceResponse(
    val sources: List<TileSource> = emptyList(),
    val map_config_errors: List<MapConfigError> = emptyList(),
)

@Serializable
data class MapConfigError(
    val code: String = "",
    val message: String = "",
)

const val SOURCE_OSM = "osm"
const val SOURCE_MAPTILER_STREETS_DARK = "maptiler-openstreetmap-dark"
const val SOURCE_MAPTILER_STREETS = "maptiler-streets"
const val SOURCE_MAPTILER_HYBRID = "maptiler-hybrid-v4"
const val SOURCE_MAPTILER_TOPO = "maptiler-topo-v4"
const val OPTION_STREET = "street"
const val OPTION_STREET_DARK = "street_dark"
const val OPTION_SATELLITE = "satellite"
const val OPTION_TOPO = "topo"
