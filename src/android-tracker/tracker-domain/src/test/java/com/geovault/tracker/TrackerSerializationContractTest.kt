package com.geovault.tracker

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerSerializationContractTest {

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

        val json = Json.encodeToJsonElement(TrackerSettingsRequest.serializer(), request).jsonObject

        assertEquals("#A7DE00", json.getValue("color").jsonPrimitive.content)
        assertEquals("current_session", json.getValue("recent_data_window").jsonPrimitive.content)
        assertEquals("true", json.getValue("share_params_with_recipients").jsonPrimitive.content)
        assertEquals("false", json.getValue("share_params_with_world").jsonPrimitive.content)
        assertEquals("a@example.com", json.getValue("shared_with_emails").jsonArray[0].jsonPrimitive.content)
        assertEquals("false", json.getValue("world_share_enabled").jsonPrimitive.content)
        assertEquals("true", json.getValue("allow_group_reshare").jsonPrimitive.content)

        assertFalse(json.containsKey("recentDataWindow"))
        assertFalse(json.containsKey("shareParamsWithRecipients"))
        assertFalse(json.containsKey("shareParamsWithWorld"))
        assertFalse(json.containsKey("sharedWithEmails"))
        assertFalse(json.containsKey("worldShareEnabled"))
        assertFalse(json.containsKey("allowGroupReshare"))
    }
}
