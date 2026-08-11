package com.jerreader.unified.lexical

/**
 * The learning state of a word saved to the vocabulary collection.
 *
 * Storage ids are part of both platform databases and backup payloads. Keep
 * them stable; unknown values deliberately fall back to [LEARNING] so an old
 * favourite is never presented as already mastered after an upgrade.
 */
enum class VocabularyStatus(val storageId: String, val title: String) {
    NEW("new", "待学习"),
    LEARNING("learning", "学习中"),
    KNOWN("known", "已掌握"),
    IGNORED("ignored", "已忽略");

    companion object {
        fun fromStorageId(id: String?): VocabularyStatus =
            entries.firstOrNull { it.storageId == id?.trim()?.lowercase() } ?: LEARNING
    }
}

/** The four recall judgements shown after a learning card is revealed. */
enum class VocabularyReviewRating(
    val storageId: String,
    val title: String,
    val detail: String
) {
    AGAIN("again", "忘记", "10 分钟后再看"),
    HARD("hard", "模糊", "缩短复习间隔"),
    GOOD("good", "认识", "按计划复习"),
    EASY("easy", "熟练", "延长复习间隔")
}

/**
 * The persistent result of one recall judgement.
 *
 * Epoch milliseconds and primitive counters keep this model equally easy to
 * store in Room and SwiftData. A zero timestamp means that a card has never
 * been reviewed; the scheduler itself always returns real timestamps.
 */
data class VocabularyReviewResult(
    val status: VocabularyStatus,
    val reviewCount: Int,
    val reviewStage: Int,
    val intervalDays: Int,
    val lapseCount: Int,
    val lastReviewedAtEpochMillis: Long,
    val nextReviewAtEpochMillis: Long
)

enum class VocabularyReviewQueueState {
    UNSEEN,
    DUE,
    UPCOMING,
    EXCLUDED
}

data class VocabularyReviewPrompt(
    val text: String,
    val isCloze: Boolean
)

/**
 * A deliberately small, deterministic spaced-review policy for the local
 * learning loop. It is inspired by common expanding-interval systems, but does
 * not pretend to be FSRS without a trained parameter set.
 */
object VocabularyReviewScheduler {
    const val AGAIN_DELAY_MILLIS: Long = 10L * 60L * 1_000L
    private const val DAY_MILLIS: Long = 24L * 60L * 60L * 1_000L
    const val DAILY_NEW_LIMIT: Int = 10
    const val DAILY_DUE_LIMIT: Int = 50

    fun review(
        reviewCount: Int,
        reviewStage: Int,
        currentIntervalDays: Int,
        currentLapseCount: Int,
        currentStatus: VocabularyStatus,
        rating: VocabularyReviewRating,
        reviewedAtEpochMillis: Long
    ): VocabularyReviewResult {
        val safeStage = reviewStage.coerceAtLeast(0)
        val safeInterval = currentIntervalDays.coerceAtLeast(0)
        val timestamp = reviewedAtEpochMillis.coerceAtLeast(0)

        val nextStage: Int
        val nextInterval: Int
        val nextLapses: Int
        val nextStatus: VocabularyStatus
        val nextReviewAt: Long

        when (rating) {
            VocabularyReviewRating.AGAIN -> {
                nextStage = 0
                nextInterval = 0
                nextLapses = currentLapseCount.coerceAtLeast(0) + 1
                nextStatus = VocabularyStatus.LEARNING
                nextReviewAt = timestamp + AGAIN_DELAY_MILLIS
            }

            VocabularyReviewRating.HARD -> {
                nextStage = maxOf(safeStage, 1)
                nextInterval = if (safeInterval == 0) {
                    1
                } else {
                    maxOf(safeInterval + 1, (safeInterval * 1.2).toInt())
                }
                nextLapses = currentLapseCount.coerceAtLeast(0)
                nextStatus = VocabularyStatus.LEARNING
                nextReviewAt = timestamp + nextInterval * DAY_MILLIS
            }

            VocabularyReviewRating.GOOD -> {
                nextStage = safeStage + 1
                nextInterval = when (safeStage) {
                    0 -> 1
                    1 -> 3
                    else -> maxOf(safeInterval + 1, (safeInterval * 2.3).toInt())
                }
                nextLapses = currentLapseCount.coerceAtLeast(0)
                nextStatus = successfulStatus(currentStatus, nextStage)
                nextReviewAt = timestamp + nextInterval * DAY_MILLIS
            }

            VocabularyReviewRating.EASY -> {
                nextStage = safeStage + 2
                nextInterval = if (safeInterval == 0) {
                    4
                } else {
                    maxOf(safeInterval + 2, safeInterval * 3)
                }
                nextLapses = currentLapseCount.coerceAtLeast(0)
                nextStatus = successfulStatus(currentStatus, nextStage)
                nextReviewAt = timestamp + nextInterval * DAY_MILLIS
            }
        }

        return VocabularyReviewResult(
            status = nextStatus,
            reviewCount = reviewCount.coerceAtLeast(0) + 1,
            reviewStage = nextStage,
            intervalDays = nextInterval,
            lapseCount = nextLapses,
            lastReviewedAtEpochMillis = timestamp,
            nextReviewAtEpochMillis = nextReviewAt
        )
    }

    fun queueState(
        isFavorite: Boolean,
        status: VocabularyStatus,
        reviewCount: Int,
        nextReviewAtEpochMillis: Long,
        nowEpochMillis: Long
    ): VocabularyReviewQueueState {
        if (!isFavorite || status == VocabularyStatus.IGNORED) {
            return VocabularyReviewQueueState.EXCLUDED
        }
        if (reviewCount <= 0 || nextReviewAtEpochMillis <= 0) {
            return VocabularyReviewQueueState.UNSEEN
        }
        return if (nextReviewAtEpochMillis <= nowEpochMillis) {
            VocabularyReviewQueueState.DUE
        } else {
            VocabularyReviewQueueState.UPCOMING
        }
    }

    /**
     * Japanese cards hide the encountered form inside its original sentence.
     * If no reliable occurrence exists, the word itself remains the prompt so
     * a record with sparse metadata is still reviewable.
     */
    fun prompt(
        surfaceForm: String,
        lemma: String?,
        sentenceContext: String?
    ): VocabularyReviewPrompt {
        val context = sentenceContext.normalizedReviewText()
        val candidates = listOf(surfaceForm, lemma.orEmpty())
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        if (context.isNotEmpty()) {
            val term = candidates.firstOrNull(context::contains)
            if (term != null) {
                return VocabularyReviewPrompt(
                    text = context.replaceFirst(term, "＿＿"),
                    isCloze = true
                )
            }
        }
        return VocabularyReviewPrompt(
            text = surfaceForm.normalizedReviewText().ifEmpty { lemma.orEmpty().trim() },
            isCloze = false
        )
    }

    private fun successfulStatus(
        currentStatus: VocabularyStatus,
        nextStage: Int
    ): VocabularyStatus = if (
        currentStatus == VocabularyStatus.KNOWN || nextStage >= 3
    ) {
        VocabularyStatus.KNOWN
    } else {
        VocabularyStatus.LEARNING
    }

    private fun String?.normalizedReviewText(): String = this
        ?.trim()
        ?.replace(Regex("\\s+"), " ")
        .orEmpty()
}

/** Shared decisions for status transitions and the bounded source-context list. */
object VocabularyLearningPolicy {
    const val MAXIMUM_CONTEXTS: Int = 5
    private const val CONTEXT_SEPARATOR: String = "\u001E"

    fun initialStatus(isFavorite: Boolean): VocabularyStatus =
        if (isFavorite) VocabularyStatus.LEARNING else VocabularyStatus.NEW

    fun allStatuses(): List<VocabularyStatus> = VocabularyStatus.entries

    fun statusTitle(storageId: String?): String = VocabularyStatus.fromStorageId(storageId).title

    /** Saving a new/history-only word starts learning; an explicit state is retained. */
    fun statusAfterSaving(current: VocabularyStatus): VocabularyStatus =
        if (current == VocabularyStatus.NEW) VocabularyStatus.LEARNING else current

    fun contextsAfterEncounter(existing: List<String>, newContext: String?): List<String> {
        val normalizedNew = normalizeContext(newContext)
        val result = ArrayList<String>(MAXIMUM_CONTEXTS)
        if (normalizedNew != null) result += normalizedNew
        existing.forEach { value ->
            val normalized = normalizeContext(value) ?: return@forEach
            if (result.none { canonicalContext(it) == canonicalContext(normalized) }) {
                result += normalized
            }
        }
        return result.take(MAXIMUM_CONTEXTS)
    }

    fun encodeContexts(contexts: List<String>): String = contextsAfterEncounter(contexts, null)
        .joinToString(CONTEXT_SEPARATOR)

    fun decodeContexts(raw: String?): List<String> = raw
        ?.split(CONTEXT_SEPARATOR)
        ?.let { contextsAfterEncounter(it, null) }
        ?: emptyList()

    private fun normalizeContext(value: String?): String? {
        val normalized = value
            ?.replace(CONTEXT_SEPARATOR, " ")
            ?.trim()
            ?.replace(Regex("\\s+"), " ")
            .orEmpty()
        return normalized.takeIf(String::isNotEmpty)
    }

    private fun canonicalContext(value: String): String = value.lowercase()
}
