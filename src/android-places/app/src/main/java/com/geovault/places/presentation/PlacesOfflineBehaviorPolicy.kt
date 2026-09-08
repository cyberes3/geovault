package com.geovault.places.presentation

import com.geovault.places.model.PendingChange
import com.geovault.places.model.Place

enum class PlacesOfflineDestructiveAction {
    Delete,
    Revert,
    Discard,
}

object PlacesOfflineBehaviorPolicy {
    const val SAVED_OFFLINE_MESSAGE: String = "Saved offline. Pull to sync."
    const val SAVED_OFFLINE_NETWORK_MESSAGE: String =
        "Couldn't reach the server. Saved locally — pull to sync."
    const val AUTH_REQUIRED_MESSAGE: String = "Sign in again to save this place."
    const val VALIDATION_FAILED_MESSAGE: String = "Server rejected this place. Fix the fields and try again."
    const val REFRESH_CANCELLED_USING_CACHE_MESSAGE: String = "Cancelled - using cached data"
    const val DELETE_QUEUED_OFFLINE_MESSAGE: String =
        "Couldn't reach the server. Delete queued — pull to sync."
    const val DELETE_SERVER_ERROR_MESSAGE: String = "Failed to delete: Server error"
    const val MAP_APP_UNAVAILABLE_MESSAGE: String = "No map app available"
    const val REVERTED_CHANGES_MESSAGE: String = "Changes reverted - showing original"
    const val DISCARDED_OFFLINE_PLACE_MESSAGE: String = "Offline place discarded"

    fun destructiveActionFor(place: Place): PlacesOfflineDestructiveAction {
        return when (place.pending) {
            is PendingChange.Update -> PlacesOfflineDestructiveAction.Revert
            PendingChange.Create -> PlacesOfflineDestructiveAction.Discard
            else -> PlacesOfflineDestructiveAction.Delete
        }
    }

    fun destructiveActionLabel(action: PlacesOfflineDestructiveAction): String {
        return when (action) {
            PlacesOfflineDestructiveAction.Delete -> "Delete"
            PlacesOfflineDestructiveAction.Revert -> "Revert"
            PlacesOfflineDestructiveAction.Discard -> "Discard"
        }
    }

    fun offlineRemovalMessage(place: Place): String {
        return if (place.pending is PendingChange.Update) {
            REVERTED_CHANGES_MESSAGE
        } else {
            DISCARDED_OFFLINE_PLACE_MESSAGE
        }
    }
}
