package com.jerreader.unified.lexical

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VocabularyLearningPolicyTest {
    @Test
    fun storageIdsRoundTripAndUnknownValuesStayInLearning() {
        VocabularyStatus.entries.forEach { status ->
            assertEquals(status, VocabularyStatus.fromStorageId(status.storageId))
        }
        assertEquals(VocabularyStatus.LEARNING, VocabularyStatus.fromStorageId("future"))
        assertEquals(VocabularyStatus.LEARNING, VocabularyStatus.fromStorageId(null))
    }

    @Test
    fun savingStartsNewWordsWithoutOverwritingExplicitProgress() {
        assertEquals(
            VocabularyStatus.LEARNING,
            VocabularyLearningPolicy.statusAfterSaving(VocabularyStatus.NEW)
        )
        assertEquals(
            VocabularyStatus.KNOWN,
            VocabularyLearningPolicy.statusAfterSaving(VocabularyStatus.KNOWN)
        )
        assertEquals(
            VocabularyStatus.IGNORED,
            VocabularyLearningPolicy.statusAfterSaving(VocabularyStatus.IGNORED)
        )
    }

    @Test
    fun contextHistoryIsNewestFirstDeduplicatedAndBounded() {
        var contexts = emptyList<String>()
        (1..7).forEach { index ->
            contexts = VocabularyLearningPolicy.contextsAfterEncounter(
                contexts,
                "  第 $index 个  句子。\n"
            )
        }
        assertEquals(5, contexts.size)
        assertEquals("第 7 个 句子。", contexts.first())
        assertEquals("第 3 个 句子。", contexts.last())

        contexts = VocabularyLearningPolicy.contextsAfterEncounter(contexts, "第 5 个 句子。")
        assertEquals("第 5 个 句子。", contexts.first())
        assertEquals(5, contexts.size)
    }

    @Test
    fun contextEncodingRoundTrips() {
        val contexts = listOf("吾輩は猫である。", "She said: Hello.")
        assertEquals(
            contexts,
            VocabularyLearningPolicy.decodeContexts(
                VocabularyLearningPolicy.encodeContexts(contexts)
            )
        )
    }

    @Test
    fun japanesePromptUsesEncounteredFormAndDoesNotRevealTheAnswer() {
        val prompt = VocabularyReviewScheduler.prompt(
            surfaceForm = "食べました",
            lemma = "食べる",
            sentenceContext = "昨日、家族と寿司を食べました。"
        )

        assertTrue(prompt.isCloze)
        assertEquals("昨日、家族と寿司を＿＿。", prompt.text)
        assertFalse(prompt.text.contains("食べ"))
    }

    @Test
    fun reviewScheduleExpandsAndMasteryRequiresRepeatedRecall() {
        val first = VocabularyReviewScheduler.review(
            reviewCount = 0,
            reviewStage = 0,
            currentIntervalDays = 0,
            currentLapseCount = 0,
            currentStatus = VocabularyStatus.LEARNING,
            rating = VocabularyReviewRating.GOOD,
            reviewedAtEpochMillis = 1_000L
        )
        assertEquals(1, first.intervalDays)
        assertEquals(VocabularyStatus.LEARNING, first.status)

        val second = VocabularyReviewScheduler.review(
            reviewCount = first.reviewCount,
            reviewStage = first.reviewStage,
            currentIntervalDays = first.intervalDays,
            currentLapseCount = first.lapseCount,
            currentStatus = first.status,
            rating = VocabularyReviewRating.GOOD,
            reviewedAtEpochMillis = first.nextReviewAtEpochMillis
        )
        val third = VocabularyReviewScheduler.review(
            reviewCount = second.reviewCount,
            reviewStage = second.reviewStage,
            currentIntervalDays = second.intervalDays,
            currentLapseCount = second.lapseCount,
            currentStatus = second.status,
            rating = VocabularyReviewRating.GOOD,
            reviewedAtEpochMillis = second.nextReviewAtEpochMillis
        )
        assertEquals(3, second.intervalDays)
        assertEquals(VocabularyStatus.KNOWN, third.status)
        assertTrue(third.intervalDays > second.intervalDays)
    }

    @Test
    fun forgottenCardReturnsSoonAndIsNoLongerMastered() {
        val result = VocabularyReviewScheduler.review(
            reviewCount = 8,
            reviewStage = 5,
            currentIntervalDays = 21,
            currentLapseCount = 1,
            currentStatus = VocabularyStatus.KNOWN,
            rating = VocabularyReviewRating.AGAIN,
            reviewedAtEpochMillis = 50_000L
        )

        assertEquals(VocabularyStatus.LEARNING, result.status)
        assertEquals(0, result.reviewStage)
        assertEquals(2, result.lapseCount)
        assertEquals(
            50_000L + VocabularyReviewScheduler.AGAIN_DELAY_MILLIS,
            result.nextReviewAtEpochMillis
        )
    }

    @Test
    fun queueSeparatesUnseenDueUpcomingAndIgnoredCards() {
        val now = 1_000_000L
        assertEquals(
            VocabularyReviewQueueState.UNSEEN,
            VocabularyReviewScheduler.queueState(true, VocabularyStatus.LEARNING, 0, 0, now)
        )
        assertEquals(
            VocabularyReviewQueueState.DUE,
            VocabularyReviewScheduler.queueState(true, VocabularyStatus.LEARNING, 2, now, now)
        )
        assertEquals(
            VocabularyReviewQueueState.UPCOMING,
            VocabularyReviewScheduler.queueState(true, VocabularyStatus.KNOWN, 4, now + 1, now)
        )
        assertEquals(
            VocabularyReviewQueueState.EXCLUDED,
            VocabularyReviewScheduler.queueState(true, VocabularyStatus.IGNORED, 0, 0, now)
        )
    }
}
