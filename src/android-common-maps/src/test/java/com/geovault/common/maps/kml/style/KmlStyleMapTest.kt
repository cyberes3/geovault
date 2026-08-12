package com.geovault.common.maps.kml.style

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class KmlStyleMapTest {

    @Test
    fun googleEarthCascadingStyle_indexesAnonymousStyleByKmlId() {
        val kml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <kml xmlns="http://www.opengis.net/kml/2.2"
                 xmlns:gx="http://www.google.com/kml/ext/2.2"
                 xmlns:kml="http://www.opengis.net/kml/2.2">
              <Document>
                <gx:CascadingStyle kml:id="__managed_style_abc_normal">
                  <styleUrl>https://earth.google.com/balloon_components/base/1.1.0.0/none_template.kml#main</styleUrl>
                  <Style>
                    <IconStyle>
                      <Icon>
                        <href>https://earth.google.com/earth/document/icon?color=fbc02d&amp;id=2000&amp;scale=4</href>
                      </Icon>
                    </IconStyle>
                    <LineStyle>
                      <color>ff2dc0fb</color>
                    </LineStyle>
                  </Style>
                </gx:CascadingStyle>
                <StyleMap id="__managed_style_abc">
                  <Pair>
                    <key>normal</key>
                    <styleUrl>#__managed_style_abc_normal</styleUrl>
                  </Pair>
                  <Pair>
                    <key>highlight</key>
                    <styleUrl>#__managed_style_abc_highlight</styleUrl>
                  </Pair>
                </StyleMap>
              </Document>
            </kml>
        """.trimIndent()

        val map = KmlStyleMap.parse(kml)
        val viaMap = map.resolve("#__managed_style_abc")
        val viaCascading = map.resolve("#__managed_style_abc_normal")
        assertEquals(
            "https://earth.google.com/earth/document/icon?color=fbc02d&id=2000&scale=4",
            viaMap.iconHref,
        )
        assertEquals("#fbc02d", viaMap.strokeColor)
        assertEquals(viaMap, viaCascading)
    }

    @Test
    fun styleMap_usesFirstStyleUrl_likeTogeojsonVal1() {
        val kml = """
            <kml xmlns="http://www.opengis.net/kml/2.2">
              <Document>
                <Style id="red">
                  <IconStyle><Icon><href>https://example.com/red.png</href></Icon></IconStyle>
                </Style>
                <Style id="blue">
                  <IconStyle><Icon><href>https://example.com/blue.png</href></Icon></IconStyle>
                </Style>
                <StyleMap id="pin">
                  <Pair><key>highlight</key><styleUrl>#blue</styleUrl></Pair>
                  <Pair><key>normal</key><styleUrl>#red</styleUrl></Pair>
                </StyleMap>
              </Document>
            </kml>
        """.trimIndent()

        val style = KmlStyleMap.parse(kml).resolve("#pin")
        assertEquals("https://example.com/blue.png", style.iconHref)
    }

    @Test
    fun sixDigitLineColor_andPolyFillZero() {
        val kml = """
            <kml xmlns="http://www.opengis.net/kml/2.2">
              <Style id="s">
                <LineStyle><color>00ff00</color><width>2.5</width></LineStyle>
                <PolyStyle><color>ff0000ff</color><fill>0</fill></PolyStyle>
              </Style>
            </kml>
        """.trimIndent()

        val style = KmlStyleMap.parse(kml).resolve("s")
        assertEquals("#00ff00", style.strokeColor)
        assertEquals(2.5, style.strokeWidth!!, 0.0)
        assertEquals("#ff0000", style.fillColor)
        assertEquals(0.0, style.fillOpacity!!, 0.0)
    }

    @Test
    fun missingStyleUrl_isEmpty() {
        val style = KmlStyleMap.parse("<kml xmlns=\"http://www.opengis.net/kml/2.2\"/>").resolve("#missing")
        assertNull(style.iconHref)
        assertEquals(KmlResolvedStyle.Empty, style)
    }
}
