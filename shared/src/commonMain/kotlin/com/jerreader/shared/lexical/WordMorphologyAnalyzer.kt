package com.jerreader.shared.lexical

import com.jerreader.shared.domain.LanguageCode

interface WordBoundaryTokenizer {
    fun tokenize(text: String, language: LanguageCode): List<TextToken>
}

class WordMorphologyAnalyzer(
    private val tokenizer: WordBoundaryTokenizer
) {
    fun analyze(request: WordAnalysisRequest): WordAnalysis? {
        if (request.text.isBlank()) return null
        val language = detectLanguage(request.text, request.languageHint) ?: return null
        val tokens = tokenizer.tokenize(request.text, language)
        val focusStart = request.focusStart.coerceIn(0, request.text.length)
        val focusEnd = request.focusEndExclusive.coerceIn(focusStart, request.text.length)
        val trimmedStart = request.text.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        val trimmedEnd = request.text.indexOfLast { !it.isWhitespace() }.let { it + 1 }
        val explicitSingleSelection = focusStart <= trimmedStart &&
            focusEnd >= trimmedEnd &&
            request.text.substring(trimmedStart, trimmedEnd).none(Char::isWhitespace)
        val token = if (explicitSingleSelection) {
            TextToken(
                request.text.substring(trimmedStart, trimmedEnd),
                trimmedStart,
                trimmedEnd
            )
        } else {
            tokens.firstOrNull { candidate ->
                candidate.start < focusEnd && candidate.endExclusive > focusStart
            } ?: tokens.firstOrNull { candidate ->
                focusStart in candidate.start..candidate.endExclusive
            } ?: return null
        }

        val surface = token.text.trim().trim { character -> character in EDGE_PUNCTUATION }
        if (surface.isBlank() || surface.none(::isLexicalCharacter)) return null
        val normalized = if (language == LanguageCode.ENGLISH) surface.lowercase() else surface
        val lemmaResult = when (language) {
            LanguageCode.ENGLISH -> englishLemma(normalized)
            LanguageCode.JAPANESE -> japaneseLemma(normalized)
            LanguageCode.CHINESE_SIMPLIFIED -> LemmaResult(null, emptyList(), LemmaConfidence.UNKNOWN)
        }

        return WordAnalysis(
            surfaceForm = surface,
            normalizedForm = normalized,
            lemma = lemmaResult.lemma,
            lemmaCandidates = lemmaResult.candidates,
            language = language,
            confidence = lemmaResult.confidence,
            rangeStart = token.start,
            rangeEndExclusive = token.endExclusive,
            source = request.source
        )
    }

    private fun detectLanguage(text: String, hint: LanguageCode?): LanguageCode? {
        if (text.any(::isJapaneseCharacter)) return LanguageCode.JAPANESE
        if (text.any(::isLatinLetter)) return LanguageCode.ENGLISH
        return hint?.takeIf { it != LanguageCode.CHINESE_SIMPLIFIED }
    }

    private fun englishLemma(word: String): LemmaResult {
        ENGLISH_IRREGULAR[word]?.let { lemma ->
            return LemmaResult(lemma, listOf(lemma), LemmaConfidence.IRREGULAR)
        }

        val candidates = mutableListOf<String>()
        var working = word
        if (working.endsWith("'s") || working.endsWith("’s")) {
            working = working.dropLast(2)
            candidates += working
        } else if (working.endsWith("s'") || working.endsWith("s’")) {
            working = working.dropLast(1)
            candidates += working
        }

        when {
            working.endsWith("ies") && working.length > 3 -> candidates += working.dropLast(3) + "y"
            working.endsWith("ied") && working.length > 3 -> candidates += working.dropLast(3) + "y"
            working.endsWith("ing") && working.length > 4 -> {
                val stem = working.dropLast(3)
                candidates += undoubleFinalConsonant(stem)
                candidates += stem + "e"
                candidates += stem
            }
            working.endsWith("ed") && working.length > 3 -> {
                val stem = working.dropLast(2)
                candidates += undoubleFinalConsonant(stem)
                candidates += stem + "e"
                candidates += stem
            }
            working.endsWith("es") && working.length > 3 -> {
                candidates += working.dropLast(2)
                candidates += working.dropLast(1)
            }
            working.endsWith("s") && working.length > 2 && !working.endsWith("ss") -> {
                candidates += working.dropLast(1)
            }
        }

        val unique = unique(candidates.filter { it.length >= 2 && it != word })
        return if (unique.isEmpty()) {
            LemmaResult(word, listOf(word), LemmaConfidence.EXACT)
        } else {
            LemmaResult(unique.first(), unique, LemmaConfidence.HEURISTIC)
        }
    }

    private fun japaneseLemma(word: String): LemmaResult {
        JAPANESE_IRREGULAR[word]?.let { lemma ->
            return LemmaResult(lemma, listOf(lemma), LemmaConfidence.IRREGULAR)
        }

        val candidates = mutableListOf<String>()
        for (suffix in listOf("ませんでした", "ません", "ました", "ます")) {
            if (!word.endsWith(suffix) || word.length <= suffix.length) continue
            val stem = word.dropLast(suffix.length)
            godanDictionaryForm(stem)?.let(candidates::add)
            candidates += stem + "る"
            break
        }
        if (word.endsWith("ている") && word.length > 3) candidates += word.dropLast(3) + "る"
        if (word.endsWith("ない") && word.length > 2) {
            val stem = word.dropLast(2)
            godanNegativeDictionaryForm(stem)?.let(candidates::add)
            candidates += stem + "る"
        }
        if (word.endsWith("かった") && word.length > 3) candidates += word.dropLast(3) + "い"
        if (word.endsWith("した") && word.length > 2) {
            val stem = word.dropLast(2)
            candidates += stem + "す"
            if (stem.isNotEmpty()) candidates += stem + "する"
        }
        if (word.endsWith("いた") && word.length > 2) candidates += word.dropLast(2) + "く"
        if (word.endsWith("いだ") && word.length > 2) candidates += word.dropLast(2) + "ぐ"
        if (word.endsWith("んだ") && word.length > 2) {
            val stem = word.dropLast(2)
            candidates += listOf(stem + "む", stem + "ぶ", stem + "ぬ")
        }
        if (word.endsWith("った") && word.length > 2) {
            val stem = word.dropLast(2)
            candidates += listOf(stem + "う", stem + "つ", stem + "る")
        }
        if (word.endsWith("た") && word.length > 1) candidates += word.dropLast(1) + "る"

        val unique = unique(candidates.filter { it != word && it.length >= 2 })
        return if (unique.isEmpty()) {
            LemmaResult(word, listOf(word), LemmaConfidence.EXACT)
        } else {
            LemmaResult(unique.first(), unique, LemmaConfidence.HEURISTIC)
        }
    }

    private fun godanDictionaryForm(stem: String): String? {
        val ending = when (stem.lastOrNull()) {
            'い' -> 'う'
            'き' -> 'く'
            'ぎ' -> 'ぐ'
            'し' -> 'す'
            'ち' -> 'つ'
            'に' -> 'ぬ'
            'び' -> 'ぶ'
            'み' -> 'む'
            'り' -> 'る'
            else -> null
        } ?: return null
        return stem.dropLast(1) + ending
    }

    private fun godanNegativeDictionaryForm(stem: String): String? {
        val ending = when (stem.lastOrNull()) {
            'わ' -> 'う'
            'か' -> 'く'
            'が' -> 'ぐ'
            'さ' -> 'す'
            'た' -> 'つ'
            'な' -> 'ぬ'
            'ば' -> 'ぶ'
            'ま' -> 'む'
            'ら' -> 'る'
            else -> null
        } ?: return null
        return stem.dropLast(1) + ending
    }

    private fun undoubleFinalConsonant(stem: String): String {
        if (stem.length < 2) return stem
        val last = stem.last()
        val previous = stem[stem.lastIndex - 1]
        return if (last == previous && last in "bcdfgklmnprst") stem.dropLast(1) else stem
    }

    private fun unique(values: List<String>): List<String> {
        val seen = mutableSetOf<String>()
        return values.filter(seen::add)
    }

    private data class LemmaResult(
        val lemma: String?,
        val candidates: List<String>,
        val confidence: LemmaConfidence
    )

    private companion object {
        val EDGE_PUNCTUATION = charArrayOf(
            '.', ',', '!', '?', ':', ';', '\'', '"', '(', ')', '[', ']', '{', '}',
            '。', '，', '！', '？', '：', '；', '「', '」', '『', '』', '（', '）', '【', '】'
        )

        val ENGLISH_IRREGULAR = mapOf(
            "went" to "go", "gone" to "go", "was" to "be", "were" to "be",
            "been" to "be", "ate" to "eat", "eaten" to "eat", "did" to "do",
            "done" to "do", "had" to "have", "made" to "make", "took" to "take",
            "taken" to "take", "saw" to "see", "seen" to "see", "came" to "come",
            "ran" to "run", "wrote" to "write", "written" to "write", "read" to "read",
            "bought" to "buy", "brought" to "bring", "thought" to "think",
            "taught" to "teach", "spoke" to "speak", "spoken" to "speak",
            "gave" to "give", "given" to "give", "got" to "get", "gotten" to "get",
            "knew" to "know", "known" to "know", "found" to "find", "left" to "leave",
            "felt" to "feel", "kept" to "keep", "told" to "tell", "said" to "say"
        )

        val JAPANESE_IRREGULAR = mapOf(
            "しました" to "する", "しません" to "する", "している" to "する",
            "した" to "する", "来ました" to "来る", "きました" to "くる"
        )

        fun isLatinLetter(character: Char): Boolean =
            character in 'A'..'Z' || character in 'a'..'z' || character in '\u00C0'..'\u024F'

        fun isJapaneseCharacter(character: Char): Boolean =
            character in '\u3040'..'\u30FF' || character in '\u3400'..'\u9FFF'

        fun isLexicalCharacter(character: Char): Boolean =
            isLatinLetter(character) || isJapaneseCharacter(character)
    }
}
