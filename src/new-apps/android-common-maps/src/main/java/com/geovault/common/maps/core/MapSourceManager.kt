package com.geovault.common.maps.core

import android.content.Context
import android.content.res.Configuration
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.maps.model.OPTION_SATELLITE
import com.geovault.common.maps.model.OPTION_STREET
import com.geovault.common.maps.model.OPTION_TOPO
import com.geovault.common.maps.model.SOURCE_MAPTILER_HYBRID
import com.geovault.common.maps.model.SOURCE_MAPTILER_STREETS
import com.geovault.common.maps.model.SOURCE_MAPTILER_TOPO
import com.geovault.common.maps.model.SOURCE_OSM
import com.geovault.common.maps.model.SOURCE_OSM_DARK
import com.geovault.common.maps.model.TileClientConfig
import com.geovault.common.maps.model.TileSource

class MapSourceManager(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var availableSources: List<TileSource> = listOf(
        defaultOsmSource(),
        defaultOsmDarkSource(),
    )

    fun setSources(sources: List<TileSource>) {
        val allowedIds = setOf(
            SOURCE_OSM,
            SOURCE_OSM_DARK,
            SOURCE_MAPTILER_STREETS,
            SOURCE_MAPTILER_HYBRID,
            SOURCE_MAPTILER_TOPO,
        )
        val filtered = sources.filter { it.id in allowedIds && !it.hidden }
        val baseSources = mutableListOf<TileSource>()
        baseSources.add(filtered.find { it.id == SOURCE_OSM } ?: defaultOsmSource())
        baseSources.add(filtered.find { it.id == SOURCE_OSM_DARK } ?: defaultOsmDarkSource())
        filtered.find { it.id == SOURCE_MAPTILER_STREETS }?.let { baseSources.add(it) }
        filtered.find { it.id == SOURCE_MAPTILER_HYBRID }?.let { baseSources.add(it) }
        filtered.find { it.id == SOURCE_MAPTILER_TOPO }?.let { baseSources.add(it) }
        availableSources = baseSources
    }

    fun setOsmOnly() {
        availableSources = listOf(defaultOsmSource(), defaultOsmDarkSource())
    }

    fun getSources(): List<TileSource> = availableSources

    fun getSelectedSourceId(): String {
        val raw = prefs.getString(KEY_SELECTED_SOURCE, OPTION_STREET) ?: OPTION_STREET
        val normalized = MapSourcePolicy.normalizeSelection(raw)
        if (raw != normalized) {
            prefs.edit().putString(KEY_SELECTED_SOURCE, normalized).apply()
        }
        return normalized
    }

    fun setSelectedSourceId(id: String) {
        prefs.edit().putString(KEY_SELECTED_SOURCE, MapSourcePolicy.normalizeSelection(id)).apply()
    }

    fun getNextSourceId(): String {
        return MapSourcePolicy.nextSelection(getSelectedSourceId())
    }

    fun getEffectiveStreetSourceId(): String {
        val isDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return MapSourcePolicy.effectiveStreetSource(
            isDarkMode = isDark,
            hasOsmDark = availableSources.any { it.id == SOURCE_OSM_DARK },
            hasMapTilerStreets = availableSources.any { it.id == SOURCE_MAPTILER_STREETS },
        )
    }

    fun getEffectiveSourceId(): String {
        return MapSourcePolicy.effectiveSource(
            selectedOption = getSelectedSourceId(),
            effectiveStreetSourceId = getEffectiveStreetSourceId(),
            hasMapTilerHybrid = availableSources.any { it.id == SOURCE_MAPTILER_HYBRID },
            hasMapTilerTopo = availableSources.any { it.id == SOURCE_MAPTILER_TOPO },
        )
    }

    fun getSource(id: String): TileSource? = availableSources.find { it.id == id }

    fun isVectorSource(id: String): Boolean {
        val source = getSource(id) ?: return false
        val cfg = source.client_config
        return cfg.style_url != null || cfg.type == "maptiler"
    }

    fun getStyleUrl(id: String): String? = getSource(id)?.client_config?.style_url

    fun getResolvedStyleUrl(id: String): String? {
        val url = getStyleUrl(id) ?: return null
        if (!url.startsWith("/")) return url
        val baseUrl = GeovaultAuthManager.getServerUrl(context).trimEnd('/')
        return if (baseUrl.isBlank()) null else "$baseUrl$url"
    }

    fun getStreetFallbackRasterUrl(): String? = getRasterUrl(SOURCE_OSM)

    fun getRasterUrl(id: String): String? {
        val source = getSource(id) ?: return null
        val url = source.client_config.url ?: return null
        if (!url.startsWith("/")) return url
        val baseUrl = GeovaultAuthManager.getServerUrl(context).trimEnd('/')
        return if (baseUrl.isBlank()) null else "$baseUrl$url"
    }

    private fun defaultOsmSource(): TileSource = TileSource(
        id = SOURCE_OSM,
        name = "OpenStreetMap",
        type = "xyz",
        client_config = TileClientConfig(url = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"),
    )

    private fun defaultOsmDarkSource(): TileSource = TileSource(
        id = SOURCE_OSM_DARK,
        name = "OpenStreetMap Dark",
        type = "xyz",
        client_config = TileClientConfig(url = "https://a.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png"),
    )

    companion object {
        private const val PREFS_NAME = "geovault_prefs"
        private const val KEY_SELECTED_SOURCE = "selected_map_source"
    }
}
