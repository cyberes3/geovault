package com.geovault.common.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoVaultQueuedSyncMessageFormatterTest {
    @Test
    fun formatsAllSuccessMessage() {
        val message = GeoVaultQueuedSyncMessageFormatter.format(
            outcome = GeoVaultQueuedSyncOutcome(successCount = 2, failedCount = 0),
            itemLabelSingular = "offline item",
            itemLabelPlural = "offline items",
        )
        assertEquals("Synced 2 offline items.", message)
    }

    @Test
    fun formatsPartialSuccessMessage() {
        val message = GeoVaultQueuedSyncMessageFormatter.format(
            outcome = GeoVaultQueuedSyncOutcome(successCount = 1, failedCount = 2),
            itemLabelSingular = "offline item",
            itemLabelPlural = "offline items",
        )
        assertEquals("Synced 1 offline item. 2 still waiting to sync.", message)
    }

    @Test
    fun formatsConflictMessage() {
        val message = GeoVaultQueuedSyncMessageFormatter.format(
            outcome = GeoVaultQueuedSyncOutcome(successCount = 2, failedCount = 0, conflictCount = 1),
            itemLabelSingular = "offline item",
            itemLabelPlural = "offline items",
        )
        assertEquals("Synced 2 offline items. 1 conflict copy was saved as a new item.", message)
    }
}
