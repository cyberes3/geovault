package com.geovault.common.maps.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoVaultMainMapPreloadStoreTest {
    @Test
    fun acquireForTest_reusesControllerRefForSameKey() {
        GeoVaultMainMapControllerStore.resetForTest()
        var factoryCalls = 0
        val firstRef = FakeRef()
        val secondRef = FakeRef()

        GeoVaultMainMapControllerStore.acquireForTest("main") {
            factoryCalls += 1
            firstRef
        }
        GeoVaultMainMapControllerStore.acquireForTest("main") {
            factoryCalls += 1
            secondRef
        }

        assertEquals(1, factoryCalls)
        assertEquals(2, GeoVaultMainMapControllerStore.currentRefCountForTest("main"))
        assertFalse(firstRef.destroyed)
        assertFalse(secondRef.destroyed)
    }

    @Test
    fun release_partialSharedOwnership_doesNotDestroy() {
        GeoVaultMainMapControllerStore.resetForTest()
        val ref = FakeRef()
        GeoVaultMainMapControllerStore.acquireForTest("main") { ref }
        GeoVaultMainMapControllerStore.acquireForTest("main") { ref }

        GeoVaultMainMapControllerStore.release("main")

        assertEquals(1, GeoVaultMainMapControllerStore.currentRefCountForTest("main"))
        assertFalse(ref.destroyed)
    }

    @Test
    fun release_finalSharedOwner_destroys() {
        GeoVaultMainMapControllerStore.resetForTest()
        val ref = FakeRef()
        GeoVaultMainMapControllerStore.acquireForTest("main") { ref }
        GeoVaultMainMapControllerStore.acquireForTest("main") { ref }

        GeoVaultMainMapControllerStore.release("main")
        GeoVaultMainMapControllerStore.release("main")

        assertTrue(ref.destroyed)
        assertEquals(0, GeoVaultMainMapControllerStore.currentRefCountForTest("main"))
    }

    @Test
    fun release_destroysOnlyRequestedKey() {
        GeoVaultMainMapControllerStore.resetForTest()
        val firstRef = FakeRef()
        val secondRef = FakeRef()
        GeoVaultMainMapControllerStore.acquireForTest("main") { firstRef }
        GeoVaultMainMapControllerStore.acquireForTest("other") { secondRef }

        GeoVaultMainMapControllerStore.release("main")

        assertTrue(firstRef.destroyed)
        assertFalse(secondRef.destroyed)
    }

    @Test
    fun preloadAndRelease_remainsDeterministic() {
        GeoVaultMainMapControllerStore.resetForTest()
        val ref = FakeRef()
        GeoVaultMainMapControllerStore.acquireForTest("main") { ref }

        GeoVaultMainMapControllerStore.preloadForTest("main")
        GeoVaultMainMapControllerStore.release("main")

        assertEquals(1, ref.preloadCalls)
        assertTrue(ref.destroyed)
    }

    @Test
    fun forceReleaseKeyForReset_destroysRegardlessOfRefCount() {
        GeoVaultMainMapControllerStore.resetForTest()
        val ref = FakeRef()
        GeoVaultMainMapControllerStore.acquireForTest("main") { ref }
        GeoVaultMainMapControllerStore.acquireForTest("main") { ref }

        GeoVaultMainMapControllerStore.forceReleaseKeyForReset("main")

        assertTrue(ref.destroyed)
        assertEquals(0, GeoVaultMainMapControllerStore.currentRefCountForTest("main"))
    }

    @Test
    fun release_afterForceRelease_isNoOp() {
        GeoVaultMainMapControllerStore.resetForTest()
        val ref = FakeRef()
        GeoVaultMainMapControllerStore.acquireForTest("main") { ref }

        GeoVaultMainMapControllerStore.forceReleaseKeyForReset("main")
        GeoVaultMainMapControllerStore.release("main")

        assertTrue(ref.destroyed)
    }

    @Test
    fun releaseAll_destroysEveryControllerRef() {
        GeoVaultMainMapControllerStore.resetForTest()
        val firstRef = FakeRef()
        val secondRef = FakeRef()
        GeoVaultMainMapControllerStore.acquireForTest("main") { firstRef }
        GeoVaultMainMapControllerStore.acquireForTest("other") { secondRef }

        GeoVaultMainMapControllerStore.releaseAll()

        assertTrue(firstRef.destroyed)
        assertTrue(secondRef.destroyed)
    }

    private class FakeRef : MainMapControllerRef {
        override val controller: GeoVaultMapController
            get() = throw UnsupportedOperationException("Controller not needed in this test")

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
