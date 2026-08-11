package com.jerreader.unified.translation

import com.jerreader.unified.domain.LanguageCode

/**
 * What an on-device translator should actually be handed.
 *
 * The on-device models are sentence-scale: they are trained on single
 * sentences, and a whole paragraph handed over in one call comes back
 * truncated, half-translated, or with the tail invented. Text lifted off a
 * rendered page also carries the page's typesetting -- the hard line breaks
 * where lines wrapped, the soft hyphens the renderer inserted, the hyphen a
 * word was broken across -- none of which belong to the sentence.
 *
 * So a request is cleaned of the typesetting first, then cut at sentence
 * boundaries, and each sentence is translated on its own.
 */
object OnDeviceTranslationPlan {

    /** Above this a single sentence is broken up rather than handed over whole. */
    const val MAXIMUM_SEGMENT_LENGTH = 300

    /**
     * A fragment shorter than this is glued onto the following sentence: on its
     * own it gives the model nothing to work with.
     */
    const val MINIMUM_SEGMENT_LENGTH = 12

    /** Clause marks, where an over-long sentence may be cut without much loss. */
    private const val CLAUSE_MARKS = "，、；：,;:"

    /** The pieces to translate, in order. Empty only when [text] has no content. */
    fun segments(text: String, sourceLanguage: LanguageCode?): List<String> {
        val normalized = TranslationTextNormalizer.normalized(text)
        if (normalized.isEmpty()) return emptyList()

        val sentences = ReaderSentenceSegmenter.sentences(normalized, sourceLanguage)
            .ifEmpty { listOf(normalized) }

        val merged = mutableListOf<String>()
        var pending: String? = null
        for (sentence in sentences) {
            val combined = pending?.let { joined(it, sentence) } ?: sentence
            if (combined.length < MINIMUM_SEGMENT_LENGTH) {
                pending = combined
            } else {
                merged += combined
                pending = null
            }
        }
        pending?.let { tail ->
            // A trailing scrap has nothing after it to lean on, so it rides with
            // the sentence before it instead.
            if (merged.isEmpty()) {
                merged += tail
            } else {
                merged[merged.lastIndex] = joined(merged.last(), tail)
            }
        }

        return merged.flatMap(::split)
    }

    /** How the translated [segments] are put back together for a [target] reader. */
    fun joinedTranslation(parts: List<String>, target: LanguageCode): String {
        val separator = if (target.usesScriptWithoutWordSpaces()) "" else " "
        return parts.map(String::trim).filter(String::isNotEmpty).joinToString(separator)
    }

    private fun joined(left: String, right: String): String =
        if (left.lastOrNull()?.isCjk() == true && right.firstOrNull()?.isCjk() == true) {
            left + right
        } else {
            "$left $right"
        }

    /**
     * A sentence too long for one call, cut at the last breathing point that
     * fits -- a clause mark, failing that a space. Only when there is no such
     * point at all is the text cut mid-word.
     */
    private fun split(sentence: String): List<String> {
        if (sentence.length <= MAXIMUM_SEGMENT_LENGTH) return listOf(sentence)

        val pieces = mutableListOf<String>()
        var rest = sentence
        while (rest.length > MAXIMUM_SEGMENT_LENGTH) {
            val window = rest.substring(0, MAXIMUM_SEGMENT_LENGTH)
            val clause = window.indexOfLast { it in CLAUSE_MARKS }
                .takeIf { it > MINIMUM_SEGMENT_LENGTH }
                ?.plus(1)
            val space = window.indexOfLast { it.isWhitespace() }
                .takeIf { it > MINIMUM_SEGMENT_LENGTH }
            val cut = clause ?: space ?: MAXIMUM_SEGMENT_LENGTH
            pieces += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trimStart()
        }
        if (rest.isNotEmpty()) pieces += rest
        return pieces.filter(String::isNotEmpty)
    }

    private fun LanguageCode.usesScriptWithoutWordSpaces(): Boolean = when (this) {
        LanguageCode.CHINESE_SIMPLIFIED, LanguageCode.JAPANESE -> true
        LanguageCode.ENGLISH -> false
    }
}

/**
 * Strips a page's typesetting back out of text that was lifted off it, so what
 * reaches a translator is the sentence the author wrote rather than the shape
 * the renderer gave it.
 */
object TranslationTextNormalizer {

    /** Invisible characters that only ever confuse a tokenizer. */
    private val INVISIBLE = setOf(
        '\u00AD', // soft hyphen, inserted by hyphenating renderers
        '\u200B', // zero-width space
        '\u200C', // zero-width non-joiner
        '\u200D', // zero-width joiner
        '\u2060', // word joiner
        '\uFEFF' // byte-order mark
    )

    /** Spaces that are only typography, not structure. */
    private val SPACE_LIKE = setOf(
        '\u00A0', // no-break space
        '\u202F', // narrow no-break space
        '\u2007', // figure space
        '\u2009', // thin space
        '\u3000' // ideographic space
    )

    fun normalized(text: String): String {
        if (text.isEmpty()) return ""
        val stripped = buildString(text.length) {
            for (character in text) {
                when {
                    character in INVISIBLE -> Unit
                    character in SPACE_LIKE -> append(' ')
                    else -> append(character)
                }
            }
        }
        return collapse(stripped)
    }

    /**
     * Whitespace runs become a single space, except between two CJK characters,
     * which were adjacent in the author's sentence and only came apart where
     * the renderer wrapped the line.
     */
    private fun collapse(text: String): String {
        val result = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val character = text[index]
            if (!character.isWhitespace()) {
                result.append(character)
                index++
                continue
            }
            var end = index
            while (end < text.length && text[end].isWhitespace()) end++
            val before = result.lastOrNull()
            val after = text.getOrNull(end)
            when {
                before == null || after == null -> Unit
                closesHyphenatedWord(result, after) -> result.setLength(result.length - 1)
                before.isCjk() && after.isCjk() -> Unit
                else -> result.append(' ')
            }
            index = end
        }
        return result.toString().trim()
    }

    /** `inter-` then `national`: one word the renderer broke across two lines. */
    private fun closesHyphenatedWord(result: StringBuilder, after: Char): Boolean =
        result.lastOrNull() == '-' &&
            result.getOrNull(result.length - 2)?.isLetter() == true &&
            after.isLowerCase()
}

/**
 * Which language a passage is in, when nothing has told us.
 *
 * The rule that matters: a Latin sentence that merely mentions a CJK word is
 * still a Latin sentence. Reading a stray ideograph as "this paragraph is
 * Chinese" used to send English text into a Chinese-to-Chinese request, which
 * fails outright.
 */
object TranslationLanguageHeuristic {

    /** How far Latin text has to outweigh CJK text before the CJK is a quotation. */
    const val QUOTATION_RATIO = 4

    fun detect(text: String, fallback: LanguageCode = LanguageCode.ENGLISH): LanguageCode {
        var kana = 0
        var han = 0
        var latin = 0
        for (character in text) {
            when {
                character.isKana() -> kana++
                character.isHan() -> han++
                character.isLatinLetter() -> latin++
            }
        }
        val cjk = kana + han
        if (cjk == 0) return fallback
        if (latin >= cjk * QUOTATION_RATIO) return fallback
        return if (kana > 0) LanguageCode.JAPANESE else LanguageCode.CHINESE_SIMPLIFIED
    }

    /** Any letter outside the CJK blocks: Latin, Greek and Cyrillic alike. */
    private fun Char.isLatinLetter(): Boolean = isLetter() && code < 0x2E80
}

/** Kana are all but absent from Chinese, so they settle the question alone. */
internal fun Char.isKana(): Boolean = code in 0x3041..0x30FF && this != '・'

internal fun Char.isHan(): Boolean =
    code in 0x3400..0x4DBF || code in 0x4E00..0x9FFF || code in 0xF900..0xFAFF

/** Characters that are set without spaces between words. */
internal fun Char.isCjk(): Boolean =
    isKana() || isHan() || code in 0x3000..0x303F || code in 0xFF01..0xFF60
