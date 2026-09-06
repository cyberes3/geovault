package com.geovault.common.maps.navigation

import com.geovault.common.geo.GeoMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    fun `target-only (no user fix) is empty - map layer shows the station`() {
        val fc = GeoVaultNavigationToPointPlugin.RenderGeometry.buildFeatureCollection(
            targetLatitude = 45.0,
            targetLongitude = -93.0,
            userLatitude = null,
            userLongitude = null,
        )
        assertEquals(0, fc.features()?.size ?: 0)
    }

    @Test
    fun `target plus user produces a single line feature`() {
        val fc = GeoVaultNavigationToPointPlugin.RenderGeometry.buildFeatureCollection(
            targetLatitude = 45.0,
            targetLongitude = -93.0,
            userLatitude = 44.9,
            userLongitude = -93.1,
        )
        val features = fc.features()!!
        assertEquals(1, features.size)
        val lineFeature = features[0]
        assertEquals(GeoVaultNavigationToPointPlugin.RenderGeometry.LINE_FEATURE_ID, lineFeature.id())
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
    fun `partial user coords (only latitude) are ignored - no line`() {
        val fc = GeoVaultNavigationToPointPlugin.RenderGeometry.buildFeatureCollection(
            targetLatitude = 10.0,
            targetLongitude = 20.0,
            userLatitude = 11.0,
            userLongitude = null,
        )
        assertEquals(0, fc.features()?.size ?: 0)
    }

    @Test
    fun `haversine returns zero for identical coordinates`() {
        val d = GeoMath.haversineMeters(45.0, -93.0, 45.0, -93.0)
        assertEquals(0.0, d, 1e-6)
    }

    @Test
    fun `haversine approximates known distance Minneapolis to Saint Paul`() {
        val d = GeoMath.haversineMeters(
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
        val forward = GeoMath.haversineMeters(10.0, 20.0, 30.0, 40.0)
        val backward = GeoMath.haversineMeters(30.0, 40.0, 10.0, 20.0)
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
        assertEquals(0, fc.features()?.size ?: 0)
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
