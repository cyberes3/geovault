package com.geovault.common.maps.core

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoVaultMapPluginRegistryTest {
    @Test
    fun remove_callsOnDestroyOnceForRemovedPlugin() {
        val registry = GeoVaultMapPluginRegistry()
        val plugin = TestPlugin()
        registry.add(plugin)

        registry.remove(plugin)
        registry.remove(plugin)

        assertEquals(1, plugin.destroyCalls)
    }

    @Test
    fun clearAndDestroy_callsOnDestroyForAllRegisteredPlugins() {
        val registry = GeoVaultMapPluginRegistry()
        val first = TestPlugin()
        val second = TestPlugin()
        registry.add(first)
        registry.add(second)

        registry.clearAndDestroy()

        assertEquals(1, first.destroyCalls)
        assertEquals(1, second.destroyCalls)
    }

    private class TestPlugin : GeoVaultMapPlugin {
        var destroyCalls: Int = 0

        override fun onDestroy() {
            destroyCalls += 1
        }
    }
}
