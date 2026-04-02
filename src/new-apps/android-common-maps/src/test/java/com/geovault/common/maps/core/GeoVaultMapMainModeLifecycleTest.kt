package com.geovault.common.maps.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.android.geometry.LatLng

class GeoVaultMapMainModeLifecycleTest {
    @Test
    fun resolveCameraTarget_empty_returnsNone() {
        val target = resolveGeoVaultMainMapPreloadCameraTarget(emptyList())
        assertTrue(target is GeoVaultMainMapPreloadCameraTarget.None)
    }

    @Test
    fun resolveCameraTarget_singlePoint_returnsSingle() {
        val target = resolveGeoVaultMainMapPreloadCameraTarget(listOf(LatLng(10.0, 20.0)))
        assertTrue(target is GeoVaultMainMapPreloadCameraTarget.Single)
        target as GeoVaultMainMapPreloadCameraTarget.Single
        assertEquals(10.0, target.lat, 0.0)
        assertEquals(20.0, target.lon, 0.0)
    }

    @Test
    fun resolveCameraTarget_multiplePoints_returnsBounds() {
        val target = resolveGeoVaultMainMapPreloadCameraTarget(
            listOf(
                LatLng(10.0, 20.0),
                LatLng(15.0, 30.0),
            ),
        )
        assertTrue(target is GeoVaultMainMapPreloadCameraTarget.Bounds)
        target as GeoVaultMainMapPreloadCameraTarget.Bounds
        assertEquals(10.0, target.bounds.southWest.latitude, 0.0)
        assertEquals(20.0, target.bounds.southWest.longitude, 0.0)
        assertEquals(15.0, target.bounds.northEast.latitude, 0.0)
        assertEquals(30.0, target.bounds.northEast.longitude, 0.0)
    }
}
