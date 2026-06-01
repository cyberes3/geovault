package com.geovault.tracker

import android.location.Location
import com.geovault.tracker.tracking.FallbackPersistencePolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class TrackingServiceFallbackPersistenceTest {
    @Test
    fun shouldPersistFallbackPoint_nullPrevious_persists() {
        val candidate = makeFix(lat = 0.5, lon = 0.5, accuracy = 30f)
        assertTrue(FallbackPersistencePolicy.shouldPersistFallbackPoint(null, candidate))
    }

    @Test
    fun shouldPersistFallbackPoint_identicalCoords_suppressed() {
        val previous = makeFix(lat = 1.0, lon = 1.0, accuracy = 25f)
        val candidate = makeFix(lat = 1.0, lon = 1.0, accuracy = 25f)
        assertFalse(FallbackPersistencePolicy.shouldPersistFallbackPoint(previous, candidate))
    }

    @Test
    fun shouldPersistFallbackPoint_withinCombinedAccuracyEnvelope_suppressed() {
        val previous = makeFix(lat = 0.0, lon = 0.0, accuracy = 20f)
        val candidate = makeFix(lat = 0.0002, lon = 0.0, accuracy = 20f)
        assertFalse(FallbackPersistencePolicy.shouldPersistFallbackPoint(previous, candidate))
    }

    @Test
    fun shouldPersistFallbackPoint_outsideCombinedAccuracyEnvelope_persists() {
        val previous = makeFix(lat = 0.0, lon = 0.0, accuracy = 20f)
        val candidate = makeFix(lat = 0.001, lon = 0.0, accuracy = 20f)
        assertTrue(FallbackPersistencePolicy.shouldPersistFallbackPoint(previous, candidate))
    }

    @Test
    fun shouldPersistFallbackPoint_missingAccuracy_treatedAsZeroEnvelope() {
        val previous = makeFix(lat = 0.0, lon = 0.0, accuracy = null)
        val candidate = makeFix(lat = 0.00001, lon = 0.0, accuracy = null)
        assertTrue(FallbackPersistencePolicy.shouldPersistFallbackPoint(previous, candidate))
    }

    @Test
    fun shouldPersistFallbackPoint_distanceExactlyAtEnvelope_suppressed() {
        val previous = makeFix(lat = 0.0, lon = 0.0, accuracy = 5f)
        val candidate = makeFix(lat = 0.0, lon = 0.0, accuracy = 5f)
        assertFalse(FallbackPersistencePolicy.shouldPersistFallbackPoint(previous, candidate))
    }

    private fun makeFix(lat: Double, lon: Double, accuracy: Float?): Location {
        return Location("gps").apply {
            latitude = lat
            longitude = lon
            time = 1_000L
            if (accuracy != null) this.accuracy = accuracy
        }
    }
}
