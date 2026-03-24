package com.geovault.tracker

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerSettingsRequestSerializationTest {

    @Test
    fun trackerSettingsRequest_serializesSnakeCaseKeys() {
        val request = TrackerSettingsRequest(
            color = "#A7DE00",
            recent_data_window = "current_session",
            share_params_with_recipients = true,
            share_params_with_world = false,
            shared_with_emails = listOf("a@example.com"),
            world_share_enabled = false,
            allow_group_reshare = true
        )

        val json = JsonParser.parseString(Gson().toJson(request)).asJsonObject

        assertEquals("#A7DE00", json.get("color").asString)
        assertEquals("current_session", json.get("recent_data_window").asString)
        assertTrue(json.get("share_params_with_recipients").asBoolean)
        assertFalse(json.get("share_params_with_world").asBoolean)
        assertEquals("a@example.com", json.getAsJsonArray("shared_with_emails")[0].asString)
        assertFalse(json.get("world_share_enabled").asBoolean)
        assertTrue(json.get("allow_group_reshare").asBoolean)

        assertFalse(json.has("recentDataWindow"))
        assertFalse(json.has("shareParamsWithRecipients"))
        assertFalse(json.has("shareParamsWithWorld"))
        assertFalse(json.has("sharedWithEmails"))
        assertFalse(json.has("worldShareEnabled"))
        assertFalse(json.has("allowGroupReshare"))
    }
}
