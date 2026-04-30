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

    init {
        migrateSelectedSourceFromSharedPrefsIfNeeded()
    }

    private var availableSources: List<TileSource> = emptyList()

    /**
     * One-shot import of the user's previously selected map source from the older
     * `geovault_prefs` SharedPreferences bucket into the DataStore-backed
     * [GeoVaultPrefsStore]. Runs on every [MapSourceManager] construction but is idempotent:
     *  - If the new store already has a value, we leave it alone (user already touched the
     *    new setting, so we never clobber a newer choice with a stale value).
     *  - If the older bucket has no `selected_map_source`, we also do nothing (fresh install
     *    or already-wiped prefs).
     *  - Otherwise we copy the value across and delete it from the old bucket so
     *    subsequent launches are a true no-op.
     */
    private fun migrateSelectedSourceFromSharedPrefsIfNeeded() {
        val existing = store.getBlocking(KEY_SELECTED_SOURCE)
        if (existing.isNotBlank()) return
        val oldPrefs = context.getSharedPreferences(OLD_PREFS_NAME, Context.MODE_PRIVATE)
        val oldValue = oldPrefs.getString(OLD_KEY_SELECTED_SOURCE, null)
        if (oldValue.isNullOrBlank()) return
        store.putBlocking(KEY_SELECTED_SOURCE, MapSourcePolicy.normalizeSelection(oldValue))
        oldPrefs.edit().remove(OLD_KEY_SELECTED_SOURCE).apply()
    }

    fun setSources(sources: List<TileSource>) {
        val allowedIds = setOf(
            SOURCE_OSM,
            SOURCE_MAPTILER_STREETS_DARK,
            SOURCE_MAPTILER_STREETS,
            SOURCE_MAPTILER_HYBRID,
            SOURCE_MAPTILER_TOPO,
        )
        val filtered = sources.filter { it.id in allowedIds && !it.hidden }
        val baseSources = mutableListOf<TileSource>()
        filtered.find { it.id == SOURCE_OSM }?.let { baseSources.add(it) }
        filtered.find { it.id == SOURCE_MAPTILER_STREETS_DARK }?.let { baseSources.add(it) }
        filtered.find { it.id == SOURCE_MAPTILER_STREETS }?.let { baseSources.add(it) }
        filtered.find { it.id == SOURCE_MAPTILER_HYBRID }?.let { baseSources.add(it) }
        filtered.find { it.id == SOURCE_MAPTILER_TOPO }?.let { baseSources.add(it) }
        if (baseSources.isNotEmpty() && baseSources.none { it.id == SOURCE_MAPTILER_HYBRID }) {
            baseSources.add(defaultGoogleHybridFallbackSource())
        }
        availableSources = baseSources
        val sanitized = MapSourcePolicy.sanitizeSelection(getSelectedSourceId(), getAvailableSelections())
        if (sanitized != getSelectedSourceId()) {
            setSelectedSourceId(sanitized)
        }
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
            hasMapTilerStreets = availableSources.any { it.id == SOURCE_MAPTILER_STREETS },
            hasOsm = availableSources.any { it.id == SOURCE_OSM },
        )
    }

    fun getEffectiveSourceId(): String {
        val resolved = MapSourcePolicy.effectiveSource(
            selectedOption = getSelectedSourceId(),
            availableSelections = getAvailableSelections(),
            streetSourceId = getEffectiveStreetSourceId(),
            hasMapTilerStreetDark = availableSources.any { it.id == SOURCE_MAPTILER_STREETS_DARK },
            hasMapTilerHybrid = availableSources.any { it.id == SOURCE_MAPTILER_HYBRID },
            hasMapTilerTopo = availableSources.any { it.id == SOURCE_MAPTILER_TOPO },
        )
        return if (availableSources.any { it.id == resolved }) {
            resolved
        } else {
            availableSources.firstOrNull()?.id ?: SOURCE_MAPTILER_STREETS
        }
    }

    fun getAvailableSelections(): List<String> {
        return MapSourcePolicy.availableSelections(
            hasMapTilerStreetDark = availableSources.any { it.id == SOURCE_MAPTILER_STREETS_DARK },
            hasMapTilerTopo = availableSources.any { it.id == SOURCE_MAPTILER_TOPO },
            hasSatellite = availableSources.any {
                it.id == SOURCE_MAPTILER_HYBRID || it.id == SOURCE_GOOGLE_HYBRID_FALLBACK
            },
        )
    }

    fun getSource(id: String): TileSource? = availableSources.find { it.id == id }

    /**
     * Resolves the configured tile-source [id] into a typed [ResolvedBasemap]
     * with a non-blank URL guaranteed at the type level. Vector sources whose
     * `style_url` is missing/blank/unparseable, or raster sources whose `url`
     * is missing/blank, return `null` so callers can surface a style failure
     * explicitly instead of silently passing empty strings to MapLibre.
     */
    fun resolveBasemap(id: String): ResolvedBasemap? {
        val source = getSource(id) ?: return null
        val cfg = source.client_config
        val isVector = !cfg.style_url.isNullOrBlank() || cfg.type == "maptiler"
        return if (isVector) {
            val resolved = resolveServerRelative(cfg.style_url) ?: return null
            ResolvedBasemap.vector(id, resolved)
        } else {
            val resolved = resolveServerRelative(cfg.url) ?: return null
            ResolvedBasemap.raster(id, resolved)
        }
    }

    private fun resolveServerRelative(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if (!trimmed.startsWith("/")) return trimmed
        val baseUrl = GeovaultAuthManager.getServerUrl(context).trimEnd('/')
        return if (baseUrl.isBlank()) null else "$baseUrl$trimmed"
    }

    private fun defaultGoogleHybridFallbackSource(): TileSource = TileSource(
        id = SOURCE_GOOGLE_HYBRID_FALLBACK,
        name = "Google Hybrid Fallback",
        type = "xyz",
        client_config = TileClientConfig(
            url = "https://mt1.google.com/vt/lyrs=y&x={x}&y={y}&z={z}",
        ),
    )

    companion object {
        private const val PREFS_NAME = "geovault_map_source"
        private const val SCHEMA_VERSION = 1

        // Older SharedPreferences bucket + key used before this module moved to DataStore.
        // Retained only as migration sources — do NOT read these paths for runtime state.
        private const val OLD_PREFS_NAME = "geovault_prefs"
        private const val OLD_KEY_SELECTED_SOURCE = "selected_map_source"

        private val KEY_SELECTED_SOURCE = PrefKey.StringKey("selected_map_source")

        private val ALL_KEYS: Set<PrefKey<*>> = setOf(KEY_SELECTED_SOURCE)
    }
}
