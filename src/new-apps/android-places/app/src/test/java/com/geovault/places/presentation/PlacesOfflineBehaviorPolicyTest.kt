package com.geovault.places.presentation

import com.geovault.places.model.Feature
import com.geovault.places.model.Geometry
import com.geovault.places.model.OfflineFeature
import com.geovault.places.model.Properties
import org.junit.Assert.assertEquals
import org.junit.Test

class PlacesOfflineBehaviorPolicyTest {
    @Test
    fun destructiveActionForRow_matchesOfflineAndSavedRules() {
        val saved = PlacesOfflineBehaviorPolicy.destructiveActionForRow(
            isOffline = false,
            offlineFeature = null
        )
        val offlineExisting = PlacesOfflineBehaviorPolicy.destructiveActionForRow(
            isOffline = true,
            offlineFeature = offlineFeature(databaseId = 7)
        )
        val offlineDraft = PlacesOfflineBehaviorPolicy.destructiveActionForRow(
            isOffline = true,
            offlineFeature = offlineFeature(databaseId = null)
        )

        assertEquals(PlacesOfflineDestructiveAction.Delete, saved)
        assertEquals(PlacesOfflineDestructiveAction.Revert, offlineExisting)
        assertEquals(PlacesOfflineDestructiveAction.Discard, offlineDraft)
    }

    @Test
    fun deleteFailureMessage_matchesLegacyOfflineAndServerErrors() {
        assertEquals(
            PlacesOfflineBehaviorPolicy.DELETE_SERVER_ERROR_MESSAGE,
            PlacesOfflineBehaviorPolicy.deleteFailureMessage("Failed to delete place: 500")
        )
        assertEquals(
            PlacesOfflineBehaviorPolicy.DELETE_WHILE_OFFLINE_MESSAGE,
            PlacesOfflineBehaviorPolicy.deleteFailureMessage("Unable to resolve host")
        )
        assertEquals(
            PlacesOfflineBehaviorPolicy.DELETE_WHILE_OFFLINE_MESSAGE,
            PlacesOfflineBehaviorPolicy.deleteFailureMessage(null)
        )
    }

    @Test
    fun offlineRemovalMessage_matchesLegacyRevertAndDiscardCopy() {
        assertEquals(
            PlacesOfflineBehaviorPolicy.REVERTED_CHANGES_MESSAGE,
            PlacesOfflineBehaviorPolicy.offlineRemovalMessage(offlineFeature(databaseId = 99))
        )
        assertEquals(
            PlacesOfflineBehaviorPolicy.DISCARDED_OFFLINE_PLACE_MESSAGE,
            PlacesOfflineBehaviorPolicy.offlineRemovalMessage(offlineFeature(databaseId = null))
        )
    }

    private fun offlineFeature(databaseId: Int?): OfflineFeature {
        return OfflineFeature(
            feature = Feature(
                geometry = Geometry(coordinates = listOf(1.0, 2.0)),
                properties = Properties(database_id = databaseId, name = "Sample")
            ),
            original = null
        )
    }
}
