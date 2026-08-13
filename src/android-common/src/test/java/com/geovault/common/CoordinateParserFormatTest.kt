package com.geovault.common

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class CoordinateParserFormatTest {
    private lateinit var originalLocale: Locale

    @Before
    fun saveLocale() {
        originalLocale = Locale.getDefault()
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun formatLatLon_usesUsDecimalsWhenDefaultLocaleIsFrance() {
        Locale.setDefault(Locale.FRANCE)
        assertEquals("12.340000, 56.780000", CoordinateParser.formatLatLon(12.34, 56.78))
    }
}
