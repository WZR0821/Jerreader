package com.jerreader.android.lexical

/** Local, conservative readings. Unknown or ambiguous words stay unchanged. */
object JapaneseReadingFormatter {
    data class Segment(val text: String, val reading: String? = null)
    fun segments(text: String, lexicon: JapaneseChineseLexicon): List<Segment> {
        val result = mutableListOf<Segment>()
        fun plain(value: String) {
            val last = result.lastOrNull()
            if (last != null && last.reading == null) result[result.lastIndex] = last.copy(text = last.text + value)
            else result += Segment(value)
        }
        var cursor = 0
        while (cursor < text.length) {
            var match: Pair<String, String>? = null
            var ambiguous: String? = null
            val limit = (cursor + 24).coerceAtMost(text.length)
            var end = cursor
            while (end < limit && (isKanji(text[end]) || isKana(text[end]))) end++
            for (stop in end downTo cursor + 1) {
                val token = text.substring(cursor, stop)
                if (token.none(::isKanji)) continue
                val entries = lexicon.matches(token)
                val readings = entries.mapNotNull { entry ->
                    val reading = entry.reading.takeIf { it.isNotEmpty() && it.all(::isKana) } ?: return@mapNotNull null
                    if (entry.term == token) reading
                    else {
                        // Inflections are already verified by the lexical rules. Only
                        // replace their written kana suffix, never invent a kanji reading.
                        val suffix = entry.term.takeLastWhile(::isKana)
                        val stem = entry.term.dropLast(suffix.length)
                        if (suffix.isNotEmpty() && stem.isNotEmpty() && reading.endsWith(suffix) &&
                            token.startsWith(stem) && token.drop(stem.length).all(::isKana))
                            reading.dropLast(suffix.length) + token.drop(stem.length)
                        else null
                    }
                }.distinct()
                if (readings.size == 1) { match = token to readings.single(); break }
                if (entries.any { it.term == token }) { ambiguous = token; break }
            }
            if (ambiguous != null) { plain(ambiguous); cursor += ambiguous.length }
            else if (match == null) {
                val size = Character.charCount(Character.codePointAt(text, cursor))
                plain(text.substring(cursor, cursor + size)); cursor += size
            } else {
                result += splitReading(match.first, match.second)
                cursor += match.first.length
            }
        }
        return result
    }

    private fun splitReading(token: String, reading: String): List<Segment> {
        val runs = mutableListOf<Pair<String, Boolean>>()
        for (char in token) {
            val kanji = isKanji(char)
            if (runs.lastOrNull()?.second == kanji) {
                val last = runs.removeAt(runs.lastIndex); runs += (last.first + char) to kanji
            } else runs += char.toString() to kanji
        }
        val output = mutableListOf<Segment>()
        var remaining = reading
        var pending: String? = null
        for ((index, run) in runs.withIndex()) {
            if (run.second) { pending = run.first; continue }
            val kana = run.first.map { if (it in '\u30A1'..'\u30F6') (it.code - 0x60).toChar() else it }.joinToString("")
            if (pending != null) {
                val offset = if (runs.drop(index + 1).any { it.second }) remaining.indexOf(kana) else remaining.lastIndexOf(kana)
                if (offset <= 0) return listOf(Segment(token, reading))
                output += Segment(pending, remaining.take(offset))
                output += Segment(run.first)
                remaining = remaining.drop(offset + kana.length); pending = null
            } else {
                output += Segment(run.first)
                if (!remaining.startsWith(kana)) return listOf(Segment(token, reading))
                remaining = remaining.drop(kana.length)
            }
        }
        pending?.let { if (remaining.isNotEmpty()) output += Segment(it, remaining) else return listOf(Segment(token, reading)) }
        return output
    }

    private fun isKanji(value: Char) = value in '\u3400'..'\u4DBF' || value in '\u4E00'..'\u9FFF' || value in '\uF900'..'\uFAFF'
    private fun isKana(value: Char) = value in '\u3041'..'\u3096' || value in '\u30A1'..'\u30FA' || value == 'ー'
}
