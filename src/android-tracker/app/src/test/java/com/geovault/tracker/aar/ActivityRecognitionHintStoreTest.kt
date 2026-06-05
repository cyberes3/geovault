package com.geovault.tracker.aar

import com.geovault.tracker.sensor.ActivityHint
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ActivityRecognitionHintStoreTest {

    @Test
    fun `no hint returns null`() {
        val store = ActivityRecognitionHintStore()
        assertNull(store.currentHint(nowMs = 1000L))
    }

    @Test
    fun `active hint returns ActivityHint within window`() {
        val store = ActivityRecognitionHintStore()
        store.setHint(untilMs = 2000L)
        assertSame(ActivityHint, store.currentHint(nowMs = 1500L))
    }

    @Test
    fun `hint returns null exactly at expiry`() {
        val store = ActivityRecognitionHintStore()
        store.setHint(untilMs = 2000L)
        assertNull(store.currentHint(nowMs = 2000L))
    }

    @Test
    fun `hint returns null after expiry`() {
        val store = ActivityRecognitionHintStore()
        store.setHint(untilMs = 2000L)
        assertNull(store.currentHint(nowMs = 5000L))
    }

    @Test
    fun `clear removes active hint`() {
        val store = ActivityRecognitionHintStore()
        store.setHint(untilMs = 99999L)
        store.clear()
        assertNull(store.currentHint(nowMs = 1000L))
    }

    @Test
    fun `setHint extends existing window`() {
        val store = ActivityRecognitionHintStore()
        store.setHint(untilMs = 2000L)
        store.setHint(untilMs = 5000L)
        assertSame(ActivityHint, store.currentHint(nowMs = 4000L))
        assertNull(store.currentHint(nowMs = 6000L))
    }
}
