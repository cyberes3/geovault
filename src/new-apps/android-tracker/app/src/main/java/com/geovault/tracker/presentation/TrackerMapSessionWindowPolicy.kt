package com.geovault.tracker.presentation

object TrackerMapSessionWindowPolicy {
    fun normalizeTimestampToMs(value: Any?): Long? {
        val raw = when (value) {
            null -> return null
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull() ?: return null
            else -> return null
        }
        return if (raw in 1L..999_999_999_999L) raw * 1000L else raw
    }
}
