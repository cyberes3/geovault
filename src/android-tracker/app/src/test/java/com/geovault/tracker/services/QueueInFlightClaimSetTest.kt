package com.geovault.tracker.services

import com.geovault.tracker.db.QueuedLocation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueInFlightClaimSetTest {

    private fun row(id: Long, trackerId: String = "t", time: Long = id): QueuedLocation =
        QueuedLocation(
            id = id,
            trackerId = trackerId,
            time = time,
            latitude = 0.0,
            longitude = 0.0,
            altitude = null,
            speed = null,
            bearing = null,
            accuracy = null,
            sat = null,
            prov = null,
            dist = null,
        )

    @Test
    fun claim_reservesUpToLimit_inProvidedOrder() = runBlocking {
        val set = QueueInFlightClaimSet()
        val batch = set.claim(listOf(row(1), row(2), row(3), row(4)), limit = 2)
        assertEquals(listOf(1L, 2L), batch.map { it.id })
    }

    @Test
    fun claim_skipsRowsAlreadyReservedByAnotherClaimer() = runBlocking {
        val set = QueueInFlightClaimSet()
        val candidates = listOf(row(1), row(2), row(3), row(4))
        val first = set.claim(candidates, limit = 2)
        val second = set.claim(candidates, limit = 2)
        assertEquals(listOf(1L, 2L), first.map { it.id })
        assertEquals(listOf(3L, 4L), second.map { it.id })
    }

    @Test
    fun release_allowsRowsToBeReclaimed() = runBlocking {
        val set = QueueInFlightClaimSet()
        val candidates = listOf(row(1), row(2))
        val first = set.claim(candidates, limit = 2)
        set.release(first)
        val second = set.claim(candidates, limit = 2)
        assertEquals(listOf(1L, 2L), second.map { it.id })
    }

    @Test
    fun claim_emptyOrNonPositiveLimit_returnsEmpty() = runBlocking {
        val set = QueueInFlightClaimSet()
        assertTrue(set.claim(emptyList(), limit = 5).isEmpty())
        assertTrue(set.claim(listOf(row(1)), limit = 0).isEmpty())
    }
}
