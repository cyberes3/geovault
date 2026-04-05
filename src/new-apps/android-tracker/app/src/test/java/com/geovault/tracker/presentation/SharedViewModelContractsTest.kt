package com.geovault.tracker.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

class SharedViewModelContractsTest {

    @Test
    fun resolveBulkFeedbackKind_success() {
        val outcome = SharedBulkMutationOutcome(
            attemptedIds = listOf("a", "b"),
            succeededIds = listOf("a", "b"),
            failedIds = emptyList(),
        )

        val kind = SharedViewModelContracts.resolveBulkFeedbackKind(outcome)

        assertEquals(SharedBulkFeedbackKind.SUCCESS, kind)
    }

    @Test
    fun resolveBulkFeedbackKind_partialFailure() {
        val outcome = SharedBulkMutationOutcome(
            attemptedIds = listOf("a", "b"),
            succeededIds = listOf("a"),
            failedIds = listOf("b"),
        )

        val kind = SharedViewModelContracts.resolveBulkFeedbackKind(outcome)

        assertEquals(SharedBulkFeedbackKind.PARTIAL_FAILURE, kind)
    }

    @Test
    fun resolveBulkFeedbackKind_fullFailure() {
        val outcome = SharedBulkMutationOutcome(
            attemptedIds = listOf("a", "b"),
            succeededIds = emptyList(),
            failedIds = listOf("a", "b"),
        )

        val kind = SharedViewModelContracts.resolveBulkFeedbackKind(outcome)

        assertEquals(SharedBulkFeedbackKind.FULL_FAILURE, kind)
    }
}
