package com.geovault.common.maps.core

import android.content.Context
import com.geovault.common.GeovaultAuthManager
import com.geovault.common.maps.model.OPTION_STREET
import com.geovault.common.maps.model.SOURCE_GOOGLE_HYBRID_FALLBACK
import com.geovault.common.maps.model.SOURCE_MAPTILER_HYBRID
import com.geovault.common.maps.model.SOURCE_MAPTILER_STREETS
import com.geovault.common.maps.model.SOURCE_MAPTILER_STREETS_DARK
import com.geovault.common.maps.model.SOURCE_MAPTILER_TOPO
import com.geovault.common.maps.model.SOURCE_OSM
import com.geovault.common.maps.model.TileClientConfig
import com.geovault.common.maps.model.TileSource
import com.geovault.common.settings.GeoVaultPrefsStore
import com.geovault.common.settings.PrefKey

class MapSourceManager(private val context: Context) {
    private val store = GeoVaultPrefsStore(
        context = context,
        prefsName = PREFS_NAME,
        schemaVersion = SCHEMA_VERSION,
        registeredKeys = ALL_KEYS
    )
    private var availableSources: List<TileSource> = listOf(
        defaultOsmSource(),
    )

    fun setSources(sources: List<TileSource>) {
        val allowedIds = setOf(
            SOURCE_OSM,
            SOURCE_MAPTILER_STREETS_DARK,
            SOURCE_MAPTILER_STREETS,
            SOURCE_MAPTILER_HYBRID,
            SOURCE_MAPTILER_TOPO,
        )
        val filtered = sources.filter { it.id in allowedIds && !it.hidden }
        if (!isAuthenticated()) {
            setOsmOnly()
            return
        }
        val baseSources = mutableListOf<TileSource>()
        baseSources.add(filtered.find { it.id == SOURCE_OSM } ?: defaultOsmSource())
        filtered.find { it.id == SOURCE_MAPTILER_STREETS_DARK }?.let { baseSources.add(it) }
        filtered.find { it.id == SOURCE_MAPTILER_STREETS }?.let { baseSources.add(it) }
        filtered.find { it.id == SOURCE_MAPTILER_HYBRID }?.let { baseSources.add(it) }
        filtered.find { it.id == SOURCE_MAPTILER_TOPO }?.let { baseSources.add(it) }
        if (baseSources.none { it.id == SOURCE_MAPTILER_HYBRID }) {
            baseSources.add(defaultGoogleHybridFallbackSource())
        }
        availableSources = baseSources
        val sanitized = MapSourcePolicy.sanitizeSelection(getSelectedSourceId(), getAvailableSelections())
        if (sanitized != getSelectedSourceId()) {
            setSelectedSourceId(sanitized)
        }
    }

    fun setOsmOnly() {
        availableSources = listOf(defaultOsmSource())
    }

    fun getSources(): List<TileSource> = availableSources

    fun getSelectedSourceId(): String {
        val raw = store.getBlocking(KEY_SELECTED_SOURCE)
        val effective = raw.ifBlank { OPTION_STREET }
        val normalized = MapSourcePolicy.normalizeSelection(effective)
        if (effective != normalized) {
            store.putBlocking(KEY_SELECTED_SOURCE, normalized)
        }
        return normalized
    }

    fun setSelectedSourceId(id: String) {
        store.putBlocking(KEY_SELECTED_SOURCE, MapSourcePolicy.normalizeSelection(id))
    }

    fun getNextSourceId(): String {
        return MapSourcePolicy.nextSelection(getSelectedSourceId(), getAvailableSelections())
    }

    fun getEffectiveStreetSourceId(): String {
        return MapSourcePolicy.effectiveStreetSource(
            isAuthenticated = isAuthenticated(),
            hasMapTilerStreets = availableSources.any { it.id == SOURCE_MAPTILER_STREETS },
        )
    }

    fun getEffectiveSourceId(): String {
        val resolved = MapSourcePolicy.effectiveSource(
            selectedOption = getSelectedSourceId(),
            availableSelections = getAvailableSelections(),
            streetSourceId = getEffectiveStreetSourceId(),
            hasMapTilerStreetDark = availableSources.any { it.id == SOURCE_MAPTILER_STREETS_DARK },
            isAuthenticated = isAuthenticated(),
            hasMapTilerHybrid = availableSources.any { it.id == SOURCE_MAPTILER_HYBRID },
            hasMapTilerTopo = availableSources.any { it.id == SOURCE_MAPTILER_TOPO },
        )
        return if (availableSources.any { it.id == resolved }) {
            resolved
        } else {
            SOURCE_OSM
        }
    }

    fun getAvailableSelections(): List<String> {
        return MapSourcePolicy.availableSelections(
            isAuthenticated = isAuthenticated(),
            hasMapTilerStreetDark = availableSources.any { it.id == SOURCE_MAPTILER_STREETS_DARK },
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

    private fun defaultGoogleHybridFallbackSource(): TileSource = TileSource(
        id = SOURCE_GOOGLE_HYBRID_FALLBACK,
        name = "Google Hybrid Fallback",
        type = "xyz",
        client_config = TileClientConfig(
            url = "https://mt1.google.com/vt/lyrs=y&x={x}&y={y}&z={z}",
        ),
    )

    private fun isAuthenticated(): Boolean = GeovaultAuthManager.isLoggedIn(context)

    companion object {
        private const val PREFS_NAME = "geovault_map_source"
        private const val SCHEMA_VERSION = 1

        private val KEY_SELECTED_SOURCE = PrefKey.StringKey("selected_map_source")

        private val ALL_KEYS: Set<PrefKey<*>> = setOf(KEY_SELECTED_SOURCE)
    }
}
