package com.geovault.tracker.presentation

object TrackerMapRenderContract {
    const val SOURCE_ID_PREFIX: String = "gv-tracker-map"

    fun pointsLabelLayerId(sourceIdPrefix: String = SOURCE_ID_PREFIX): String {
        return "$sourceIdPrefix-points-label-layer"
    }
}
