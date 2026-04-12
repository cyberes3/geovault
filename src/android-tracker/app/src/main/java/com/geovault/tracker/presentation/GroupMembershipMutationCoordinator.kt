package com.geovault.tracker.presentation

data class GroupMembershipMutationOutcome(
    val attemptedRemovals: List<String>,
    val attemptedAdditions: List<String>,
    val successfulRemovals: List<String>,
    val successfulAdditions: List<String>,
    val failedRemovals: List<String>,
    val failedAdditions: List<String>,
) {
    val attemptedCount: Int
        get() = attemptedRemovals.size + attemptedAdditions.size
    val succeededCount: Int
        get() = successfulRemovals.size + successfulAdditions.size
    val failedCount: Int
        get() = failedRemovals.size + failedAdditions.size
    val hasAnySuccess: Boolean
        get() = succeededCount > 0
}

object GroupMembershipMutationCoordinator {
    suspend fun run(
        plan: GroupMembershipSyncPlan,
        removeTrackerFromGroup: suspend (String) -> Boolean,
        addTrackerToGroup: suspend (String) -> Boolean,
    ): GroupMembershipMutationOutcome {
        val removalOrder = plan.removeIds.toList().sorted()
        val additionOrder = plan.addIds.toList().sorted()
        val successfulRemovals = mutableListOf<String>()
        val failedRemovals = mutableListOf<String>()
        val successfulAdditions = mutableListOf<String>()
        val failedAdditions = mutableListOf<String>()
        for (trackId in removalOrder) {
            if (removeTrackerFromGroup(trackId)) {
                successfulRemovals.add(trackId)
            } else {
                failedRemovals.add(trackId)
            }
        }
        for (trackId in additionOrder) {
            if (addTrackerToGroup(trackId)) {
                successfulAdditions.add(trackId)
            } else {
                failedAdditions.add(trackId)
            }
        }
        return GroupMembershipMutationOutcome(
            attemptedRemovals = removalOrder,
            attemptedAdditions = additionOrder,
            successfulRemovals = successfulRemovals,
            successfulAdditions = successfulAdditions,
            failedRemovals = failedRemovals,
            failedAdditions = failedAdditions,
        )
    }
}
