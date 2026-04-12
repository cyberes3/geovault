package com.geovault.tracker.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerMapIconIdsTest {

    @Test
    fun selectedForColor_normalizesHexAndBuildsSelectedPrefix() {
        val id = TrackerMapIconIds.selectedForColor("00ff00")
        assertEquals("track-direction-arrow-00ff00", id)
    }

    @Test
    fun simpleForColor_normalizesHexAndBuildsSimplePrefix() {
        val id = TrackerMapIconIds.simpleForColor("#a1b2c3")
        assertEquals("track-direction-arrow-simple-a1b2c3", id)
    }

    @Test
    fun parseSpec_recognizesSelectedAndSimpleAndDefault() {
        val selected = TrackerMapIconIds.parseSpec("track-direction-arrow-A1B2C3")
        val simple = TrackerMapIconIds.parseSpec("track-direction-arrow-simple-A1B2C3")
        val fallback = TrackerMapIconIds.parseSpec(TrackerMapIconIds.SELECTED_DEFAULT)

        assertNotNull(selected)
        assertEquals("#A1B2C3", selected!!.colorHex)
        assertFalse(selected.chevronOnly)

        assertNotNull(simple)
        assertEquals("#A1B2C3", simple!!.colorHex)
        assertTrue(simple.chevronOnly)

        assertNotNull(fallback)
        assertEquals(TrackerMapIconIds.DEFAULT_COLOR_HEX, fallback!!.colorHex)
        assertFalse(fallback.chevronOnly)
    }
}
