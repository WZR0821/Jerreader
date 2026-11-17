package com.jerreader.android.learning

import com.jerreader.android.data.WordLookupEntity
import com.jerreader.shared.learning.ReadingErrorSignal
import com.jerreader.shared.learning.ReadingReviewSummary

/**
 * Turns saved word records into the inputs of the shared reading-companion
 * rules. A word looked up outside a book has no source and is not a reading
 * difficulty, so it is left out here rather than in every caller.
 */
object ReadingErrorSignals {
    fun from(records: List<WordLookupEntity>): List<ReadingErrorSignal> = records.mapNotNull(::signal)

    fun signal(record: WordLookupEntity): ReadingErrorSignal? {
        val bookId = record.sourceBookId?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val term = record.surfaceForm.trim().takeIf(String::isNotEmpty) ?: return null
        val review = WordReviewCodec.decode(record.reviewJson)
        return ReadingErrorSignal(
            lookupKey = record.lookupKey,
            displayTerm = term,
            lemma = record.lemma,
            sentenceContext = record.sentenceContext.orEmpty(),
            sourceBookId = bookId,
            sourceBookTitle = record.sourceBookTitle.orEmpty(),
            lookupCount = record.lookupCount,
            review = ReadingReviewSummary.of(review.answers.map { it.known })
        )
    }
}
