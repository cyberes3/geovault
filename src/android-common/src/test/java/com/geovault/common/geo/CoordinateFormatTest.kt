package com.geovault.common.geo

import org.junit.Assert.assertEquals
import org.junit.Test

class CoordinateFormatTest {
    @Test
    fun namedPrecisionsFormatLatLonWithUsDecimals() {
        val point = Wgs84Point(12.34567891, -56.78901234)
        assertEquals("12.3457, -56.7890", CoordinateFormat.DECIMAL_4.formatLatLon(point))
        assertEquals("12.345679, -56.789012", CoordinateFormat.DECIMAL_6.formatLatLon(point))
        assertEquals("12.34567891, -56.78901234", CoordinateFormat.DECIMAL_8.formatLatLon(point))
    }

    @Test
    fun compactFormatOmitsSpaceAfterComma() {
        assertEquals("1.00000000,2.00000000", CoordinateFormat.DECIMAL_8.formatLatLonCompact(1.0, 2.0))
    }
}
