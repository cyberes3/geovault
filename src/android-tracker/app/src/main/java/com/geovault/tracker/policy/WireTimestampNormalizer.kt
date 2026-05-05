package com.geovault.tracker.policy

object WireTimestampNormalizer {
    fun normalizeToMilliseconds(value: Any?): Long? {
        val raw = when (value) {
            null -> return null
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull() ?: return null
            else -> return null
        }
        if (raw <= 0L) return null
        return if (raw < 1_000_000_000_000L) raw * 1000L else raw
    }
}
