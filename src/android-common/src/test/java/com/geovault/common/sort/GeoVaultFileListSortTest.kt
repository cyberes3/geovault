package com.geovault.common.sort

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoVaultFileListSortTest {
    private data class Row(val name: String, val modifiedAt: Long)

    @Test
    fun `NAME_A_TO_Z uses natural order`() {
        val rows = listOf(
            Row("file10", 0L),
            Row("file2", 0L),
            Row("file1", 0L),
        )
        val sorted = rows.sortedWith(
            GeoVaultFileListSort.comparator(GeoVaultFileListSortMode.NAME_A_TO_Z, { it.name }, { it.modifiedAt }),
        )
        assertEquals(listOf("file1", "file2", "file10"), sorted.map { it.name })
    }

    @Test
    fun `NAME_Z_TO_A reverses natural order`() {
        val rows = listOf(Row("file1", 0L), Row("file2", 0L), Row("file10", 0L))
        val sorted = rows.sortedWith(
            GeoVaultFileListSort.comparator(GeoVaultFileListSortMode.NAME_Z_TO_A, { it.name }, { it.modifiedAt }),
        )
        assertEquals(listOf("file10", "file2", "file1"), sorted.map { it.name })
    }

    @Test
    fun `MODIFIED_NEWEST sorts by timestamp descending with name tie-break`() {
        val rows = listOf(
            Row("b", 100L),
            Row("a", 200L),
            Row("c", 200L),
        )
        val sorted = rows.sortedWith(
            GeoVaultFileListSort.comparator(GeoVaultFileListSortMode.MODIFIED_NEWEST, { it.name }, { it.modifiedAt }),
        )
        assertEquals(listOf("a", "c", "b"), sorted.map { it.name })
    }

    @Test
    fun `MODIFIED_OLDEST sorts by timestamp ascending with name tie-break`() {
        val rows = listOf(
            Row("b", 200L),
            Row("c", 200L),
            Row("a", 100L),
        )
        val sorted = rows.sortedWith(
            GeoVaultFileListSort.comparator(GeoVaultFileListSortMode.MODIFIED_OLDEST, { it.name }, { it.modifiedAt }),
        )
        assertEquals(listOf("a", "b", "c"), sorted.map { it.name })
    }
}
