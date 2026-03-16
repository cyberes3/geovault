package com.geovault.common.map

import android.content.Context
import android.content.res.Configuration
import com.geovault.common.GeovaultAuthManager

class MapSourceManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
    private var availableSources: List<TileSource> = listOf(
        TileSource(SOURCE_OSM, "OpenStreetMap", "xyz", client_config = TileClientConfig(url = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"))
    )

    fun setSources(sources: List<TileSource>) {
        val allowedIds = setOf(SOURCE_OSM, SOURCE_OSM_DARK, SOURCE_MAPTILER_STREETS, SOURCE_MAPTILER_HYBRID, OPTION_SATELLITE)
        val filtered = sources.filter { it.id in allowedIds }
        val baseSources = mutableListOf<TileSource>()

        if (filtered.none { it.id == SOURCE_OSM }) {
            baseSources.add(TileSource(SOURCE_OSM, "OpenStreetMap", "xyz", client_config = TileClientConfig(url = "https://tile.openstreetmap.org/{z}/{x}/{y}.png")))
        } else {
            baseSources.add(filtered.find { it.id == SOURCE_OSM }!!)
        }

        filtered.find { it.id == SOURCE_OSM_DARK }?.let { baseSources.add(it) }
        filtered.find { it.id == SOURCE_MAPTILER_STREETS }?.let { baseSources.add(it) }

        filtered.find { it.id == SOURCE_MAPTILER_HYBRID }?.let { baseSources.add(it) }

        availableSources = baseSources
    }

    /** Force OSM-only sources (e.g. guest mode with no server). Uses default OSM tile URL. */
    fun setOsmOnly() {
        availableSources = listOf(
            TileSource(SOURCE_OSM, "OpenStreetMap", "xyz", client_config = TileClientConfig(url = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"))
        )
    }

    /** User-selected option: "street" or "satellite". */
    fun getSelectedSourceId(): String {
        val raw = prefs.getString("selected_map_source", OPTION_STREET) ?: OPTION_STREET
        return if (raw == SOURCE_OSM) OPTION_STREET else raw
    }

    fun setSelectedSourceId(id: String) {
        val toStore = when (id) {
            SOURCE_OSM, SOURCE_OSM_DARK, SOURCE_MAPTILER_STREETS -> OPTION_STREET
            SOURCE_MAPTILER_HYBRID -> OPTION_SATELLITE
            else -> id
        }
        prefs.edit().putString("selected_map_source", toStore).apply()
    }

    /** Cycle between Street and Satellite. */
    fun getNextSourceId(): String {
        return if (getSelectedSourceId() == OPTION_STREET) OPTION_SATELLITE else OPTION_STREET
    }

    /** Effective street source: light = MapTiler streets if server provides, else OSM; dark = OSM Dark if server provides, else OSM. */
    fun getEffectiveStreetSourceId(): String {
        val isDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        if (isDark) {
            return if (availableSources.any { it.id == SOURCE_OSM_DARK }) SOURCE_OSM_DARK else SOURCE_OSM
        }
        return if (availableSources.any { it.id == SOURCE_MAPTILER_STREETS }) SOURCE_MAPTILER_STREETS else SOURCE_OSM
    }

    /** Effective source id to load (street source or satellite: MapTiler hybrid-v4 when available, else street). */
    fun getEffectiveSourceId(): String {
        return when (getSelectedSourceId()) {
            OPTION_STREET -> getEffectiveStreetSourceId()
            else -> if (availableSources.any { it.id == SOURCE_MAPTILER_HYBRID }) SOURCE_MAPTILER_HYBRID else getEffectiveStreetSourceId()
        }
    }

    fun getSource(id: String): TileSource? = availableSources.find { it.id == id }

    fun isVectorSource(id: String): Boolean {
        val source = getSource(id) ?: return false
        val cfg = source.client_config
        return cfg.style_url != null || cfg.type == "maptiler"
    }

    fun getStyleUrl(id: String): String? = getSource(id)?.client_config?.style_url

    /** Resolved style URL (absolute; with server base for relative paths). */
    fun getResolvedStyleUrl(id: String): String? {
        val url = getStyleUrl(id) ?: return null
        if (!url.startsWith("/")) return url
        val baseUrl = GeovaultAuthManager.getServerUrl(context).let {
            if (it.isEmpty()) return null
            if (it.endsWith("/")) it.dropLast(1) else it
        }
        return "$baseUrl$url"
    }

    /** Raster URL for fallback when vector (MapTiler) street style fails to load. Always OSM light (dark source from server is vector, no raster URL). */
    fun getStreetFallbackRasterUrl(): String? = getRasterUrl(SOURCE_OSM)

    /** Resolved XYZ URL for raster source (absolute; with server base for relative paths). */
    fun getRasterUrl(id: String): String? {
        val source = getSource(id) ?: return null
        val url = source.client_config.url ?: return null
        if (!url.startsWith("/")) return url
        val baseUrl = GeovaultAuthManager.getServerUrl(context).let {
            if (it.isEmpty()) return null
            if (it.endsWith("/")) it.dropLast(1) else it
        }
        return "$baseUrl$url"
    }
}
