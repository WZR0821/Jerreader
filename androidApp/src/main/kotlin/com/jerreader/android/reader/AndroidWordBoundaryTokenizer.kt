package com.jerreader.android.reader

import android.os.Build
import com.jerreader.unified.domain.LanguageCode
import com.jerreader.unified.lexical.TextToken
import com.jerreader.unified.lexical.WordBoundaryTokenizer
import java.util.Locale

class AndroidWordBoundaryTokenizer : WordBoundaryTokenizer {
    override fun tokenize(text: String, language: LanguageCode): List<TextToken> {
        if (text.isBlank()) return emptyList()
        val locale = when (language) {
            LanguageCode.JAPANESE -> Locale.JAPANESE
            LanguageCode.ENGLISH -> Locale.ENGLISH
            LanguageCode.CHINESE_SIMPLIFIED -> Locale.SIMPLIFIED_CHINESE
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            tokenizeWithIcu(text, locale)
        } else {
            tokenizeWithJava(text, locale)
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.N)
    private fun tokenizeWithIcu(text: String, locale: Locale): List<TextToken> {
        val iterator = android.icu.text.BreakIterator.getWordInstance(locale)
        iterator.setText(text)
        val tokens = mutableListOf<TextToken>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != android.icu.text.BreakIterator.DONE) {
            val candidate = text.substring(start, end)
            if (iterator.ruleStatus != android.icu.text.BreakIterator.WORD_NONE && candidate.isLexical()) {
                tokens += TextToken(candidate, start, end)
            }
            start = end
            end = iterator.next()
        }
        return tokens
    }

    private fun tokenizeWithJava(text: String, locale: Locale): List<TextToken> {
        val iterator = java.text.BreakIterator.getWordInstance(locale)
        iterator.setText(text)
        val tokens = mutableListOf<TextToken>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != java.text.BreakIterator.DONE) {
            val candidate = text.substring(start, end)
            if (candidate.isLexical()) tokens += TextToken(candidate, start, end)
            start = end
            end = iterator.next()
        }
        return tokens
    }

    private fun String.isLexical(): Boolean = any { character ->
        character.isLetter() || character in '\u3040'..'\u30FF' || character in '\u3400'..'\u9FFF'
    }
}
