package com.geovault.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class StreamingTrackPointParserTest {

    @Test
    fun parseTrackUpdatedMessages_singlePoint_normalizesSecondsTimestamp() {
        val points = StreamingTrackPointParser.parseTrackUpdatedMessages(
            rawJson = """
                {
                  "module": "live_track",
                  "type": "track_updated",
                  "data": {
                    "track_id": "t1",
                    "point": [-71.0, 42.0, 1710000000],
                    "props": {"acc": 4.2}
                  }
                }
            """.trimIndent(),
            nowMs = 99L,
        )

        assertEquals(1, points.size)
        assertEquals("t1", points.first().trackId)
        assertEquals(1_710_000_000_000L, points.first().timestampMs)
        assertEquals(4.2f, points.first().accuracyMeters ?: -1f, 0.0f)
    }

    @Test
    fun parseTrackUpdatedMessages_batchedUpdates_emitsEveryPoint() {
        val points = StreamingTrackPointParser.parseTrackUpdatedMessages(
            rawJson = """
                {
                  "module": "live_track",
                  "type": "track_updated",
                  "data": {
                    "track_id": "t1",
                    "updates": [
                      {"point": [-71.0, 42.0, 1710000000000], "props": {"acc": 4}},
                      {"point": [-72.0, 43.0, 1710000001000], "props": {}}
                    ]
                  }
                }
            """.trimIndent(),
            nowMs = 99L,
        )

        assertEquals(2, points.size)
        assertEquals(-71.0, points[0].lon, 0.0)
        assertEquals(-72.0, points[1].lon, 0.0)
        assertEquals(1_710_000_001_000L, points[1].timestampMs)
    }

    @Test
    fun parseTrackUpdatedMessages_missingTimestamp_usesCurrentTime() {
        val points = StreamingTrackPointParser.parseTrackUpdatedMessages(
            rawJson = """
                {
                  "module": "live_track",
                  "type": "track_updated",
                  "data": {
                    "track_id": "t1",
                    "point": [-71.0, 42.0],
                    "props": {}
                  }
                }
            """.trimIndent(),
            nowMs = 1234L,
        )

        assertEquals(1, points.size)
        assertEquals(1234L, points.first().timestampMs)
    }

    @Test
    fun isPongMessage_recognizesLiveTrackPong() {
        assertTrue(
            StreamingTrackPointParser.isPongMessage(
                """{"module":"live_track","type":"pong","data":{}}"""
            )
        )
    }

    @Test
    fun isPongMessage_rejectsTrackUpdated() {
        assertFalse(
            StreamingTrackPointParser.isPongMessage(
                """{"module":"live_track","type":"track_updated","data":{}}"""
            )
        )
    }
}
