package com.geovault.common.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeoVaultServerUrlTest {
    @Test
    fun parse_addsHttpsAndStripsSlash() {
        val parsed = GeoVaultServerUrl.parse("example.com/")
        assertEquals("https://example.com", parsed?.value)
        assertEquals("https://example.com/", parsed?.asRetrofitBase())
        assertEquals("https://example.com/api/health/", parsed?.resolve("api/health/"))
    }

    @Test
    fun parse_blank_isNull() {
        assertNull(GeoVaultServerUrl.parse("   "))
    }

    @Test
    fun parse_keepsHttp() {
        assertEquals("http://10.0.0.1:8000", GeoVaultServerUrl.parse("http://10.0.0.1:8000/")?.value)
    }
}
