package com.geovault.tracker.presentation

import com.geovault.tracker.Group
import com.geovault.tracker.MapVisibilityResponse
import com.geovault.tracker.Tracker

/**
 * Two-axis fingerprint over store data that drives map metadata refreshes.
 *
 * The map ViewModel cares about two distinct classes of change:
 *  - [cosmetic]: tracker name / color edits. The map already reads names and colors from the
 *    live store at render time, so cosmetic changes only require a render package republish,
 *    not a server geometry refetch.
 *  - [structural]: tracker roster membership, per-tracker `hidden` flag, group membership,
 *    and map-visibility hidden ids. These affect *which* trackers' geometry should be on
 *    screen and therefore require a server reload that respects the new roster.
 *
 * Splitting the signature avoids triggering an HTTP roster fetch on every rename, while
 * still ensuring every hide/unhide, group edit, or list-shrink prompts a fresh geometry
 * pull. Excluded fields (e.g. `recent_data_window`, geometry coordinates, updated_at) are
 * intentionally handled by other paths: window changes flow through
 * [TrackerMapFilterChangeReactor]; live data flows through [TrackerMapUiState] updates.
 */
data class TrackerMapRenderMetadataFingerprint(
    val cosmetic: String,
    val structural: String,
) {
    /** Single composite for [TrackerMapUiState.renderMetadataSignature]. */
    val combined: String get() = "$cosmetic#$structural"

    companion object {
        fun from(
            trackers: List<Tracker>,
            groups: List<Group>,
            mapVisibility: MapVisibilityResponse?,
        ): TrackerMapRenderMetadataFingerprint {
            val cosmetic = trackers
                .asSequence()
                .sortedBy { it.id }
                .joinToString(separator = "|") { "${it.id}:${it.name}:${it.color ?: ""}" }
            val trackerStructural = trackers
                .asSequence()
                .sortedBy { it.id }
                .joinToString(separator = "|") { "${it.id}:${readHidden(it)}" }
            val groupStructural = groups
                .asSequence()
                .sortedBy { it.id }
                .joinToString(separator = "|") { group ->
                    val memberIds = group.track_ids.orEmpty().sorted().joinToString(",")
                    "${group.id}:$memberIds"
                }
            val visibilityStructural = mapVisibility?.let { vis ->
                val hiddenGroups = vis.hidden_group_ids.sorted().joinToString(",")
                val hiddenTrackers = vis.hidden_track_ids.sorted().joinToString(",")
                "$hiddenGroups|$hiddenTrackers"
            } ?: "none"
            val structural = "$trackerStructural#$groupStructural#$visibilityStructural"
            return TrackerMapRenderMetadataFingerprint(cosmetic = cosmetic, structural = structural)
        }

        private fun readHidden(tracker: Tracker): String {
            val raw = tracker.settings?.get("hidden") ?: return "0"
            val normalized = when (raw) {
                is Boolean -> raw
                is Number -> raw.toInt() != 0
                is String -> raw.equals("true", ignoreCase = true) || raw == "1"
                else -> false
            }
            return if (normalized) "1" else "0"
        }
    }
}
