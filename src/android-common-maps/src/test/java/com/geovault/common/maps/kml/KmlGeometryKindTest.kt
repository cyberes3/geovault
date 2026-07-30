package com.geovault.common.maps.kml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KmlGeometryKindTest {

    @Test
    fun fromType_points() {
        assertEquals(KmlGeometryKind.Point, KmlGeometryKind.fromType("Point"))
        assertEquals(KmlGeometryKind.Point, KmlGeometryKind.fromType("MultiPoint"))
        assertFalse(KmlGeometryKind.Point.isLinework)
    }

    @Test
    fun fromType_lines() {
        assertEquals(KmlGeometryKind.Line, KmlGeometryKind.fromType("LineString"))
        assertEquals(KmlGeometryKind.Line, KmlGeometryKind.fromType("MultiLineString"))
        assertTrue(KmlGeometryKind.Line.isLinework)
    }

    @Test
    fun fromType_polygons() {
        assertEquals(KmlGeometryKind.Polygon, KmlGeometryKind.fromType("Polygon"))
        assertEquals(KmlGeometryKind.Polygon, KmlGeometryKind.fromType("MultiPolygon"))
        assertTrue(KmlGeometryKind.Polygon.isLinework)
    }

    @Test
    fun fromType_unknownAndBlank() {
        assertEquals(KmlGeometryKind.Unknown, KmlGeometryKind.fromType("GeometryCollection"))
        assertEquals(KmlGeometryKind.Unknown, KmlGeometryKind.fromType(null))
        assertEquals(KmlGeometryKind.Unknown, KmlGeometryKind.fromType("  "))
        assertFalse(KmlGeometryKind.Unknown.isLinework)
    }
}
