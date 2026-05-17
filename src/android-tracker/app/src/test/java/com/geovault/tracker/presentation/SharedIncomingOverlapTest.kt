package com.geovault.tracker.presentation

import com.geovault.tracker.AvailableToAddItem
import org.junit.Assert.assertEquals
import org.junit.Test

class SharedIncomingOverlapTest {

    @Test
    fun countOverlappingIncomingShares_returnsZeroWhenNoOverlap() {
        val incoming = listOf(
            AvailableToAddItem(id = "t-1", name = "Tracker 1"),
            AvailableToAddItem(id = "t-2", name = "Tracker 2"),
        )

        assertEquals(
            0,
            countOverlappingIncomingShares(incoming, listOf("t-3", "t-4")),
        )
    }

    @Test
    fun countOverlappingIncomingShares_countsPartialOverlap() {
        val incoming = listOf(
            AvailableToAddItem(id = "t-1", name = "Tracker 1"),
            AvailableToAddItem(id = "t-2", name = "Tracker 2"),
            AvailableToAddItem(id = "t-3", name = "Tracker 3"),
        )

        assertEquals(
            2,
            countOverlappingIncomingShares(incoming, listOf("t-2", "t-4", "t-3")),
        )
    }

    @Test
    fun countOverlappingIncomingShares_normalizesIdsBeforeComparing() {
        val incoming = listOf(AvailableToAddItem(id = "42", name = "Tracker 42"))

        assertEquals(1, countOverlappingIncomingShares(incoming, listOf("42")))
    }

    @Test
    fun countOverlappingIncomingShares_handlesEmptyInputs() {
        assertEquals(0, countOverlappingIncomingShares(emptyList(), listOf("t-1")))
        assertEquals(0, countOverlappingIncomingShares(listOf(AvailableToAddItem(id = "t-1", name = "Tracker 1")), emptyList()))
        assertEquals(0, countOverlappingIncomingShares(emptyList(), null))
    }
}
