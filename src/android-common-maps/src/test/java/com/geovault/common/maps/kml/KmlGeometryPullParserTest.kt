package com.geovault.common.maps.kml

import android.util.Xml
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class KmlGeometryPullParserTest {

    private val parser = KmlGeometryPullParser()

    private fun newParser(xml: String): XmlPullParser {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        p.setInput(StringReader(xml.trim()))
        return p
    }

    private fun advanceToStart(parser: XmlPullParser, localName: String) {
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == localName) return
        }
        error("missing start tag: $localName")
    }

    @Test
    fun polygon_outerBoundaryIs_linearRing_triangle() {
        val kml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <kml xmlns="http://www.opengis.net/kml/2.2">
              <Polygon>
                <outerBoundaryIs>
                  <LinearRing>
                    <coordinates>-1,0 0,1 1,0 -1,0</coordinates>
                  </LinearRing>
                </outerBoundaryIs>
              </Polygon>
            </kml>
        """.trimIndent()
        val p = newParser(kml)
        advanceToStart(p, "Polygon")
        val out = parser.parseGeometrySubtree(p)
        assertEquals(1, out.size)
        val poly = out[0] as ParsedKmlGeometry.Polygon
        assertEquals(1, poly.rings.size)
        assertEquals(4, poly.rings[0].size)
        assertEquals(-1.0, poly.rings[0][0].longitude, 0.0)
        assertEquals(0.0, poly.rings[0][0].latitude, 0.0)
    }

    @Test
    fun polygon_outer_and_inner_hole() {
        val kml = """
            <kml xmlns="http://www.opengis.net/kml/2.2">
              <Polygon>
                <outerBoundaryIs>
                  <LinearRing>
                    <coordinates>0,0 4,0 4,4 0,4 0,0</coordinates>
                  </LinearRing>
                </outerBoundaryIs>
                <innerBoundaryIs>
                  <LinearRing>
                    <coordinates>1,1 2,1 2,2 1,2 1,1</coordinates>
                  </LinearRing>
                </innerBoundaryIs>
              </Polygon>
            </kml>
        """.trimIndent()
        val p = newParser(kml)
        advanceToStart(p, "Polygon")
        val poly = parser.parseGeometrySubtree(p).single() as ParsedKmlGeometry.Polygon
        assertEquals(2, poly.rings.size)
        assertEquals(5, poly.rings[0].size)
        assertEquals(5, poly.rings[1].size)
    }

    @Test
    fun polygon_legacy_direct_coordinates_under_polygon() {
        val kml = """
            <kml xmlns="http://www.opengis.net/kml/2.2">
              <Polygon>
                <coordinates>0,0 1,0 1,1 0,0</coordinates>
              </Polygon>
            </kml>
        """.trimIndent()
        val p = newParser(kml)
        advanceToStart(p, "Polygon")
        val poly = parser.parseGeometrySubtree(p).single() as ParsedKmlGeometry.Polygon
        assertEquals(1, poly.rings.size)
        assertEquals(4, poly.rings[0].size)
    }

    @Test
    fun multiGeometry_two_polygons() {
        val kml = """
            <kml xmlns="http://www.opengis.net/kml/2.2">
              <MultiGeometry>
                <Polygon>
                  <outerBoundaryIs><LinearRing>
                    <coordinates>0,0 1,0 0,1 0,0</coordinates>
                  </LinearRing></outerBoundaryIs>
                </Polygon>
                <Polygon>
                  <outerBoundaryIs><LinearRing>
                    <coordinates>2,2 3,2 2,3 2,2</coordinates>
                  </LinearRing></outerBoundaryIs>
                </Polygon>
              </MultiGeometry>
            </kml>
        """.trimIndent()
        val p = newParser(kml)
        advanceToStart(p, "MultiGeometry")
        val out = parser.parseGeometrySubtree(p)
        assertEquals(2, out.size)
        assertTrue(out.all { it is ParsedKmlGeometry.Polygon })
    }

    @Test
    fun point_coordinates() {
        val kml = """
            <kml xmlns="http://www.opengis.net/kml/2.2">
              <Point><coordinates>-71.06,42.36</coordinates></Point>
            </kml>
        """.trimIndent()
        val p = newParser(kml)
        advanceToStart(p, "Point")
        val pt = parser.parseGeometrySubtree(p).single() as ParsedKmlGeometry.Point
        assertEquals(-71.06, pt.position.longitude, 0.0)
        assertEquals(42.36, pt.position.latitude, 0.0)
    }

    @Test
    fun lineString_two_vertices() {
        val kml = """
            <kml xmlns="http://www.opengis.net/kml/2.2">
              <LineString><coordinates>0,0.1 1,1.1 2,2.1</coordinates></LineString>
            </kml>
        """.trimIndent()
        val p = newParser(kml)
        advanceToStart(p, "LineString")
        val line = parser.parseGeometrySubtree(p).single() as ParsedKmlGeometry.LineString
        assertEquals(3, line.positions.size)
    }
}
