package com.geovault.tracker.presentation

object TrackerMapRenderContract {
    const val SOURCE_ID_PREFIX: String = "gv-tracker-map"

    fun pointsIconLayerId(sourceIdPrefix: String = SOURCE_ID_PREFIX): String =
        "$sourceIdPrefix-points-icon-layer"

    /** Present only when [com.geovault.common.maps.render.GeoJsonRenderConfig.showPointTextLabels] is true. */
    fun pointsLabelLayerId(sourceIdPrefix: String = SOURCE_ID_PREFIX): String =
        "$sourceIdPrefix-points-label-layer"

    /** Marker hit-testing when the map has no text labels (tracker default). */
    fun pointsMarkerHitTestLayerId(sourceIdPrefix: String = SOURCE_ID_PREFIX): String =
        pointsIconLayerId(sourceIdPrefix)
}
