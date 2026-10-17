package com.jerreader.android.lexical

import java.text.Normalizer
import java.net.URLEncoder

/** Same attributed TSV and exact indexes as iOS; no fuzzy reverse translation. */
class JapaneseChineseLexicon(tsv: String, supplement: String = "") {
    data class Entry(val term: String, val reading: String, val partOfSpeech: String,
        val definition: String, val aliases: List<String>, val forms: List<String>, val sourcePage: String) {
        val display: String get() = if (reading.isEmpty()) term else "$term（$reading）"
        val attribution: String get() = if (sourcePage.isEmpty()) "Jerreader 基础词条" else "中文维基词典贡献者 · CC BY-SA 4.0"
        val sourceUrl: String? get() = sourcePage.takeIf(String::isNotEmpty)?.let {
            "https://zh.wiktionary.org/wiki/" + URLEncoder.encode(it, "UTF-8").replace("+", "%20") + "#日語"
        }
    }
    val entries = (tsv + "\n" + supplement).lineSequence().filterNot { it.startsWith("#") }.mapNotNull { line ->
        val fields = line.split('\t')
        if (fields.size !in listOf(5, 7) || fields[0].isEmpty() || fields[3].isEmpty()) null
        else Entry(fields[0], fields[1], fields[2], fields[3], fields[4].split('|').filter(String::isNotEmpty),
            if (fields.size == 7) fields[5].split('|').filter(String::isNotEmpty) else emptyList(),
            if (fields.size == 7) fields[6] else "")
    }.toList()
    val headwordCount = entries.map { it.term }.toSet().size
    private val heads = mutableMapOf<String, MutableList<Int>>()
    private val readings = mutableMapOf<String, MutableList<Int>>()
    private val inflections = mutableMapOf<String, MutableList<Int>>()
    private val reverse = mutableMapOf<String, MutableList<Int>>()
    init {
        entries.forEachIndexed { index, entry ->
            listOf(entry.term, entry.display).distinct().forEach { heads.getOrPut(normalize(it)) { mutableListOf() }.add(index) }
            entry.reading.split('／').filter(String::isNotEmpty).forEach { readings.getOrPut(normalize(it)) { mutableListOf() }.add(index) }
            entry.forms.forEach { inflections.getOrPut(normalize(it)) { mutableListOf() }.add(index) }
            entry.aliases.forEach { reverse.getOrPut(normalize(it)) { mutableListOf() }.add(index) }
        }
    }
    private fun normalize(value: String) = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)

    fun japanese(word: String, candidates: List<String> = emptyList()): Entry? {
        val local = matches(word)
        if (local.isNotEmpty()) return local.firstOrNull().takeIf { local.map { it.term }.distinct().size == 1 }
        for (candidate in candidates) {
            val values = matches(candidate)
            if (values.map { it.term }.distinct().size == 1) return values.first()
        }
        return null
    }
    fun matches(word: String, chinese: Boolean = false): List<Entry> {
        val key = normalize(word)
        if (chinese) return reverse[key].orEmpty().take(20).map { entries[it] }
        heads[key]?.let { return it.take(20).map { index -> entries[index] } }
        val indices = (inflections[key] ?: readings[key]).orEmpty().toMutableList()
        for (candidate in JapaneseLookupInflection.candidates(key)) {
            indices += heads[candidate.lemma].orEmpty().filter {
                val pos = entries[it].partOfSpeech
                pos.contains("动") || pos.contains("動") || pos.contains("形容")
            }
        }
        return indices.distinct().take(20).map { entries[it] }
    }
    fun inflectionNote(word: String, entry: Entry): String? = JapaneseLookupInflection.note(normalize(word), entry.term)
    fun chinese(word: String): String? {
        val values = matches(word, chinese = true)
        val preferred = values.filter { it.sourcePage.isEmpty() }
        return (preferred.ifEmpty { values }).map { it.display }.distinct()
            .takeIf { it.isNotEmpty() }?.joinToString("；")
    }
    companion object {
        const val VERSION = "2026-09-01"
        const val SOURCE = "https://kaikki.org/zhwiktionary/日语/"
        const val LICENSE = "https://creativecommons.org/licenses/by-sa/4.0/"
    }
}

/** One-step, bounded candidate rules, verified against dictionary entries by the lexicon. */
object JapaneseLookupInflection {
    data class Candidate(val lemma: String, val note: String)
    fun candidates(word: String): List<Candidate> {
        if (word.isEmpty() || word.length > 80 || word.any { it.isWhitespace() || it in "。！？、" }) return emptyList()
        return rules.mapNotNull { (suffix, ending, note) ->
            if (!word.endsWith(suffix)) null else {
                val lemma = word.dropLast(suffix.length) + ending
                if (lemma == word) null else Candidate(lemma, note)
            }
        }.distinctBy { it.lemma }
    }
    fun note(word: String, lemma: String): String? = candidates(word).firstOrNull { it.lemma == lemma }?.let {
        "词典形「$lemma」；${it.note}。规则分析候选，请结合原句确认。"
    }
    // Same bounded rule families and priority as iOS.
    internal val rules: List<Triple<String, String, String>> = buildList {
        fun rule(suffix: String, ending: String, note: String) { add(Triple(suffix, ending, note)) }
        val polite = listOf("ませんでした" to "礼貌体 · 否定 · 过去", "ません" to "礼貌体 · 否定", "ました" to "礼貌体 · 过去", "ます" to "礼貌体")
        val dictionary = "うくぐすつぬぶむる".map { it.toString() }
        val politeStems = "いきぎしちにびみり".map { it.toString() }
        val negativeStems = "わかがさたなばまら".map { it.toString() }
        for ((suffix, note) in polite) {
            for ((stem, ending) in politeStems.zip(dictionary)) rule(stem + suffix, ending, note)
            rule(suffix, "る", note)
        }
        for ((suffix, note) in listOf("なかった" to "否定 · 过去", "ない" to "否定")) {
            for ((stem, ending) in negativeStems.zip(dictionary)) rule(stem + suffix, ending, note)
            rule(suffix, "る", note)
        }
        val pastAndTe = listOf("った" to "う", "った" to "つ", "った" to "る", "いた" to "く", "いだ" to "ぐ", "した" to "す",
            "んだ" to "む", "んだ" to "ぶ", "んだ" to "ぬ", "た" to "る", "って" to "う", "って" to "つ", "って" to "る",
            "いて" to "く", "いで" to "ぐ", "して" to "す", "んで" to "む", "んで" to "ぶ", "んで" to "ぬ", "て" to "る")
        for ((suffix, ending) in pastAndTe) {
            val isTe = suffix.endsWith("て") || suffix.endsWith("で")
            rule(suffix, ending, if (isTe) "て形 · 连接" else "过去 / 完成")
            if (isTe) for ((tail, note) in listOf("いる" to "进行 / 结果状态", "いた" to "进行 / 结果状态 · 过去",
                "いない" to "进行 / 结果状态 · 否定", "います" to "进行 / 结果状态 · 礼貌体")) rule(suffix + tail, ending, note)
        }
        for ((suffix, note) in listOf("かった" to "い形容词 · 过去", "くない" to "い形容词 · 否定", "くなかった" to "い形容词 · 否定 · 过去")) rule(suffix, "い", note)
        val voices = mutableListOf(Triple("させられる", "る", "使役被动"), Triple("させる", "る", "使役"), Triple("られる", "る", "可能 / 被动 / 尊敬（需结合原句）"))
        for ((stem, ending) in negativeStems.zip(dictionary)) voices += listOf(Triple(stem + "れる", ending, "被动 / 尊敬（需结合原句）"),
            Triple(stem + "せる", ending, "使役"), Triple(stem + "せられる", ending, "使役被动"))
        for ((stem, ending) in "えけげせてねべめれ".map { it.toString() }.zip(dictionary)) voices += Triple(stem + "る", ending, "可能形候选")
        for ((suffix, ending, note) in voices) {
            rule(suffix, ending, note)
            for ((tail, tense) in listOf("た" to "过去", "て" to "て形", "ない" to "否定", "なかった" to "否定 · 过去") + polite)
                rule(suffix.dropLast(1) + tail, ending, "$note · $tense")
        }
        for ((suffix, ending, note) in listOf(Triple("した", "する", "过去"), Triple("して", "する", "て形"), Triple("しない", "する", "否定"),
            Triple("しなかった", "する", "否定 · 过去"), Triple("できる", "する", "可能"), Triple("来た", "来る", "过去"),
            Triple("来て", "来る", "て形"), Triple("来ない", "来る", "否定"), Triple("行った", "行く", "过去"), Triple("行って", "行く", "て形"))) rule(suffix, ending, note)
        for ((suffix, note) in polite) { rule("し" + suffix, "する", note); rule("来" + suffix, "来る", note) }
    }.sortedByDescending { it.first.length }
}
