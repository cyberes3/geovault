package com.geovault.tracker.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderHealthControllerTest {
    @Test
    fun evaluate_recentFixIsHealthy() {
        val controller = ProviderHealthController(staleFixDeliveryMs = 90_000L)
        controller.markRequestApplied(1_000L)
        controller.markFixDelivered(10_000L)

        val decision = controller.evaluate(
            nowMs = 20_000L,
            isTracking = true,
            expectsActiveFixDelivery = true,
            gpsProviderAvailable = true,
        )

        assertEquals(ProviderHealthReason.HEALTHY, decision.reason)
    }

    @Test
    fun evaluate_silentCallbackRequestsReapply() {
        val controller = ProviderHealthController(staleFixDeliveryMs = 90_000L)
        controller.markRequestApplied(1_000L)

        val decision = controller.evaluate(
            nowMs = 100_001L,
            isTracking = true,
            expectsActiveFixDelivery = true,
            gpsProviderAvailable = true,
        )

        assertTrue(decision is ProviderHealthDecision.ReapplyRequest)
        assertEquals(ProviderHealthReason.CALLBACK_SILENT, decision.reason)
    }

    @Test
    fun evaluate_silentCallbackCarriesStaleFreshnessSignal() {
        val controller = ProviderHealthController(staleFixDeliveryMs = 90_000L)
        controller.markRequestApplied(1_000L)

        val decision = controller.evaluate(
            nowMs = 100_001L,
            isTracking = true,
            expectsActiveFixDelivery = true,
            gpsProviderAvailable = true,
            localRecoveryDue = true,
        )

        assertTrue(decision is ProviderHealthDecision.ReapplyRequest)
        assertTrue((decision as ProviderHealthDecision.ReapplyRequest).staleFreshness)
    }

    @Test
    fun evaluate_recentFixDoesNotCarryStaleFreshnessSignal() {
        val controller = ProviderHealthController(staleFixDeliveryMs = 90_000L)
        controller.markRequestApplied(1_000L)
        controller.markFixDelivered(10_000L)

        val decision = controller.evaluate(
            nowMs = 20_000L,
            isTracking = true,
            expectsActiveFixDelivery = true,
            gpsProviderAvailable = true,
            localRecoveryDue = true,
        )

        assertEquals(ProviderHealthReason.HEALTHY, decision.reason)
    }

    @Test
    fun evaluate_disabledGpsProviderUsesExplicitProviderReason() {
        val controller = ProviderHealthController(staleFixDeliveryMs = 90_000L)

        val decision = controller.evaluate(
            nowMs = 20_000L,
            isTracking = true,
            expectsActiveFixDelivery = true,
            gpsProviderAvailable = false,
        )

        assertEquals(ProviderHealthReason.GPS_PROVIDER_UNAVAILABLE, decision.reason)
        assertEquals("gps-provider-unavailable", decision.telemetryValue)
    }

    @Test
    fun shouldLogOnlyOnTransitions() {
        val controller = ProviderHealthController(staleFixDeliveryMs = 90_000L)

        assertTrue(controller.shouldLog(ProviderHealthDecision.Healthy))
        assertFalse(controller.shouldLog(ProviderHealthDecision.Healthy))
        assertTrue(controller.shouldLog(ProviderHealthDecision.Wait(ProviderHealthReason.PAUSED)))
    }
}
