package com.geovault.tracker

import com.geovault.tracker.db.QueuedLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class BinaryPayloadBuilderTest {
    @Test
    fun buildPayload_writesGvltMagic() {
        val payload = BinaryPayloadBuilder.buildPayload(
            locations = listOf(sampleLocation()),
            trackerId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            sessionStartTimeMs = 123L,
            batteryLevel = 80,
            isCharging = false,
            buildSerial = "serial"
        )
        assertEquals('G'.code.toByte(), payload[0])
        assertEquals('V'.code.toByte(), payload[1])
        assertEquals('L'.code.toByte(), payload[2])
        assertEquals('T'.code.toByte(), payload[3])
        assertTrue(payload.size > 20)
    }

    @Test
    fun buildPayloadMinimal_writesGvlmMagic() {
        val payload = BinaryPayloadBuilder.buildPayloadMinimal(
            locations = listOf(sampleLocation()),
            trackerId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        )
        assertEquals('G'.code.toByte(), payload[0])
        assertEquals('V'.code.toByte(), payload[1])
        assertEquals('L'.code.toByte(), payload[2])
        assertEquals('M'.code.toByte(), payload[3])
        assertEquals(37, payload.size)
    }

    private fun sampleLocation(): QueuedLocation {
        return QueuedLocation(
            id = 1L,
            time = 1_000L,
            latitude = 10.0,
            longitude = 20.0,
            altitude = 1.0,
            speed = 1.0f,
            bearing = 2.0f,
            accuracy = 3.0f,
            sat = 4,
            prov = "gps",
            dist = 5.0f
        )
    }
}
