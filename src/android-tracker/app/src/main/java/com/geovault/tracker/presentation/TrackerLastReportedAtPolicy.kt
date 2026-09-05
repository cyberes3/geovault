package com.geovault.tracker.presentation

import com.geovault.tracker.services.TrackingRuntimeSnapshot

/**
 * Single source of truth for which timestamp drives "last reported at" UI for a tracker.
 *
 * For the device's own actively-recording tracker we authoritatively know when we last
 * successfully uploaded ([TrackingRuntimeSnapshot.lastPointSentAtMs]); using that value
 * keeps the Home screen's "Last" stat and the map info box's "Updated ... ago" text in
 * lockstep, regardless of any unsent runtime fixes the resolver might prefer.
 *
 * For every other tracker (remote, or local-but-not-currently-recording) we fall back to
 * [resolverLastUpdatedMs], which is normally
 * `TrackerMapLastPointResolver.resolve(...).lastUpdatedMs` -- the
 * freshest known data point timestamp.
 *
 * Returning `null` signals "no reported timestamp known yet" and renderers should treat
 * it as "Waiting for data" rather than fabricating a value.
 */
object TrackerLastReportedAtPolicy {
    fun resolve(
        trackerId: String,
        runtime: TrackingRuntimeSnapshot,
        resolverLastUpdatedMs: Long?,
    ): Long? {
        val normalized = trackerId.trim()
        if (normalized.isEmpty()) return resolverLastUpdatedMs
        if (normalized == runtime.locallyRecordedTrackerId) {
            val sent = runtime.lastPointSentAtMs
            return if (sent > 0L) sent else null
        }
        return resolverLastUpdatedMs
    }
}
