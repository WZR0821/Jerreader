package com.jerreader.shared.learning

import kotlin.math.floor

/**
 * 本章还剩约 N 分钟 (2.1.0, EPUB only): the reader's own pace so far, applied
 * to what is left of the chapter. Checked by shared/parity/chapter_estimate.json.
 */
object ChapterTimeEstimatePolicy {
    const val MINIMUM_READING_SECONDS = 600.0
    const val MINIMUM_PROGRESSION = 0.02
    const val MAXIMUM_MINUTES = 120
    const val UNDER_ONE_MINUTE_SECONDS = 30.0

    /**
     * Whole minutes left, 0 for under half a minute, or null when the pace is
     * not known well enough, the chapter end is unknown, or the position is
     * already past it.
     */
    fun minutes(readingSeconds: Double, progression: Double, chapterEnd: Double?): Int? {
        if (chapterEnd == null || readingSeconds.isNaN() || progression.isNaN() || chapterEnd.isNaN()) return null
        if (readingSeconds < MINIMUM_READING_SECONDS || progression < MINIMUM_PROGRESSION) return null
        if (chapterEnd < progression) return null
        val remainingSeconds = (chapterEnd - progression) * readingSeconds / progression
        if (remainingSeconds < UNDER_ONE_MINUTE_SECONDS) return 0
        val minutes = maxOf(1.0, floor(remainingSeconds / 60.0 + 0.5))
        if (minutes > MAXIMUM_MINUTES) return null
        return minutes.toInt()
    }

    fun text(minutes: Int?): String? = when {
        minutes == null -> null
        minutes <= 0 -> "本章还剩不到 1 分钟"
        else -> "本章还剩约 $minutes 分钟"
    }

    fun text(readingSeconds: Double, progression: Double, chapterEnd: Double?): String? =
        text(minutes(readingSeconds, progression, chapterEnd))
}

/** A date on the reader's calendar, already in the reader's time zone. */
data class CalendarDay(val year: Int, val month: Int, val day: Int) {
    /** Days since 1970-01-01 in the proleptic Gregorian calendar. */
    val epochDay: Long
        get() {
            val y = (if (month <= 2) year - 1 else year).toLong()
            val era = (if (y >= 0) y else y - 399) / 400
            val yearOfEra = y - era * 400
            val monthFromMarch = (month + 9) % 12
            val dayOfYear = (153 * monthFromMarch + 2) / 5 + day - 1
            val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
            return era * 146_097 + dayOfEra - 719_468
        }
}

/**
 * 「第 N 次查 · 上次 MM-dd」 on the lookup card and on a one-word translation card.
 * N counts this lookup; the previous date must be read before it is recorded.
 * Checked by shared/parity/lookup_hint.json.
 */
object LookupHintPolicy {
    const val MINIMUM_HINT_COUNT = 2
    const val PRIMARY_ADD_TO_VOCABULARY_COUNT = 3

    fun hint(lookupCount: Int, previousLookup: CalendarDay?, today: CalendarDay): String? {
        if (lookupCount < MINIMUM_HINT_COUNT) return null
        val count = "第 $lookupCount 次查"
        previousLookup ?: return count
        val label = when (today.epochDay - previousLookup.epochDay) {
            0L -> "今天"
            1L -> "昨天"
            else -> if (previousLookup.year != today.year) {
                "${previousLookup.year.padded(4)}-${previousLookup.month.padded(2)}-${previousLookup.day.padded(2)}"
            } else {
                "${previousLookup.month.padded(2)}-${previousLookup.day.padded(2)}"
            }
        }
        return "$count · 上次 $label"
    }

    /** From the third lookup 「加入生词本」 is the card's solid button. */
    fun addToVocabularyIsPrimary(lookupCount: Int): Boolean = lookupCount >= PRIMARY_ADD_TO_VOCABULARY_COUNT

    private fun Int.padded(width: Int): String = toString().padStart(width, '0')
}

/**
 * The red dot on the reading-companion button when a book is opened. A book
 * reopened within six hours does not light it; finishing a chapter still does,
 * elsewhere. Checked by shared/parity/companion_dot.json.
 */
object ReaderCompanionDotPolicy {
    const val MINIMUM_PROGRESS = 0.01
    const val QUIET_HOURS = 6
    private const val QUIET_MILLIS = QUIET_HOURS * 60L * 60L * 1_000L

    /**
     * [lastOpenedAtMillis] is the previous open, read before this open is
     * recorded; null means never, which counts as long ago. A time in the
     * future, after a clock change, counts as recent.
     */
    fun showsDot(progress: Double, lastOpenedAtMillis: Long?, nowMillis: Long): Boolean {
        if (!(progress > MINIMUM_PROGRESS)) return false
        lastOpenedAtMillis ?: return true
        val elapsed = nowMillis - lastOpenedAtMillis
        return elapsed >= QUIET_MILLIS
    }
}
