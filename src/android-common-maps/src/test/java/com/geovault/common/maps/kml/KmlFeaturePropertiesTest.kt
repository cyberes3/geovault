package com.geovault.common.maps.kml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KmlFeaturePropertiesTest {

    @Test
    fun code_readsTrimmedValue() {
        assertEquals("GRID-A7", KmlFeatureProperties.code("""{"code":"GRID-A7","name":"MH-12"}"""))
        assertEquals("P-99", KmlFeatureProperties.code("""{"code":"  P-99  "}"""))
    }

    @Test
    fun code_blankOrMissingIsNull() {
        assertNull(KmlFeatureProperties.code("""{"code":""}"""))
        assertNull(KmlFeatureProperties.code("""{"code":"   "}"""))
        assertNull(KmlFeatureProperties.code("""{"name":"only"}"""))
        assertNull(KmlFeatureProperties.code(null))
        assertNull(KmlFeatureProperties.code(""))
        assertNull(KmlFeatureProperties.code("not-json"))
    }

    @Test
    fun iconHrefAndColor_readImportedFields() {
        val json = """{"imported_icon_href":"files/pin.png","imported_icon_color":"#f00000"}"""
        assertEquals("files/pin.png", KmlFeatureProperties.iconHref(json))
        assertEquals("#f00000", KmlFeatureProperties.iconColor(json))
        assertNull(KmlFeatureProperties.iconColor("""{"imported_marker_color":"#FF0000"}"""))
        assertNull(KmlFeatureProperties.iconHref("""{"name":"x"}"""))
    }
}
