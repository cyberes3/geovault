package com.geovault.tracker.fragments.map

import com.geovault.common.map.OutlinedGeoJsonLineLayers
import com.geovault.tracker.Tracker
import com.geovault.tracker.TrackUpdateHelper
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

internal data class MultiTrackRenderData(
    val normalizedCoordsById: Map<String, MutableList<List<Double>>>,
    val lineFeatures: List<Feature>,
    val pointFeatures: List<Feature>,
    val allCoords: List<LatLng>,
    val coordsByTrackerId: Map<String, List<LatLng>>
)

internal object MapMultiTrackRenderer {
    internal fun extractLastUpdateMs(coord: List<*>): Long? {
        return MapCoordinateUtils.timestampFromCoordinateMs(coord)
    }

    fun buildRenderData(
        trackers: List<Tracker>,
        coordsById: Map<String, List<List<Double>>>,
        selectedTrackerId: String?,
        style: Style,
        defaultColor: String,
        outlineColor: String,
        maxJumpMeters: Float,
        seedCoordsFromLastPoint: (Tracker) -> MutableList<List<Double>>,
        addTrackerPropertiesToPointFeature: (Feature, Tracker, Double, Double, Long?) -> Unit,
        ensureArrowImageInStyle: (Style, String, Boolean) -> Unit
    ): MultiTrackRenderData {
        val normalizedCoordsById = mutableMapOf<String, MutableList<List<Double>>>()
        val lineFeatures = mutableListOf<Feature>()
        val pointFeatures = mutableListOf<Feature>()
        val allCoords = mutableListOf<LatLng>()
        val coordsByTrackerId = mutableMapOf<String, MutableList<LatLng>>()

        for (tracker in trackers) {
            val trackerCoords = mutableListOf<LatLng>()
            val coords = coordsById[tracker.id] ?: tracker.geometry?.coordinates ?: emptyList()
            if (coords.isEmpty()) {
                tracker.last_point?.takeIf { it.size >= 2 }?.let { lp ->
                    normalizedCoordsById[tracker.id] = seedCoordsFromLastPoint(tracker)
                    val pt = LatLng(lp[1], lp[0])
                    allCoords.add(pt)
                    trackerCoords.add(pt)
                    val hexColor = tracker.color?.let { if (it.startsWith("#")) it else "#$it" } ?: defaultColor
                    val isSelected = tracker.id == selectedTrackerId
                    ensureArrowImageInStyle(style, hexColor, !isSelected)
                    val suffix = hexColor.replace("#", "")
                    val imageId = if (isSelected) "track-direction-arrow-$suffix" else "track-direction-arrow-simple-$suffix"
                    val pointFeature = Feature.fromGeometry(Point.fromLngLat(lp[0], lp[1]))
                    pointFeature.addStringProperty("icon", imageId)
                    addTrackerPropertiesToPointFeature(pointFeature, tracker, pt.latitude, pt.longitude, null)
                    pointFeatures.add(pointFeature)
                }
                coordsByTrackerId[tracker.id] = trackerCoords
                continue
            }

            val normalizedCoords = MapCoordinateUtils.normalizeRawCoordinates(coords)
            normalizedCoordsById[tracker.id] = normalizedCoords
            val lastN = normalizedCoords.takeLast(TrackUpdateHelper.MAX_POINTS)
            val points = lastN.map { c -> LatLng((c[1] as Number).toDouble(), (c[0] as Number).toDouble()) }
            points.forEach { allCoords.add(it); trackerCoords.add(it) }
            coordsByTrackerId[tracker.id] = trackerCoords

            val lineColor = tracker.color?.let { if (it.startsWith("#")) it else "#$it" } ?: defaultColor
            val segments = MapTrackGeometryRenderer.splitTrackIntoSegments(points, maxJumpMeters)
            for (segment in segments) {
                if (segment.size < 2) continue
                val lineString = LineString.fromLngLats(segment.map { Point.fromLngLat(it.longitude, it.latitude) })
                val feature = Feature.fromGeometry(lineString)
                feature.addStringProperty(OutlinedGeoJsonLineLayers.PROPERTY_OUTLINE_COLOR, outlineColor)
                feature.addStringProperty(OutlinedGeoJsonLineLayers.PROPERTY_LINE_COLOR, lineColor)
                lineFeatures.add(feature)
            }

            val lastPoint = points.last()
            val rotation = if (points.size >= 2) MapTrackGeometryRenderer.getTrackDirectionDegrees(points) else 0f
            val hexColor = tracker.color?.let { if (it.startsWith("#")) it else "#$it" } ?: defaultColor
            val isSelected = tracker.id == selectedTrackerId
            ensureArrowImageInStyle(style, hexColor, !isSelected)
            val suffix = hexColor.replace("#", "")
            val imageId = if (isSelected) "track-direction-arrow-$suffix" else "track-direction-arrow-simple-$suffix"
            val pointFeature = Feature.fromGeometry(Point.fromLngLat(lastPoint.longitude, lastPoint.latitude))
            pointFeature.addStringProperty("icon", imageId)
            pointFeature.addNumberProperty("rotate", rotation.toDouble())
            val lastUpdateMs = lastN.lastOrNull()?.let { extractLastUpdateMs(it) }
            addTrackerPropertiesToPointFeature(pointFeature, tracker, lastPoint.latitude, lastPoint.longitude, lastUpdateMs)
            pointFeatures.add(pointFeature)
        }

        return MultiTrackRenderData(
            normalizedCoordsById = normalizedCoordsById,
            lineFeatures = lineFeatures,
            pointFeatures = pointFeatures,
            allCoords = allCoords,
            coordsByTrackerId = coordsByTrackerId
        )
    }
}
