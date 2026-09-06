package com.geovault.common.geo.external

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoVaultExternalMapLauncherTest {

    @Test
    fun buildMapsUrl_usesMapsGoogleHostWithQParam() {
        val url = GeoVaultExternalMapLauncher.buildMapsUrl(latitude = 38.8977, longitude = -77.0365)
        assertTrue(url.startsWith("https://maps.google.com/"))
        assertTrue(url.contains("q=38.89770000"))
        assertTrue(url.contains("-77.03650000"))
        assertFalse(url.contains("/search/"))
        assertFalse(url.contains("api=1"))
        assertFalse(url.contains("geo:"))
    }

    @Test
    fun buildQueryValue_includesLabelInParenthesesWhenPresent() {
        val query = GeoVaultExternalMapLauncher.buildQueryValue(
            latitude = 1.0,
            longitude = 2.0,
            label = "Test Place",
        )
        assertEquals("1.00000000,2.00000000(Test Place)", query)
    }

    @Test
    fun buildQueryValue_omitsLabelWhenBlank() {
        val query = GeoVaultExternalMapLauncher.buildQueryValue(
            latitude = 1.0,
            longitude = 2.0,
            label = "   ",
        )
        assertEquals("1.00000000,2.00000000", query)
    }

    @Test
    fun buildMapsUrl_includesLabelInQueryWhenPresent() {
        val url = GeoVaultExternalMapLauncher.buildMapsUrl(
            latitude = 38.8977,
            longitude = -77.0365,
            label = "City Hall",
        )
        assertTrue(url.contains("City+Hall") || url.contains("City%20Hall"))
    }
}
