package com.geovault.tracker

import com.geovault.tracker.db.QueuedLocation
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class TrackingServiceQueueInFlightClaimSetTest {
    private fun queued(id: Long): QueuedLocation {
        return QueuedLocation(
            id = id,
            time = id,
            latitude = 1.0,
            longitude = 1.0,
            altitude = null,
            speed = null,
            bearing = null,
            accuracy = null,
            sat = null,
            prov = null,
            dist = null
        )
    }

    @Test
    fun claim_skipsAlreadyClaimedRows() = runBlocking {
        val claims = TrackingService.QueueInFlightClaimSet()
        val candidates = (1L..5L).map(::queued)

        val first = claims.claim(candidates, limit = 3)
        val second = claims.claim(candidates, limit = 3)

        assertEquals(listOf(1L, 2L, 3L), first.map { it.id })
        assertEquals(listOf(4L, 5L), second.map { it.id })
    }

    @Test
    fun release_allowsReclaimingRows() = runBlocking {
        val claims = TrackingService.QueueInFlightClaimSet()
        val candidates = listOf(queued(10L), queued(11L))

        val first = claims.claim(candidates, limit = 2)
        claims.release(first)
        val second = claims.claim(candidates, limit = 2)

        assertEquals(first.map { it.id }, second.map { it.id })
    }

    @Test
    fun concurrent_claims_doNotOverlapRows() = runBlocking {
        val claims = TrackingService.QueueInFlightClaimSet()
        val candidates = (100L..109L).map(::queued)

        val results = listOf(
            async { claims.claim(candidates, limit = 6) },
            async { claims.claim(candidates, limit = 6) }
        ).awaitAll()

        val allIds = results.flatten().map { it.id }
        val uniqueIds = allIds.toSet()

        assertEquals(uniqueIds.size, allIds.size)
        assertTrue(uniqueIds.size <= candidates.size)
    }

    @Test
    fun releaseIds_cleansUpLeakedClaims() = runBlocking {
        val claims = TrackingService.QueueInFlightClaimSet()
        val candidates = listOf(queued(200L), queued(201L), queued(202L))
        val claimed = claims.claim(candidates, limit = 3)
        assertEquals(3, claims.claimedCount())

        claims.releaseIds(setOf(200L, 202L))
        assertEquals(1, claims.claimedCount())

        val reclaimed = claims.claim(candidates, limit = 3).map { it.id }.toSet()
        assertTrue(200L in reclaimed)
        assertTrue(202L in reclaimed)
        assertFalse(201L in reclaimed)
    }
}
