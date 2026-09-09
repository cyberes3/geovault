package com.geovault.common.concurrent

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class GeoVaultStateStoreTest {

    @Test
    fun update_appliesTransformAtomically() {
        val store = GeoVaultStateStore(0)
        val next = store.update { it + 3 }
        assertEquals(3, next)
        assertEquals(3, store.value)
        assertEquals(3, store.state.value)
    }

    @Test
    fun concurrentUpdates_doNotDropIncrements() {
        val store = GeoVaultStateStore(0)
        val threads = 8
        val incrementsPerThread = 200
        val executor = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) {
            executor.execute {
                start.await()
                repeat(incrementsPerThread) {
                    store.update { it + 1 }
                }
                done.countDown()
            }
        }
        start.countDown()
        assertEquals(true, done.await(10, TimeUnit.SECONDS))
        executor.shutdown()
        assertEquals(threads * incrementsPerThread, store.value)
    }

    @Test
    fun replace_overwritesValue() {
        val store = GeoVaultStateStore("a")
        assertEquals("b", store.replace("b"))
        assertEquals("b", store.value)
    }
}
