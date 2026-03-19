package com.geovault.tracker.fragments.map

import com.geovault.tracker.Tracker

data class AllTrackersMapState(
    val trackers: List<Tracker>,
    val normalizedCoordsById: Map<String, List<List<Double>>>
)

