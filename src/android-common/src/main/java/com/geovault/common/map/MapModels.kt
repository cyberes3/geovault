package com.geovault.common.map

data class TileSource(
    val id: String,
    val name: String,
    val type: String,
    val requires_proxy: Boolean = false,
    val needs_hillshade: Boolean = false,
    val hidden: Boolean = false,
    val client_config: TileClientConfig
)

data class TileClientConfig(
    val type: String? = null,
    val url: String? = null,
    val style_url: String? = null,
    val tileSize: Int? = null,
    val attribution: String? = null
)

data class TileSourceResponse(val sources: List<TileSource>)

const val SOURCE_OSM = "osm"
const val SOURCE_OSM_DARK = "osm-dark"
const val SOURCE_MAPTILER_STREETS = "maptiler-streets"
const val SOURCE_MAPTILER_HYBRID = "maptiler-hybrid-v4"
const val OPTION_STREET = "street"
const val OPTION_SATELLITE = "satellite"
