package com.geovault.tracker

import android.location.Location
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

object BinaryPayloadBuilder {
    private val MAGIC_BYTES = byteArrayOf('G'.code.toByte(), 'V'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte())
    private const val VERSION_V2: Byte = 2

    /**
     * Builds the GVLT v2 binary payload: magic (4) + version 0x02 (1) + tracker_id UUID (16) + points.
     *
     * @param locations List of Location objects to pack.
     * @param trackerId UUID of the track to upload to (included in payload).
     * @param includeExtendedData Whether to try packing extended data (altitude, speed, bearing, accuracy).
     * @return ByteArray containing the payload.
     */
    fun buildPayload(locations: List<Location>, trackerId: UUID, includeExtendedData: Boolean): ByteArray {
        // Header: 4 (magic) + 1 (version) + 16 (UUID). Points: 25 or 41 bytes each.
        val capacity = 5 + 16 + locations.size * 41
        val buffer = ByteBuffer.allocate(capacity)
        buffer.order(ByteOrder.BIG_ENDIAN)

        buffer.put(MAGIC_BYTES)
        buffer.put(VERSION_V2)
        buffer.putLong(trackerId.mostSignificantBits)
        buffer.putLong(trackerId.leastSignificantBits)

        for (loc in locations) {
            val hasExtended = includeExtendedData && (loc.hasAltitude() || loc.hasSpeed() || loc.hasBearing() || loc.hasAccuracy())
            val flag: Byte = if (hasExtended) 1 else 0

            // Base Data (25 bytes)
            buffer.put(flag)
            buffer.putLong(loc.time)
            buffer.putDouble(loc.latitude)
            buffer.putDouble(loc.longitude)

            // Extended Data (16 bytes)
            if (hasExtended) {
                buffer.putFloat(if (loc.hasAltitude()) loc.altitude.toFloat() else 0f)
                // Speed from Android Location is in m/s. Backend expects spd_kph.
                val speedKmh = if (loc.hasSpeed()) loc.speed * 3.6f else 0f
                buffer.putFloat(speedKmh)
                buffer.putFloat(if (loc.hasBearing()) loc.bearing else 0f)
                buffer.putFloat(if (loc.hasAccuracy()) loc.accuracy else 0f)
            }
        }

        val result = ByteArray(buffer.position())
        buffer.rewind()
        buffer.get(result)
        return result
    }
}
