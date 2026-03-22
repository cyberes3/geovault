package com.geovault.common.map

import android.content.Context
import android.content.res.Configuration
import com.geovault.common.GeovaultAuthManager

class MapSourceManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("geovault_prefs", Context.MODE_PRIVATE)
    private var availableSources: List<TileSource> = listOf(
        defaultOsmSource(),
        defaultOsmDarkSource()
    )

    private fun defaultOsmSource(): TileSource = TileSource(
        SOURCE_OSM,
        "OpenStreetMap",
        "xyz",
        client_config = TileClientConfig(url = "https://tile.openstreetmap.org/{z}/{x}/{y}.png")
    )

    private fun defaultOsmDarkSource(): TileSource = TileSource(
        SOURCE_OSM_DARK,
        "OpenStreetMap Dark",
        "xyz",
        client_config = TileClientConfig(url = "https://a.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png")
    )

    fun setSources(sources: List<TileSource>) {
        val allowedIds = setOf(
            SOURCE_OSM,
            SOURCE_OSM_DARK,
            SOURCE_MAPTILER_STREETS,
            SOURCE_MAPTILER_HYBRID,
            SOURCE_MAPTILER_TOPO
        )
        val filtered = sources.filter { it.id in allowedIds }
        val baseSources = mutableListOf<TileSource>()

        if (filtered.none { it.id == SOURCE_OSM }) {
            baseSources.add(defaultOsmSource())
        } else {
            baseSources.add(filtered.find { it.id == SOURCE_OSM }!!)
        }

        baseSources.add(filtered.find { it.id == SOURCE_OSM_DARK } ?: defaultOsmDarkSource())
        filtered.find { it.id == SOURCE_MAPTILER_STREETS }?.let { baseSources.add(it) }

        filtered.find { it.id == SOURCE_MAPTILER_HYBRID }?.let { baseSources.add(it) }
        filtered.find { it.id == SOURCE_MAPTILER_TOPO }?.let { baseSources.add(it) }

        availableSources = baseSources
    }

    /** Force OSM-only sources (e.g. guest mode with no server). Uses default OSM tile URL. */
    fun setOsmOnly() {
        availableSources = listOf(
            defaultOsmSource(),
            defaultOsmDarkSource()
        )
    }

    /** User-selected option: "street", "satellite", or "topo". */
    fun getSelectedSourceId(): String {
        val raw = prefs.getString("selected_map_source", OPTION_STREET) ?: OPTION_STREET
        val normalized = normalizeSelection(raw)
        if (normalized != raw) {
            prefs.edit().putString("selected_map_source", normalized).apply()
        }
        return normalized
    }

    fun setSelectedSourceId(id: String) {
        val toStore = normalizeSelection(id)
        prefs.edit().putString("selected_map_source", toStore).apply()
    }

    /** Cycle between Street, Satellite, and Topo. */
    fun getNextSourceId(): String {
        return when (getSelectedSourceId()) {
            OPTION_STREET -> OPTION_SATELLITE
            OPTION_SATELLITE -> OPTION_TOPO
            else -> OPTION_STREET
        }
    }

    /** Effective street source: light = MapTiler streets if server provides, else OSM; dark = OSM Dark if server provides, else OSM. */
    fun getEffectiveStreetSourceId(): String {
        val isDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        if (isDark) {
            return if (availableSources.any { it.id == SOURCE_OSM_DARK }) SOURCE_OSM_DARK else SOURCE_OSM
        }
        return if (availableSources.any { it.id == SOURCE_MAPTILER_STREETS }) SOURCE_MAPTILER_STREETS else SOURCE_OSM
    }

    /** Effective source id to load for street/satellite/topo selections with fallbacks. */
    fun getEffectiveSourceId(): String {
        return when (getSelectedSourceId()) {
            OPTION_STREET -> getEffectiveStreetSourceId()
            OPTION_SATELLITE -> if (availableSources.any { it.id == SOURCE_MAPTILER_HYBRID }) SOURCE_MAPTILER_HYBRID else getEffectiveStreetSourceId()
            OPTION_TOPO -> if (availableSources.any { it.id == SOURCE_MAPTILER_TOPO }) SOURCE_MAPTILER_TOPO else getEffectiveStreetSourceId()
            else -> getEffectiveStreetSourceId()
        }
    }

    private fun normalizeSelection(raw: String): String {
        return when (raw) {
            SOURCE_OSM, SOURCE_OSM_DARK, SOURCE_MAPTILER_STREETS, OPTION_STREET -> OPTION_STREET
            SOURCE_MAPTILER_HYBRID, OPTION_SATELLITE -> OPTION_SATELLITE
            SOURCE_MAPTILER_TOPO, OPTION_TOPO -> OPTION_TOPO
            else -> OPTION_STREET
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
