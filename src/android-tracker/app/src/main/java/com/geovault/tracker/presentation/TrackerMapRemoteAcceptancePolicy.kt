package com.geovault.tracker.presentation

/**
 * Unified set of tracker ids for multi-tracker map modes: subscribed targets requested by map/params logic
 * plus ids the foreground streaming service considers active once connected.
 */
object TrackerMapRemoteAcceptancePolicy {
    fun mergedAcceptedRemoteTrackerIds(
        streamTargetIds: Set<String>,
        activeStreamedTrackerIds: Set<String>,
    ): Set<String> {
        val out = LinkedHashSet<String>()
        for (id in streamTargetIds) {
            val t = id.trim()
            if (t.isNotEmpty()) out.add(t)
        }
        for (id in activeStreamedTrackerIds) {
            val t = id.trim()
            if (t.isNotEmpty()) out.add(t)
        }
        return out
    }
}
