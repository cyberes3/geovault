package com.geovault.tracker.location

import android.location.Location
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepeatedOutlierSuppressorTest {

    @Test
    fun repeatedFarLowAccuracyFix_isSuppressedAfterFirstObservation() {
        val suppressor = RepeatedOutlierSuppressor()
        val anchor = location(lat = 56.09, lon = -100.90, accuracy = 8f)
        val ghost = location(lat = 56.11507141, lon = -100.98210632, accuracy = 3452f)

        val first = suppressor.evaluate(
            candidate = ghost,
            anchor = anchor,
            effectiveAccuracyThresholdMeters = 50f,
            nowMs = 1_000L,
        )
        val second = suppressor.evaluate(
            candidate = ghost,
            anchor = anchor,
            effectiveAccuracyThresholdMeters = 50f,
            nowMs = 2_000L,
        )

        assertFalse(first.suppress)
        assertEquals("first-low-accuracy-outlier", first.reason)
        assertTrue(second.suppress)
        assertEquals("repeated-low-accuracy-outlier", second.reason)
    }

    @Test
    fun accurateFarFix_isNotSuppressed() {
        val suppressor = RepeatedOutlierSuppressor()
        val anchor = location(lat = 56.09, lon = -100.90, accuracy = 8f)
        val moved = location(lat = 56.11, lon = -100.93, accuracy = 12f)

        val decision = suppressor.evaluate(
            candidate = moved,
            anchor = anchor,
            effectiveAccuracyThresholdMeters = 50f,
            nowMs = 1_000L,
        )

        assertFalse(decision.suppress)
    }

    private fun location(lat: Double, lon: Double, accuracy: Float): Location {
        return Location("test").apply {
            latitude = lat
            longitude = lon
            this.accuracy = accuracy
        }
    }
}
