package com.geovault.common.maps.ui.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoVaultMapCameraDirectiveBusTest {

    private data class Directive(val id: Long, val generation: Long, val label: String)

    @Test
    fun publishIfUnchanged_reusesTheSameDirectiveForIdenticalKeys() {
        val bus = GeoVaultMapCameraDirectiveBus(Directive(0L, 0L, "none"))
        bus.publishIfUnchanged("lock") { id, generation -> Directive(id, generation, "lock") }
        val first = bus.directive.value
        bus.publishIfUnchanged("lock") { id, generation -> Directive(id, generation, "lock") }
        assertEquals(first, bus.directive.value)
    }

    @Test
    fun publishIfUnchanged_mintsWhenTheKeyChanges() {
        val bus = GeoVaultMapCameraDirectiveBus(Directive(0L, 0L, "none"))
        bus.publishIfUnchanged("a") { id, generation -> Directive(id, generation, "a") }
        val first = bus.directive.value
        bus.publishIfUnchanged("b") { id, generation -> Directive(id, generation, "b") }
        assertNotEquals(first.id, bus.directive.value.id)
        assertEquals("b", bus.directive.value.label)
    }

    @Test
    fun resetDedup_forcesTheNextIdenticalKeyToMint() {
        val bus = GeoVaultMapCameraDirectiveBus(Directive(0L, 0L, "none"))
        bus.publishIfUnchanged("lock") { id, generation -> Directive(id, generation, "lock") }
        val first = bus.directive.value
        bus.resetDedup()
        bus.publishIfUnchanged("lock") { id, generation -> Directive(id, generation, "lock") }
        assertNotEquals(first.id, bus.directive.value.id)
    }

    @Test
    fun onUserGestureStarted_bumpsGenerationAndClearsOwnedZoom() {
        val bus = GeoVaultMapCameraDirectiveBus(Directive(0L, 0L, "none"))
        bus.onUserOwnedZoom()
        assertTrue(bus.userOwnsZoom)
        val before = bus.generation
        bus.onUserGestureStarted()
        assertFalse(bus.userOwnsZoom)
        assertNotEquals(before, bus.generation)
        assertEquals(bus.generation, bus.generationFlow.value)
    }

    @Test
    fun publish_alwaysMintsAndClearsDedup() {
        val bus = GeoVaultMapCameraDirectiveBus(Directive(0L, 0L, "none"))
        bus.publishIfUnchanged("lock") { id, generation -> Directive(id, generation, "lock") }
        bus.publish { id, generation -> Directive(id, generation, "explicit") }
        val afterPublish = bus.directive.value
        assertEquals("explicit", afterPublish.label)
        bus.publishIfUnchanged("lock") { id, generation -> Directive(id, generation, "lock") }
        assertNotEquals(afterPublish.id, bus.directive.value.id)
    }
}
