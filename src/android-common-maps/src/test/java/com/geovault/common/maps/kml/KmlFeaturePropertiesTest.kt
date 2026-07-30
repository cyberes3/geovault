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
}
