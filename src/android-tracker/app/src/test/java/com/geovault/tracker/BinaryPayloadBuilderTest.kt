package com.geovault.tracker

import com.geovault.tracker.db.QueuedLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class BinaryPayloadBuilderTest {

    @Test
    fun extendedPayload_roundTripsAllFields() {
        val trackerUuid = UUID.fromString("11111111-2222-3333-4444-555555555555")
        val sessionStart = 1_705_312_700_000L
        val build = "build-9.9"
        val locations = listOf(
            queuedLocation(
                time = 1_705_312_800_000L,
                lat = 37.123456,
                lon = -122.654321,
                altitude = 100.5,
                speedMps = 10.0f,
                bearing = 180.0f,
                accuracy = 5.0f,
                sat = 8,
                prov = "gps",
                dist = 100.5f,
            ),
            queuedLocation(
                time = 1_705_312_810_000L,
                lat = 37.13,
                lon = -122.65,
                altitude = 200.0,
                speedMps = 0f,
                bearing = 90.0f,
                accuracy = 3.0f,
                sat = null,
                prov = "fused",
                dist = 200.5f,
            ),
        )
        val bytes = BinaryPayloadBuilder.build(
            locations = locations,
            header = BinaryPayloadBuilder.Header(
                trackerUuid = trackerUuid,
                sessionStartMs = sessionStart,
                hasExtended = true,
                buildSerial = build,
            ),
            batteryLevel = 75,
            isCharging = true,
        )
        val parsed = parseHeader(bytes)
        assertEquals("GVL2", parsed.magic)
        assertEquals(trackerUuid, parsed.trackerUuid)
        assertTrue(parsed.hasExtended)
        assertEquals(sessionStart, parsed.sessionStartMs)
        assertEquals(build, parsed.serString)

        val points = parsed.parseExtendedPoints(locations.size)
        assertEquals(2, points.size)
        assertEquals(1_705_312_800_000L, points[0].timeMs)
        assertEquals(37.123456f, points[0].lat, 1e-4f)
        assertEquals(-122.654321f, points[0].lon, 1e-4f)
        assertEquals(8, points[0].sat)
        assertEquals(100.5f, points[0].alt, 0.1f)
        assertEquals(10.0f * 3.6f, points[0].spdKph, 0.01f)
        assertEquals(180.0f, points[0].bearing, 0.01f)
        assertEquals(5.0f, points[0].acc, 0.01f)
        assertEquals(75, points[0].batt)
        assertTrue(points[0].isCharging)
        assertEquals(100.5f, points[0].dist, 0.1f)
        assertEquals("gps", points[0].prov)
        assertEquals("", points[0].desc)

        assertEquals(0, points[1].sat) // null sat encodes as 0
        assertEquals("fused", points[1].prov)
    }

    @Test
    fun minimalPayload_omitsExtendedFields_andSer_butKeepsSessionStart() {
        val trackerUuid = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        val sessionStart = 1_705_312_700_000L
        val locations = listOf(
            queuedLocation(time = 1_705_312_800_000L, lat = 37.0, lon = -122.0),
            queuedLocation(time = 1_705_312_860_000L, lat = 37.01, lon = -122.01),
        )
        val bytes = BinaryPayloadBuilder.build(
            locations = locations,
            header = BinaryPayloadBuilder.Header(
                trackerUuid = trackerUuid,
                sessionStartMs = sessionStart,
                hasExtended = false,
                buildSerial = "ignored-build",
            ),
            batteryLevel = 50,
            isCharging = false,
        )
        val parsed = parseHeader(bytes)
        assertEquals("GVL2", parsed.magic)
        assertEquals(trackerUuid, parsed.trackerUuid)
        assertEquals(false, parsed.hasExtended)
        assertEquals(sessionStart, parsed.sessionStartMs)
        assertEquals("", parsed.serString)

        val expectedSize = (4 + 16 + 1 + 8) + locations.size * 17
        assertEquals(expectedSize, bytes.size)

        val points = parsed.parseMinimalPoints(locations.size)
        assertEquals(2, points.size)
        assertEquals(1_705_312_800_000L, points[0].timeMs)
        assertEquals(1_705_312_860_000L, points[1].timeMs)
        assertEquals(37.01f, points[1].lat, 1e-4f)
    }

    @Test
    fun extendedAndMinimal_haveDifferentFlagBytes() {
        val trackerUuid = UUID.randomUUID()
        val ext = BinaryPayloadBuilder.build(
            locations = emptyList(),
            header = BinaryPayloadBuilder.Header(
                trackerUuid = trackerUuid,
                sessionStartMs = 0L,
                hasExtended = true,
                buildSerial = "",
            ),
            batteryLevel = 0,
            isCharging = false,
        )
        val min = BinaryPayloadBuilder.build(
            locations = emptyList(),
            header = BinaryPayloadBuilder.Header(
                trackerUuid = trackerUuid,
                sessionStartMs = 0L,
                hasExtended = false,
                buildSerial = "",
            ),
            batteryLevel = 0,
            isCharging = false,
        )
        // flags byte is at offset 20
        assertEquals(0x01.toByte(), ext[20])
        assertEquals(0x00.toByte(), min[20])
        assertNotEquals(ext.size, min.size) // extended carries an extra ser_len byte
    }

    private data class ParsedHeader(
        val magic: String,
        val trackerUuid: UUID,
        val hasExtended: Boolean,
        val sessionStartMs: Long,
        val serString: String,
        val pointsOffset: Int,
        val raw: ByteArray,
    ) {
        fun parseExtendedPoints(count: Int): List<ParsedExtendedPoint> {
            val buf = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
            buf.position(pointsOffset)
            val out = ArrayList<ParsedExtendedPoint>(count)
            repeat(count) {
                buf.get() // flag
                val time = buf.long
                val lat = buf.float
                val lon = buf.float
                val sat = buf.short.toInt() and 0xFFFF
                val alt = buf.float
                val spdKph = buf.float
                val bearing = buf.float
                val acc = buf.float
                val batt = buf.get().toInt() and 0xFF
                val isCharging = buf.get().toInt() != 0
                val dist = buf.float
                val provLen = buf.get().toInt() and 0xFF
                val provBytes = ByteArray(provLen).also { buf.get(it) }
                val descLen = buf.short.toInt() and 0xFFFF
                val descBytes = ByteArray(descLen).also { buf.get(it) }
                out.add(
                    ParsedExtendedPoint(
                        timeMs = time,
                        lat = lat,
                        lon = lon,
                        sat = sat,
                        alt = alt,
                        spdKph = spdKph,
                        bearing = bearing,
                        acc = acc,
                        batt = batt,
                        isCharging = isCharging,
                        dist = dist,
                        prov = String(provBytes, Charsets.UTF_8),
                        desc = String(descBytes, Charsets.UTF_8),
                    )
                )
            }
            return out
        }

        fun parseMinimalPoints(count: Int): List<ParsedMinimalPoint> {
            val buf = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
            buf.position(pointsOffset)
            val out = ArrayList<ParsedMinimalPoint>(count)
            repeat(count) {
                buf.get()
                val time = buf.long
                val lat = buf.float
                val lon = buf.float
                out.add(ParsedMinimalPoint(time, lat, lon))
            }
            return out
        }
    }

    private data class ParsedExtendedPoint(
        val timeMs: Long,
        val lat: Float,
        val lon: Float,
        val sat: Int,
        val alt: Float,
        val spdKph: Float,
        val bearing: Float,
        val acc: Float,
        val batt: Int,
        val isCharging: Boolean,
        val dist: Float,
        val prov: String,
        val desc: String,
    )

    private data class ParsedMinimalPoint(val timeMs: Long, val lat: Float, val lon: Float)

    private fun parseHeader(bytes: ByteArray): ParsedHeader {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val magicBytes = ByteArray(4).also { buf.get(it) }
        val msb = buf.long
        val lsb = buf.long
        val flags = buf.get().toInt()
        val sessionStart = buf.long
        var ser = ""
        if ((flags and 0x01) != 0) {
            val serLen = buf.get().toInt() and 0xFF
            val serBytes = ByteArray(serLen).also { buf.get(it) }
            ser = String(serBytes, Charsets.UTF_8)
        }
        return ParsedHeader(
            magic = String(magicBytes, Charsets.US_ASCII),
            trackerUuid = UUID(msb, lsb),
            hasExtended = (flags and 0x01) != 0,
            sessionStartMs = sessionStart,
            serString = ser,
            pointsOffset = buf.position(),
            raw = bytes,
        )
    }

    private var idCounter = 1L

    private fun queuedLocation(
        time: Long,
        lat: Double,
        lon: Double,
        altitude: Double? = null,
        speedMps: Float? = null,
        bearing: Float? = null,
        accuracy: Float? = null,
        sat: Int? = null,
        prov: String? = null,
        dist: Float? = null,
    ): QueuedLocation = QueuedLocation(
        id = idCounter++,
        trackerId = "tracker-1",
        time = time,
        latitude = lat,
        longitude = lon,
        altitude = altitude,
        speed = speedMps,
        bearing = bearing,
        accuracy = accuracy,
        sat = sat,
        prov = prov,
        dist = dist,
        startTimestampMs = null,
    )
}
