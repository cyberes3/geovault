package com.geovault.tracker.presentation

data class SharedBulkMutationOutcome(
    val attemptedIds: List<String>,
    val succeededIds: List<String>,
    val failedIds: List<String>,
) {
    val attemptedCount: Int
        get() = attemptedIds.size
    val succeededCount: Int
        get() = succeededIds.size
    val failedCount: Int
        get() = failedIds.size
    val hasAnySuccess: Boolean
        get() = succeededIds.isNotEmpty()
}

object SharedBulkMutationCoordinator {
    fun normalizeIds(ids: List<String>): List<String> {
        return ids
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    suspend fun run(
        ids: List<String>,
        mutate: suspend (String) -> Boolean
    ): SharedBulkMutationOutcome {
        val normalized = normalizeIds(ids)
        val succeeded = mutableListOf<String>()
        val failed = mutableListOf<String>()
        normalized.forEach { id ->
            if (mutate(id)) {
                succeeded.add(id)
            } else {
                failed.add(id)
            }
        }
        return SharedBulkMutationOutcome(
            attemptedIds = normalized,
            succeededIds = succeeded,
            failedIds = failed
        )
    }
}
