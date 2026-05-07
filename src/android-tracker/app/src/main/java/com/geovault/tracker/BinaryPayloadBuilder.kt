package com.geovault.tracker

import com.geovault.tracker.db.QueuedLocation
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * GVL2 binary upload format. Single self-describing format that always carries
 * the batch `session_start_ms` so the server can attribute every point to a
 * tracking session. A single feature-flags byte gates whether each point also
 * carries the extended fields (sat/alt/spd/bearing/acc/batt/charging/dist/prov/desc).
 *
 * Header layout:
 *   magic[4]              "GVL2"
 *   uuid[16]              tracker UUID
 *   flags[1]              bit0 = HAS_EXTENDED (other bits reserved, must be 0)
 *   session_start_ms[8]   batch session start, milliseconds
 *   -- if HAS_EXTENDED:
 *   ser_len[1]
 *   ser_bytes[ser_len]    build serial (truncated to MAX_SER_BYTES)
 *
 * Per-point layout:
 *   flag[1] ts_ms[8] lat_f32[4] lon_f32[4]                       (17 base bytes)
 *   -- if HAS_EXTENDED:
 *   sat_u16[2] alt_f32[4] spd_kph_f32[4] bearing_f32[4] acc_f32[4]
 *   batt_u8[1] ischarging_i8[1] dist_m_f32[4]
 *   prov_len_u8[1] prov_bytes[P]
 *   desc_len_u16[2] desc_bytes[D]
 */
object BinaryPayloadBuilder {
    private val MAGIC_BYTES = byteArrayOf('G'.code.toByte(), 'V'.code.toByte(), 'L'.code.toByte(), '2'.code.toByte())

    private const val FLAG_HAS_EXTENDED: Int = 0x01

    private const val MAX_PROV_BYTES = 64
    private const val MAX_SER_BYTES = 64
    private const val MAX_DESC_BYTES = 256

    private const val BASE_HEADER_BYTES = 4 + 16 + 1 + 8
    private const val BASE_POINT_BYTES = 1 + 8 + 4 + 4
    private const val EXTENDED_FIXED_PER_POINT_BYTES = 2 + 4 * 4 + 1 + 1 + 4
    private const val EXTENDED_MAX_STRINGS_PER_POINT = 1 + MAX_PROV_BYTES + 2 + MAX_DESC_BYTES

    /**
     * Header descriptor that fully specifies the GVL2 batch envelope. The
     * uploader builds one of these per batch; the encoder uses it without
     * branching on per-call flags.
     */
    data class Header(
        val trackerUuid: UUID,
        val sessionStartMs: Long,
        val hasExtended: Boolean,
        val buildSerial: String,
    ) {
        internal val flagsByte: Byte
            get() = if (hasExtended) FLAG_HAS_EXTENDED.toByte() else 0

        internal val serBytes: ByteArray
            get() = if (hasExtended) {
                buildSerial.toByteArray(Charsets.UTF_8).let {
                    if (it.size > MAX_SER_BYTES) it.copyOf(MAX_SER_BYTES) else it
                }
            } else {
                EMPTY_BYTES
            }
    }

    fun build(
        locations: List<QueuedLocation>,
        header: Header,
        batteryLevel: Int,
        isCharging: Boolean,
    ): ByteArray {
        val serBytes = header.serBytes
        val headerBytes = if (header.hasExtended) {
            BASE_HEADER_BYTES + 1 + serBytes.size
        } else {
            BASE_HEADER_BYTES
        }
        val perPointBytes = if (header.hasExtended) {
            BASE_POINT_BYTES + EXTENDED_FIXED_PER_POINT_BYTES + EXTENDED_MAX_STRINGS_PER_POINT
        } else {
            BASE_POINT_BYTES
        }
        val capacity = headerBytes + locations.size * perPointBytes
        val buffer = ByteBuffer.allocate(capacity)
        buffer.order(ByteOrder.BIG_ENDIAN)

        buffer.put(MAGIC_BYTES)
        buffer.putLong(header.trackerUuid.mostSignificantBits)
        buffer.putLong(header.trackerUuid.leastSignificantBits)
        buffer.put(header.flagsByte)
        buffer.putLong(header.sessionStartMs)
        if (header.hasExtended) {
            buffer.put(serBytes.size.toByte())
            buffer.put(serBytes)
        }

        for (loc in locations) {
            writeBasePoint(buffer, loc)
            if (header.hasExtended) {
                writeExtendedFields(buffer, loc, batteryLevel, isCharging)
            }
        }

        val result = ByteArray(buffer.position())
        buffer.rewind()
        buffer.get(result)
        return result
    }

    private fun writeBasePoint(buffer: ByteBuffer, loc: QueuedLocation) {
        buffer.put(0)
        buffer.putLong(loc.time)
        buffer.putFloat(loc.latitude.toFloat())
        buffer.putFloat(loc.longitude.toFloat())
    }

    private fun writeExtendedFields(
        buffer: ByteBuffer,
        loc: QueuedLocation,
        batteryLevel: Int,
        isCharging: Boolean,
    ) {
        buffer.putShort(((loc.sat ?: 0) and 0xFFFF).toShort())
        buffer.putFloat((loc.altitude ?: 0.0).toFloat())
        val speedKmh = (loc.speed ?: 0f) * 3.6f
        buffer.putFloat(speedKmh)
        buffer.putFloat(loc.bearing ?: 0f)
        buffer.putFloat(loc.accuracy ?: 0f)

        buffer.put((batteryLevel.coerceIn(0, 100)).toByte())
        buffer.put(if (isCharging) 1 else 0)
        buffer.putFloat(loc.dist ?: 0f)

        val provBytes = (loc.prov ?: "").toByteArray(Charsets.UTF_8).let {
            if (it.size > MAX_PROV_BYTES) it.copyOf(MAX_PROV_BYTES) else it
        }
        buffer.put(provBytes.size.toByte())
        buffer.put(provBytes)

        val descBytes = EMPTY_BYTES
        buffer.putShort((descBytes.size and 0xFFFF).toShort())
        buffer.put(descBytes)
    }

    private val EMPTY_BYTES = ByteArray(0)
}
