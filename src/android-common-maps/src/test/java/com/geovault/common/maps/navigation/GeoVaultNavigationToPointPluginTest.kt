package com.geovault.common.maps.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.abs

/**
 * Exercises [GeoVaultNavigationToPointPlugin.RenderGeometry] without MapLibre [Style].
 */
class GeoVaultNavigationToPointPluginTest {

    @Test
    fun `no target produces empty feature collection`() {
        val fc = GeoVaultNavigationToPointPlugin.RenderGeometry.buildFeatureCollection(
            targetLatitude = null,
            targetLongitude = null,
            userLatitude = 1.0,
            userLongitude = 2.0,
        )
        assertEquals(0, fc.features()?.size ?: 0)
    }

    @Test
    fun `target-only produces a single point feature with the target id`() {
        val fc = GeoVaultNavigationToPointPlugin.RenderGeometry.buildFeatureCollection(
            targetLatitude = 45.0,
            targetLongitude = -93.0,
            userLatitude = null,
            userLongitude = null,
        )
        val features = fc.features()
        assertNotNull(features)
        assertEquals(1, features!!.size)
        val targetFeature = features[0]
        assertEquals(GeoVaultNavigationToPointPlugin.RenderGeometry.TARGET_FEATURE_ID, targetFeature.id())
        val geometry = targetFeature.geometry() as? Point
        assertNotNull("Target must be a Point geometry", geometry)
        assertEquals(-93.0, geometry!!.longitude(), 1e-9)
        assertEquals(45.0, geometry.latitude(), 1e-9)
    }

    @Test
    fun `target plus user produces both point and line features`() {
        val fc = GeoVaultNavigationToPointPlugin.RenderGeometry.buildFeatureCollection(
            targetLatitude = 45.0,
            targetLongitude = -93.0,
            userLatitude = 44.9,
            userLongitude = -93.1,
        )
        val features = fc.features()!!
        assertEquals(2, features.size)
        val lineFeature = features.first { it.id() == GeoVaultNavigationToPointPlugin.RenderGeometry.LINE_FEATURE_ID }
        val line = lineFeature.geometry() as? LineString
        assertNotNull("Line feature must carry a LineString geometry", line)
        val coords = line!!.coordinates()
        assertEquals(2, coords.size)
        assertEquals(-93.1, coords[0].longitude(), 1e-9)
        assertEquals(44.9, coords[0].latitude(), 1e-9)
        assertEquals(-93.0, coords[1].longitude(), 1e-9)
        assertEquals(45.0, coords[1].latitude(), 1e-9)
    }

    @Test
    fun `partial user coords (only latitude) are ignored - no line drawn`() {
        val fc = GeoVaultNavigationToPointPlugin.RenderGeometry.buildFeatureCollection(
            targetLatitude = 10.0,
            targetLongitude = 20.0,
            userLatitude = 11.0,
            userLongitude = null,
        )
        val features = fc.features()!!
        assertEquals(1, features.size)
        assertEquals(GeoVaultNavigationToPointPlugin.RenderGeometry.TARGET_FEATURE_ID, features[0].id())
    }

    @Test
    fun `haversine returns zero for identical coordinates`() {
        val d = GeoVaultNavigationToPointPlugin.RenderGeometry.haversineMeters(45.0, -93.0, 45.0, -93.0)
        assertEquals(0.0, d, 1e-6)
    }

    @Test
    fun `haversine approximates known distance Minneapolis to Saint Paul`() {
        val d = GeoVaultNavigationToPointPlugin.RenderGeometry.haversineMeters(
            44.9778, -93.2650,
            44.9537, -93.0900,
        )
        val expected = 14_000.0
        val tolerance = expected * 0.10
        assertTrue(
            "Expected ~14.0 km, got ${d / 1000.0} km (|diff|=${abs(d - expected)})",
            abs(d - expected) < tolerance,
        )
    }

    @Test
    fun `haversine is symmetric`() {
        val forward = GeoVaultNavigationToPointPlugin.RenderGeometry.haversineMeters(10.0, 20.0, 30.0, 40.0)
        val backward = GeoVaultNavigationToPointPlugin.RenderGeometry.haversineMeters(30.0, 40.0, 10.0, 20.0)
        assertEquals(forward, backward, 1e-6)
    }

    @Test
    fun `null user lat drops the line even if lon present`() {
        val fc = GeoVaultNavigationToPointPlugin.RenderGeometry.buildFeatureCollection(
            targetLatitude = 1.0,
            targetLongitude = 2.0,
            userLatitude = null,
            userLongitude = 3.0,
        )
        assertNull(fc.features()!!.find { it.id() == GeoVaultNavigationToPointPlugin.RenderGeometry.LINE_FEATURE_ID })
    }

    @Test
    fun `label is anchored at user not target`() {
        val fc = GeoVaultNavigationToPointPlugin.RenderGeometry.buildLabelFeatureCollection(
            userLatitude = 44.9,
            userLongitude = -93.1,
            title = "A",
            distanceMeters = 10.0,
        )
        val f = fc.features()!!.first()
        val p = f.geometry() as Point
        assertEquals(-93.1, p.longitude(), 1e-9)
        assertEquals(44.9, p.latitude(), 1e-9)
    }

    @Test
    fun `label is empty when user position is unknown`() {
        val fc = GeoVaultNavigationToPointPlugin.RenderGeometry.buildLabelFeatureCollection(
            userLatitude = null,
            userLongitude = 20.0,
            title = "A",
            distanceMeters = 1.0,
        )
        assertEquals(0, fc.features()?.size ?: 0)
    }
}
