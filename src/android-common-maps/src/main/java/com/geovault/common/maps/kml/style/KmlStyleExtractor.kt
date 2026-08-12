package com.geovault.common.maps.kml.style

import org.xmlpull.v1.XmlPullParser

/**
 * Reads a KML `<Style>` element (parser on the start tag) into [KmlResolvedStyle].
 *
 * Field merge order matches backend `extract_style()`: PolyStyle, then LineStyle,
 * then IconStyle, so later groups overwrite overlapping keys (e.g. LineStyle
 * color opacity wins over PolyStyle `outline=0`).
 */
object KmlStyleExtractor {

    fun readStyle(parser: XmlPullParser): KmlResolvedStyle {
        check(parser.eventType == XmlPullParser.START_TAG && parser.name == "Style") {
            "KmlStyleExtractor: expected Style start tag"
        }
        var poly = KmlResolvedStyle.Empty
        var line = KmlResolvedStyle.Empty
        var icon = KmlResolvedStyle.Empty
        val depth = parser.depth
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "Style")) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "PolyStyle" -> poly = readPolyStyle(parser)
                    "LineStyle" -> line = readLineStyle(parser)
                    "IconStyle" -> icon = readIconStyle(parser)
                    else -> consumeSubtree(parser)
                }
            }
            event = parser.next()
        }
        return poly.overlay(line).overlay(icon)
    }

    private fun readIconStyle(parser: XmlPullParser): KmlResolvedStyle {
        var iconHref: String? = null
        val depth = parser.depth
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "IconStyle")) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "Icon" -> iconHref = readIconHref(parser) ?: iconHref
                    else -> consumeSubtree(parser)
                }
            }
            event = parser.next()
        }
        return KmlResolvedStyle(iconHref = iconHref)
    }

    private fun readIconHref(parser: XmlPullParser): String? {
        var href: String? = null
        val depth = parser.depth
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "Icon")) {
            if (event == XmlPullParser.START_TAG && parser.name == "href") {
                href = parser.nextText().trim().takeIf { it.isNotEmpty() }
            } else if (event == XmlPullParser.START_TAG) {
                consumeSubtree(parser)
            }
            event = parser.next()
        }
        return href
    }

    private fun readLineStyle(parser: XmlPullParser): KmlResolvedStyle {
        var strokeColor: String? = null
        var strokeOpacity: Double? = null
        var strokeWidth: Double? = null
        val depth = parser.depth
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "LineStyle")) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "color" -> {
                        val parsed = KmlColors.parse(parser.nextText())
                        strokeColor = parsed?.hexRgb
                        strokeOpacity = parsed?.opacity
                    }
                    "width" -> {
                        val width = parser.nextText().trim().toDoubleOrNull()
                        // Backend `num_prop` skips 0 the same way JS treats 0 as falsy.
                        if (width != null && width != 0.0) strokeWidth = width
                    }
                    else -> consumeSubtree(parser)
                }
            }
            event = parser.next()
        }
        return KmlResolvedStyle(
            strokeColor = strokeColor,
            strokeOpacity = strokeOpacity,
            strokeWidth = strokeWidth,
        )
    }

    private fun readPolyStyle(parser: XmlPullParser): KmlResolvedStyle {
        var fillColor: String? = null
        var colorOpacity: Double? = null
        var fillFlagZero = false
        var outlineZero = false
        val depth = parser.depth
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "PolyStyle")) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "color" -> {
                        val parsed = KmlColors.parse(parser.nextText())
                        fillColor = parsed?.hexRgb
                        colorOpacity = parsed?.opacity
                    }
                    "fill" -> {
                        fillFlagZero = parser.nextText().trim() == "0"
                    }
                    "outline" -> {
                        outlineZero = parser.nextText().trim() == "0"
                    }
                    else -> consumeSubtree(parser)
                }
            }
            event = parser.next()
        }
        return KmlResolvedStyle(
            fillColor = fillColor,
            fillOpacity = if (fillFlagZero) 0.0 else colorOpacity,
            strokeOpacity = if (outlineZero) 0.0 else null,
        )
    }

    private fun consumeSubtree(parser: XmlPullParser) {
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
