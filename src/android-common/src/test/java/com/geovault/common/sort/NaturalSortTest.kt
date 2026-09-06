package com.geovault.common.sort

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class NaturalSortTest {
    @Test
    fun `naturalOrderBy sorts numeric segments naturally`() {
        val values = listOf("file10", "file2", "file1")
        val sorted = values.sortedWith(NaturalSort.naturalOrderBy { it })
        assertEquals(listOf("file1", "file2", "file10"), sorted)
    }

    @Test
    fun byName_isCaseInsensitiveForTheGivenLocale() {
        val values = listOf("b2", "A10", "a2")
        val sorted = values.sortedWith(NaturalSort.byName(Locale.US))
        assertEquals(listOf("a2", "A10", "b2"), sorted)
    }
}
