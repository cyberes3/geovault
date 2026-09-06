package com.geovault.common.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class DialogFilterTest {

    private data class R(val k: String, val label: String)

    private val rows = listOf(
        R("AL", "Alabama"),
        R("CA", "California"),
        R("TX", "Texas"),
    )
    private val label: (R) -> String = { it.label + " (" + it.k + ")" }

    private val options = listOf(
        GeoVaultSelectOption(value = 1, label = "Alabama East"),
        GeoVaultSelectOption(value = 2, label = "Alabama West"),
        GeoVaultSelectOption(value = 3, label = "California Zone 1"),
        GeoVaultSelectOption(value = 4, label = "California Zone 2"),
        GeoVaultSelectOption(value = 5, label = "Texas North Central"),
    )

    @Test
    fun `blank query returns items unchanged`() {
        assertEquals(rows, filterItemsByLabel(rows, "", label))
        assertEquals(rows, filterItemsByLabel(rows, "   ", label))
        assertEquals(options, filterItemsByLabel(options, "") { it.label })
        assertEquals(options, filterItemsByLabel(options, "   ") { it.label })
    }

    @Test
    fun `filter is case-insensitive and matches substrings`() {
        assertEquals(listOf("AL"), filterItemsByLabel(rows, "alabama", label).map { it.k })
        assertEquals(listOf(1, 2), filterItemsByLabel(options, "alabama") { it.label }.map { it.value })
        assertEquals(listOf("CA"), filterItemsByLabel(rows, "or", label).map { it.k })
        assertEquals(listOf(3, 4), filterItemsByLabel(options, "zone") { it.label }.map { it.value })
    }

    @Test
    fun `query with leading or trailing whitespace is trimmed`() {
        assertEquals(listOf("CA"), filterItemsByLabel(rows, "  California  ", label).map { it.k })
        assertEquals(
            listOf(3, 4),
            filterItemsByLabel(options, "  California  ") { it.label }.map { it.value },
        )
    }

    @Test
    fun `non-matching query yields an empty list`() {
        assertEquals(emptyList<String>(), filterItemsByLabel(rows, "nevada", label).map { it.k })
        assertEquals(emptyList<Int>(), filterItemsByLabel(options, "nevada") { it.label }.map { it.value })
    }

    @Test
    fun `empty list stays empty regardless of query`() {
        val emptyRows = emptyList<R>()
        assertEquals(emptyRows, filterItemsByLabel(emptyRows, "", label))
        assertEquals(emptyRows, filterItemsByLabel(emptyRows, "anything", label))
        val emptyOptions = emptyList<GeoVaultSelectOption<Int>>()
        assertEquals(emptyOptions, filterItemsByLabel(emptyOptions, "") { it.label })
        assertEquals(emptyOptions, filterItemsByLabel(emptyOptions, "anything") { it.label })
    }
}
