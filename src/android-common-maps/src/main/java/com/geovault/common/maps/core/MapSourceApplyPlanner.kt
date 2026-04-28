package com.geovault.common.maps.core

internal enum class MapSourceApplyPlan {
    Noop,
    ReplaceRasterInPlace,
    LoadFullStyle,
}

internal object MapSourceApplyPlanner {
    fun plan(
        requestedSourceKey: String,
        pendingSourceKey: String?,
        lastAppliedSourceKey: String?,
        hasCurrentStyle: Boolean,
    ): MapSourceApplyPlan {
        if (requestedSourceKey == pendingSourceKey ||
            (hasCurrentStyle && requestedSourceKey == lastAppliedSourceKey)
        ) {
            return MapSourceApplyPlan.Noop
        }

        return if (
            hasCurrentStyle &&
            requestedSourceKey.isRasterSourceKey() &&
            lastAppliedSourceKey?.isRasterSourceKey() == true
        ) {
            MapSourceApplyPlan.ReplaceRasterInPlace
        } else {
            MapSourceApplyPlan.LoadFullStyle
        }
    }

    private fun String.isRasterSourceKey(): Boolean = startsWith(ResolvedBasemap.RASTER_KEY_PREFIX)
}
