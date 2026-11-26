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

    /**
     * Pairs whose contents belong to the enclosing sentence. A terminator
     * inside one of them ends the quoted utterance, not the sentence that
     * carries it: 「何だよ、それ。手頃なあばらやって」 is a single tap target.
     */
    private val QUOTE_PAIRS = mapOf(
        '「' to '」',
        '『' to '』',
        '“' to '”',
        '‘' to '’',
        '（' to '）',
        '(' to ')',
        '【' to '】',
        '《' to '》',
        '〈' to '〉',
        '〔' to '〕'
    )

    /**
     * Closers that end a spoken line. Unlike a bracket, reaching one of these
     * finishes the sentence even without a terminator inside the quote.
     */
    private const val SPEECH_CLOSERS = "」』”’"

    /**
     * What keeps a sentence going after a closing quote: a quotative
     * (「本当？」と, って) or a particle that makes the quote a noun
     * (「待てよ」の一言, 「はい」を, 「はい」だった). The same lists as the iOS
     * `ReaderSentenceSegmenter`.
     */
    private val JAPANESE_QUOTE_PARTICLES = listOf(
        "って", "と", "など", "なんて", "なんか", "の", "を", "が", "は", "も",
        "に", "へ", "で", "や", "から", "まで", "より", "だ", "じゃ", "しか",
        "ばかり", "くらい", "ぐらい", "こそ", "さえ", "すら"
    )

    /** Words that begin a new sentence although they start like a particle. */
    private val JAPANESE_INDEPENDENT_STARTERS = listOf(
        "とても", "ところ", "とにかく", "とはいえ", "とは言え", "とりあえず",
        "とうとう", "ともかく", "とたん", "とつぜん", "ときどき",
        "もう", "もし", "もっと", "もちろん", "もともと",
        "やがて", "やっと", "やはり", "やっぱり", "はっと", "はっきり",
        "にっこり", "にやり", "しかし", "しかも",
        "だが", "だから", "だけど", "だけれど", "でも、"
    )

    /**
     * Punctuation right after a closing quote: 「待てよ」。 ends at the mark,
     * and 「待てよ」、と… keeps going.
     */
    private const val JAPANESE_QUOTE_FOLLOWERS = "。．.！!？?、，,…‥・：:；;"

    /**
     * English (and other space-separated) text, 2.1.0. The same lists as the
     * iOS `refinedEnglishRanges`: a title such as "Mr." never ends a sentence,
     * and an initial or "U.S." ends one only before a word that clearly starts
     * a new sentence.
     */
    private const val ENGLISH_TERMINATORS = ".!?"
    private const val ENGLISH_OPENING_MARKS = "\"“‘'([{<«‹"
    private const val ENGLISH_CLOSING_MARKS = "\"”’')]}>»›"

    private val ENGLISH_NON_TERMINAL_ABBREVIATIONS = setOf(
        "mr.", "mrs.", "ms.", "dr.", "prof.", "rev.", "hon.", "pres.", "gov.", "sen.",
        "rep.", "gen.", "capt.", "cmdr.", "lt.", "col.", "sgt.", "st.", "mt.", "fig.",
        "ch.", "vol."
    )

    /** `No. 5`, `p. 12`: before a number these keep the sentence going, as on iOS. */
    private val ENGLISH_NUMBER_ABBREVIATIONS = setOf(
        "no.", "nos.", "p.", "pp.", "art.", "sec.", "nr.", "op."
    )

    private val ENGLISH_STRONG_SENTENCE_STARTERS = setOf(
        "a", "after", "afterward", "afterwards", "although", "an", "and", "are", "as",
        "before", "but", "can", "could", "did", "do", "does", "finally", "had", "has",
        "have", "he", "here", "however", "how", "i", "if", "in", "instead", "is", "it",
        "later", "meanwhile", "nevertheless", "nonetheless", "otherwise", "she", "should",
        "so", "still", "suddenly", "that", "the", "then", "there", "these", "they", "this",
        "those", "though", "we", "were", "what", "when", "where", "while", "who", "why",
        "will", "would", "yet", "you"
    )

    /** Range of the sentence containing [offset], or null when out of bounds. */
    fun sentenceRange(text: String, offset: Int, language: LanguageCode?): IntRange? {
        if (text.isEmpty()) return null
        val position = offset.coerceIn(0, text.length - 1)
        val japanese = language == LanguageCode.JAPANESE || containsJapanese(text)
        // Chinese text, and Latin words inside it, keep the rules below.
        if (!japanese && (language == LanguageCode.ENGLISH || !containsHan(text))) {
            return englishSentenceRange(text, position)
        }
        val quotes = QuoteMap(text)

        var start = position
        while (start > 0) {
            val candidate = start - 1
            if (isBoundaryAfter(text, candidate, japanese, quotes)) break
            start = candidate
        }
        while (start < text.length && text[start].isWhitespace()) start++

        var end = position
        while (end < text.length && !isBoundaryAfter(text, end, japanese, quotes)) end++
        if (end < text.length) end++
        while (end < text.length && text[end] in CLOSING_MARKS) end++

        if (end <= start) return null
        return start until end
    }

    /**
     * Positions of the quotation marks that actually pair up. An unbalanced
     * `「` is ignored so a half-quoted paragraph still splits into sentences
     * instead of collapsing into one.
     */
    private class QuoteMap(text: String) {
        /** True where a character sits strictly inside a matched pair. */
        val enclosed = BooleanArray(text.length)

        /** True at the closer that completes a spoken line. */
        val speechClose = BooleanArray(text.length)

        init {
            val openIndices = ArrayDeque<Int>()
            text.forEachIndexed { index, character ->
                val expected = openIndices.lastOrNull()?.let { QUOTE_PAIRS[text[it]] }
                if (expected != null && character == expected) {
                    val opener = openIndices.removeLast()
                    for (inner in (opener + 1) until index) enclosed[inner] = true
                    if (character in SPEECH_CLOSERS) speechClose[index] = true
                } else if (character in QUOTE_PAIRS) {
                    openIndices.addLast(index)
                }
            }
        }
    }

    private fun isBoundaryAfter(
        text: String,
        index: Int,
        japanese: Boolean,
        quotes: QuoteMap
    ): Boolean {
        val character = text[index]
        val closesSpeech = quotes.speechClose[index]
        if (!closesSpeech) {
            if (character !in TERMINATORS) return false
            if (character == '.' && isAbbreviationDot(text, index)) return false
            // Inside 「…」 the terminator punctuates the quoted line; the
            // sentence keeps running until the quote itself closes.
            if (quotes.enclosed[index]) return false
        }

        var after = index + 1
        while (after < text.length && text[after] in CLOSING_MARKS) after++
        val closedQuote = closesSpeech || after > index + 1
        if (japanese) {
            if (closedQuote) {
                var probe = after
                while (probe < text.length && text[probe].isWhitespace()) probe++
                // 「待てよ」。 ends at the 。 that follows, not before it.
                if (closesSpeech && text.getOrNull(probe)?.let { it in JAPANESE_QUOTE_FOLLOWERS } == true) {
                    return false
                }
                // 「本当？」と彼は…… keeps running: a quotation particle right
                // after a closing mark means the sentence has not ended yet.
                if (continuesAfterJapaneseQuote(text, probe)) return false
            }
        } else if (closedQuote) {
            // "Stop!" he said. — a lower-case word after the closing quote
            // continues the same sentence.
            var probe = after
            while (probe < text.length && text[probe].isWhitespace()) probe++
            val next = text.getOrNull(probe)
            if (next != null && next.isLowerCase()) return false
        }
        return true
    }

    private fun continuesAfterJapaneseQuote(text: String, after: Int): Boolean {
        if (JAPANESE_INDEPENDENT_STARTERS.any { text.startsWith(it, after) }) return false
        return JAPANESE_QUOTE_PARTICLES.any { text.startsWith(it, after) }
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

    private fun containsHan(text: String): Boolean =
        text.any { it in '一'..'鿿' || it in '㐀'..'䶿' }

    /**
     * Unlike the Japanese rules, quotation marks do not hold an English
     * sentence together: in a quotation that runs for several sentences, each
     * one is its own tap target, and the reporting clause stays with the
     * sentence it follows (`“Yes,” Holmes said.`).
     */
    private fun englishSentenceRange(text: String, offset: Int): IntRange? {
        // A tap on the closing quote after a terminator belongs to the
        // sentence that quote closes, not to the one after it.
        var position = offset
        var back = offset
        while (
            back > 0 &&
            text[back] in ENGLISH_CLOSING_MARKS &&
            (text[back - 1] in ENGLISH_CLOSING_MARKS || text[back - 1] in ENGLISH_TERMINATORS)
        ) {
            back--
            if (text[back] in ENGLISH_TERMINATORS) {
                position = back
                break
            }
        }

        var start = position
        while (start > 0 && !isEnglishBoundaryAfter(text, start - 1)) start--
        while (start < text.length && text[start].isWhitespace()) start++

        var end = position
        while (end < text.length && !isEnglishBoundaryAfter(text, end)) end++
        if (end < text.length) end++
        while (end < text.length && text[end] in ENGLISH_CLOSING_MARKS) end++
        while (end > start && text[end - 1].isWhitespace()) end--

        if (end <= start) return null
        return start until end
    }

    private fun isEnglishBoundaryAfter(text: String, index: Int): Boolean {
        val character = text[index]
        if (character !in ENGLISH_TERMINATORS) return false
        // "Wait..." and "?!" end at their last mark.
        if (text.getOrNull(index + 1)?.let { it in ENGLISH_TERMINATORS } == true) return false
        val endsARun = text.getOrNull(index - 1)?.let { it in ENGLISH_TERMINATORS } == true

        var after = index + 1
        while (after < text.length && text[after] in ENGLISH_CLOSING_MARKS) after++
        var probe = after
        while (probe < text.length && text[probe].isWhitespace()) probe++
        if (probe >= text.length) return true
        // 3.14, U.S.A. and e.g., : no space after the mark, no boundary.
        if (probe == after) return false
        // "Stop!" he said. / 5 p.m. and dark: a lower-case word continues.
        if (text[probe].isLowerCase()) return false
        if (character == '.' && !endsARun) {
            val token = periodToken(text, index)
            if (token.lowercase() in ENGLISH_NON_TERMINAL_ABBREVIATIONS) return false
            if (token.lowercase() in ENGLISH_NUMBER_ABBREVIATIONS && text[probe].isDigit()) return false
            val stem = token.dropLast(1)
            if ((stem.length == 1 && stem[0].isUpperCase()) || stem.contains('.')) {
                // J. K. Rowling / the U.S. Army keep going; "in the U.S. He
                // moved" ends, because "He" clearly starts a new sentence.
                val word = englishWordAt(text, probe) ?: return false
                return word.lowercase() in ENGLISH_STRONG_SENTENCE_STARTERS
            }
        }
        return true
    }

    /** The letters and dots that end at the period at [index]: `Mr.`, `U.S.`. */
    private fun periodToken(text: String, index: Int): String {
        var start = index
        while (start > 0 && (text[start - 1].isLetter() || text[start - 1] == '.')) start--
        return text.substring(start, index + 1)
    }

    /** The word at [index], after any spaces and opening quotes. */
    private fun englishWordAt(text: String, index: Int): String? {
        var start = index
        while (
            start < text.length &&
            (text[start].isWhitespace() || text[start] in ENGLISH_OPENING_MARKS)
        ) {
            start++
        }
        if (start >= text.length || !text[start].isLetter()) return null
        var end = start + 1
        while (end < text.length && text[end].isLetter()) end++
        return text.substring(start, end)
    }
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
