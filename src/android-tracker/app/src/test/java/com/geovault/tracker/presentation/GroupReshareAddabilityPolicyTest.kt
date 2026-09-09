package com.geovault.tracker.presentation

import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackerCatalogSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupReshareAddabilityPolicyTest {

    @Test
    fun ownedTracker_isAddable_evenWithoutReshareSetting() {
        val tracker = tracker(isOwner = true, settings = null)
        assertTrue(GroupReshareAddabilityPolicy.isAddableToGroup(tracker))
    }

    @Test
    fun ownedTracker_isAddable_whenReshareExplicitlyFalse() {
        val tracker = tracker(
            isOwner = true,
            settings = TrackerCatalogSettings(allowGroupReshare = false),
        )
        assertTrue(GroupReshareAddabilityPolicy.isAddableToGroup(tracker))
    }

    @Test
    fun sharedTracker_isAddable_whenReshareTrue() {
        val tracker = tracker(
            isOwner = false,
            settings = TrackerCatalogSettings(allowGroupReshare = true),
        )
        assertTrue(GroupReshareAddabilityPolicy.isAddableToGroup(tracker))
    }

    @Test
    fun sharedTracker_isNotAddable_whenReshareFalse() {
        val tracker = tracker(
            isOwner = false,
            settings = TrackerCatalogSettings(allowGroupReshare = false),
        )
        assertFalse(GroupReshareAddabilityPolicy.isAddableToGroup(tracker))
    }

    @Test
    fun sharedTracker_isNotAddable_whenReshareKeyMissing() {
        val tracker = tracker(
            isOwner = false,
            settings = TrackerCatalogSettings(hidden = false),
        )
        assertFalse(GroupReshareAddabilityPolicy.isAddableToGroup(tracker))
    }

    @Test
    fun sharedTracker_isNotAddable_whenSettingsNull() {
        val tracker = tracker(isOwner = false, settings = null)
        assertFalse(GroupReshareAddabilityPolicy.isAddableToGroup(tracker))
    }

    @Test
    fun sharedTracker_isNotAddable_whenReshareValueIsNotBoolean() {
        val tracker = tracker(
            isOwner = false,
            settings = TrackerCatalogSettings(allowGroupReshare = null),
        )
        assertFalse(GroupReshareAddabilityPolicy.isAddableToGroup(tracker))
    }

    private fun tracker(
        isOwner: Boolean,
        settings: TrackerCatalogSettings?,
    ): Tracker = Tracker(
        id = "t1",
        name = "Tracker",
        color = null,
        settings = settings,
        is_owner = isOwner,
    )
}
