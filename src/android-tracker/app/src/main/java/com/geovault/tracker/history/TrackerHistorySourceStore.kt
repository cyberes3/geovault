package com.geovault.tracker.history

class TrackerHistorySourceStore {
    private val trunks = mutableMapOf<TrackerHistoryKey, TrackerHistorySourceBatch>()
    private val overlays = mutableMapOf<TrackerHistoryKey, MutableMap<TrackerHistorySourceKind, TrackerHistorySourceBatch>>()
    private val clearBoundaries = mutableMapOf<String, TrackerHistoryClearBoundary>()

    @Synchronized
    fun putTrunk(batch: TrackerHistorySourceBatch) {
        trunks[TrackerHistoryKey(batch.normalizedTrackerId, batch.window)] = batch
        pruneAbsorbedOverlay(batch)
    }

    @Synchronized
    fun putOverlay(batch: TrackerHistorySourceBatch) {
        val key = TrackerHistoryKey(batch.normalizedTrackerId, batch.window)
        val byKind = overlays.getOrPut(key) { mutableMapOf() }
        val existing = byKind[batch.sourceKind]
        val points = (existing?.points.orEmpty() + batch.points)
            .distinctBy { it.key }
            .sortedBy { it.timestampMs }
        byKind[batch.sourceKind] = batch.copy(points = points)
    }

    @Synchronized
    fun trunk(key: TrackerHistoryKey): TrackerHistorySourceBatch? = trunks[key]

    @Synchronized
    fun overlays(key: TrackerHistoryKey): List<TrackerHistorySourceBatch> {
        return overlays[key]?.values?.toList().orEmpty()
    }

    @Synchronized
    fun setClearBoundary(boundary: TrackerHistoryClearBoundary) {
        val trackerId = boundary.trackerId.trim()
        clearBoundaries[trackerId] = boundary
        trunks.keys.filter { it.normalizedTrackerId == trackerId }.forEach(trunks::remove)
        overlays.keys.filter { it.normalizedTrackerId == trackerId }.forEach(overlays::remove)
    }

    @Synchronized
    fun clearBoundary(trackerId: String): TrackerHistoryClearBoundary? {
        return clearBoundaries[trackerId.trim()]
    }

    @Synchronized
    fun releaseClearBoundary(trackerId: String) {
        clearBoundaries.remove(trackerId.trim())
    }

    @Synchronized
    fun clearAll() {
        trunks.clear()
        overlays.clear()
        clearBoundaries.clear()
    }

    private fun pruneAbsorbedOverlay(batch: TrackerHistorySourceBatch) {
        val key = TrackerHistoryKey(batch.normalizedTrackerId, batch.window)
        val trunkKeys = batch.points.map { it.key }.toSet()
        val byKind = overlays[key] ?: return
        byKind.replaceAll { _, overlay ->
            overlay.copy(points = overlay.points.filter { it.key !in trunkKeys })
        }
    }
}
