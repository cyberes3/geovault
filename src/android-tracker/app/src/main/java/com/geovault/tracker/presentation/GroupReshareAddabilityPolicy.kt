package com.geovault.tracker.presentation

import com.geovault.tracker.Tracker

/**
 * Decides whether a tracker can be added to a group from the picker UI.
 *
 * Mirrors the backend rule in `extensions/live_track/src/backend/group_views.py`:
 * - Owner trackers are always addable.
 * - Non-owner trackers are addable only when their owner has set
 *   `settings.allow_group_reshare = true`.
 *
 * The "already a member" case is handled by the picker's existing list filtering
 * and intentionally left out of this policy so it stays a single, focused rule.
 */
object GroupReshareAddabilityPolicy {
    private const val ALLOW_GROUP_RESHARE_KEY = "allow_group_reshare"

    fun isAddableToGroup(tracker: Tracker): Boolean {
        if (tracker.isOwner()) return true
        return (tracker.settings?.get(ALLOW_GROUP_RESHARE_KEY) as? Boolean) == true
    }
}
