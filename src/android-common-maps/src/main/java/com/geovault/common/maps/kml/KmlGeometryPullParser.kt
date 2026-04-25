package com.geovault.common.maps.kml

import org.xmlpull.v1.XmlPullParser

/**
 * Reads KML 2.2 geometry elements from an [XmlPullParser] positioned at the **start tag**
 * of `Point`, `LineString`, `Polygon`, or `MultiGeometry`.
 *
 * Uses element **local names** so default KML namespaces work with
 * [XmlPullParser.FEATURE_PROCESS_NAMESPACES].
 */
class KmlGeometryPullParser {

    /**
     * Dispatch on the current start tag. Consumes the element and nested content; on return,
     * the parser is on the matching end tag for the outer geometry (caller typically follows with [XmlPullParser.next]).
     */
    fun parseGeometrySubtree(parser: XmlPullParser): List<ParsedKmlGeometry> {
        check(parser.eventType == XmlPullParser.START_TAG) {
            "KmlGeometryPullParser: expected START_TAG, was ${parser.eventType}"
        }
        return when (parser.name) {
            "Point" -> parsePoint(parser)?.let { listOf(it) } ?: emptyList()
            "LineString" -> parseLineString(parser)?.let { listOf(it) } ?: emptyList()
            "Polygon" -> parsePolygon(parser)?.let { listOf(it) } ?: emptyList()
            "MultiGeometry" -> parseMultiGeometry(parser)
            else -> {
                consumeCurrentSubtree(parser)
                emptyList()
            }
        }
    }

    private fun parsePoint(parser: XmlPullParser): ParsedKmlGeometry.Point? {
        val outerDepth = parser.depth
        var event = parser.next()
        var coordText: String? = null
        while (!(event == XmlPullParser.END_TAG && parser.depth == outerDepth && parser.name == "Point")) {
            if (event == XmlPullParser.START_TAG && parser.name == "coordinates") {
                coordText = parser.nextText()
            } else if (event == XmlPullParser.START_TAG) {
                consumeCurrentSubtree(parser)
            }
            event = parser.next()
        }
        val pos = KmlCoordinateTuples.parsePositions(coordText).firstOrNull() ?: return null
        return ParsedKmlGeometry.Point(pos)
    }

    private fun parseLineString(parser: XmlPullParser): ParsedKmlGeometry.LineString? {
        val outerDepth = parser.depth
        var event = parser.next()
        var coordText: String? = null
        while (!(event == XmlPullParser.END_TAG && parser.depth == outerDepth && parser.name == "LineString")) {
            if (event == XmlPullParser.START_TAG && parser.name == "coordinates") {
                coordText = parser.nextText()
            } else if (event == XmlPullParser.START_TAG) {
                consumeCurrentSubtree(parser)
            }
            event = parser.next()
        }
        val pts = KmlCoordinateTuples.parsePositions(coordText)
        if (pts.size < 2) return null
        return ParsedKmlGeometry.LineString(pts)
    }

    /**
     * KML [Polygon]: rings live under [outerBoundaryIs] / [innerBoundaryIs] → [LinearRing] → [coordinates],
     * or a direct [coordinates] child under [Polygon] (some KML writers).
     */
    private fun parsePolygon(parser: XmlPullParser): ParsedKmlGeometry.Polygon? {
        val rings = mutableListOf<List<KmlPosition>>()
        val outerDepth = parser.depth
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == outerDepth && parser.name == "Polygon")) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "outerBoundaryIs", "innerBoundaryIs" -> {
                        rings.addAll(readBoundaryRings(parser))
                    }
                    "coordinates" -> {
                        val ring = ringFromCoordinateText(parser.nextText())
                        if (ring.isNotEmpty()) rings += ring
                    }
                    else -> consumeCurrentSubtree(parser)
                }
            }
            event = parser.next()
        }
        if (rings.isEmpty()) return null
        return ParsedKmlGeometry.Polygon(rings)
    }

    /**
     * Parser at START [outerBoundaryIs] or [innerBoundaryIs]; leaves parser on END of that element.
     */
    private fun readBoundaryRings(parser: XmlPullParser): List<List<KmlPosition>> {
        val boundaryDepth = parser.depth
        val collected = mutableListOf<List<KmlPosition>>()
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == boundaryDepth && isBoundaryTag(parser.name))) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "LinearRing" -> {
                        readLinearRing(parser)?.let { collected += it }
                    }
                    "coordinates" -> {
                        val ring = ringFromCoordinateText(parser.nextText())
                        if (ring.isNotEmpty()) collected += ring
                    }
                    else -> consumeCurrentSubtree(parser)
                }
            }
            event = parser.next()
        }
        return collected
    }

    private fun isBoundaryTag(name: String): Boolean =
        name == "outerBoundaryIs" || name == "innerBoundaryIs"

    /** Parser at START [LinearRing]; leaves on END [LinearRing]. */
    private fun readLinearRing(parser: XmlPullParser): List<KmlPosition>? {
        val ringDepth = parser.depth
        var coordText: String? = null
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == ringDepth && parser.name == "LinearRing")) {
            if (event == XmlPullParser.START_TAG && parser.name == "coordinates") {
                coordText = parser.nextText()
            } else if (event == XmlPullParser.START_TAG) {
                consumeCurrentSubtree(parser)
            }
            event = parser.next()
        }
        val ring = ringFromCoordinateText(coordText)
        return ring.ifEmpty { null }
    }

    private fun ringFromCoordinateText(text: String?): List<KmlPosition> =
        KmlCoordinateTuples.parsePositions(text)

    private fun parseMultiGeometry(parser: XmlPullParser): List<ParsedKmlGeometry> {
        val out = mutableListOf<ParsedKmlGeometry>()
        val outerDepth = parser.depth
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == outerDepth && parser.name == "MultiGeometry")) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "Point", "LineString", "Polygon" -> out.addAll(parseGeometrySubtree(parser))
                    else -> consumeCurrentSubtree(parser)
                }
            }
            event = parser.next()
        }
        return out
    }

    private fun consumeCurrentSubtree(parser: XmlPullParser) {
        if (parser.eventType != XmlPullParser.START_TAG) return
        var depth = 1
        while (depth != 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
            }
        }
    }
}
