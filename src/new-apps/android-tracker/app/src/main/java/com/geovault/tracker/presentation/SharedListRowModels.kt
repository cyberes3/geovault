package com.geovault.tracker.presentation

import com.geovault.tracker.Group
import com.geovault.tracker.Tracker

sealed interface SharedListRowModel {
    val key: String

    data class TrackerRow(
        val tracker: Tracker,
        val ownerEmail: String?,
        val lastUpdateMs: Long?,
        val latitude: Double?,
        val longitude: Double?,
        val isSelected: Boolean,
        val canEdit: Boolean,
        val canOpenMap: Boolean,
    ) : SharedListRowModel {
        override val key: String = "s-t-${tracker.id}"
    }

    data class GroupRow(
        val group: Group,
        val ownerEmail: String?,
        val trackerCount: Int,
        val canEdit: Boolean,
    ) : SharedListRowModel {
        override val key: String = "s-g-${group.id}"
    }
}

fun List<SharedSurfaceItem>.toSharedListRows(selectedTrackerId: String): List<SharedListRowModel> {
    return map { item ->
        when (item) {
            is SharedSurfaceItem.TrackerItem -> item.tracker.toSharedTrackerRow(selectedTrackerId)
            is SharedSurfaceItem.GroupItem -> item.group.toSharedGroupRow()
        }
    }
}

private fun Tracker.toSharedTrackerRow(selectedTrackerId: String): SharedListRowModel.TrackerRow {
    val lastPoint = last_point
    val lat = if (lastPoint != null && lastPoint.size >= 2) lastPoint[1] else null
    val lon = if (lastPoint != null && lastPoint.size >= 2) lastPoint[0] else null
    val lastUpdate = if (lastPoint != null && lastPoint.size >= 3) {
        val raw = lastPoint[2].toLong()
        if (raw < 1_000_000_000_000L) raw * 1000L else raw
    } else {
        updated_at
    }
    return SharedListRowModel.TrackerRow(
        tracker = this,
        ownerEmail = owner_email?.takeIf { it.isNotBlank() },
        lastUpdateMs = lastUpdate,
        latitude = lat,
        longitude = lon,
        isSelected = id == selectedTrackerId,
        canEdit = SharedListActionPolicy.canEditTracker(this),
        canOpenMap = SharedListActionPolicy.canOpenTrackerMap(this),
    )
}

private fun Group.toSharedGroupRow(): SharedListRowModel.GroupRow {
    val count = track_ids.orEmpty()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .size
    return SharedListRowModel.GroupRow(
        group = this,
        ownerEmail = owner_email?.takeIf { it.isNotBlank() },
        trackerCount = count,
        canEdit = SharedListActionPolicy.canEditGroup(this),
    )
}
