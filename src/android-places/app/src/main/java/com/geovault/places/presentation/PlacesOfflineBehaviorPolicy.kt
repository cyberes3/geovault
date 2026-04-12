package com.geovault.places.presentation

import com.geovault.places.model.OfflineFeature

enum class PlacesOfflineDestructiveAction {
    Delete,
    Revert,
    Discard,
}

object PlacesOfflineBehaviorPolicy {
    const val SAVED_OFFLINE_MESSAGE: String = "Saved offline. Pull to sync."
    const val REFRESH_CANCELLED_USING_CACHE_MESSAGE: String = "Cancelled - using cached data"
    const val REFRESH_CANCELLED_MESSAGE: String = "Syncing cancelled"
    const val DELETE_WHILE_OFFLINE_MESSAGE: String =
        "Cannot delete while offline. Please try again when connected."
    const val DELETE_SERVER_ERROR_MESSAGE: String = "Failed to delete: Server error"
    const val MAP_APP_UNAVAILABLE_MESSAGE: String = "No map app available"
    const val REVERTED_CHANGES_MESSAGE: String = "Changes reverted - showing original"
    const val DISCARDED_OFFLINE_PLACE_MESSAGE: String = "Offline place discarded"

    fun destructiveActionForRow(isOffline: Boolean, offlineFeature: OfflineFeature?): PlacesOfflineDestructiveAction {
        if (!isOffline || offlineFeature == null) return PlacesOfflineDestructiveAction.Delete
        return if (offlineFeature.feature.properties.database_id != null) {
            PlacesOfflineDestructiveAction.Revert
        } else {
            PlacesOfflineDestructiveAction.Discard
        }
    }

    fun destructiveActionLabel(action: PlacesOfflineDestructiveAction): String {
        return when (action) {
            PlacesOfflineDestructiveAction.Delete -> "Delete"
            PlacesOfflineDestructiveAction.Revert -> "Revert"
            PlacesOfflineDestructiveAction.Discard -> "Discard"
        }
    }

    fun offlineRemovalMessage(item: OfflineFeature): String {
        return if (item.feature.properties.database_id != null) {
            REVERTED_CHANGES_MESSAGE
        } else {
            DISCARDED_OFFLINE_PLACE_MESSAGE
        }
    }

    fun deleteFailureMessage(rawErrorMessage: String?): String {
        return if (rawErrorMessage?.startsWith("Failed to delete place:") == true) {
            DELETE_SERVER_ERROR_MESSAGE
        } else {
            DELETE_WHILE_OFFLINE_MESSAGE
        }
    }
}
