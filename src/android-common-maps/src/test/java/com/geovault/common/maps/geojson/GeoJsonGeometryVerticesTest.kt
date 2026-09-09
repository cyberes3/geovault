package com.geovault.common.maps.geojson

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoJsonGeometryVerticesTest {

    @Test
    fun blankOrNullJson_yieldsEmptyList() {
        assertTrue(GeoJsonGeometryVertices.vertices(null).isEmpty())
        assertTrue(GeoJsonGeometryVertices.vertices("").isEmpty())
        assertTrue(GeoJsonGeometryVertices.vertices("   ").isEmpty())
    }

    @Test
    fun malformedJson_yieldsEmptyList() {
        assertTrue(GeoJsonGeometryVertices.vertices("{not json}").isEmpty())
    }

    @Test
    fun unknownGeometryType_yieldsEmptyList() {
        val json = """{"type":"GeometryCollection","coordinates":[]}"""
        assertTrue(GeoJsonGeometryVertices.vertices(json).isEmpty())
    }

    @Test
    fun point_emitsSingleLatLonPair_inLatLonOrder() {
        val json = """{"type":"Point","coordinates":[10.5,20.25]}"""
        assertEquals(listOf(20.25 to 10.5), GeoJsonGeometryVertices.vertices(json))
        assertEquals(20.25 to 10.5, GeoJsonGeometryVertices.first(json))
    }

    @Test
    fun multiPoint_emitsAllVertices() {
        val json = """{"type":"MultiPoint","coordinates":[[1,2],[3,4],[5,6]]}"""
        assertEquals(
            listOf(2.0 to 1.0, 4.0 to 3.0, 6.0 to 5.0),
            GeoJsonGeometryVertices.vertices(json),
        )
    }

    @Test
    fun lineString_emitsAllVerticesInOrder() {
        val json = """{"type":"LineString","coordinates":[[1,2],[3,4]]}"""
        assertEquals(listOf(2.0 to 1.0, 4.0 to 3.0), GeoJsonGeometryVertices.vertices(json))
    }

    @Test
    fun polygon_emitsBothOuterAndInnerRingVertices() {
        val json = """
            {"type":"Polygon","coordinates":[
              [[0,0.1],[1,1.1],[2,2.1],[0,0.1]],
              [[0.5,0.5],[0.7,0.5],[0.7,0.7],[0.5,0.5]]
            ]}
        """.trimIndent()
        val verts = GeoJsonGeometryVertices.vertices(json)
        assertEquals(8, verts.size)
        assertEquals(0.1 to 0.0, verts.first())
    }

    @Test
    fun multiPolygon_walksEveryRingOfEveryPolygon() {
        val json = """
            {"type":"MultiPolygon","coordinates":[
              [[[0,0.1],[1,1.1],[2,2.1],[0,0.1]]],
              [[[10,10],[11,11],[12,12],[10,10]]]
            ]}
        """.trimIndent()
        assertEquals(8, GeoJsonGeometryVertices.vertices(json).size)
    }

    @Test
    fun nullIslandSentinel_isFiltered() {
        val json = """{"type":"MultiPoint","coordinates":[[0,0],[1,2],[0,0],[3,4]]}"""
        assertEquals(
            listOf(2.0 to 1.0, 4.0 to 3.0),
            GeoJsonGeometryVertices.vertices(json),
        )
        assertEquals(2.0 to 1.0, GeoJsonGeometryVertices.first(json))
    }

    @Test
    fun missingCoordinatesArray_yieldsEmptyList() {
        val json = """{"type":"Point"}"""
        assertTrue(GeoJsonGeometryVertices.vertices(json).isEmpty())
        assertNull(GeoJsonGeometryVertices.first(json))
    }
}
