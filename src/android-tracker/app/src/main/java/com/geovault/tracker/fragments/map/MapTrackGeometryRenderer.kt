package com.geovault.tracker.fragments.map

import android.location.Location
import com.geovault.common.map.OutlinedGeoJsonLineLayers
import org.maplibre.android.geometry.LatLng
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

internal object MapTrackGeometryRenderer {
    private fun isFiniteCoordinate(lat: Double, lon: Double): Boolean {
        return lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0
    }

    private fun isValidPoint(point: LatLng): Boolean = isFiniteCoordinate(point.latitude, point.longitude)

    fun buildTrackFeature(
        trackPoints: List<LatLng>,
        lineColor: String,
        outlineColor: String,
        maxJumpMeters: Float
    ): Feature? {
        val validPoints = trackPoints.filter(::isValidPoint)
        val segments = splitTrackIntoSegments(validPoints, maxJumpMeters)
        if (segments.isEmpty()) return null
        val lineStrings = segments.map { segment ->
            LineString.fromLngLats(segment.map { Point.fromLngLat(it.longitude, it.latitude) })
        }
        val multiLineString = MultiLineString.fromLineStrings(lineStrings)
        return Feature.fromGeometry(multiLineString).apply {
            addStringProperty(OutlinedGeoJsonLineLayers.PROPERTY_OUTLINE_COLOR, outlineColor)
            addStringProperty(OutlinedGeoJsonLineLayers.PROPERTY_LINE_COLOR, lineColor)
        }
    }

    fun getTrackDirectionDegrees(points: List<LatLng>): Float {
        val validPoints = points.filter(::isValidPoint)
        if (validPoints.size < 2) return 0f
        val prev = validPoints[validPoints.size - 2]
        val last = validPoints.last()
        val dLon = last.longitude - prev.longitude
        val dLat = last.latitude - prev.latitude
        if (dLon == 0.0 && dLat == 0.0) return 0f
        return (Math.atan2(dLon, dLat) * 180 / Math.PI).toFloat()
    }

    fun buildAccuracyPolygon(center: LatLng, radiusMeters: Double, steps: Int = 64): Polygon? {
        if (!isValidPoint(center)) {
            return null
        }
        val earthRadiusMeters = 6378137.0
        val latRad = Math.toRadians(center.latitude)
        val lonRad = Math.toRadians(center.longitude)
        val angularDistance = radiusMeters / earthRadiusMeters
        val ring = ArrayList<Point>(steps + 1)
        for (i in 0..steps) {
            val bearing = (2.0 * Math.PI * i.toDouble()) / steps.toDouble()
            val sinLat = kotlin.math.sin(latRad)
            val cosLat = kotlin.math.cos(latRad)
            val sinAng = kotlin.math.sin(angularDistance)
            val cosAng = kotlin.math.cos(angularDistance)
            val sinLat2 = sinLat * cosAng + cosLat * sinAng * kotlin.math.cos(bearing)
            val lat2 = kotlin.math.asin(sinLat2)
            val y = kotlin.math.sin(bearing) * sinAng * cosLat
            val x = cosAng - sinLat * sinLat2
            var lon2 = lonRad + kotlin.math.atan2(y, x)
            lon2 = (lon2 + 3.0 * Math.PI) % (2.0 * Math.PI) - Math.PI
            val lonDeg = Math.toDegrees(lon2)
            val latDeg = Math.toDegrees(lat2)
            if (isFiniteCoordinate(latDeg, lonDeg)) {
                ring.add(Point.fromLngLat(lonDeg, latDeg))
            }
        }
        if (ring.size < 4) return null
        return Polygon.fromLngLats(listOf(ring))
    }

    fun splitTrackIntoSegments(points: List<LatLng>, maxJumpMeters: Float): List<List<LatLng>> {
        val validPoints = points.filter(::isValidPoint)
        if (validPoints.size < 2) return emptyList()
        val segments = mutableListOf<MutableList<LatLng>>()
        var current = mutableListOf(validPoints[0])
        val results = FloatArray(3)
        for (i in 1 until validPoints.size) {
            val prev = validPoints[i - 1]
            val curr = validPoints[i]
            Location.distanceBetween(prev.latitude, prev.longitude, curr.latitude, curr.longitude, results)
            val distanceMeters = results[0]
            if (distanceMeters > maxJumpMeters) {
                if (current.size >= 2) segments.add(current)
                current = mutableListOf(curr)
            } else {
                current.add(curr)
            }
        }
        if (current.size >= 2) segments.add(current)
        return segments
    }
}
