package com.jerreader.shared.learning

enum class ReviewGrade(val title: String) { UNKNOWN("不认识"), FUZZY("模糊"), KNOWN("认识") }
data class ReviewAnswer(val at: Long, val known: Boolean, val bookId: String? = null)
data class WordReviewState(
    val step: Int = 0,
    val dueAt: Long = 0,
    val paused: Boolean = false,
    val answers: List<ReviewAnswer> = emptyList()
) {
    fun grade(grade: ReviewGrade, now: Long, bookId: String? = null): WordReviewState {
        val days = listOf(1, 3, 7, 14, 30, 60)
        val current = step.coerceIn(days.indices)
        val delay = when (grade) {
            ReviewGrade.KNOWN -> days[current] * 86_400_000L
            ReviewGrade.FUZZY -> 86_400_000L
            ReviewGrade.UNKNOWN -> 600_000L
        }
        val next = when (grade) {
            ReviewGrade.KNOWN -> (current + 1).coerceAtMost(days.lastIndex)
            ReviewGrade.FUZZY -> (current - 1).coerceAtLeast(0)
            ReviewGrade.UNKNOWN -> 0
        }
        return copy(step = next, dueAt = now + delay,
            answers = (answers + ReviewAnswer(now, grade == ReviewGrade.KNOWN, bookId)).takeLast(1000))
    }
}
