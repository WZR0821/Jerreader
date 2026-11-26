package com.jerreader.android.reader

import android.icu.text.BreakIterator
import android.icu.util.ULocale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Word boundaries for a short tap on Japanese, from the platform's ICU.
 *
 * Chromium's Android ICU data leaves out the Chinese/Japanese dictionary and
 * breaks Japanese by `word_ja.txt`, which only chains runs of kanji, of
 * hiragana and of katakana. Its `Intl.Segmenter('ja')` therefore runs a
 * particle on into the word after it (家|にいる, 時間|がない) and splits a
 * kanji from its okurigana (振|り|返|った), whichever WebView version a phone
 * has. android.icu keeps the dictionary, so its words are the ones the
 * short-tap rules were written against (家|に|いる), the same rules iOS
 * applies to NLTokenizer's words, and a tap selects the word iOS selects.
 */
internal object JapaneseWordBreaks {
    /**
     * `{"text": …, "breaks": [[start, end, wordLike], …]}` for the short-tap
     * script, in UTF-16 offsets as JavaScript counts them; null when the
     * platform could not break [text], so the script falls back to WebView's
     * segmenter.
     */
    fun json(text: String): String? = runCatching {
        val breaks = JSONArray()
        for (item in breaks(text)) {
            breaks.put(JSONArray().put(item.first).put(item.last).put(if (item.wordLike) 1 else 0))
        }
        JSONObject().put("text", text).put("breaks", breaks).toString()
    }.getOrNull()

    /** The segments as `segment|segment°|…`, ° marking a non-word, for test evidence. */
    fun describe(text: String): String =
        breaks(text).joinToString("|") { text.substring(it.first, it.last) + if (it.wordLike) "" else "°" }

    private class Segment(val first: Int, val last: Int, val wordLike: Boolean)

    private fun breaks(text: String): List<Segment> {
        val iterator = BreakIterator.getWordInstance(ULocale.JAPANESE)
        iterator.setText(text)
        val segments = mutableListOf<Segment>()
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            // Rule statuses below WORD_NONE_LIMIT are spaces and punctuation,
            // the ones Intl.Segmenter reports with isWordLike false.
            segments += Segment(start, end, iterator.ruleStatus >= BreakIterator.WORD_NONE_LIMIT)
            start = end
            end = iterator.next()
        }
        return segments
    }
}
