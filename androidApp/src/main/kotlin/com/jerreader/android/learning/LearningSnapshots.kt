package com.jerreader.android.learning

import com.jerreader.shared.learning.WordReviewState
import java.time.LocalDate
import java.time.ZoneId

/** Decode once per data revision, rather than from every sorting comparison or frame. */
data class WordReviewSnapshot(
    val states: Map<String, WordReviewState>,
    val orderedKeys: List<String>
) {
    fun dueKeys(now: Long) = orderedKeys.filter { key ->
        states.getValue(key).let { !it.paused && it.dueAt <= now }
    }

    companion object {
        fun create(inputs: List<Pair<String, String?>>, checkpoint: () -> Unit = {},
            decode: (String?) -> WordReviewState = WordReviewCodec::decode): WordReviewSnapshot {
            val states = inputs.associate { (key, raw) -> checkpoint(); key to decode(raw) }
            return WordReviewSnapshot(states, inputs.map { it.first }.sortedBy { states.getValue(it).dueAt })
        }
    }
}

data class ReadingGrowthSnapshot(
    val rows: List<Pair<String, ReadingDay>>,
    val seconds: Double,
    val queries: Int,
    val answerCount: Int,
    val knownAnswers: Int
) {
    val readingDays = rows.count { it.second.seconds > 0 }
    val maxSeconds = rows.maxOfOrNull { it.second.seconds }?.coerceAtLeast(1.0) ?: 1.0

    companion object {
        fun create(activities: List<String?>, reviews: List<String?>, start: LocalDate,
            selectedBook: String?, zone: ZoneId, checkpoint: () -> Unit = {}): ReadingGrowthSnapshot {
            val dates = (0L..6L).map { start.plusDays(it).toString() }
            val days = dates.associateWith { ReadingDay() }.toMutableMap()
            activities.forEach { raw ->
                checkpoint()
                val activity = ReadingActivity.decode(raw)
                dates.forEach { key ->
                    activity.days[key]?.let { day ->
                        val existing = days.getValue(key)
                        days[key] = ReadingDay(existing.seconds + day.seconds, existing.queries + day.queries)
                    }
                }
            }
            val from = start.atStartOfDay(zone).toInstant().toEpochMilli()
            val until = start.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
            var count = 0
            var known = 0
            reviews.forEach { raw ->
                checkpoint()
                WordReviewCodec.decode(raw).answers.forEach { answer ->
                    if (answer.at >= from && answer.at < until && (selectedBook == null || answer.bookId == selectedBook)) {
                        count++
                        if (answer.known) known++
                    }
                }
            }
            val rows = dates.map { it to days.getValue(it) }
            return ReadingGrowthSnapshot(rows, rows.sumOf { it.second.seconds }, rows.sumOf { it.second.queries }, count, known)
        }
    }
}
