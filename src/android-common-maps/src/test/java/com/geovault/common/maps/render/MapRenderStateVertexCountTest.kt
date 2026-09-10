package com.geovault.common.maps.render

import org.junit.Assert.assertEquals
import org.junit.Test

class MapRenderStateVertexCountTest {
    @Test
    fun vertexCount_sumsPointsLinesAndPolygonRings() {
        val state = MapRenderState(
            points = listOf(
                MapRenderPoint(id = "p1", latitude = 1.0, longitude = 2.0),
                MapRenderPoint(id = "p2", latitude = 3.0, longitude = 4.0),
            ),
            lines = listOf(
                MapRenderLine(
                    id = "l1",
                    coordinates = listOf(1.0 to 2.0, 3.0 to 4.0, 5.0 to 6.0),
                    lineColorHex = "#000000",
                ),
            ),
            polygons = listOf(
                MapRenderPolygon(
                    id = "poly1",
                    rings = listOf(
                        listOf(1.0 to 1.0, 1.0 to 2.0, 2.0 to 2.0, 2.0 to 1.0),
                    ),
                ),
            ),
        )
        assertEquals(2 + 3 + 4, state.vertexCount())
    }
}
