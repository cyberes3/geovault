package com.geovault.tracker.map

import com.geovault.tracker.presentation.TrackerMapStreamingPlan
import com.geovault.tracker.presentation.TrackerMapUiState

/**
 * CACHE-STREAMING-PLAN: [TrackerMapRuntime.projectSession] (via `resolveGroupModeSelection` /
 * `visibleMapRosterTrackerIds`) scans the full tracker/group/visibility store on every call, so
 * its cost scales with roster size. [TrackPointReducer.reduce] and `reconcileSeedKey` both run once
 * per accepted point -- without caching, a busy group re-pays that full roster scan on every
 * single incoming point from every member, not just once per meaningful state change.
 *
 * The cache is keyed by a signature over exactly the fields `projectSession` and its helpers
 * read (mode, displayed/selected/locally-recorded ids, current group, and the roster/group/
 * visibility fingerprint already maintained on [TrackerMapUiState.renderMetadataSignature]).
 * None of those change when a point only appends to a trail or updates a remote-last-point
 * entry, so such points reuse the cached plan. Building the signature itself is O(1) -- it never
 * re-scans the roster -- so callers get a real memoization rather than shifting the same cost
 * into the cache-key check. Because the check is a value comparison rather than reliance on every
 * mutation site remembering to call an explicit `invalidate()`, the cache can never serve a plan
 * that is stale relative to the state it is resolved against; the worst failure mode is a
 * redundant recompute, never an incorrect one.
 */
internal class TrackerMapStreamingPlanCache {
    private var cachedSignature: String? = null
    private var cachedPlan: TrackerMapStreamingPlan? = null

    /**
     * [compute] is only invoked on a genuine cache miss. Taking it as a parameter (rather than
     * this class depending on [TrackerMapRuntime] directly) keeps the memoization logic testable
     * in isolation from the runtime's Android/DI dependencies.
     */
    internal fun resolve(state: TrackerMapUiState, compute: (TrackerMapUiState) -> TrackerMapStreamingPlan): TrackerMapStreamingPlan {
        val signature = signatureFor(state)
        val plan = cachedPlan
        if (plan != null && signature == cachedSignature) return plan
        val fresh = compute(state)
        cachedSignature = signature
        cachedPlan = fresh
        return fresh
    }

    /** Lets a caller that already computed a plan from fresher inputs (e.g. [StreamRosterResolver.refreshStreamTargets]) warm the cache for free instead of leaving it to miss on the very next call. */
    internal fun warm(state: TrackerMapUiState, plan: TrackerMapStreamingPlan) {
        cachedSignature = signatureFor(state)
        cachedPlan = plan
    }

    private fun signatureFor(state: TrackerMapUiState): String {
        val runtime = state.runtime
        return buildString {
            append(state.mode)
            append('|').append(state.displayedTrackerId.trim())
            append('|').append(state.displayedTrackerName.trim())
            append('|').append(state.currentGroupId.trim())
            append('|').append(runtime.selectedTrackerId.trim())
            append('|').append(runtime.localRecordingActive)
            append('|').append(runtime.locallyRecordedTrackerId.trim())
            append('|').append(state.renderMetadataSignature)
        }
    }
}
