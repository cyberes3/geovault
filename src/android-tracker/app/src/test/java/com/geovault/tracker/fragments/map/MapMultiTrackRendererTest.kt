package com.geovault.tracker.fragments.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MapMultiTrackRendererTest {
    @Test
    fun extractLastUpdateMs_handlesTwoElementCoordsWithoutCrash() {
        assertNull(MapMultiTrackRenderer.extractLastUpdateMs(listOf(10.0, 20.0)))
    }

    @Test
    fun extractLastUpdateMs_normalizesSecondsToMilliseconds() {
        assertEquals(
            1_700_000_000_000L,
            MapMultiTrackRenderer.extractLastUpdateMs(listOf(10.0, 20.0, 1_700_000_000.0))
        )
    }
}
