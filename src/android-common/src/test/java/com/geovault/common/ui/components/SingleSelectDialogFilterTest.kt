package com.geovault.common.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the pure filter predicate backing [GeoVaultSingleSelectDialog]'s search bar.
 *
 * The Compose UI is out of scope for JVM unit tests, but the filtering rule is the one piece
 * of the dialog with non-trivial logic, so we keep it testable in isolation via the top-level
 * [filterSelectOptions] helper.
 */
class SingleSelectDialogFilterTest {

    private val options = listOf(
        GeoVaultSelectOption(value = 1, label = "Alabama East"),
        GeoVaultSelectOption(value = 2, label = "Alabama West"),
        GeoVaultSelectOption(value = 3, label = "California Zone 1"),
        GeoVaultSelectOption(value = 4, label = "California Zone 2"),
        GeoVaultSelectOption(value = 5, label = "Texas North Central"),
    )

    @Test
    fun `blank query returns all options unchanged`() {
        assertEquals(options, filterSelectOptions(options, ""))
        assertEquals(options, filterSelectOptions(options, "   "))
    }

    @Test
    fun `filter is case-insensitive and matches substrings`() {
        val result = filterSelectOptions(options, "alabama")
        assertEquals(listOf(1, 2), result.map { it.value })
    }

    @Test
    fun `filter matches fragments inside labels`() {
        val result = filterSelectOptions(options, "zone")
        assertEquals(listOf(3, 4), result.map { it.value })
    }

    @Test
    fun `query with leading or trailing whitespace is trimmed before matching`() {
        val result = filterSelectOptions(options, "  California  ")
        assertEquals(listOf(3, 4), result.map { it.value })
    }

    @Test
    fun `non-matching query yields an empty list`() {
        val result = filterSelectOptions(options, "nevada")
        assertEquals(emptyList<Int>(), result.map { it.value })
    }

    @Test
    fun `empty options always produce empty result regardless of query`() {
        val empty = emptyList<GeoVaultSelectOption<Int>>()
        assertEquals(empty, filterSelectOptions(empty, ""))
        assertEquals(empty, filterSelectOptions(empty, "anything"))
    }
}
