package com.geovault.common.maps.core

internal class GeoVaultMapPluginRegistry {
    private val plugins = linkedSetOf<GeoVaultMapPlugin>()
    private var attachedMap: org.maplibre.android.maps.MapLibreMap? = null

    fun add(plugin: GeoVaultMapPlugin): Boolean {
        val added = plugins.add(plugin)
        if (added) {
            attachedMap?.let { plugin.onMapAttached(it) }
        }
        return added
    }

    fun remove(plugin: GeoVaultMapPlugin): Boolean {
        val removed = plugins.remove(plugin)
        if (removed) {
            if (attachedMap != null) {
                plugin.onMapDetached()
            }
            plugin.onPluginDestroyed()
        }
        return removed
    }

    fun onMapAttached(map: org.maplibre.android.maps.MapLibreMap) {
        attachedMap = map
        plugins.forEach { it.onMapAttached(map) }
    }

    fun onMapDetached() {
        if (attachedMap == null) return
        plugins.forEach { it.onMapDetached() }
        attachedMap = null
    }

    fun onStyleWillChange(
        map: org.maplibre.android.maps.MapLibreMap,
        currentStyle: org.maplibre.android.maps.Style?,
    ) {
        plugins.forEach { it.onStyleWillChange(map, currentStyle) }
    }

    fun onStyleLoaded(
        map: org.maplibre.android.maps.MapLibreMap,
        style: org.maplibre.android.maps.Style,
    ) {
        plugins.forEach { it.onStyleLoaded(map, style) }
    }

    fun clearAndDestroy() {
        onMapDetached()
        plugins.forEach { it.onPluginDestroyed() }
        plugins.clear()
    }
}
