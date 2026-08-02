package com.jerreader.shared.translation

import com.jerreader.shared.domain.LanguageCode

/**
 * Kotlin port of the iOS cross-page sentence expansion
 * (`ReaderCrossPageContextBuilder` / `ReaderCrossPageTranslationResolver`).
 *
 * When a sentence is cut by a screen or PDF page boundary, the reader first
 * rebuilds a complete sentence locally from text it already has, and only then
 * issues a single translation request. Without a trustworthy context it returns
 * nothing rather than inventing text.
 */
data class ReaderCrossPageExpansion(
    val text: String,
    val contextText: String
)

/** Text lifted from one rendered page, tagged with its resource identity. */
data class ReaderCrossPageTextFragment(
    val resourceIdentifier: String,
    val chapterIdentifier: String?,
    val text: String
)

object ReaderTextNormalizer {
    private val whitespace = Regex("\\s+")

    fun normalized(text: String?): String =
        text?.replace(whitespace, " ")?.trim().orEmpty()
}

object ReaderSentenceSegmenter {
    const val MAXIMUM_CONTEXT_CHARACTER_COUNT = 1_200

    private const val TERMINATORS = "。！？!?."
    private const val CLOSING_MARKS = "」』”\"')）】》>"
    /** Japanese quotation helpers that continue the sentence after a mark. */
    private val JAPANESE_QUOTE_PARTICLES = listOf("と", "って", "という", "とは", "とも")

    /** Range of the sentence containing [offset], or null when out of bounds. */
    fun sentenceRange(text: String, offset: Int, language: LanguageCode?): IntRange? {
        if (text.isEmpty()) return null
        val position = offset.coerceIn(0, text.length - 1)

        var start = position
        while (start > 0) {
            val candidate = start - 1
            if (isBoundaryAfter(text, candidate, language)) break
            start = candidate
        }
        while (start < text.length && text[start].isWhitespace()) start++

        var end = position
        while (end < text.length && !isBoundaryAfter(text, end, language)) end++
        if (end < text.length) end++
        while (end < text.length && text[end] in CLOSING_MARKS) end++

        if (end <= start) return null
        return start until end
    }

    private fun isBoundaryAfter(text: String, index: Int, language: LanguageCode?): Boolean {
        val character = text[index]
        if (character !in TERMINATORS) return false
        if (character == '.' && isAbbreviationDot(text, index)) return false

        var after = index + 1
        while (after < text.length && text[after] in CLOSING_MARKS) after++
        if (language == LanguageCode.JAPANESE || containsJapanese(text)) {
            // 「本当？」と彼は…… keeps running: a quotation particle right after a
            // closing mark means the sentence has not ended yet.
            if (after > index + 1 && JAPANESE_QUOTE_PARTICLES.any { text.startsWith(it, after) }) {
                return false
            }
        }
        return true
    }

    /** Keeps `J. K. Rowling`, `U.S.` and `3.14` inside one sentence. */
    private fun isAbbreviationDot(text: String, index: Int): Boolean {
        val previous = text.getOrNull(index - 1)
        val next = text.getOrNull(index + 1)
        if (previous != null && previous.isDigit() && next != null && next.isDigit()) return true
        if (previous != null && previous.isUpperCase()) {
            val beforePrevious = text.getOrNull(index - 2)
            if (beforePrevious == null || !beforePrevious.isLetter() || beforePrevious == '.') {
                return true
            }
        }
        return false
    }

    private fun containsJapanese(text: String): Boolean =
        text.any { it in '぀'..'ヿ' }
}

object ReaderCrossPageContextBuilder {
    /**
     * Boundary-aware variant: neighbouring page text is only used when it
     * belongs to the same chapter, or to the same resource when no chapter
     * identity is available.
     */
    fun context(
        sourceText: String,
        currentResourceIdentifier: String,
        currentChapterIdentifier: String?,
        previousPage: ReaderCrossPageTextFragment?,
        localContext: String?,
        nextPage: ReaderCrossPageTextFragment?,
        maximumCharacterCount: Int = ReaderSentenceSegmenter.MAXIMUM_CONTEXT_CHARACTER_COUNT
    ): String? {
        fun allowedText(fragment: ReaderCrossPageTextFragment?): String? {
            if (fragment == null) return null
            return if (currentChapterIdentifier != null) {
                fragment.text.takeIf { fragment.chapterIdentifier == currentChapterIdentifier }
            } else {
                fragment.text.takeIf { fragment.resourceIdentifier == currentResourceIdentifier }
            }
        }

        return context(
            sourceText = sourceText,
            previousPageText = allowedText(previousPage),
            localContext = localContext,
            nextPageText = allowedText(nextPage),
            maximumCharacterCount = maximumCharacterCount
        )
    }

    fun context(
        sourceText: String,
        previousPageText: String?,
        localContext: String?,
        nextPageText: String?,
        maximumCharacterCount: Int = ReaderSentenceSegmenter.MAXIMUM_CONTEXT_CHARACTER_COUNT
    ): String? {
        val source = ReaderTextNormalizer.normalized(sourceText)
        if (source.isEmpty() || maximumCharacterCount <= source.length) return null

        val local = ReaderTextNormalizer.normalized(localContext)
        val previous = ReaderTextNormalizer.normalized(previousPageText)
        val next = ReaderTextNormalizer.normalized(nextPageText)

        // The page text layer can normalize ligatures or OCR punctuation
        // differently from the selection. Without a confirmed local range we
        // keep the neighbouring pages but do not splice an uncertain window.
        val localRange = rangeClosestToMiddle(source, local)
        val localBefore = localRange?.let { local.substring(0, it.first) }.orEmpty()
        val localAfter = localRange?.let { local.substring(it.last + 1) }.orEmpty()

        val before = listOf(previous, localBefore).filter(String::isNotEmpty).joinToString(" ")
        val after = listOf(localAfter, next).filter(String::isNotEmpty).joinToString(" ")
        if (before.isEmpty() && after.isEmpty()) return null

        val remaining = (maximumCharacterCount - source.length).coerceAtLeast(0)
        val beforeBudget = remaining / 2
        val afterBudget = remaining - beforeBudget
        val trimmedBefore = before.takeLast(beforeBudget)
        val trimmedAfter = after.take(afterBudget)

        val context = buildString {
            if (trimmedBefore.isNotEmpty()) {
                append(trimmedBefore)
                append(separator(trimmedBefore, source))
            }
            append(source)
            if (trimmedAfter.isNotEmpty()) {
                append(separator(source, trimmedAfter))
                append(trimmedAfter)
            }
        }
        return context.takeIf { it.length > source.length }
    }

    private fun separator(left: String, right: String): String {
        val leftCharacter = left.lastOrNull() ?: return ""
        val rightCharacter = right.firstOrNull() ?: return ""
        if (leftCharacter == '-') return ""
        if (leftCharacter.isWhitespace() || rightCharacter.isWhitespace()) return ""
        val latin = leftCharacter.isLetterOrDigit() && leftCharacter.code < 0x2E80
        if (!latin) return ""
        if (!rightCharacter.isLetterOrDigit()) return ""
        return " "
    }

    /** Prefers the occurrence nearest the middle, like the iOS resolver. */
    internal fun rangeClosestToMiddle(needle: String, haystack: String): IntRange? {
        if (needle.isEmpty() || haystack.isEmpty()) return null
        val matches = mutableListOf<Int>()
        var cursor = 0
        while (cursor <= haystack.length - needle.length) {
            val index = haystack.indexOf(needle, cursor)
            if (index < 0) break
            matches += index
            cursor = index + needle.length.coerceAtLeast(1)
        }
        if (matches.isEmpty()) return null
        val middle = haystack.length / 2
        val best = matches.minBy { kotlin.math.abs(it + needle.length / 2 - middle) }
        return best until (best + needle.length)
    }
}

object ReaderCrossPageTranslationResolver {
    fun expansion(
        sourceText: String,
        contextText: String?,
        language: LanguageCode?,
        maximumCharacterCount: Int = TranslationInputPolicy.MAXIMUM_TEXT_LENGTH
    ): ReaderCrossPageExpansion? {
        val source = ReaderTextNormalizer.normalized(sourceText)
        val context = ReaderTextNormalizer.normalized(contextText)
        if (source.isEmpty() || context.length <= source.length) return null
        if (source.length > maximumCharacterCount) return null

        val sourceRange = ReaderCrossPageContextBuilder.rangeClosestToMiddle(source, context)
            ?: return null
        val first = ReaderSentenceSegmenter.sentenceRange(context, sourceRange.first, language)
            ?: return null
        val last = ReaderSentenceSegmenter.sentenceRange(context, sourceRange.last, language)
            ?: return null

        val lower = minOf(first.first, last.first)
        val upper = maxOf(first.last, last.last) + 1
        val expanded = ReaderTextNormalizer.normalized(context.substring(lower, upper))

        if (expanded == source || expanded.length > maximumCharacterCount) return null
        if (!expanded.contains(source)) return null
        return ReaderCrossPageExpansion(text = expanded, contextText = context)
    }
}
