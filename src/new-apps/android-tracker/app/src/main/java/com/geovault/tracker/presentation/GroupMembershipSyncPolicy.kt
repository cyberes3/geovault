package com.geovault.tracker.presentation

data class GroupMembershipSyncPlan(
    val removeIds: Set<String>,
    val addIds: Set<String>
) {
    val isNoOp: Boolean
        get() = removeIds.isEmpty() && addIds.isEmpty()
}

object GroupMembershipSyncPolicy {
    fun plan(
        currentTrackerIds: Collection<String>,
        targetTrackerIds: Collection<String>
    ): GroupMembershipSyncPlan {
        val current = currentTrackerIds
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        val target = targetTrackerIds
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        return GroupMembershipSyncPlan(
            removeIds = current - target,
            addIds = target - current
        )
    }
}
