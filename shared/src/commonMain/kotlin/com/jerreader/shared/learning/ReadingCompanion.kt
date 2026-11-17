package com.jerreader.shared.learning

/*
 * Reading-driven learning: the confusion map, the hint level fed back into
 * the AI reading lens, the chapter loop and the continuation recap.
 *
 * Ported from the unified line (1.6.1–1.6.2) and adapted to what this app
 * records. A word here keeps one record per lemma with its latest sentence,
 * the book it came from, a lookup count and a known/not-known answer history;
 * it has no per-word Readium locator and no per-question review modes. So the
 * map has three kinds instead of four, and "back to the source" searches the
 * book for the word instead of jumping to a saved locator.
 *
 * Everything here is a local, reproducible rule. Nothing calls a network
 * service, and the iOS mirror in Jerreader/Features/Learning/ReadingCompanion.swift
 * is checked against the same cases in shared/parity/reading_companion.json.
 */

enum class ReadingCompanionMode(val storageId: String, val title: String) {
    FICTION("fiction", "小说"),
    NONFICTION("nonfiction", "非虚构");

    companion object {
        fun fromStorageId(id: String?): ReadingCompanionMode =
            entries.firstOrNull { it.storageId == id } ?: FICTION
    }
}

enum class ReadingConfusionKind(val storageId: String, val title: String) {
    WORD_MEANING("word_meaning", "词义"),
    GRAMMAR("grammar", "语法"),
    RECALL("recall", "回想")
}

/** What a known/not-known review history says about a word right now. */
data class ReadingReviewSummary(
    val failedAnswerCount: Int,
    /** Answers that were wrong right after a right one: the word was forgotten. */
    val lapseCount: Int,
    /** Right answers since the newest wrong one. */
    val successStreak: Int,
    /** The newest answer was wrong. */
    val hasUnresolvedFailure: Boolean
) {
    companion object {
        /** [knownOldestFirst] is the review history in the order it is stored. */
        fun of(knownOldestFirst: List<Boolean>): ReadingReviewSummary {
            var lapses = 0
            for (index in 1 until knownOldestFirst.size) {
                if (!knownOldestFirst[index] && knownOldestFirst[index - 1]) lapses += 1
            }
            return ReadingReviewSummary(
                failedAnswerCount = knownOldestFirst.count { !it },
                lapseCount = lapses,
                successStreak = knownOldestFirst.asReversed().takeWhile { it }.size,
                hasUnresolvedFailure = knownOldestFirst.lastOrNull() == false
            )
        }
    }
}

/** One saved word as the confusion map sees it. */
data class ReadingErrorSignal(
    val lookupKey: String,
    val displayTerm: String,
    val lemma: String?,
    val sentenceContext: String,
    val sourceBookId: String,
    val sourceBookTitle: String,
    val lookupCount: Int,
    val review: ReadingReviewSummary
) {
    /** The reader looked up a conjugated or inflected form, not the dictionary form. */
    val isInflectedForm: Boolean
        get() {
            val base = lemma?.trim().orEmpty()
            return base.isNotEmpty() && base.lowercase() != displayTerm.trim().lowercase()
        }
}

data class ReadingConfusionItem(
    val lookupKey: String,
    val displayTerm: String,
    val kind: ReadingConfusionKind,
    val score: Int,
    val reason: String,
    val sentenceContext: String,
    val sourceBookId: String,
    val sourceBookTitle: String
)

data class ReadingConfusionMap(
    val items: List<ReadingConfusionItem>,
    val topKind: ReadingConfusionKind?,
    val summary: String
)

/**
 * Maps only friction the reader actually met. A language or a book never
 * makes a word difficult by itself, and a word looked up outside a book (in
 * the lookup tool) is not a reading difficulty.
 */
object ReadingConfusionMapPolicy {
    const val DEFAULT_LIMIT = 5
    private const val RESOLVED_SUCCESS_STREAK = 3

    /** [bookId] limits the map to one book, as the reader does. */
    fun build(
        signals: List<ReadingErrorSignal>,
        limit: Int = DEFAULT_LIMIT,
        bookId: String? = null
    ): ReadingConfusionMap {
        val items = signals
            .filter { bookId == null || it.sourceBookId == bookId }
            .mapNotNull(::item)
            .sortedWith(
                compareByDescending<ReadingConfusionItem> { it.score }
                    .thenBy { it.displayTerm.lowercase() }
                    .thenBy { it.lookupKey }
            )
            .take(limit.coerceIn(0, 20))
        val counts = ReadingConfusionKind.entries.associateWith { kind -> items.count { it.kind == kind } }
        // Most items wins; a tie goes to the kind listed first.
        val topKind = ReadingConfusionKind.entries
            .filter { counts.getValue(it) > 0 }
            .maxByOrNull { kind -> counts.getValue(kind) * 10 - kind.ordinal }
        val summary = when {
            items.isEmpty() -> "还没有足够的真实阅读困难，继续阅读和复习后会自动形成。"
            topKind == null -> "已从真实阅读记录找到 ${items.size} 个待巩固点。"
            else -> "当前主要困惑集中在${topKind.title}，共 ${items.size} 个可以回到书中核对的点。"
        }
        return ReadingConfusionMap(items, topKind, summary)
    }

    private fun item(signal: ReadingErrorSignal): ReadingConfusionItem? {
        if (signal.sourceBookId.isBlank() || signal.displayTerm.isBlank()) return null
        val review = signal.review
        val repeated = signal.lookupCount.coerceAtLeast(0)
        val failures = review.failedAnswerCount.coerceAtLeast(0)
        val lapses = review.lapseCount.coerceAtLeast(0)
        val baseScore = ((repeated - 1).coerceAtLeast(0) * 16).coerceAtMost(48) +
            (lapses * 22).coerceAtMost(66) +
            (failures * 18).coerceAtMost(72)
        if (baseScore <= 0) return null

        // The map shows current friction, not a permanent record: a stable
        // streak removes the word, a short one keeps it as a low-priority
        // "improving" item that a new mistake brings straight back.
        val streak = review.successStreak.coerceAtLeast(0)
        if (!review.hasUnresolvedFailure && streak >= RESOLVED_SUCCESS_STREAK) return null
        val score = if (!review.hasUnresolvedFailure && streak > 0) {
            (baseScore / (streak + 1)).coerceAtLeast(1)
        } else {
            baseScore
        }

        val kind = when {
            failures > 0 || lapses > 0 -> ReadingConfusionKind.RECALL
            signal.isInflectedForm -> ReadingConfusionKind.GRAMMAR
            else -> ReadingConfusionKind.WORD_MEANING
        }
        val reason = buildList {
            if (repeated > 1) add("查过 $repeated 次")
            if (failures > 0 || lapses > 0) {
                add(
                    if (review.hasUnresolvedFailure) {
                        "最近一次复习没想起来"
                    } else {
                        "复习中忘记过 ${maxOf(failures, lapses)} 次"
                    }
                )
            }
            if (signal.isInflectedForm) add("查的是变形，基本形「${signal.lemma?.trim()}」")
            if (!review.hasUnresolvedFailure && streak > 0) add("连续答对 $streak 次 · 正在改善")
        }.joinToString(" · ")
        return ReadingConfusionItem(
            lookupKey = signal.lookupKey,
            displayTerm = signal.displayTerm,
            kind = kind,
            score = score,
            reason = reason,
            sentenceContext = signal.sentenceContext,
            sourceBookId = signal.sourceBookId,
            sourceBookTitle = signal.sourceBookTitle
        )
    }
}

enum class ReadingAssistLevel(val storageId: String, val title: String) {
    HINT_FIRST("hint_first", "提示优先"),
    GUIDED("guided", "分步引导"),
    EXPLICIT("explicit", "明确讲解")
}

data class AdaptiveReadingAssist(
    val level: ReadingAssistLevel,
    val focusTerms: List<String>,
    val instruction: String,
    val reason: String
)

/** Review results flow back into how much the next AI explanation gives away. */
object AdaptiveReadingAssistPolicy {
    /** [bookId] keeps other books' records from raising the support in this one. */
    fun resolve(
        allSignals: List<ReadingErrorSignal>,
        mode: ReadingCompanionMode,
        bookId: String? = null
    ): AdaptiveReadingAssist {
        val signals = allSignals.filter { bookId == null || it.sourceBookId == bookId }
        val map = ReadingConfusionMapPolicy.build(signals, limit = 3)
        // Only the current state counts: a right answer after a mistake
        // clears hasUnresolvedFailure, and successStreak restarts at every
        // mistake, so old successes cannot hide a new failure.
        val unresolved = signals.sumOf { signal ->
            if (!signal.review.hasUnresolvedFailure) 0
            else signal.review.failedAnswerCount.coerceIn(1, 2)
        }
        val successes = signals.sumOf { it.review.successStreak.coerceAtLeast(0) }
        val level = when {
            unresolved >= 2 -> ReadingAssistLevel.EXPLICIT
            unresolved > 0 -> ReadingAssistLevel.GUIDED
            successes >= 3 -> ReadingAssistLevel.HINT_FIRST
            else -> ReadingAssistLevel.GUIDED
        }
        val focus = when (mode) {
            ReadingCompanionMode.FICTION -> "人物动机、语气和指代"
            ReadingCompanionMode.NONFICTION -> "核心概念、论点和证据关系"
        }
        val instruction = when (level) {
            ReadingAssistLevel.HINT_FIRST ->
                "先给一个可回到原文验证的提示和一个自检问题，不要立即给出完整结论；重点关注$focus。"
            ReadingAssistLevel.GUIDED ->
                "分两步引导理解，先指出文本证据，再说明结论；重点关注$focus。"
            ReadingAssistLevel.EXPLICIT ->
                "对薄弱点给出明确、分项讲解，并在结尾给一道很短的原文自检题；重点关注$focus。"
        }
        return AdaptiveReadingAssist(
            level = level,
            focusTerms = map.items.map { it.displayTerm },
            instruction = instruction,
            reason = if (map.items.isEmpty()) {
                "尚无明显薄弱点，采用${level.title}。"
            } else {
                "依据近期复习与阅读困惑，采用${level.title}。"
            }
        )
    }
}

data class ChapterClosurePrompt(
    val title: String,
    val prompt: String,
    /** A word to search the book for when the reader checks against the text. */
    val searchTerm: String?
)

data class ChapterClosurePlan(
    val chapterTitle: String,
    val estimatedSeconds: Int,
    val prompts: List<ChapterClosurePrompt>
)

/** Three 30-second steps at the end of a chapter: recall, the real mistake, the text. */
object ChapterClosurePolicy {
    const val CLOZE_BLANK = "＿＿"

    fun build(
        mode: ReadingCompanionMode,
        chapterTitle: String,
        confusionItems: List<ReadingConfusionItem>
    ): ChapterClosurePlan {
        val recall = when (mode) {
            ReadingCompanionMode.FICTION ->
                "不用看答案，用一句话回想本章人物做了什么、为什么这样做。"
            ReadingCompanionMode.NONFICTION ->
                "不用看答案，用一句话复述本章核心论点和最关键的证据。"
        }
        val weak = confusionItems.firstOrNull()
        val term = weak?.displayTerm?.trim()?.takeIf(String::isNotEmpty)
        // Only the first occurrence is blanked, matched literally.
        val cloze = if (weak != null && term != null) {
            val sentence = weak.sentenceContext
            sentence.indexOf(term).takeIf { it >= 0 }?.let { start ->
                sentence.substring(0, start) + CLOZE_BLANK + sentence.substring(start + term.length)
            }
        } else {
            null
        }
        val mistake = when {
            cloze != null -> "补全真实原句：$cloze"
            term != null -> "回想“$term”在本章语境中的含义，并说出判断依据。"
            else -> "选一个刚读过的关键句，复述它与本章主旨的关系。"
        }
        val check = if (term != null) {
            "回到原文核对“$term”，只修正一个最影响理解的点。"
        } else {
            "回到本章最后一页，确认刚才的概括能被原文支持。"
        }
        return ChapterClosurePlan(
            chapterTitle = chapterTitle,
            estimatedSeconds = 90,
            prompts = listOf(
                ChapterClosurePrompt("30 秒 · 章节回想", recall, null),
                ChapterClosurePrompt("30 秒 · 阅读错误", mistake, term),
                ChapterClosurePrompt("30 秒 · 回到原文", check, term)
            )
        )
    }
}

data class ContinuationRecapPlan(
    val title: String,
    val bullets: List<String>
)

/** What to recall before reading on: where you were, how the book is built, what tripped you. */
object ContinuationRecapPolicy {
    fun build(
        mode: ReadingCompanionMode,
        bookTitle: String,
        chapterTitle: String,
        confusionItems: List<ReadingConfusionItem>
    ): ContinuationRecapPlan {
        val focus = confusionItems.take(3).joinToString("、") { it.displayTerm }
        val structure = when (mode) {
            ReadingCompanionMode.FICTION -> "续读前先回想人物关系、上一段行动与尚未确认的动机。"
            ReadingCompanionMode.NONFICTION -> "续读前先回想上一节的核心概念、论点与证据链。"
        }
        val weakness = if (focus.isBlank()) {
            "目前没有明显阅读困难，先用章节标题恢复上下文。"
        } else {
            "你的近期薄弱点：$focus；遇到时先根据上下文判断，再决定是否查词。"
        }
        val position = chapterTitle.trim().ifEmpty { "上次的位置" }
        return ContinuationRecapPlan(
            title = "继续《$bookTitle》",
            bullets = listOf("上次读到：$position", structure, weakness)
        )
    }
}
