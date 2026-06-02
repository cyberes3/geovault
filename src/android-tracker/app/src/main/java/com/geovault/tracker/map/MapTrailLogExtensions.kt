package com.geovault.tracker.map

import com.geovault.tracker.db.QueuedLocation
import org.maplibre.android.geometry.LatLngBounds

internal fun List<QueuedLocation>.trailSummary(): String {
    val first = firstOrNull()
    val last = lastOrNull()
    return "count=$size first=${first?.time}:${first?.latitude},${first?.longitude}/${first?.prov}/${first?.startTimestampMs} " +
        "last=${last?.time}:${last?.latitude},${last?.longitude}/${last?.prov}/${last?.startTimestampMs}"
}

internal fun Map<String, List<QueuedLocation>>.mapSizes(): String {
    if (isEmpty()) return "{}"
    return entries
        .sortedBy { it.key }
        .joinToString(prefix = "{", postfix = "}") { (trackerId, points) ->
            "$trackerId:${points.size}:${points.lastOrNull()?.time}:${points.lastOrNull()?.prov}"
        }
}

internal fun LatLngBounds?.boundsSummary(): String {
    this ?: return "null"
    return "sw=${southWest.latitude},${southWest.longitude} ne=${northEast.latitude},${northEast.longitude}"
}
