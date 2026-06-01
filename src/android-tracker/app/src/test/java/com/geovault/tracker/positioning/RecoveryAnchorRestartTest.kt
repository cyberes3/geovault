package com.geovault.tracker.positioning

import com.geovault.tracker.location.RecoveryAnchorState
import com.geovault.tracker.location.RecoveryAnchorStore
import com.geovault.tracker.services.TrackingMotionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class RecoveryAnchorRestartTest {

    @Test
    fun anchorStore_roundTripsWithinSession() {
        val store = RecoveryAnchorStore(RuntimeEnvironment.getApplication())
        store.clear()
        val anchor = RecoveryAnchorState(
            trackerId = "tracker-1",
            sessionBoundaryId = 42L,
            latitude = 12.34,
            longitude = 56.78,
            timestampMs = 9_000L,
            elapsedRealtimeNanos = 1L,
            accuracyMeters = 8f,
            radiusMeters = 25f,
            source = "characterization",
            motionMode = TrackingMotionMode.WALKING,
        )
        store.save(anchor)
        val loaded = store.load(trackerId = "tracker-1", sessionBoundaryId = 42L)
        assertNotNull(loaded)
        assertEquals(anchor.latitude, loaded!!.latitude, 0.0001)
        assertEquals(anchor.longitude, loaded.longitude, 0.0001)
        assertEquals(anchor.timestampMs, loaded.timestampMs)

        assertNull(store.load(trackerId = "other", sessionBoundaryId = 42L))
        store.clear()
        assertNull(store.load(trackerId = "tracker-1", sessionBoundaryId = 42L))
    }
}
