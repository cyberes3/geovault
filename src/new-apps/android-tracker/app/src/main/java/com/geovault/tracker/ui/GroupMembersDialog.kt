package com.geovault.tracker.ui

import com.geovault.tracker.Group

data class GroupMembersOverlayState(
    val group: Group,
    val highlightedTrackerId: String?,
)
