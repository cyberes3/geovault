package com.geovault.tracker.services

enum class ProviderHealthReason(val telemetryValue: String) {
    HEALTHY("healthy"),
    NOT_TRACKING("not-tracking"),
    GPS_PROVIDER_UNAVAILABLE("gps-provider-unavailable"),
    PAUSED("paused"),
    NO_REQUEST("no-request"),
    CALLBACK_SILENT("callback-silent"),
}

sealed class ProviderHealthDecision(
    val reason: ProviderHealthReason,
    val ageMs: Long? = null,
) {
    data object Healthy : ProviderHealthDecision(ProviderHealthReason.HEALTHY)
    class Wait(reason: ProviderHealthReason) : ProviderHealthDecision(reason)
    class ReapplyRequest(ageMs: Long) : ProviderHealthDecision(ProviderHealthReason.CALLBACK_SILENT, ageMs)

    val telemetryValue: String
        get() = ageMs?.let { "${reason.telemetryValue}:ageMs=$it" } ?: reason.telemetryValue
}

class ProviderHealthController(
    private val staleFixDeliveryMs: Long,
) {
    private var lastFixDeliveredAtMs: Long = 0L
    private var lastRequestAppliedAtMs: Long = 0L
    private var lastLoggedDecision: String? = null

    fun reset() {
        lastFixDeliveredAtMs = 0L
        lastRequestAppliedAtMs = 0L
        lastLoggedDecision = null
    }

    fun markFixDelivered(nowMs: Long) {
        lastFixDeliveredAtMs = nowMs
    }

    fun markRequestApplied(nowMs: Long) {
        lastRequestAppliedAtMs = nowMs
    }

    fun lastFixDeliveredAtMs(): Long = lastFixDeliveredAtMs

    fun lastRequestAppliedAtMs(): Long = lastRequestAppliedAtMs

    fun evaluate(
        nowMs: Long,
        isTracking: Boolean,
        expectsActiveFixDelivery: Boolean,
        gpsProviderAvailable: Boolean,
    ): ProviderHealthDecision {
        if (!isTracking) return ProviderHealthDecision.Wait(ProviderHealthReason.NOT_TRACKING)
        if (!gpsProviderAvailable) return ProviderHealthDecision.Wait(ProviderHealthReason.GPS_PROVIDER_UNAVAILABLE)
        if (!expectsActiveFixDelivery) return ProviderHealthDecision.Wait(ProviderHealthReason.PAUSED)
        val baselineMs = lastFixDeliveredAtMs.takeIf { it > 0L } ?: lastRequestAppliedAtMs
        if (baselineMs <= 0L) return ProviderHealthDecision.Wait(ProviderHealthReason.NO_REQUEST)
        val ageMs = nowMs - baselineMs
        return if (ageMs > staleFixDeliveryMs) {
            ProviderHealthDecision.ReapplyRequest(ageMs)
        } else {
            ProviderHealthDecision.Healthy
        }
    }

    fun shouldLog(decision: ProviderHealthDecision): Boolean {
        val key = decision.telemetryValue
        if (key == lastLoggedDecision) return false
        lastLoggedDecision = key
        return true
    }
}
