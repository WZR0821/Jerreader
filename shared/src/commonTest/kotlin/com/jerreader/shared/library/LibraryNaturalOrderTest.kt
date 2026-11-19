package com.jerreader.shared.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibraryNaturalOrderTest {
    private fun sorted(vararg titles: String): List<String> =
        titles.toList().sortedWith(LibraryNaturalOrder)

    @Test
    fun volumesSortByTheirNumber() {
        assertEquals(
            listOf("第1卷", "第2卷", "第10卷", "第11卷", "第100卷"),
            sorted("第10卷", "第100卷", "第2卷", "第11卷", "第1卷")
        )
        assertEquals(
            listOf("Vol. 2", "Vol. 9", "Vol. 10"),
            sorted("Vol. 10", "Vol. 9", "Vol. 2")
        )
    }

    @Test
    fun letterCaseIsIgnored() {
        assertEquals(listOf("alpha", "Beta", "gamma"), sorted("gamma", "Beta", "alpha"))
        assertEquals(0, LibraryNaturalOrder.compare("Kokoro", "kokoro"))
    }

    @Test
    fun fullWidthDigitsCountAsNumbers() {
        assertEquals(
            listOf("巻２", "巻３", "巻１０"),
            sorted("巻１０", "巻３", "巻２")
        )
        assertEquals(0, LibraryNaturalOrder.compare("巻１０", "巻10"))
    }

    @Test
    fun leadingZerosDoNotChangeTheNumber() {
        assertEquals(listOf("第2卷", "第02卷", "第3卷"), sorted("第3卷", "第02卷", "第2卷"))
        assertTrue(LibraryNaturalOrder.compare("第02卷", "第10卷") < 0)
    }

    @Test
    fun aTitleSortsBeforeItsContinuation() {
        assertEquals(listOf("雪国", "雪国 上", "雪国 下"), sorted("雪国 下", "雪国", "雪国 上"))
        assertEquals(listOf("", "a"), sorted("a", ""))
    }

    @Test
    fun numbersTooLongForAnIntegerStillCompare() {
        val small = "编号 99999999999999999999"
        val large = "编号 100000000000000000000"
        assertTrue(LibraryNaturalOrder.compare(small, large) < 0)
        assertTrue(LibraryNaturalOrder.compare(large, small) > 0)
    }
}
