package com.geovault.tracker.data

import com.geovault.common.concurrent.GeoVaultStateStore
import com.geovault.tracker.AvailableToAddGroup
import com.geovault.tracker.AvailableToAddItem
import com.geovault.tracker.Tracker
import kotlinx.coroutines.flow.StateFlow

enum class CatalogEntityType {
    Tracker,
    Group,
    Share,
    Membership,
}

data class PendingTransaction(
    val entityType: CatalogEntityType,
    val entityId: String,
    val op: String,
    val phase: String,
    val occupancyKey: String,
    val addedTrackerId: String? = null,
    val addedTracker: Tracker? = null,
    val removalTrackerId: String? = null,
    val incomingTracker: AvailableToAddItem? = null,
    val incomingGroup: AvailableToAddGroup? = null,
    val publicTracker: AvailableToAddItem? = null,
    val publicGroup: AvailableToAddGroup? = null,
)

class MutationQueue {
    private val store = GeoVaultStateStore<List<PendingTransaction>>(emptyList())
    val state: StateFlow<List<PendingTransaction>> = store.state

    fun enqueue(transaction: PendingTransaction): Boolean {
        val current = store.value
        if (current.any { it.entityType == transaction.entityType && it.entityId == transaction.entityId }) {
            return false
        }
        store.update { it + transaction }
        return true
    }

    fun complete(entityType: CatalogEntityType, entityId: String) {
        store.update { rows ->
            rows.filterNot { it.entityType == entityType && it.entityId == entityId }
        }
    }

    fun membershipIds(): Set<String> {
        return store.value
            .filter { it.entityType == CatalogEntityType.Membership }
            .map { it.entityId }
            .toSet()
    }
}
