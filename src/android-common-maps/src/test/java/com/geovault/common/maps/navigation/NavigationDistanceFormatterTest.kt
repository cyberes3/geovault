package com.geovault.common.maps.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationDistanceFormatterTest {

    @Test
    fun `format title with distance joins lines with newline`() {
        val result = NavigationDistanceFormatter.format("Point A", 10.0)
        assertEquals("Point A\n33 ft", result)
    }

    @Test
    fun `format trims whitespace from title`() {
        val result = NavigationDistanceFormatter.format("  Point A  ", 0.0)
        assertEquals("Point A\n0 ft", result)
    }

    @Test
    fun `format with blank title drops the title line`() {
        assertEquals("33 ft", NavigationDistanceFormatter.format("   ", 10.0))
    }

    @Test
    fun `format with null title returns distance only`() {
        assertEquals("33 ft", NavigationDistanceFormatter.format(null, 10.0))
    }

    @Test
    fun `format with null distance returns title only`() {
        assertEquals("Point A", NavigationDistanceFormatter.format("Point A", null))
    }

    @Test
    fun `format with null and null returns empty string`() {
        assertEquals("", NavigationDistanceFormatter.format(null, null))
    }

    @Test
    fun `formatDistance matches legacy imperial output`() {
        assertEquals("0 ft", NavigationDistanceFormatter.formatDistance(0.0))
        assertEquals("3 ft", NavigationDistanceFormatter.formatDistance(1.0))
        assertEquals("328 ft", NavigationDistanceFormatter.formatDistance(100.0))
        assertEquals("5280 ft", NavigationDistanceFormatter.formatDistance(1609.344))
    }

    @Test
    fun `formatDistance rounds to nearest whole foot`() {
        assertEquals("0 ft", NavigationDistanceFormatter.formatDistance(0.15))
        assertEquals("1 ft", NavigationDistanceFormatter.formatDistance(0.2))
    }
}
