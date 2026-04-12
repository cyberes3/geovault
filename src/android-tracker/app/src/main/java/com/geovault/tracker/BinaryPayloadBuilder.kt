package com.geovault.tracker

import com.geovault.tracker.db.QueuedLocation
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

object BinaryPayloadBuilder {
    private val MAGIC_BYTES = byteArrayOf('G'.code.toByte(), 'V'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte())
    private val MAGIC_MINIMAL_BYTES = byteArrayOf('G'.code.toByte(), 'V'.code.toByte(), 'L'.code.toByte(), 'M'.code.toByte())

    private const val MAX_PROV_BYTES = 64
    private const val MAX_SER_BYTES = 64
    private const val MAX_DESC_BYTES = 256

    fun buildPayload(
        locations: List<QueuedLocation>,
        trackerId: UUID,
        sessionStartTimeMs: Long,
        batteryLevel: Int,
        isCharging: Boolean,
        buildSerial: String
    ): ByteArray {
        val serBytes = buildSerial.toByteArray(Charsets.UTF_8).let {
            if (it.size > MAX_SER_BYTES) it.copyOf(MAX_SER_BYTES) else it
        }
        val batchHeaderSize = 20 + 8 + 1 + serBytes.size
        val basePerPoint = 17
        val extendedFixedPerPoint = 2 + 4 * 4 + 1 + 1 + 4
        val maxStringsPerPoint = 1 + MAX_PROV_BYTES + 2 + MAX_DESC_BYTES
        val capacity = batchHeaderSize + locations.size * (basePerPoint + extendedFixedPerPoint + maxStringsPerPoint)
        val buffer = ByteBuffer.allocate(capacity)
        buffer.order(ByteOrder.BIG_ENDIAN)

        buffer.put(MAGIC_BYTES)
        buffer.putLong(trackerId.mostSignificantBits)
        buffer.putLong(trackerId.leastSignificantBits)
        buffer.putLong(sessionStartTimeMs)
        buffer.put(serBytes.size.toByte())
        buffer.put(serBytes)

        for (loc in locations) {
            writePoint(buffer, loc, batteryLevel, isCharging)
        }

        val result = ByteArray(buffer.position())
        buffer.rewind()
        buffer.get(result)
        return result
    }

    fun buildPayloadMinimal(locations: List<QueuedLocation>, trackerId: UUID): ByteArray {
        val capacity = 20 + locations.size * 17
        val buffer = ByteBuffer.allocate(capacity)
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.put(MAGIC_MINIMAL_BYTES)
        buffer.putLong(trackerId.mostSignificantBits)
        buffer.putLong(trackerId.leastSignificantBits)
        for (loc in locations) {
            buffer.put(0)
            buffer.putLong(loc.time)
            buffer.putFloat(loc.latitude.toFloat())
            buffer.putFloat(loc.longitude.toFloat())
        }
        val result = ByteArray(buffer.position())
        buffer.rewind()
        buffer.get(result)
        return result
    }

    private fun writePoint(
        buffer: ByteBuffer,
        loc: QueuedLocation,
        batteryLevel: Int,
        isCharging: Boolean
    ) {
        buffer.put(0)
        buffer.putLong(loc.time)
        buffer.putFloat(loc.latitude.toFloat())
        buffer.putFloat(loc.longitude.toFloat())

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

        val descBytes = "".toByteArray(Charsets.UTF_8)
        buffer.putShort((descBytes.size and 0xFFFF).toShort())
        buffer.put(descBytes)
    }
}
