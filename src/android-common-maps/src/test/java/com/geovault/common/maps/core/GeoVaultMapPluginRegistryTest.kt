package com.geovault.common.maps.core

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoVaultMapPluginRegistryTest {
    @Test
    fun remove_callsOnPluginDestroyedOnceForRemovedPlugin() {
        val registry = GeoVaultMapPluginRegistry()
        val plugin = TestPlugin()
        registry.add(plugin)

        registry.remove(plugin)
        registry.remove(plugin)

        assertEquals(1, plugin.pluginDestroyedCalls)
    }

    @Test
    fun clearAndDestroy_callsOnPluginDestroyedForAllRegisteredPlugins() {
        val registry = GeoVaultMapPluginRegistry()
        val first = TestPlugin()
        val second = TestPlugin()
        registry.add(first)
        registry.add(second)

        registry.clearAndDestroy()

        assertEquals(1, first.pluginDestroyedCalls)
        assertEquals(1, second.pluginDestroyedCalls)
    }

    private class TestPlugin : GeoVaultMapPlugin {
        var pluginDestroyedCalls: Int = 0

        override fun onPluginDestroyed() {
            pluginDestroyedCalls += 1
        }
    }
}
