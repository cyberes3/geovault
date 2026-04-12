package com.geovault.tracker.services

import com.geovault.tracker.db.QueuedLocation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class QueueInFlightClaimHomogeneousTest {

    private fun row(id: Long, trackerId: String?, time: Long = id): QueuedLocation = QueuedLocation(
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
    fun claimHomogeneousConsecutive_splitsWhenTrackerChanges() = runBlocking {
        val set = QueueInFlightClaimSet()
        val candidates = listOf(row(1, "a"), row(2, "a"), row(3, "b"))
        val first = set.claimHomogeneousConsecutive(candidates, limit = 10, fallbackTrackerId = "x")
        assertEquals(listOf(1L, 2L), first.map { it.id })
        val second = set.claimHomogeneousConsecutive(candidates, limit = 10, fallbackTrackerId = "x")
        assertEquals(listOf(3L), second.map { it.id })
    }

    @Test
    fun nullTrackerIdUsesFallbackSoBatchStaysHomogeneous() = runBlocking {
        val set = QueueInFlightClaimSet()
        val fid = "00000000-0000-4000-8000-000000000099"
        val candidates = listOf(row(1, null), row(2, null))
        val batch = set.claimHomogeneousConsecutive(candidates, limit = 10, fallbackTrackerId = fid)
        assertEquals(2, batch.size)
        assertEquals(null, batch[0].trackerId)
        assertEquals(null, batch[1].trackerId)
    }
}
