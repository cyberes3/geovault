package com.geovault.common

import org.junit.Assert.assertEquals
import org.junit.Test

class NaturalSortTest {
    @Test
    fun `naturalOrderBy sorts numeric segments naturally`() {
        val values = listOf("file10", "file2", "file1")
        val sorted = values.sortedWith(NaturalSort.naturalOrderBy { it })
        assertEquals(listOf("file1", "file2", "file10"), sorted)
    }
}
