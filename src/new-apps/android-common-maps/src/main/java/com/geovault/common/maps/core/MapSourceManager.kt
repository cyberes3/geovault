package com.geovault.common.maps.core

import android.content.Context
import com.geovault.common.maps.model.MapSourceIds
import com.geovault.common.maps.model.TileSource

class MapSourceManager(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var cachedSources: List<TileSource> = listOf(
        TileSource(MapSourceIds.STREET, "Street", "vector"),
        TileSource(MapSourceIds.SATELLITE, "Satellite", "raster"),
        TileSource(MapSourceIds.TOPO, "Topo", "raster"),
    )

    fun setSources(sources: List<TileSource>) {
        cachedSources = if (sources.isEmpty()) cachedSources else sources
    }

    fun getSources(): List<TileSource> = cachedSources

    fun getSelectedSourceId(): String {
        val selected = prefs.getString(KEY_SELECTED_SOURCE, null)
        return selected ?: MapSourceIds.STREET
    }

    fun setSelectedSourceId(sourceId: String) {
        prefs.edit().putString(KEY_SELECTED_SOURCE, sourceId).apply()
    }

    fun getNextSourceId(): String {
        val sources = getSources()
        if (sources.isEmpty()) return MapSourceIds.STREET
        val selected = getSelectedSourceId()
        val idx = sources.indexOfFirst { it.id == selected }.takeIf { it >= 0 } ?: 0
        return sources[(idx + 1) % sources.size].id
    }

    companion object {
        private const val PREFS_NAME = "geovault_prefs"
        private const val KEY_SELECTED_SOURCE = "selected_map_source"
    }
}
