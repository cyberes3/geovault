package com.geovault.common.maps.camera

import com.geovault.common.maps.core.GeoVaultBaseMap
import com.geovault.common.maps.core.animateCameraToFitLatLngBounds
import com.geovault.common.maps.core.animateCameraToHomeFit
import com.geovault.common.maps.core.geoVaultCenterCameraPreserveZoom
import com.geovault.common.maps.core.geoVaultLatLngBoundsForPoints
import com.geovault.common.maps.core.moveCameraToFitLatLngBounds
import com.geovault.common.maps.geojson.GeoJsonGeometryVertices
import com.geovault.common.maps.render.MapRenderState
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds

/**
 * Named camera intents over [GeoVaultBaseMap]: fit points, drawer-aware pans, and navigation
 * framing. Bounds use [geoVaultLatLngBoundsForPoints] so Pacific / dateline extents take the
 * short longitude arc.
 *
 * Every method is a no-op when the map is not attached.
 */
class GeoVaultMapCameraController(
    private val map: GeoVaultBaseMap,
) {
    fun snapFitAll(renderState: MapRenderState, paddingPx: IntArray): Boolean {
        return snapFitPoints(renderStatePoints(renderState), paddingPx)
    }

    fun fitLatLngBounds(
        bounds: LatLngBounds,
        paddingPx: IntArray,
        animate: Boolean = false,
    ) {
        if (map.maplibreMap == null) return
        if (animate) {
            map.animateCameraToFitLatLngBounds(bounds, paddingPx)
        } else {
            map.moveCameraToFitLatLngBounds(bounds, paddingPx)
        }
    }

    fun animateHomeFit(
        bounds: LatLngBounds?,
        gpsAnchor: LatLng?,
        paddingPx: IntArray,
    ) {
        if (map.maplibreMap == null) return
        map.animateCameraToHomeFit(bounds, gpsAnchor, paddingPx)
    }

    fun snapFitBounds(bounds: LatLngBounds, paddingPx: IntArray): Boolean {
        require(paddingPx.size == 4)
        val mapLibre = map.maplibreMap ?: return false
        val update = CameraUpdateFactory.newLatLngBounds(
            bounds,
            paddingPx[0], paddingPx[1], paddingPx[2], paddingPx[3],
        )
        val computed = update.getCameraPosition(mapLibre) ?: return false
        mapLibre.cameraPosition = CameraPosition.Builder(computed)
            .bearing(0.0)
            .tilt(0.0)
            .build()
        return true
    }

    fun fitAll(
        renderState: MapRenderState,
        paddingPx: IntArray,
        animate: Boolean = true,
    ) {
        fitPoints(renderStatePoints(renderState), paddingPx, animate)
    }

    fun fitGeometryJson(
        geometryJson: String?,
        paddingPx: IntArray,
        animate: Boolean = true,
        singlePointZoom: Double = LIST_INITIAL_FOCUS_ZOOM,
    ): Boolean {
        val points = GeoJsonGeometryVertices.latLngs(geometryJson)
        return if (animate) {
            fitPoints(
                points = points,
                paddingPx = paddingPx,
                animate = true,
                singlePointZoom = singlePointZoom,
            )
        } else {
            snapFitPoints(
                points = points,
                paddingPx = paddingPx,
                singlePointZoom = singlePointZoom,
            )
        }
    }

    fun snapFitPoints(
        points: List<LatLng>,
        paddingPx: IntArray,
        singlePointZoom: Double = TAP_POINT_ZOOM,
    ): Boolean {
        require(paddingPx.size == 4)
        val mapLibre = map.maplibreMap ?: return false
        val valid = validPoints(points)
        when (valid.size) {
            0 -> return false
            1 -> {
                val only = valid[0]
                mapLibre.cameraPosition = CameraPosition.Builder()
                    .target(LatLng(only.latitude, only.longitude))
                    .zoom(singlePointZoom)
                    .bearing(0.0)
                    .tilt(0.0)
                    .build()
                return true
            }
            else -> {
                val bounds = geoVaultLatLngBoundsForPoints(valid) ?: return false
                val update = CameraUpdateFactory.newLatLngBounds(
                    bounds,
                    paddingPx[0], paddingPx[1], paddingPx[2], paddingPx[3],
                )
                val computed = update.getCameraPosition(mapLibre) ?: return false
                mapLibre.cameraPosition = CameraPosition.Builder(computed)
                    .bearing(0.0)
                    .tilt(0.0)
                    .build()
                return true
            }
        }
    }

    fun fitPoints(
        points: List<LatLng>,
        paddingPx: IntArray,
        animate: Boolean = true,
        singlePointZoom: Double = TAP_POINT_ZOOM,
    ): Boolean {
        require(paddingPx.size == 4)
        if (map.maplibreMap == null) return false
        val valid = validPoints(points)
        when (valid.size) {
            0 -> return false
            1 -> {
                val only = valid[0]
                focusPointAtZoom(only.latitude, only.longitude, singlePointZoom, animate)
                return true
            }
            else -> {
                val bounds = geoVaultLatLngBoundsForPoints(valid) ?: return false
                if (animate) {
                    map.animateCameraToFitLatLngBounds(bounds, paddingPx)
                } else {
                    map.moveCameraToFitLatLngBounds(bounds, paddingPx)
                }
                return true
            }
        }
    }

    fun focusPoint(latitude: Double, longitude: Double) {
        geoVaultCenterCameraPreserveZoom(map, latitude, longitude)
    }

    fun panIfObscuredByDrawer(
        latitude: Double,
        longitude: Double,
        drawerReservePx: Int,
        edgeMarginPx: Int,
    ): Boolean {
        val deltaY = computeObscuredByDrawerScrollDeltaY(
            latitude,
            longitude,
            drawerReservePx,
            edgeMarginPx,
        ) ?: return false
        val mapLibre = map.maplibreMap ?: return false
        mapLibre.scrollBy(0f, deltaY)
        return true
    }

    fun selectionWouldBeObscuredByDrawer(
        latitude: Double,
        longitude: Double,
        drawerSettledReservePx: Int,
        edgeMarginPx: Int,
    ): Boolean {
        val mapLibre = map.maplibreMap ?: return false
        if (drawerSettledReservePx <= 0) return false
        val viewportHeight = mapLibre.height.toInt()
        if (viewportHeight <= 0) return false
        val drawerTopY = (viewportHeight - drawerSettledReservePx).toFloat().coerceAtLeast(0f)
        val obscureThresholdY = (drawerTopY - edgeMarginPx).coerceAtLeast(0f)
        val screenPoint = mapLibre.projection.toScreenLocation(LatLng(latitude, longitude))
        return screenPoint.y >= obscureThresholdY
    }

    fun mirrorPanToDrawerHeight(
        latitude: Double,
        longitude: Double,
        drawerReservePx: Int,
    ): Boolean {
        val mapLibre = map.maplibreMap ?: return false
        if (drawerReservePx <= 0) return false
        val viewportHeight = mapLibre.height.toInt()
        if (viewportHeight <= 0) return false
        val drawerTopY = (viewportHeight - drawerReservePx).toFloat().coerceAtLeast(0f)
        val targetY = drawerTopY / 2f
        val screenPoint = mapLibre.projection.toScreenLocation(LatLng(latitude, longitude))
        val deltaY = targetY - screenPoint.y
        if (kotlin.math.abs(deltaY) < 0.5f) return false
        mapLibre.scrollBy(0f, deltaY)
        return true
    }

    fun focusOnSelectionAtTapZoom(
        latitude: Double,
        longitude: Double,
        zoom: Double = TAP_POINT_ZOOM,
    ) {
        focusPointAtZoom(latitude, longitude, zoom, animate = true)
    }

    /**
     * Snap onto a drawer-list pick, placing the marker in the visible area above the drawer
     * rather than at the geometric viewport center (which sits behind the sheet).
     */
    fun focusOnSelectionFromDrawer(
        latitude: Double,
        longitude: Double,
        drawerReservePx: Int = 0,
        zoom: Double = DRAWER_POINT_ZOOM,
    ) {
        val mapLibre = map.maplibreMap ?: return
        val update = CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder(mapLibre.cameraPosition)
                .target(LatLng(latitude, longitude))
                .zoom(zoom)
                .padding(0.0, 0.0, 0.0, 0.0)
                .build(),
        )
        mapLibre.moveCamera(update)
        if (drawerReservePx <= 0) return
        val viewportHeight = mapLibre.height.toInt()
        if (viewportHeight <= 0) return
        val drawerTopY = (viewportHeight - drawerReservePx).toFloat().coerceAtLeast(0f)
        val targetY = drawerTopY / 2f
        val deltaY = targetY - viewportHeight / 2f
        if (kotlin.math.abs(deltaY) >= 0.5f) {
            mapLibre.scrollBy(0f, deltaY)
        }
    }

    fun focusPointAtZoom(
        latitude: Double,
        longitude: Double,
        zoom: Double,
        animate: Boolean = true,
    ) {
        if (map.maplibreMap == null) return
        val update = CameraUpdateFactory.newLatLngZoom(LatLng(latitude, longitude), zoom)
        if (animate) {
            map.animateCameraWithPadding(update)
        } else {
            map.moveCameraWithPadding(update)
        }
    }

    fun fitTargetAndUserForNavigation(
        targetLat: Double,
        targetLon: Double,
        userLat: Double,
        userLon: Double,
        paddingPx: IntArray,
        animate: Boolean = true,
    ) {
        require(paddingPx.size == 4)
        if (map.maplibreMap == null) return
        val bounds = geoVaultLatLngBoundsForPoints(
            listOf(LatLng(targetLat, targetLon), LatLng(userLat, userLon)),
        ) ?: return
        val maxZoom = map.manager.resolveEffectiveMaxZoom()
        if (animate) {
            map.animateCameraToFitLatLngBounds(bounds, paddingPx, maxZoom = maxZoom)
        } else {
            map.moveCameraToFitLatLngBounds(bounds, paddingPx, maxZoom = maxZoom)
        }
    }

    private fun computeObscuredByDrawerScrollDeltaY(
        latitude: Double,
        longitude: Double,
        drawerReservePx: Int,
        edgeMarginPx: Int,
    ): Float? {
        val mapLibre = map.maplibreMap ?: return null
        if (drawerReservePx <= 0) return null
        val viewportHeight = mapLibre.height.toInt()
        if (viewportHeight <= 0) return null
        val drawerTopY = (viewportHeight - drawerReservePx).toFloat().coerceAtLeast(0f)
        val obscureThresholdY = (drawerTopY - edgeMarginPx).coerceAtLeast(0f)
        val screenPoint = mapLibre.projection.toScreenLocation(LatLng(latitude, longitude))
        if (screenPoint.y < obscureThresholdY) return null
        val targetY = drawerTopY / 2f
        return targetY - screenPoint.y
    }

    private fun renderStatePoints(state: MapRenderState): List<LatLng> {
        val fromPoints = state.points.map { LatLng(it.latitude, it.longitude) }
        val fromLines = state.lines.flatMap { line ->
            line.coordinates.map { (lat, lon) -> LatLng(lat, lon) }
        }
        val fromPolygons = state.polygons.flatMap { polygon ->
            polygon.rings.flatMap { ring ->
                ring.map { (lat, lon) -> LatLng(lat, lon) }
            }
        }
        return fromPoints + fromLines + fromPolygons
    }

    private fun validPoints(points: List<LatLng>): List<LatLng> {
        return points.filter { point ->
            point.latitude in -90.0..90.0 && point.longitude in -180.0..180.0
        }
    }

    companion object {
        const val TAP_POINT_ZOOM: Double = 14.0
        const val DRAWER_POINT_ZOOM: Double = 14.0
        const val LIST_INITIAL_FOCUS_ZOOM: Double = 12.0
    }
}
