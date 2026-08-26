package com.geovault.common.maps.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapMetadataLoadGateTest {

    @Test
    fun cacheOnly_immediateDelivery_usesCache() {
        val gate = MapMetadataLoadGate(
            plan = MapNetworkAccessPlan.CacheOnly,
            cached = "disk",
            timeoutPlaceholder = "empty",
        )

        val delivery = gate.immediateDelivery()
        assertNotNull(delivery)
        assertEquals("disk", delivery!!.value)
        assertTrue(delivery.applyToMap)
        assertFalse(delivery.isLate)
        assertNull(gate.immediateDelivery())
    }

    @Test
    fun deadlineRace_networkWins_beforeDeadline() {
        val gate = MapMetadataLoadGate(
            plan = MapNetworkAccessPlan.NetworkWithCacheDeadline,
            cached = "disk",
            timeoutPlaceholder = "empty",
        )

        assertNull(gate.immediateDelivery())
        val delivery = gate.onNetworkResult("fresh", isUsable = true, applyLate = false)
        assertNotNull(delivery)
        assertEquals("fresh", delivery!!.value)
        assertTrue(delivery.applyToMap)
        assertFalse(delivery.isLate)
        assertNull(gate.onDeadline())
    }

    @Test
    fun deadlineRace_deadlineWins_lateNetworkDoesNotApply() {
        val gate = MapMetadataLoadGate(
            plan = MapNetworkAccessPlan.NetworkWithCacheDeadline,
            cached = "disk",
            timeoutPlaceholder = "empty",
        )

        val deadline = gate.onDeadline()
        assertNotNull(deadline)
        assertEquals("disk", deadline!!.value)
        assertTrue(deadline.applyToMap)
        assertFalse(deadline.isLate)

        val late = gate.onNetworkResult("fresh", isUsable = true, applyLate = false)
        assertNotNull(late)
        assertEquals("fresh", late!!.value)
        assertFalse(late.applyToMap)
        assertTrue(late.isLate)
    }

    @Test
    fun deadlineRace_unusableNetworkBeforeDeadline_doesNotDeliver() {
        val gate = MapMetadataLoadGate(
            plan = MapNetworkAccessPlan.NetworkWithCacheDeadline,
            cached = "disk",
            timeoutPlaceholder = "empty",
        )

        assertNull(gate.onNetworkResult("empty", isUsable = false, applyLate = false))
        val deadline = gate.onDeadline()
        assertEquals("disk", deadline!!.value)
        assertTrue(deadline.applyToMap)
    }

    @Test
    fun waitForNetwork_deadline_emptyThenLateSuccessApplies() {
        val gate = MapMetadataLoadGate(
            plan = MapNetworkAccessPlan.WaitForNetwork,
            cached = null,
            timeoutPlaceholder = "empty",
        )

        assertNull(gate.immediateDelivery())
        assertNull(gate.onNetworkResult("empty", isUsable = false, applyLate = false))

        val deadline = gate.onDeadline()
        assertEquals("empty", deadline!!.value)
        assertTrue(deadline.applyToMap)
        assertFalse(deadline.isLate)

        val late = gate.onNetworkResult("fresh", isUsable = true, applyLate = true)
        assertEquals("fresh", late!!.value)
        assertTrue(late.applyToMap)
        assertTrue(late.isLate)
    }

    @Test
    fun waitForNetwork_successBeforeDeadline_appliesOnce() {
        val gate = MapMetadataLoadGate(
            plan = MapNetworkAccessPlan.WaitForNetwork,
            cached = null,
            timeoutPlaceholder = "empty",
        )

        val delivery = gate.onNetworkResult("fresh", isUsable = true, applyLate = true)
        assertEquals("fresh", delivery!!.value)
        assertTrue(delivery.applyToMap)
        assertFalse(delivery.isLate)
        assertNull(gate.onDeadline())
    }

    @Test
    fun waitForNetwork_lateFailureAfterDeadline_doesNotReapply() {
        val gate = MapMetadataLoadGate(
            plan = MapNetworkAccessPlan.WaitForNetwork,
            cached = null,
            timeoutPlaceholder = "empty",
        )

        gate.onDeadline()
        assertNull(gate.onNetworkResult("empty", isUsable = false, applyLate = false))
    }
}
