package com.geovault.tracker.presentation

enum class SharedBulkFeedbackKind {
    SUCCESS,
    PARTIAL_FAILURE,
    FULL_FAILURE,
}

object SharedViewModelContracts {
    fun resolveBulkFeedbackKind(outcome: SharedBulkMutationOutcome): SharedBulkFeedbackKind {
        return when {
            outcome.failedCount == 0 -> SharedBulkFeedbackKind.SUCCESS
            outcome.hasAnySuccess -> SharedBulkFeedbackKind.PARTIAL_FAILURE
            else -> SharedBulkFeedbackKind.FULL_FAILURE
        }
    }
}
