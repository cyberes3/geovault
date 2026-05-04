package com.geovault.common.maps.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoVaultMainMapPreloadStoreTest {
    @Test
    fun acquire_returnsSameControllerForSameKey() {
        GeoVaultMainMapControllerStore.resetForTest()
        var factoryCalls = 0
        val firstRef = FakeRef()
        val secondRef = FakeRef()

        val first = GeoVaultMainMapControllerStore.getOrCreateRefForTest("main") {
            factoryCalls += 1
            firstRef
        }
        val second = GeoVaultMainMapControllerStore.getOrCreateRefForTest("main") {
            factoryCalls += 1
            secondRef
        }

        assertSame(firstRef, first)
        assertSame(firstRef, second)
        assertEquals(1, factoryCalls)
        assertEquals(1, GeoVaultMainMapControllerStore.currentKeyCountForTest())
        assertFalse(firstRef.destroyed)
        assertFalse(secondRef.destroyed)
    }

    @Test
    fun acquire_returnsDifferentControllerPerKey() {
        GeoVaultMainMapControllerStore.resetForTest()
        val firstRef = FakeRef()
        val secondRef = FakeRef()

        val first = GeoVaultMainMapControllerStore.getOrCreateRefForTest("main") { firstRef }
        val second = GeoVaultMainMapControllerStore.getOrCreateRefForTest("other") { secondRef }

        assertSame(firstRef, first)
        assertSame(secondRef, second)
        assertNotSame(first, second)
        assertEquals(2, GeoVaultMainMapControllerStore.currentKeyCountForTest())
    }

    @Test
    fun acquire_keepsEntryUntilExplicitReleaseKey_thenRecreates() {
        GeoVaultMainMapControllerStore.resetForTest()
        var factoryCalls = 0
        val firstRef = FakeRef()
        val secondRef = FakeRef()

        val first = GeoVaultMainMapControllerStore.getOrCreateRefForTest("main") {
            factoryCalls += 1
            firstRef
        }
        val stillFirst = GeoVaultMainMapControllerStore.getOrCreateRefForTest("main") {
            factoryCalls += 1
            secondRef
        }

        assertSame(first, stillFirst)
        assertEquals(1, factoryCalls)
        assertFalse(firstRef.destroyed)

        GeoVaultMainMapControllerStore.releaseKey("main")
        val recreated = GeoVaultMainMapControllerStore.getOrCreateRefForTest("main") {
            factoryCalls += 1
            secondRef
        }

        assertTrue(firstRef.destroyed)
        assertSame(secondRef, recreated)
        assertEquals(2, factoryCalls)
        assertEquals(1, GeoVaultMainMapControllerStore.currentKeyCountForTest())
    }

    @Test
    fun releaseKey_destroysOnlyThatKey() {
        GeoVaultMainMapControllerStore.resetForTest()
        val firstRef = FakeRef()
        val secondRef = FakeRef()
        GeoVaultMainMapControllerStore.getOrCreateRefForTest("main") { firstRef }
        GeoVaultMainMapControllerStore.getOrCreateRefForTest("other") { secondRef }

        GeoVaultMainMapControllerStore.releaseKey("main")

        assertTrue(firstRef.destroyed)
        assertFalse(secondRef.destroyed)
        assertEquals(1, GeoVaultMainMapControllerStore.currentKeyCountForTest())
    }

    @Test
    fun releaseAll_destroysEveryController() {
        GeoVaultMainMapControllerStore.resetForTest()
        val firstRef = FakeRef()
        val secondRef = FakeRef()
        GeoVaultMainMapControllerStore.getOrCreateRefForTest("main") { firstRef }
        GeoVaultMainMapControllerStore.getOrCreateRefForTest("other") { secondRef }

        GeoVaultMainMapControllerStore.releaseAll()

        assertTrue(firstRef.destroyed)
        assertTrue(secondRef.destroyed)
        assertEquals(0, GeoVaultMainMapControllerStore.currentKeyCountForTest())
    }

    @Test
    fun preload_initializesEntry_andEntryPersistsAcrossNoConsumers() {
        GeoVaultMainMapControllerStore.resetForTest()
        val ref = FakeRef()

        GeoVaultMainMapControllerStore.preloadForTest("main") { ref }

        assertEquals(1, ref.preloadCalls)
        assertFalse(ref.destroyed)
        assertEquals(1, GeoVaultMainMapControllerStore.currentKeyCountForTest())

        val acquired = GeoVaultMainMapControllerStore.getOrCreateRefForTest("main") { FakeRef() }

        assertSame(ref, acquired)
        assertFalse(ref.destroyed)
    }

    private class FakeRef : MainMapControllerRef {
        override val map: GeoVaultMainMap
            get() = throw UnsupportedOperationException("Map not needed in this test")

        var destroyed: Boolean = false
        var preloadCalls: Int = 0

        override fun preload() {
            preloadCalls += 1
        }

        override fun destroy() {
            destroyed = true
        }
    }
}
