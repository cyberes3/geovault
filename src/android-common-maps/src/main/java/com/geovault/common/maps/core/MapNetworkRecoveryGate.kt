package com.geovault.common.maps.core

internal class MapNetworkRecoveryGate(
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val cacheOnlyCooldownMs: Long = CACHE_ONLY_RECONNECT_COOLDOWN_MS,
    private val nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    private var lastRetryAtMs: Long = Long.MIN_VALUE

    fun shouldRetry(
        phase: GeoVaultMapPhase,
        errorNotice: GeoVaultMapErrorNotice?,
        preferShortCooldown: Boolean = false,
    ): Boolean {
        if (phase == GeoVaultMapPhase.StyleLoading) return false
        if (errorNotice?.type == GeoVaultMapErrorNoticeType.Configuration) return false

        val now = nowMs()
        val requiredCooldown = if (preferShortCooldown) cacheOnlyCooldownMs else cooldownMs
        if (lastRetryAtMs != Long.MIN_VALUE && now - lastRetryAtMs < requiredCooldown) return false

        lastRetryAtMs = now
        return true
    }

    companion object {
        const val DEFAULT_COOLDOWN_MS = 45_000L
        const val CACHE_ONLY_RECONNECT_COOLDOWN_MS = 2_000L
    }
}
