package com.geovault.tracker

data class TrackerMeta(
    val id: String,
    val name: String,
    val isOwner: Boolean,
    val lastUpdateMs: Long?,
    val lastPosition: Pair<Double, Double>?
)

fun Tracker.toTrackerMeta(): TrackerMeta {
    return TrackerMeta(
        id = id,
        name = name,
        isOwner = isOwner(),
        lastUpdateMs = lastUpdateMs(),
        lastPosition = lastPosition()
    )
}

fun Tracker.lastUpdateMs(): Long? {
    val coord = last_point ?: return null
    if (coord.size < 3) return null
    val value = coord[2].toLong()
    return if (value < 1_000_000_000_000L) value * 1000L else value
}

fun Tracker.lastPosition(): Pair<Double, Double>? {
    val coord = last_point ?: return null
    if (coord.size < 2) return null
    return Pair(coord[1], coord[0])
}

