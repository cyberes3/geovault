package com.geovault.common.maps.core

import org.junit.Assert.assertEquals
import org.junit.Test

class MapSourceApplyPlannerTest {

    @Test
    fun rasterToRasterWithExistingStyle_replacesRasterInPlace() {
        assertEquals(
            MapSourceApplyPlan.ReplaceRasterInPlace,
            MapSourceApplyPlanner.plan(
                requestedSourceKey = "raster:osm-dark:https://tiles/dark/{z}/{x}/{y}.png",
                pendingSourceKey = null,
                lastAppliedSourceKey = "raster:osm:https://tiles/osm/{z}/{x}/{y}.png",
                hasCurrentStyle = true,
            ),
        )
    }

    @Test
    fun rasterWithoutExistingStyle_loadsFullStyle() {
        assertEquals(
            MapSourceApplyPlan.LoadFullStyle,
            MapSourceApplyPlanner.plan(
                requestedSourceKey = "raster:osm:https://tiles/osm/{z}/{x}/{y}.png",
                pendingSourceKey = null,
                lastAppliedSourceKey = "raster:osm-dark:https://tiles/dark/{z}/{x}/{y}.png",
                hasCurrentStyle = false,
            ),
        )
    }

    @Test
    fun vectorToRaster_loadsFullStyle() {
        assertEquals(
            MapSourceApplyPlan.LoadFullStyle,
            MapSourceApplyPlanner.plan(
                requestedSourceKey = "raster:osm:https://tiles/osm/{z}/{x}/{y}.png",
                pendingSourceKey = null,
                lastAppliedSourceKey = "vector:maptiler:/style.json",
                hasCurrentStyle = true,
            ),
        )
    }

    @Test
    fun rasterToVector_loadsFullStyle() {
        assertEquals(
            MapSourceApplyPlan.LoadFullStyle,
            MapSourceApplyPlanner.plan(
                requestedSourceKey = "vector:maptiler:/style.json",
                pendingSourceKey = null,
                lastAppliedSourceKey = "raster:osm:https://tiles/osm/{z}/{x}/{y}.png",
                hasCurrentStyle = true,
            ),
        )
    }

    @Test
    fun alreadyAppliedWithExistingStyle_isNoop() {
        assertEquals(
            MapSourceApplyPlan.Noop,
            MapSourceApplyPlanner.plan(
                requestedSourceKey = "raster:osm:https://tiles/osm/{z}/{x}/{y}.png",
                pendingSourceKey = null,
                lastAppliedSourceKey = "raster:osm:https://tiles/osm/{z}/{x}/{y}.png",
                hasCurrentStyle = true,
            ),
        )
    }

    @Test
    fun pendingRequest_isNoop() {
        assertEquals(
            MapSourceApplyPlan.Noop,
            MapSourceApplyPlanner.plan(
                requestedSourceKey = "raster:osm:https://tiles/osm/{z}/{x}/{y}.png",
                pendingSourceKey = "raster:osm:https://tiles/osm/{z}/{x}/{y}.png",
                lastAppliedSourceKey = "raster:osm-dark:https://tiles/dark/{z}/{x}/{y}.png",
                hasCurrentStyle = true,
            ),
        )
    }
}
