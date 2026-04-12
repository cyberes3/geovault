package com.geovault.common

import java.math.BigInteger

object NaturalSort {
    fun <T> naturalOrderBy(selector: (T) -> String): Comparator<T> =
        Comparator { left, right -> compareNatural(selector(left), selector(right)) }

    private fun compareNatural(left: String, right: String): Int {
        var i = 0
        var j = 0
        while (i < left.length && j < right.length) {
            val leftDigit = left[i].isDigit()
            val rightDigit = right[j].isDigit()
            if (leftDigit && rightDigit) {
                val iStart = i
                while (i < left.length && left[i].isDigit()) i++
                val jStart = j
                while (j < right.length && right[j].isDigit()) j++
                val numCmp = compareDigitSegments(left.substring(iStart, i), right.substring(jStart, j))
                if (numCmp != 0) return numCmp
            } else {
                val iStart = i
                while (i < left.length && !left[i].isDigit()) i++
                val jStart = j
                while (j < right.length && !right[j].isDigit()) j++
                val txtCmp = left.substring(iStart, i).compareTo(right.substring(jStart, j))
                if (txtCmp != 0) return txtCmp
            }
        }
        return left.length.compareTo(right.length)
    }

    private fun compareDigitSegments(left: String, right: String): Int {
        val leftNumber = left.toBigIntegerOrNull() ?: BigInteger.ZERO
        val rightNumber = right.toBigIntegerOrNull() ?: BigInteger.ZERO
        val cmp = leftNumber.compareTo(rightNumber)
        return if (cmp != 0) cmp else left.compareTo(right)
    }
}
