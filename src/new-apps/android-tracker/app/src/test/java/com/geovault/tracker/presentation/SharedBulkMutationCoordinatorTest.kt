package com.geovault.tracker.presentation

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedBulkMutationCoordinatorTest {

    @Test
    fun normalizeIds_trimsDedupesAndRemovesBlanks() {
        val normalized = SharedBulkMutationCoordinator.normalizeIds(
            listOf(" a ", "", "b", "a", "  ")
        )

        assertEquals(listOf("a", "b"), normalized)
    }

    @Test
    fun run_tracksSuccessAndFailuresDeterministically() = runBlocking {
        val outcome = SharedBulkMutationCoordinator.run(
            ids = listOf("a", "b", "c")
        ) { id ->
            id != "b"
        }

        assertEquals(listOf("a", "b", "c"), outcome.attemptedIds)
        assertEquals(listOf("a", "c"), outcome.succeededIds)
        assertEquals(listOf("b"), outcome.failedIds)
        assertEquals(3, outcome.attemptedCount)
        assertEquals(2, outcome.succeededCount)
        assertEquals(1, outcome.failedCount)
        assertTrue(outcome.hasAnySuccess)
        assertFalse(outcome.failedCount == 0)
    }
}
