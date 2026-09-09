package com.geovault.tracker

import com.geovault.tracker.presentation.GroupReshareAddabilityPolicy
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerCatalogSettingsMapperTest {

    @Test
    fun hidden_parsesLooseBooleanValues() {
        assertTrue(settings(hidden = JsonPrimitive(true)).hidden)
        assertTrue(settings(hidden = JsonPrimitive("true")).hidden)
        assertTrue(settings(hidden = JsonPrimitive("1")).hidden)
        assertTrue(settings(hidden = JsonPrimitive("yes")).hidden)
        assertTrue(settings(hidden = JsonPrimitive(1)).hidden)
        assertFalse(settings(hidden = JsonPrimitive(false)).hidden)
        assertFalse(settings(hidden = JsonPrimitive("false")).hidden)
        assertFalse(settings(hidden = JsonPrimitive("0")).hidden)
        assertFalse(settings(hidden = JsonPrimitive(0)).hidden)
        assertFalse(JsonObject().toCatalogSettings().hidden)
    }

    @Test
    fun allowGroupReshare_parsesStrictJsonBooleanOnly() {
        assertEquals(true, settings(reshare = JsonPrimitive(true)).allowGroupReshare)
        assertEquals(false, settings(reshare = JsonPrimitive(false)).allowGroupReshare)
        assertNull(settings(reshare = JsonPrimitive("true")).allowGroupReshare)
        assertNull(JsonObject().toCatalogSettings().allowGroupReshare)
    }

    @Test
    fun sharedTracker_isNotAddable_whenReshareValueIsStringTrue() {
        val tracker = Tracker(
            id = "t1",
            name = "Tracker",
            color = null,
            settings = settings(reshare = JsonPrimitive("true")),
            is_owner = false,
        )
        assertFalse(GroupReshareAddabilityPolicy.isAddableToGroup(tracker))
    }

    @Test
    fun strings_trimRecentDataWindowAndColor() {
        val json = JsonObject()
        json.add("recent_data_window", JsonPrimitive(" 1h "))
        json.add("color", JsonPrimitive(" #abc "))
        val settings = json.toCatalogSettings()
        assertEquals("1h", settings.recentDataWindow)
        assertEquals("#abc", settings.color)
    }

    private fun settings(
        hidden: JsonPrimitive? = null,
        reshare: JsonPrimitive? = null,
    ): TrackerCatalogSettings {
        val json = JsonObject()
        if (hidden != null) json.add("hidden", hidden)
        if (reshare != null) json.add("allow_group_reshare", reshare)
        return json.toCatalogSettings()
    }
}
