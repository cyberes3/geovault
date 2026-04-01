package com.geovault.common.maps.core

internal class GeoVaultMapPluginRegistry {
    private val plugins = linkedSetOf<GeoVaultMapPlugin>()

    fun add(plugin: GeoVaultMapPlugin): Boolean = plugins.add(plugin)

    fun remove(plugin: GeoVaultMapPlugin): Boolean {
        val removed = plugins.remove(plugin)
        if (removed) {
            plugin.onDestroy()
        }
        return removed
    }

    fun forEach(action: (GeoVaultMapPlugin) -> Unit) {
        plugins.forEach(action)
    }

    fun clearAndDestroy() {
        plugins.forEach { it.onDestroy() }
        plugins.clear()
    }
}
