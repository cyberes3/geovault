package com.geovault.common.maps.kml.style

import android.util.Xml
import java.io.StringReader
import org.xmlpull.v1.XmlPullParser

/**
 * Document-scoped KML style index, matching backend
 * `geo_lib.togeojson.kml.style_map.StyleMap.build`.
 *
 * 1. Every `<Style>` is keyed by its `id`, or — when the Style is anonymous —
 *    by the parent `gx:CascadingStyle` `kml:id`.
 * 2. Each `<StyleMap>` copies the first descendant `styleUrl` that already
 *    resolves onto the StyleMap's own `id` (togeojson `val1` / first match).
 *
 * Built from the whole document before placemarks are interpreted, so a
 * Placemark that appears above its Style still resolves.
 */
class KmlStyleMap(
    private val styles: Map<String, KmlResolvedStyle>,
) {

    fun resolve(styleUrl: String?): KmlResolvedStyle {
        val id = normalizeId(styleUrl) ?: return KmlResolvedStyle.Empty
        return styles[id] ?: KmlResolvedStyle.Empty
    }

    companion object {
        private const val KML_NAMESPACE = "http://www.opengis.net/kml/2.2"

        fun parse(kmlText: String): KmlStyleMap {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(StringReader(kmlText))

            val styles = mutableMapOf<String, KmlResolvedStyle>()
            val styleMaps = mutableListOf<Pair<String, String>>()
            val cascadingIds = ArrayDeque<String?>()

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "CascadingStyle" -> cascadingIds.addLast(readId(parser))
                        "Style" -> {
                            val id = readId(parser) ?: cascadingIds.lastOrNull() ?: ""
                            styles[id] = KmlStyleExtractor.readStyle(parser)
                        }
                        "StyleMap" -> {
                            val id = readId(parser)
                            val ref = readFirstStyleUrl(parser)
                            if (id != null && ref != null) {
                                styleMaps += id to ref
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> if (parser.name == "CascadingStyle" && cascadingIds.isNotEmpty()) {
                        cascadingIds.removeLast()
                    }
                }
                event = parser.next()
            }

            for ((mapId, ref) in styleMaps) {
                val resolved = styles[ref] ?: continue
                styles[mapId] = resolved
            }
            return KmlStyleMap(styles)
        }

        private fun readId(parser: XmlPullParser): String? {
            val raw = parser.getAttributeValue(null, "id")
                ?: parser.getAttributeValue(KML_NAMESPACE, "id")
                ?: parser.getAttributeValue("http://www.w3.org/XML/1998/namespace", "id")
            return normalizeId(raw)
        }

        /**
         * First descendant `styleUrl` under the current start tag (StyleMap or Pair
         * walk). Consumes the current element. Same as backend `val_one(..., "styleUrl")`.
         */
        private fun readFirstStyleUrl(parser: XmlPullParser): String? {
            val tag = parser.name
            val depth = parser.depth
            var first: String? = null
            var event = parser.next()
            while (!(event == XmlPullParser.END_TAG && parser.depth == depth && parser.name == tag)) {
                if (event == XmlPullParser.START_TAG && parser.name == "styleUrl" && first == null) {
                    first = normalizeId(parser.nextText())
                }
                event = parser.next()
            }
            return first
        }

        internal fun normalizeId(styleUrl: String?): String? {
            if (styleUrl.isNullOrBlank()) return null
            return styleUrl.trim().removePrefix("#").takeIf { it.isNotEmpty() }
        }
    }
}
