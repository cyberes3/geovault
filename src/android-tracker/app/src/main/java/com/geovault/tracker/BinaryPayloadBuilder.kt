package com.geovault.tracker

import android.location.Location
import java.nio.ByteBuffer
import java.nio.ByteOrder

object BinaryPayloadBuilder {
    private val MAGIC_BYTES = byteArrayOf('G'.code.toByte(), 'V'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte())
    private const val VERSION: Byte = 1

    /**
     * Builds the binary payload for the given list of locations.
     * @param locations List of Location objects to pack.
     * @param includeExtendedData Whether to try packing extended data (altitude, speed, bearing, accuracy).
     * @return ByteArray containing the payload.
     */
    fun buildPayload(locations: List<Location>, includeExtendedData: Boolean): ByteArray {
        // Base point size = 25 bytes
        // Extended point size = 16 bytes
        // Max possible size = 5 (header) + locations.size * 41
        val capacity = 5 + locations.size * 41
        val buffer = ByteBuffer.allocate(capacity)
        buffer.order(ByteOrder.BIG_ENDIAN)

        // Header
        buffer.put(MAGIC_BYTES)
        buffer.put(VERSION)

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
