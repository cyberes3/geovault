package com.geovault.common

import org.junit.Assert.assertEquals
import org.junit.Test

class NaturalSortTest {

    private val order = NaturalSort.naturalOrder()

    @Test
    fun numericChunksOrderByValue() {
        assertEquals(
            listOf("1", "2", "3", "10", "20"),
            listOf("1", "10", "2", "20", "3").sortedWith(order)
        )
    }

    @Test
    fun mixedPrefixAndNumbers() {
        assertEquals(
            listOf("a1", "a2", "a10"),
            listOf("a2", "a10", "a1").sortedWith(order)
        )
    }

    @Test
    fun purelyTextLexicographic() {
        assertEquals(
            listOf("apple", "banana", "cherry"),
            listOf("cherry", "apple", "banana").sortedWith(order)
        )
    }

    @Test
    fun naturalOrderByUsesSelector() {
        data class Row(val name: String)
        val rows = listOf(Row("b10"), Row("b2"), Row("b1"))
        val sorted = rows.sortedWith(NaturalSort.naturalOrderBy { it.name.lowercase() })
        assertEquals(listOf("b1", "b2", "b10"), sorted.map { it.name })
    }
}
