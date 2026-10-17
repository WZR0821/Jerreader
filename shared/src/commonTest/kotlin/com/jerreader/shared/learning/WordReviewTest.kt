package com.jerreader.shared.learning

import kotlin.test.*

class WordReviewTest {
    @Test fun knownUsesIncreasingIntervalsAndCaps() {
        var state = WordReviewState()
        listOf(1, 3, 7, 14, 30, 60, 60).forEach { days ->
            state = state.grade(ReviewGrade.KNOWN, 1_000)
            assertEquals(1_000 + days * 86_400_000L, state.dueAt)
        }
        assertEquals(7, state.answers.size)
        assertTrue(state.answers.all { it.known })
    }
    @Test fun lapseAndFuzzyDoNotGraduateWord() {
        val mature = WordReviewState(step = 4, paused = true)
        val lapse = mature.grade(ReviewGrade.UNKNOWN, 50_000)
        assertEquals(0, lapse.step)
        assertEquals(650_000, lapse.dueAt)
        assertTrue(lapse.paused)
        assertFalse(lapse.answers.single().known)
        val fuzzy = mature.grade(ReviewGrade.FUZZY, 0)
        assertEquals(3, fuzzy.step)
        assertEquals(86_400_000, fuzzy.dueAt)
    }
    @Test fun legacyAndMalformedStepsStaySafe() {
        assertEquals(0, WordReviewState().dueAt)
        assertEquals(86_400_000, WordReviewState(step = -100).grade(ReviewGrade.KNOWN, 0).dueAt)
        assertEquals(60 * 86_400_000L, WordReviewState(step = 100).grade(ReviewGrade.KNOWN, 0).dueAt)
    }
}
