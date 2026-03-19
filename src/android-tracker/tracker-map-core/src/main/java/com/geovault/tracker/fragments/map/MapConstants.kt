package com.geovault.tracker.fragments.map

object MapConstants {
    /** Duration (ms) for animating the camera when follow lock is on and the track moves. */
    const val FOLLOW_LOCK_ANIMATION_MS = 300
    const val STANDALONE_FIX_FRESHNESS_MS = 20_000L
    /** No padding so the lock target is centered in the viewport. */
    val FOLLOW_LOCK_PADDING = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
    /** Target zoom when enabling follow lock from a zoomed-out state. */
    const val FOLLOW_LOCK_TARGET_ZOOM = 16.0
    const val FOLLOW_LOCK_TARGET_ZOOM_EPSILON = 0.05
    /** Zoom used when opening a specific tracker from group members onto the group map. */
    const val TRACKER_CARD_FOCUS_ZOOM = 14.0
    /** Map cannot zoom out past this level (tracker map only). */
    const val MIN_ZOOM = 1.0
    const val MIN_ZOOM_EPSILON = 0.001
    /** Do not draw track across jumps larger than this (meters). 30 miles. */
    const val MAX_JUMP_METERS = 30f * 1609.344f
    /** Content padding (dp) so overlays (name card, buttons, spinner) don't cut off the track. */
    const val MAP_PADDING_LEFT_DP = 28
    const val MAP_PADDING_TOP_DP = 130
    const val MAP_PADDING_EDGE_EXTRA_DP = 12
    const val MAP_PADDING_RIGHT_DP = 60
    const val MAP_PADDING_BOTTOM_DP = 48
    /** Keep at least this fraction of width available during bounds fitting. */
    const val MIN_BOUNDS_FIT_VIEWPORT_WIDTH_FRACTION = 0.55
    /** Keep at least this fraction of height available during bounds fitting. */
    const val MIN_BOUNDS_FIT_VIEWPORT_HEIGHT_FRACTION = 0.50
    /** Approximate height (dp) of the tracker info card when visible for padding. */
    const val MAP_TRACKER_INFO_CARD_HEIGHT_DP = 200
    /** Coalesce bursts of multi-tracker streamed points before full layer redraw. */
    const val MULTI_TRACK_RENDER_DEBOUNCE_MS = 120L
    /** Coalesce bursts for single-tracker + GPS live-fit updates. */
    const val SINGLE_LIVE_FIT_DEBOUNCE_MS = 120L
    /** Tracker is considered live-active if updated in this window. */
    const val LIVE_ACTIVE_TRACKER_WINDOW_MS = 15 * 60 * 1000L

    // Map source/layer IDs (tracker map only)
    const val TRACK_SOURCE_ID = "track-source"
    const val TRACK_OUTER_OUTLINE_LAYER_ID = "track-outer-outline-layer"
    const val TRACK_OUTLINE_LAYER_ID = "track-outline-layer"
    const val TRACK_FILL_LAYER_ID = "track-fill-layer"
    const val TRACK_POSITION_SOURCE_ID = "track-position-source"
    const val TRACK_POSITION_ACCURACY_SOURCE_ID = "track-position-accuracy-source"
    const val TRACK_POSITION_LAYER_ID = "track-position-layer"
    const val TRACK_POSITION_ACCURACY_LAYER_ID = "track-position-accuracy-layer"

    const val ALL_TRACKS_SOURCE_ID = "all-tracks-source"
    const val ALL_TRACKS_POINTS_SOURCE_ID = "all-tracks-points-source"
    const val ALL_TRACKS_OUTER_OUTLINE_LAYER_ID = "all-tracks-outer-outline-layer"
    const val ALL_TRACKS_OUTLINE_LAYER_ID = "all-tracks-outline-layer"
    const val ALL_TRACKS_FILL_LAYER_ID = "all-tracks-fill-layer"
    const val ALL_TRACKS_POINTS_LAYER_ID = "all-tracks-points-layer"
    /** Max distance (px) from tracker position to count as tap-on-tracker in single-tracker mode. */
    const val TAP_NEAR_POINT_PX = 80f
}

