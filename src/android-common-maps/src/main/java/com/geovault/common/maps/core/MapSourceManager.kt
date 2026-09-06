package com.geovault.common.maps.core

import android.content.Context
import android.content.res.Configuration
import com.geovault.common.auth.GeoVaultAuthSession
import com.geovault.common.maps.model.OPTION_STREET
import com.geovault.common.maps.model.SOURCE_MAPTILER_HYBRID
import com.geovault.common.maps.model.SOURCE_MAPTILER_STREETS
import com.geovault.common.maps.model.SOURCE_MAPTILER_STREETS_DARK
import com.geovault.common.maps.model.SOURCE_MAPTILER_TOPO
import com.geovault.common.maps.model.SOURCE_OSM
import com.geovault.common.maps.model.TileSource
import com.geovault.common.settings.GeoVaultDocumentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Resolves which tile source id to load for the user’s basemap selection.
 *
 * **Tracker app behaviour:** the map uses an activity-scoped [Context] and reads
 * `context.resources.configuration.uiMode` whenever the effective street source id is resolved.
 * On **onResume**, if sources are loaded but the current style no longer matches the effective id
 * (`!isCurrentSourceApplied`), the basemap is reapplied so a night/dark change that updated
 * configuration while the screen was paused still loads the correct street tiles.
 *
 * The [Context] passed here must be the **map or activity** context (not
 * [android.content.Context.getApplicationContext]) so `uiMode` matches the window.
 * [GeoVaultMapHost] also pushes a night hint from Compose and can reapply explicitly; see
 * [setStreetNightUiHintFromHost].
 */
class MapSourceManager(private val context: Context) {

    /**
     * When non-null, [getEffectiveStreetSourceId] treats night as this value instead of reading
     * [Configuration.UI_MODE_NIGHT_MASK] from [context] alone. [GeoVaultMapHost] sets this from
     * configuration night **or** dark [androidx.compose.material.MaterialTheme] so street vector
     * tracks the same signal as the rest of the UI.
     */
    @Volatile
    private var streetNightUiHint: Boolean? = null

    fun setStreetNightUiHintFromHost(isNight: Boolean?) {
        streetNightUiHint = isNight
    }

    private val store = GeoVaultDocumentStore(
        context = context,
        fileName = MapSourceDocument.FILE_NAME,
        documentSerializer = MapSourceDocument.serializer(),
        defaultValue = MapSourceDocument(),
        currentVersion = MapSourceDocument.SCHEMA_VERSION,
        legacyMapper = MapSourceDocument::fromLegacy,
    )

    private var availableSources: List<TileSource> = emptyList()

    fun setSources(sources: List<TileSource>) {
        val allowedIds = setOf(
            SOURCE_OSM,
            SOURCE_MAPTILER_STREETS_DARK,
            SOURCE_MAPTILER_STREETS,
            SOURCE_MAPTILER_HYBRID,
            SOURCE_MAPTILER_TOPO,
        )
        val supportedSources = sources.filter { it.id in allowedIds }
        val baseSources = mutableListOf<TileSource>()
        supportedSources.find { it.id == SOURCE_OSM }?.let { baseSources.add(it) }
        supportedSources.find { it.id == SOURCE_MAPTILER_STREETS_DARK }?.let { baseSources.add(it) }
        supportedSources.find { it.id == SOURCE_MAPTILER_STREETS }?.let { baseSources.add(it) }
        supportedSources.find { it.id == SOURCE_MAPTILER_HYBRID }?.let { baseSources.add(it) }
        supportedSources.find { it.id == SOURCE_MAPTILER_TOPO }?.let { baseSources.add(it) }
        availableSources = baseSources
        val sanitized = MapSourcePolicy.sanitizeSelection(getSelectedSourceId(), getAvailableSelections())
        if (sanitized != getSelectedSourceId()) {
            setSelectedSourceId(sanitized)
        }
    }

    fun getSources(): List<TileSource> = availableSources

    fun getSelectedSourceId(): String {
        val raw = runBlocking(Dispatchers.IO) { store.get() }.selectedSourceId
        val effective = raw.ifBlank { OPTION_STREET }
        val normalized = MapSourcePolicy.normalizeSelection(effective)
        if (effective != normalized) {
            persistSelectedSourceId(normalized)
        }
        return normalized
    }

    fun setSelectedSourceId(id: String) {
        persistSelectedSourceId(MapSourcePolicy.normalizeSelection(id))
    }

    private fun persistSelectedSourceId(id: String) {
        runBlocking(Dispatchers.IO) {
            store.update { current -> current.copy(selectedSourceId = id) }
        }
    }

    fun getNextSourceId(): String {
        return MapSourcePolicy.nextSelection(getSelectedSourceId(), getAvailableSelections())
    }

    fun getEffectiveStreetSourceId(): String {
        val isNight = streetNightUiHint
            ?: run {
                (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
            }
        return MapSourcePolicy.effectiveStreetSource(
            isNight = isNight,
            hasMapTilerStreets = availableSources.any { it.id == SOURCE_MAPTILER_STREETS },
            hasMapTilerStreetDark = availableSources.any { it.id == SOURCE_MAPTILER_STREETS_DARK },
            hasOsm = availableSources.any { it.id == SOURCE_OSM },
        )
    }

    fun getEffectiveSourceId(): String {
        val resolved = MapSourcePolicy.effectiveSource(
            selectedOption = getSelectedSourceId(),
            availableSelections = getAvailableSelections(),
            streetSourceId = getEffectiveStreetSourceId(),
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
            hasMapTilerTopo = availableSources.any { it.id == SOURCE_MAPTILER_TOPO },
            hasSatellite = availableSources.any { it.id == SOURCE_MAPTILER_HYBRID },
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
        val baseUrl = GeoVaultAuthSession.get().getServerUrl().trimEnd('/')
        return if (baseUrl.isBlank()) null else "$baseUrl$trimmed"
    }

}
