package com.geovault.common.logging

import java.util.concurrent.ConcurrentHashMap

/**
 * Rate-limits capture-log writes for hot paths. Logcat is unaffected; only
 * [GeoVaultCaptureLog] persistence is gated when callers check these helpers first.
 */
object CaptureLogThrottle {
    private val lastAtMs = ConcurrentHashMap<String, Long>()
    private val lastSignature = ConcurrentHashMap<String, String>()

    fun shouldLogInterval(key: String, intervalMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (intervalMs <= 0L) return true
        val previous = lastAtMs[key]
        if (previous == null) {
            lastAtMs[key] = nowMs
            return true
        }
        if (nowMs - previous < intervalMs) return false
        lastAtMs[key] = nowMs
        return true
    }

    fun shouldLogOnChange(key: String, signature: String): Boolean {
        val previous = lastSignature[key]
        if (previous == signature) return false
        lastSignature[key] = signature
        return true
    }

    fun resetForTests() {
        lastAtMs.clear()
        lastSignature.clear()
    }
}
