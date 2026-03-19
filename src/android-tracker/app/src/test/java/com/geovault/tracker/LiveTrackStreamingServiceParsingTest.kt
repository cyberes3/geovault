package com.geovault.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LiveTrackStreamingServiceParsingTest {
    @Test
    fun parseTrackUpdatedMessage_parsesValidPayload() {
        val raw = """
            {
              "module":"live_track",
              "type":"track_updated",
              "data":{
                "track_id":"t1",
                "point":[10.1,20.2,1710000000],
                "props":{"acc":12.5,"spd_kph":3.0}
              }
            }
        """.trimIndent()

        val point = StreamingTrackPointParser.parseTrackUpdatedMessage(raw)
        assertNotNull(point)
        assertEquals("t1", point?.trackId)
        assertEquals(10.1, point?.lon ?: 0.0, 0.0001)
        assertEquals(20.2, point?.lat ?: 0.0, 0.0001)
        assertEquals(1710000000L, point?.timestampMs)
    }

    @Test
    fun parseTrackUpdatedMessage_ignoresNonTrackUpdatedPayload() {
        val raw = """{"module":"other","type":"heartbeat","data":{}}"""
        val point = StreamingTrackPointParser.parseTrackUpdatedMessage(raw)
        assertNull(point)
    }
}
