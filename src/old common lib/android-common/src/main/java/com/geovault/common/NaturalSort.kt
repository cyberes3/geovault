package com.geovault.common

import java.math.BigInteger

/**
 * Human-friendly string ordering: numeric substrings compare by value so "2" sorts before "10".
 */
object NaturalSort {

    fun naturalOrder(): Comparator<String> = Comparator { a, b -> compareNatural(a, b) }

    fun <T> naturalOrderBy(selector: (T) -> String): Comparator<T> =
        Comparator { x, y -> compareNatural(selector(x), selector(y)) }

    private fun compareNatural(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val aDigit = a[i].isDigit()
            val bDigit = b[j].isDigit()
            if (aDigit && bDigit) {
                val aStart = i
                while (i < a.length && a[i].isDigit()) i++
                val bStart = j
                while (j < b.length && b[j].isDigit()) j++
                val asub = a.substring(aStart, i)
                val bsub = b.substring(bStart, j)
                val cmp = compareDigitSegments(asub, bsub)
                if (cmp != 0) return cmp
            } else if (!aDigit && !bDigit) {
                val aStart = i
                while (i < a.length && !a[i].isDigit()) i++
                val bStart = j
                while (j < b.length && !b[j].isDigit()) j++
                val cmp = a.substring(aStart, i).compareTo(b.substring(bStart, j))
                if (cmp != 0) return cmp
            } else {
                val aStart = i
                if (aDigit) while (i < a.length && a[i].isDigit()) i++
                else while (i < a.length && !a[i].isDigit()) i++
                val bStart = j
                if (bDigit) while (j < b.length && b[j].isDigit()) j++
                else while (j < b.length && !b[j].isDigit()) j++
                val cmp = a.substring(aStart, i).compareTo(b.substring(bStart, j))
                if (cmp != 0) return cmp
            }
        }
        return a.length.compareTo(b.length)
    }

    private fun compareDigitSegments(asub: String, bsub: String): Int {
        val an = asub.toBigIntegerOrNull() ?: BigInteger.ZERO
        val bn = bsub.toBigIntegerOrNull() ?: BigInteger.ZERO
        val cmp = an.compareTo(bn)
        if (cmp != 0) return cmp
        return asub.compareTo(bsub)
    }
}
