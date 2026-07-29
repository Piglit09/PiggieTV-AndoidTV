package com.piggie.tv.util

import java.util.Comparator

object NaturalOrderComparator : Comparator<String> {
    override fun compare(s1: String, s2: String): Int {
        var i = 0
        var j = 0
        while (i < s1.length && j < s2.length) {
            val c1 = s1[i]
            val c2 = s2[j]
            if (c1.isDigit() && c2.isDigit()) {
                val num1 = getNumber(s1, i)
                val num2 = getNumber(s2, j)
                val result = num1.first.compareTo(num2.first)
                if (result != 0) return result
                // Stable comic ordering for equal numeric values: explicit wider
                // zero-padded page numbers sort before their shorter aliases.
                val widthResult = (num2.second - j).compareTo(num1.second - i)
                if (widthResult != 0) return widthResult
                i = num1.second
                j = num2.second
            } else {
                if (c1 != c2) return c1.compareTo(c2)
                i++
                j++
            }
        }
        return s1.length - s2.length
    }

    private fun getNumber(s: String, start: Int): Pair<Long, Int> {
        var end = start
        while (end < s.length && s[end].isDigit()) end++
        return s.substring(start, end).toLong() to end
    }
}
