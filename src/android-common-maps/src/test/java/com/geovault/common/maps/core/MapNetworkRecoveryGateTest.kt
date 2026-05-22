package com.geovault.common.maps.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapNetworkRecoveryGateTest {

    @Test
    fun shouldRetry_allowsReadyDegradedMap() {
        var now = 1_000L
        val gate = MapNetworkRecoveryGate(cooldownMs = 45_000L) { now }

        assertTrue(gate.shouldRetry(GeoVaultMapPhase.Ready, null))
    }

    @Test
    fun shouldRetry_blocksWhileStyleLoading() {
        val gate = MapNetworkRecoveryGate { 1_000L }

        assertFalse(gate.shouldRetry(GeoVaultMapPhase.StyleLoading, null))
    }

    @Test
    fun shouldRetry_blocksConfigurationErrors() {
        val gate = MapNetworkRecoveryGate { 1_000L }

        assertFalse(
            gate.shouldRetry(
                GeoVaultMapPhase.Error,
                GeoVaultMapErrorNotice(
                    type = GeoVaultMapErrorNoticeType.Configuration,
                    title = "Map Setup Required",
                    message = "Map configuration is incomplete.",
                ),
            ),
        )
    }

    @Test
    fun shouldRetry_allowsStyleLoadErrors() {
        val gate = MapNetworkRecoveryGate { 1_000L }

        assertTrue(
            gate.shouldRetry(
                GeoVaultMapPhase.Error,
                GeoVaultMapErrorNotice(
                    type = GeoVaultMapErrorNoticeType.StyleLoad,
                    title = "Map Unavailable",
                    message = "Map style failed to load.",
                ),
            ),
        )
    }

    @Test
    fun shouldRetry_enforcesCooldown() {
        var now = 1_000L
        val gate = MapNetworkRecoveryGate(cooldownMs = 45_000L) { now }

        assertTrue(gate.shouldRetry(GeoVaultMapPhase.Ready, null))
        now += 10_000L
        assertFalse(gate.shouldRetry(GeoVaultMapPhase.Ready, null))
        now += 45_000L
        assertTrue(gate.shouldRetry(GeoVaultMapPhase.Ready, null))
    }
}
