package com.geovault.common.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests [filterItemsByLabel] — the search bar on [GeoVaultMultiSelectDialog] with
 * `searchable = true` (same label-matching idea as [filterSelectOptions] on single-select).
 */
class MultiSelectDialogFilterTest {

    private data class R(val k: String, val label: String)

    private val rows = listOf(
        R("AL", "Alabama"),
        R("CA", "California"),
        R("TX", "Texas"),
    )

    private val label: (R) -> String = { it.label + " (" + it.k + ")" }

    @Test
    fun `blank query returns all rows unchanged`() {
        assertEquals(rows, filterItemsByLabel(rows, "", label))
        assertEquals(rows, filterItemsByLabel(rows, "   ", label))
    }

    @Test
    fun `filter is case-insensitive`() {
        val result = filterItemsByLabel(rows, "alabama", label)
        assertEquals(listOf("AL"), result.map { it.k })
    }

    @Test
    fun `filter matches substrings`() {
        val result = filterItemsByLabel(rows, "or", label)
        assertEquals(listOf("CA"), result.map { it.k })
    }

    @Test
    fun `query with leading or trailing whitespace is trimmed`() {
        val result = filterItemsByLabel(rows, "  California  ", label)
        assertEquals(listOf("CA"), result.map { it.k })
    }

    @Test
    fun `non-matching query yields an empty list`() {
        val result = filterItemsByLabel(rows, "nevada", label)
        assertEquals(emptyList<String>(), result.map { it.k })
    }

    @Test
    fun `empty list stays empty regardless of query`() {
        val empty = emptyList<R>()
        assertEquals(empty, filterItemsByLabel(empty, "", label))
        assertEquals(empty, filterItemsByLabel(empty, "anything", label))
    }
}
