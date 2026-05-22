package com.geovault.common.maps.core

internal class MapNetworkRecoveryGate(
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    private var lastRetryAtMs: Long = Long.MIN_VALUE

    fun shouldRetry(phase: GeoVaultMapPhase, errorNotice: GeoVaultMapErrorNotice?): Boolean {
        if (phase == GeoVaultMapPhase.StyleLoading) return false
        if (errorNotice?.type == GeoVaultMapErrorNoticeType.Configuration) return false

        val now = nowMs()
        if (lastRetryAtMs != Long.MIN_VALUE && now - lastRetryAtMs < cooldownMs) return false

        lastRetryAtMs = now
        return true
    }

    companion object {
        const val DEFAULT_COOLDOWN_MS = 45_000L
    }
}
