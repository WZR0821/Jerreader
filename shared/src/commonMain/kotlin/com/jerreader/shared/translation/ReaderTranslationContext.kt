package com.jerreader.shared.translation

/**
 * The context sent with a tapped sentence (2.1.0), ported from iOS
 * `ReaderTranslationContext.window`: at most [ReaderSentenceSegmenter.MAXIMUM_CONTEXT_CHARACTER_COUNT]
 * composed characters in all, what is left after the sentence split evenly
 * before and after it. Counts are in composed characters, like Swift's
 * `String.count`, `suffix` and `prefix`.
 */
object ReaderTranslationContext {
    fun window(
        highlight: String,
        before: String?,
        after: String?,
        maximumCharacterCount: Int = ReaderSentenceSegmenter.MAXIMUM_CONTEXT_CHARACTER_COUNT
    ): String? {
        val sourceText = normalizedText(highlight)
        if (sourceText.isEmpty()) return null
        val remaining = maxOf(maximumCharacterCount - textCharacterCount(sourceText), 0)
        val beforeCount = remaining / 2
        val afterCount = remaining - beforeCount
        val context = normalizedText(
            composedSuffix(before.orEmpty(), beforeCount) + highlight + composedPrefix(after.orEmpty(), afterCount)
        )
        return if (context.isEmpty() || context == sourceText) null else context
    }

    /**
     * The context of a tapped sentence, ported from iOS
     * `ReaderSentenceSegmentation.contextWindow` and the check `EPUBReaderHost`
     * makes after it: the whole [paragraph] while it is at most
     * [maximumCharacterCount] composed characters, otherwise the [window]
     * around the sentence from [sentenceStart] to [sentenceEnd] (UTF-16
     * offsets); null when that is only the sentence again. The paragraph is
     * the page's base text, so no ruby reading is ever part of it.
     */
    fun paragraph(
        paragraph: String,
        sentenceStart: Int,
        sentenceEnd: Int,
        maximumCharacterCount: Int = ReaderSentenceSegmenter.MAXIMUM_CONTEXT_CHARACTER_COUNT
    ): String? {
        if (sentenceStart < 0 || sentenceEnd > paragraph.length || sentenceStart >= sentenceEnd) return null
        val sentence = paragraph.substring(sentenceStart, sentenceEnd)
        val sourceText = normalizedText(sentence)
        if (sourceText.isEmpty()) return null
        val whole = normalizedText(paragraph)
        if (textCharacterCount(whole) > maximumCharacterCount) {
            return window(
                highlight = sentence,
                before = paragraph.substring(0, sentenceStart),
                after = paragraph.substring(sentenceEnd),
                maximumCharacterCount = maximumCharacterCount
            )
        }
        return whole.takeIf { it != sourceText }
    }

    /**
     * iOS `TranslationCacheStore.normalizedText`: line breaks unified, runs of
     * white space collapsed to one space, trimmed, then NFC.
     */
    fun normalizedText(text: String): String = canonicallyComposed(
        text.replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(whiteSpaceRun, " ")
            .trim()
    )

    /**
     * The window around [source] inside a longer [text], such as the
     * neighbouring pages of a completed cross-page sentence; null when the
     * source is not in it.
     */
    fun around(source: String, text: String?): String? {
        if (text.isNullOrEmpty() || source.isEmpty()) return null
        val start = text.indexOf(source).takeIf { it >= 0 } ?: return null
        return window(
            highlight = source,
            before = text.substring(0, start),
            after = text.substring(start + source.length)
        )
    }

    /** A context worth sending: non-empty and not just the source again. */
    fun meaningfulContext(context: String?, sourceText: String): String? {
        val normalized = context?.let(::normalizedText)?.takeIf(String::isNotEmpty) ?: return null
        return normalized.takeIf { it != normalizedText(sourceText) }
    }

    // ICU's `\s`, which the Swift side uses: tab, line feed, form feed,
    // carriage return and every Unicode separator.
    private val whiteSpaceRun = Regex("[\\t\\n\\f\\r\\p{Z}]+")
}

/** The first [count] composed characters of [text]. */
internal fun composedPrefix(text: String, count: Int): String {
    if (count <= 0 || text.isEmpty()) return ""
    return text.substring(0, composedCharacterEnd(text, count))
}

/** The last [count] composed characters of [text]. */
internal fun composedSuffix(text: String, count: Int): String {
    if (count <= 0 || text.isEmpty()) return ""
    val total = textCharacterCount(text)
    if (count >= total) return text
    return text.substring(composedCharacterEnd(text, total - count))
}

/**
 * Where the first [count] composed characters of [text] end, in UTF-16 units.
 * Over code-point boundaries the number of composed characters before a
 * position never falls as the position grows, and it is still [count] at the
 * end of the count-th character, so that end is the last position counting no
 * more than [count].
 */
private fun composedCharacterEnd(text: String, count: Int): Int {
    if (count <= 0) return 0
    val boundaries = ArrayList<Int>(text.length + 1)
    for (index in 0..text.length) {
        val splitsPair = index in 1 until text.length &&
            text[index - 1].isHighSurrogate() && text[index].isLowSurrogate()
        if (!splitsPair) boundaries += index
    }
    var low = 0
    var high = boundaries.size - 1
    while (low < high) {
        val middle = (low + high + 1) / 2
        if (textCharacterCount(text.substring(0, boundaries[middle])) <= count) low = middle else high = middle - 1
    }
    return boundaries[low]
}
