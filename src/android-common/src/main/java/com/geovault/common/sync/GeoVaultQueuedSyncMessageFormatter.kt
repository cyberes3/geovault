package com.geovault.common.sync

data class GeoVaultQueuedSyncOutcome(
    val successCount: Int,
    val failedCount: Int,
    val conflictCount: Int = 0,
)

object GeoVaultQueuedSyncMessageFormatter {
    fun format(
        outcome: GeoVaultQueuedSyncOutcome,
        itemLabelSingular: String = "item",
        itemLabelPlural: String = "items",
    ): String {
        val total = outcome.successCount + outcome.failedCount
        if (total <= 0) return ""
        val noun = if (total == 1) itemLabelSingular else itemLabelPlural
        if (outcome.failedCount == 0) {
            if (outcome.conflictCount > 0) {
                val conflictClause = if (outcome.conflictCount == 1) {
                    "1 conflict copy was saved as a new item."
                } else {
                    "${outcome.conflictCount} conflict copies were saved as new items."
                }
                return "Synced $total $noun. $conflictClause"
            }
            return "Synced $total $noun."
        }
        if (outcome.successCount > 0) {
            return "Synced ${outcome.successCount} ${if (outcome.successCount == 1) itemLabelSingular else itemLabelPlural}. ${outcome.failedCount} still waiting to sync."
        }
        return "Unable to sync offline $noun. Pull to retry."
    }
}
