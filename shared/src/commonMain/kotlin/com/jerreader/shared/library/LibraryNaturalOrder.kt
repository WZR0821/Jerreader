package com.jerreader.shared.library

/**
 * Orders titles and authors the way a shelf is read: letter case is ignored
 * and a run of digits counts as the number it spells, so 第2卷 comes before
 * 第10卷. Sorting the lower-cased strings character by character put volume
 * 10 ahead of volume 2, which iOS (`localizedStandardCompare`) never did.
 * Full-width digits count as their half-width values.
 */
object LibraryNaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        val left = a.lowercase()
        val right = b.lowercase()
        var i = 0
        var j = 0
        while (i < left.length && j < right.length) {
            if (left[i].isDigit() && right[j].isDigit()) {
                val leftEnd = digitRunEnd(left, i)
                val rightEnd = digitRunEnd(right, j)
                val x = number(left, i, leftEnd)
                val y = number(right, j, rightEnd)
                // Without leading zeros the longer number is the larger one.
                if (x.length != y.length) return x.length - y.length
                val byValue = x.compareTo(y)
                if (byValue != 0) return byValue
                i = leftEnd
                j = rightEnd
            } else {
                if (left[i] != right[j]) return left[i].compareTo(right[j])
                i++
                j++
            }
        }
        val remaining = (left.length - i) - (right.length - j)
        // 第02卷 and 第2卷 are the same number; the shorter spelling goes first.
        return if (remaining != 0) remaining else left.length - right.length
    }

    private fun digitRunEnd(text: String, start: Int): Int {
        var end = start
        while (end < text.length && text[end].isDigit()) end++
        return end
    }

    private fun number(text: String, start: Int, end: Int): String {
        val digits = StringBuilder(end - start)
        for (index in start until end) {
            digits.append(text[index].digitToIntOrNull() ?: 0)
        }
        return digits.trimStart('0').toString().ifEmpty { "0" }
    }
}
