package com.geovault.places.presentation

import com.geovault.places.model.PendingChange
import com.geovault.places.model.PlaceKey
import com.geovault.places.samplePlace
import org.junit.Assert.assertEquals
import org.junit.Test

class PlacesOfflineBehaviorPolicyTest {
    @Test
    fun destructiveActionMatchesPendingKind() {
        val saved = samplePlace()
        val pendingUpdate = samplePlace(pending = PendingChange.Update(samplePlace().content))
        val pendingCreate = samplePlace(
            key = PlaceKey.local("n"),
            serverId = null,
            pending = PendingChange.Create,
        )

        assertEquals(PlacesOfflineDestructiveAction.Delete, PlacesOfflineBehaviorPolicy.destructiveActionFor(saved))
        assertEquals(PlacesOfflineDestructiveAction.Revert, PlacesOfflineBehaviorPolicy.destructiveActionFor(pendingUpdate))
        assertEquals(PlacesOfflineDestructiveAction.Discard, PlacesOfflineBehaviorPolicy.destructiveActionFor(pendingCreate))
    }

    @Test
    fun offlineRemovalMessageMatchesRevertAndDiscardCopy() {
        assertEquals(
            PlacesOfflineBehaviorPolicy.REVERTED_CHANGES_MESSAGE,
            PlacesOfflineBehaviorPolicy.offlineRemovalMessage(
                samplePlace(pending = PendingChange.Update(samplePlace().content)),
            ),
        )
        assertEquals(
            PlacesOfflineBehaviorPolicy.DISCARDED_OFFLINE_PLACE_MESSAGE,
            PlacesOfflineBehaviorPolicy.offlineRemovalMessage(
                samplePlace(key = PlaceKey.local("n"), serverId = null, pending = PendingChange.Create),
            ),
        )
    }
}
