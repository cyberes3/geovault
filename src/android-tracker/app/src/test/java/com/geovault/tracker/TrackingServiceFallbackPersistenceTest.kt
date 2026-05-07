package com.geovault.tracker

import android.location.Location
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Coverage for the final persistence gate of the emergency poor-GPS-lock
 * fallback. After [LowAccuracyFallbackCoordinator] decides to emit and
 * [TrackingService.shouldEmitFallbackForTransition] approves the geometry
 * change, [TrackingService.shouldPersistFallbackPoint] is the last
 * checkpoint before the fallback is appended to the upload queue.
 *
 * It must:
 *  - persist when there is no previous accepted fix (cold-start fallback)
 *  - suppress identical coordinates (idempotent re-fire)
 *  - suppress fallbacks that fall *inside* the combined accuracy
 *    envelope of the previous fix and the candidate (no real new info)
 *  - persist fallbacks that escape the combined envelope
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class TrackingServiceFallbackPersistenceTest {

    @Test
    fun shouldPersistFallbackPoint_nullPrevious_persists() {
        val service = TrackingService()
        val candidate = makeFix(lat = 0.5, lon = 0.5, accuracy = 30f)
        assertTrue(invokeShouldPersistFallbackPoint(service, null, candidate))
    }

    @Test
    fun shouldPersistFallbackPoint_identicalCoords_suppressed() {
        val service = TrackingService()
        val previous = makeFix(lat = 1.0, lon = 1.0, accuracy = 25f)
        val candidate = makeFix(lat = 1.0, lon = 1.0, accuracy = 25f)
        assertFalse(invokeShouldPersistFallbackPoint(service, previous, candidate))
    }

    @Test
    fun shouldPersistFallbackPoint_withinCombinedAccuracyEnvelope_suppressed() {
        val service = TrackingService()
        // ~22 m apart, with 20 m + 20 m accuracy = 40 m envelope.
        val previous = makeFix(lat = 0.0, lon = 0.0, accuracy = 20f)
        val candidate = makeFix(lat = 0.0002, lon = 0.0, accuracy = 20f)
        assertFalse(invokeShouldPersistFallbackPoint(service, previous, candidate))
    }

    @Test
    fun shouldPersistFallbackPoint_outsideCombinedAccuracyEnvelope_persists() {
        val service = TrackingService()
        // ~111 m apart, well outside combined 20 m + 20 m envelope.
        val previous = makeFix(lat = 0.0, lon = 0.0, accuracy = 20f)
        val candidate = makeFix(lat = 0.001, lon = 0.0, accuracy = 20f)
        assertTrue(invokeShouldPersistFallbackPoint(service, previous, candidate))
    }

    @Test
    fun shouldPersistFallbackPoint_missingAccuracy_treatedAsZeroEnvelope() {
        // When accuracy isn't reported the envelope collapses to 0; any
        // non-zero distance must persist.
        val service = TrackingService()
        val previous = makeFix(lat = 0.0, lon = 0.0, accuracy = null)
        val candidate = makeFix(lat = 0.00001, lon = 0.0, accuracy = null)
        assertTrue(invokeShouldPersistFallbackPoint(service, previous, candidate))
    }

    @Test
    fun shouldPersistFallbackPoint_distanceExactlyAtEnvelope_suppressed() {
        // distance == prev.acc + cand.acc -> effective == 0 -> within envelope.
        val service = TrackingService()
        val previous = makeFix(lat = 0.0, lon = 0.0, accuracy = 5f)
        val candidate = makeFix(lat = 0.0, lon = 0.0, accuracy = 5f)
        assertFalse(invokeShouldPersistFallbackPoint(service, previous, candidate))
    }

    private fun makeFix(lat: Double, lon: Double, accuracy: Float?): Location {
        return Location("gps").apply {
            latitude = lat
            longitude = lon
            time = 1_000L
            if (accuracy != null) this.accuracy = accuracy
        }
    }

    private fun invokeShouldPersistFallbackPoint(
        service: TrackingService,
        previous: Location?,
        candidate: Location,
    ): Boolean {
        val method = service.javaClass.getDeclaredMethod(
            "shouldPersistFallbackPoint",
            Location::class.java,
            Location::class.java,
        )
        method.isAccessible = true
        return method.invoke(service, previous, candidate) as Boolean
    }
}
