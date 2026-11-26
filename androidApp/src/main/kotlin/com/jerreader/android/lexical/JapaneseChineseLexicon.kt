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
    /** Verbs the dictionary marks 一段; a form only a godan verb has never names one (いらない is not いる, きった is not 着る). */
    private val ichidanTerms = entries.filter { it.partOfSpeech.contains("一段") }.map { it.term }.toSet()
    init {
        entries.forEachIndexed { index, entry ->
            listOf(entry.term, entry.display).distinct().forEach { heads.getOrPut(normalize(it)) { mutableListOf() }.add(index) }
            entry.reading.split('／').filter(String::isNotEmpty).forEach { readings.getOrPut(normalize(it)) { mutableListOf() }.add(index) }
            // A kanji headword with no reading cannot conjugate into kana
            // alone; such rows carry する's own forms (安居 listed した).
            val kanaFormsFit = entry.reading.isNotEmpty() || !containsKanji(entry.term)
            entry.forms.filter { kanaFormsFit || containsKanji(it) }
                .forEach { inflections.getOrPut(normalize(it)) { mutableListOf() }.add(index) }
            entry.aliases.forEach { reverse.getOrPut(normalize(it)) { mutableListOf() }.add(index) }
        }
    }
    private fun normalize(value: String) = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
    private fun containsKanji(value: String) = value.any { it.code in 0x3400..0x9FFF }
    private fun inflects(entry: Entry) = entry.partOfSpeech.let { it.contains("动") || it.contains("動") || it.contains("形容") }
    /** Whether a candidate may name this entry: a form only a godan verb has never names a 一段 verb. */
    private fun fits(index: Int, godanOnly: Boolean) = !(godanOnly && entries[index].term in ichidanTerms)

    fun japanese(word: String, candidates: List<String> = emptyList()): Entry? {
        val local = matches(word)
        if (local.isNotEmpty()) return local.firstOrNull().takeIf { local.map { it.term }.distinct().size == 1 }
        for (candidate in candidates) {
            val values = matches(candidate)
            if (values.map { it.term }.distinct().size == 1) return values.first()
        }
        return derived(normalize(word))?.first
    }
    fun matches(word: String, chinese: Boolean = false): List<Entry> = matchIndices(word, chinese).map { entries[it] }
    private fun matchIndices(word: String, chinese: Boolean = false): List<Int> {
        val key = normalize(word)
        if (chinese) return reverse[key].orEmpty().take(20)
        heads[key]?.let { exact ->
            // NFKC folds 🈳 into 空; the headword as written wins.
            val literal = word.trim()
            val written = exact.filter { entries[it].term == literal || entries[it].display == literal }
            val chosen = written.ifEmpty { exact }
            // A kana word the core lists under kanji may be that word, not
            // the supplement's kana headword (いく is 行く or 生く, とき is 時
            // or 鴇): both stay choices, the core's first. A particle keeps
            // its own entry (かな).
            if (isHiraganaWord(key) && chosen.all {
                    entries[it].sourcePage.isNotEmpty() && !entries[it].partOfSpeech.contains("助词")
                }) {
                val core = readings[key].orEmpty().filter { entries[it].sourcePage.isEmpty() }
                if (core.isNotEmpty()) return (core + chosen).take(20)
            }
            return chosen.take(20)
        }
        // A lone hiragana that is no headword is a particle or an ending,
        // never a reading (で is not 出, ね is not 根).
        if (isLoneHiragana(key)) return emptyList()
        val indices = (inflections[key] ?: readings[key]).orEmpty().toMutableList()
        // A potential reading only counts when no other rule finds a
        // headword (入れて is 入れる, not 入る's potential).
        val plain = mutableListOf<Int>()
        val potential = mutableListOf<Int>()
        for (candidate in JapaneseLookupInflection.candidates(key)) {
            val found = heads[candidate.lemma].orEmpty().filter { inflects(entries[it]) && fits(it, candidate.godanOnly) }
            if (candidate.note.startsWith("可能形候选")) potential += found else plain += found
        }
        indices += plain.ifEmpty { potential }
        // A kana word may be a verb form the core rules leave out, next to
        // a word that reads the same (きた is 北 or 来る, くれ is 呉 or
        // くれる); both stay choices.
        if (!JapaneseLookupInflection.hasKanjiOrKatakana(key) && plain.isEmpty() && potential.isEmpty()) {
            for (candidate in JapaneseLookupInflection.allCandidates(key)) {
                indices += heads[candidate.lemma].orEmpty().filter { inflects(entries[it]) && fits(it, candidate.godanOnly) }
            }
        }
        return indices.distinct().take(20)
    }
    fun inflectionNote(word: String, entry: Entry): String? {
        val key = normalize(word)
        JapaneseLookupInflection.note(key, entry.term)?.let { return it }
        // A する verb listed under its noun (散歩した is 散歩 + する).
        JapaneseLookupInflection.note(key, entry.term + "する")?.let { return it }
        if (!JapaneseLookupInflection.hasKanjiOrKatakana(key)) {
            JapaneseLookupInflection.allCandidates(key).firstOrNull { it.lemma == entry.term }
                ?.let { return "词典形「${entry.term}」；${it.note}。规则分析候选，请结合原句确认。" }
        }
        if (matches(key).isNotEmpty()) return null
        val derived = derived(key) ?: return null
        return derived.second.takeIf { derived.first.term == entry.term }
    }

    /**
     * A whole word a short tap selects may not be in the dictionary as it
     * stands: a する verb (勉強しました → 勉強), a form two rules from its
     * headword (読んでいました → 読む), a desire, conditional or volitional
     * form (食べたい → 食べる), a name or plural suffix (子供たち → 子供) or an
     * honorific prefix (ご主人 → 主人). Only an answer with one headword
     * counts, and only after the word itself and its candidates found none.
     */
    private fun derived(key: String): Pair<Entry, String>? {
        if (isLoneHiragana(key)) return null
        resolve(key, prefix = true, suffix = true, exact = false)?.let { (index, notes) ->
            return entries[index] to (notes.joinToString("；") + "。规则分析候选，请结合原句确认。")
        }
        return compoundVerb(key)
    }

    private fun resolve(key: String, prefix: Boolean, suffix: Boolean, exact: Boolean): Pair<Int, List<String>>? {
        if (exact) {
            val found = matchIndices(key)
            val first = found.firstOrNull()
            if (first != null) {
                if (found.map { entries[it].term }.distinct().size != 1) return null
                val note = JapaneseLookupInflection.candidates(key).firstOrNull { it.lemma == entries[first].term }
                return first to (note?.let { listOf("词典形「${entries[first].term}」", it.note) } ?: emptyList())
            }
        }
        // A set phrase in the past (すみませんでした).
        if (key.endsWith("ませんでした")) {
            val phrase = heads[key.dropLast(3)].orEmpty()
            if (phrase.isNotEmpty() && phrase.map { entries[it].term }.distinct().size == 1) {
                return phrase[0] to listOf("「${entries[phrase[0]].term}」＋过去「でした」")
            }
        }
        inflected(key)?.let { return it }
        if (suffix) {
            for ((ending, kind) in JapaneseLookupInflection.nounSuffixes) {
                if (!key.endsWith(ending) || key.length <= ending.length) continue
                val noun = key.dropLast(ending.length)
                if (!JapaneseLookupInflection.hasKanjiOrKatakana(noun) || (ending == "か" && noun !in listOf("何", "誰"))) continue
                // さま after お or ご is polite, not a name (お客様).
                val honorific = ending in listOf("さま", "様") && listOf("お", "ご", "御").any { noun.startsWith(it) }
                val label = if (honorific) "敬语后缀" else kind
                if (kind == "称呼后缀") {
                    val named = heads[noun].orEmpty().firstOrNull { entries[it].partOfSpeech.contains("专有") }
                    if (named != null) return named to listOf("$label「$ending」")
                }
                val found = resolve(noun, prefix, suffix = false, exact = true)
                if (found != null) return found.first to (found.second + "$label「$ending」")
            }
        }
        if (prefix) {
            for (honorific in listOf("お", "ご", "御")) {
                if (!key.startsWith(honorific)) continue
                val rest = key.drop(honorific.length)
                val head = rest.firstOrNull() ?: continue
                if (head.code !in 0x3400..0x9FFF) continue
                val found = resolve(rest, prefix = false, suffix = suffix, exact = true) ?: continue
                return found.first to (listOf("美化语前缀「$honorific」") + found.second)
            }
        }
        return null
    }

    /** A verb or adjective one or two rules from [key], the forms listed under a headword, or the noun of a する verb. */
    private fun inflected(key: String): Pair<Int, List<String>>? {
        val first = JapaneseLookupInflection.allCandidates(key)
        val direct = first.map { Triple(it.lemma, it.note, it.godanOnly) }
        // A volitional form never inflects again (聞こえて is no form of 聞こう).
        // Nor does a form only a godan verb has: a rule's verb in る is a
        // 一段 one (開ける, 読ませる), so 開けった is no form of 開く. The
        // inner rule names the lemma, so its godan-only mark is the one kept.
        val twice = first.filterNot { it.godanOnly }.flatMap { outer ->
            JapaneseLookupInflection.allCandidates(outer.lemma).filterNot { it.note.contains("意志") }
                .map { Triple(it.lemma, "${it.note} · ${outer.note}", it.godanOnly) }
        }
        // A potential reading only counts when nothing else does
        // (しれない is 知れる, not 知る's potential).
        val potential = { path: Triple<String, String, Boolean> -> path.second.contains("可能形") }
        val levels = listOf(direct.filterNot(potential), direct.filter(potential),
            twice.filterNot(potential), twice.filter(potential))
        for (paths in levels) {
            if (paths.isEmpty()) continue
            // Headwords first, then forms listed under a headword, then the
            // reading of a kana lemma (しれる → 知れる); a later source never
            // turns an answer into a tie.
            for (source in 0 until 3) {
                val found = mutableListOf<Pair<Int, List<String>>>()
                // The noun of a する verb only counts when no verb does
                // (話していました is 話す, not 話 + する).
                val nouns = mutableListOf<Pair<Int, List<String>>>()
                for (path in paths) {
                    val (lemma, note, godanOnly) = path
                    when (source) {
                        0 -> {
                            heads[lemma].orEmpty().filter { inflects(entries[it]) && fits(it, godanOnly) }
                                .forEach { found += it to listOf("词典形「$lemma」", note) }
                            if (lemma.endsWith("する")) {
                                val noun = lemma.dropLast(2)
                                if (JapaneseLookupInflection.hasKanjiOrKatakana(noun)) {
                                    heads[noun].orEmpty().forEach { nouns += it to listOf("词典形「$lemma」", note) }
                                }
                            }
                        }
                        // A potential reading names a headword itself, never
                        // a form listed under another one (聞こう under 聞く),
                        // and a 一段 verb has no godan form (入れった).
                        1 -> inflections[lemma].orEmpty().takeUnless { potential(path) || (godanOnly && lemma in ichidanTerms) }
                            .orEmpty().filter { fits(it, godanOnly) }
                            .forEach { found += it to listOf("词典形「${entries[it].term}」", "经由「$lemma」", note) }
                        else -> if (!JapaneseLookupInflection.hasKanjiOrKatakana(lemma)) {
                            readings[lemma].orEmpty().filter { inflects(entries[it]) && fits(it, godanOnly) }
                                .forEach { found += it to listOf("词典形「${entries[it].term}」", note) }
                        }
                    }
                }
                if (found.isEmpty()) found += nouns
                if (found.isNotEmpty()) {
                    return found.first().takeIf { found.map { entries[it.first].term }.distinct().size == 1 }
                }
            }
        }
        return null
    }

    /**
     * A compound verb a short tap selects whole when the dictionary lacks
     * it (走り去った, 読み終わった): the first verb's entry, with the second
     * verb and its form in the note.
     */
    private fun compoundVerb(key: String): Pair<Entry, String>? {
        val stemEnd = key.indexOfFirst { !isKanji(it) }
        if (stemEnd <= 0 || stemEnd + 1 >= key.length || !isKanji(key[stemEnd + 1])) return null
        val head = inflected(key.substring(0, stemEnd + 1))?.takeIf { "连用形" in it.second } ?: return null
        val tail = resolve(key.substring(stemEnd + 1), prefix = false, suffix = false, exact = true) ?: return null
        if (entries[tail.first].partOfSpeech.split('／').none { it.endsWith("动词") && !it.startsWith("形容") }) return null
        val notes = listOf("复合动词：前项「${entries[head.first].term}」＋后项「${entries[tail.first].term}」") +
            tail.second.filterNot { it.startsWith("词典形") }
        return entries[head.first] to (notes.joinToString("；") + "。规则分析候选，请结合原句确认。")
    }

    private fun isKanji(char: Char) = char.code in 0x3400..0x9FFF
    private fun isLoneHiragana(key: String) = key.length == 1 && key[0].code in 0x3040..0x309F
    private fun isHiraganaWord(key: String) = key.length >= 2 && key.all { it.code in 0x3040..0x309F }
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
    /**
     * [godanOnly]: the form only a godan verb has (っ, り, ろ, or ら but not
     * られ before the ending), so the lemma is never a 一段 verb.
     */
    data class Candidate(val lemma: String, val note: String, val godanOnly: Boolean = false)
    fun candidates(word: String): List<Candidate> = candidates(word, rules)

    /** The core rules and the forms a short tap selects as a whole word. */
    fun allCandidates(word: String): List<Candidate> = candidates(word, allRules)

    private val godanEndings = setOf("う", "く", "ぐ", "す", "つ", "ぬ", "ぶ", "む", "る")

    private fun candidates(word: String, table: List<Triple<String, String, String>>): List<Candidate> {
        if (word.isEmpty() || word.length > 80 || word.any { it.isWhitespace() || it in "。！？、" }) return emptyList()
        // A kana word is never read as a potential form (いれる is not いる's).
        val kanaOnly = !hasKanjiOrKatakana(word)
        return table.mapNotNull { (suffix, ending, note) ->
            if (!word.endsWith(suffix) || (kanaOnly && note.startsWith("可能形候选"))) null else {
                val stem = word.dropLast(suffix.length)
                val lemma = stem + ending
                // An empty stem never leaves a lone godan ending (した → す, not する).
                if (lemma == word || lemma.isEmpty() || (stem.isEmpty() && lemma in godanEndings)) null
                else Candidate(lemma, note, godanOnly = ending == "る" && godanOnly(suffix))
            }
        }.distinctBy { it.lemma }
    }

    /**
     * A 一段 verb keeps its stem before every ending (いる → いた, いない,
     * います); っ, り, ろ and ら (but not られ) come only from a godan る verb
     * (要る → いった, いらない, いります).
     */
    private fun godanOnly(suffix: String) =
        listOf("っ", "り", "ろ").any { suffix.startsWith(it) } || (suffix.startsWith("ら") && !suffix.startsWith("られ"))

    /** Suffixes after a name or a noun; か only after 何 and 誰. */
    internal val nounSuffixes = listOf("さん" to "称呼后缀", "くん" to "称呼后缀", "ちゃん" to "称呼后缀",
        "さま" to "称呼后缀", "様" to "称呼后缀", "たち" to "复数后缀", "達" to "复数后缀", "か" to "不定助词")

    fun hasKanjiOrKatakana(text: String) = text.any { it.code in 0x3400..0x9FFF || it.code in 0x30A0..0x30FF }
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

    /**
     * Forms outside the core table, the same as iOS: desire, conditional,
     * listing, volitional and ず forms, more te-form tails, する after a noun,
     * すぎる, やすい and にくい, the copula, 来る in kana and a bare stem.
     */
    internal val extendedRules: List<Triple<String, String, String>> = buildList {
        fun rule(suffix: String, ending: String, note: String) { add(Triple(suffix, ending, note)) }
        val dictionary = "うくぐすつぬぶむる".map { it.toString() }
        val politeStems = "いきぎしちにびみり".map { it.toString() }
        val negativeStems = "わかがさたなばまら".map { it.toString() }
        val conditionalStems = "えけげせてねべめれ".map { it.toString() }
        val volitionalStems = "おこごそとのぼもろ".map { it.toString() }
        for ((suffix, note) in listOf("たい" to "愿望", "たかった" to "愿望 · 过去", "たくない" to "愿望 · 否定",
            "たくなかった" to "愿望 · 否定 · 过去", "たくて" to "愿望 · て形", "たく" to "愿望 · 连用",
            "ましょう" to "礼貌体 · 劝诱", "ながら" to "同时进行", "なさい" to "命令", "そう" to "样态")) {
            for ((stem, ending) in politeStems.zip(dictionary)) rule(stem + suffix, ending, note)
            rule(suffix, "る", note)
            rule("し" + suffix, "する", note)
            rule("来" + suffix, "来る", note)
        }
        val past = listOf("った" to "う", "った" to "つ", "った" to "る", "いた" to "く", "いだ" to "ぐ",
            "した" to "す", "んだ" to "む", "んだ" to "ぶ", "んだ" to "ぬ", "た" to "る",
            "した" to "する", "来た" to "来る", "行った" to "行く", "いった" to "行く", "いった" to "言う", "かった" to "い")
        for ((suffix, ending) in past) {
            rule(suffix + "ら", ending, "条件（たら）")
            rule(suffix + "り", ending, "列举（たり）")
        }
        for ((stem, ending) in conditionalStems.zip(dictionary)) rule(stem + "ば", ending, "条件（ば）")
        for ((suffix, ending) in listOf("ければ" to "い", "なければ" to "ない", "すれば" to "する", "来れば" to "来る")) {
            rule(suffix, ending, "条件（ば）")
        }
        val negatives = listOf("ず" to "否定（ず）", "ずに" to "否定（ずに）", "なくて" to "否定 · て形", "なく" to "否定 · 连用",
            "なければ" to "否定 · 条件（ば）", "ないで" to "否定 · ないで")
        for ((suffix, note) in negatives) {
            for ((stem, ending) in negativeStems.zip(dictionary)) rule(stem + suffix, ending, note)
            rule(suffix, "る", note)
            rule((if (suffix.startsWith("ず")) "せ" else "し") + suffix, "する", note)
            rule("来" + suffix, "来る", note)
        }
        for ((stem, ending) in volitionalStems.zip(dictionary)) rule(stem + "う", ending, "意志 / 劝诱")
        for ((suffix, ending) in listOf("よう" to "る", "しよう" to "する", "来よう" to "来る")) rule(suffix, ending, "意志 / 劝诱")
        val teForms = listOf("って" to "う", "って" to "つ", "って" to "る", "いて" to "く", "いで" to "ぐ",
            "して" to "す", "んで" to "む", "んで" to "ぶ", "んで" to "ぬ", "て" to "る", "して" to "する",
            "いって" to "行く", "いって" to "言う")
        val tails = listOf("いる" to "进行 / 结果状态", "いた" to "进行 / 结果状态 · 过去", "いない" to "进行 / 结果状态 · 否定",
            "います" to "进行 / 结果状态 · 礼貌体", "いました" to "进行 / 结果状态 · 礼貌体 · 过去",
            "いません" to "进行 / 结果状态 · 礼貌体 · 否定", "いなかった" to "进行 / 结果状态 · 否定 · 过去",
            "いませんでした" to "进行 / 结果状态 · 礼貌体 · 否定 · 过去")
        for ((suffix, ending) in teForms) for ((tail, note) in tails) rule(suffix + tail, ending, note)
        // ていく, てくる and てくれる after a te-form (出ていった, 持ってきた).
        val directions = listOf("いく" to "趋向（ていく）", "いった" to "趋向（ていく） · 过去", "いって" to "趋向（ていく） · て形",
            "いかない" to "趋向（ていく） · 否定", "いきます" to "趋向（ていく） · 礼貌体", "いきました" to "趋向（ていく） · 礼貌体 · 过去",
            "いかなかった" to "趋向（ていく） · 否定 · 过去", "いったら" to "趋向（ていく） · 条件（たら）",
            "くる" to "趋向（てくる）", "きた" to "趋向（てくる） · 过去", "きて" to "趋向（てくる） · て形",
            "こない" to "趋向（てくる） · 否定", "きます" to "趋向（てくる） · 礼貌体", "きました" to "趋向（てくる） · 礼貌体 · 过去",
            "こなかった" to "趋向（てくる） · 否定 · 过去", "きたら" to "趋向（てくる） · 条件（たら）", "こよう" to "趋向（てくる） · 意志 / 劝诱",
            // てくれる, so 言ってくれた is never 言ってくる's potential.
            "くれる" to "授受（てくれる）", "くれた" to "授受（てくれる） · 过去", "くれて" to "授受（てくれる） · て形",
            "くれない" to "授受（てくれる） · 否定", "くれなかった" to "授受（てくれる） · 否定 · 过去",
            "くれます" to "授受（てくれる） · 礼貌体", "くれました" to "授受（てくれる） · 礼貌体 · 过去",
            "くれません" to "授受（てくれる） · 礼貌体 · 否定", "くれ" to "授受（てくれる） · 命令")
        for ((suffix, ending) in teForms) for ((tail, note) in directions) rule(suffix + tail, ending, note)
        // 行く and 言う written in kana: いった and いって are either one.
        for (lemma in listOf("行く", "言う")) {
            rule("いった", lemma, "过去 / 完成")
            rule("いって", lemma, "て形 · 连接")
        }
        for ((suffix, note) in listOf("される" to "被动", "された" to "被动 · 过去", "されて" to "被动 · て形",
            "されない" to "被动 · 否定", "されました" to "被动 · 礼貌体 · 过去", "させる" to "使役",
            "させた" to "使役 · 过去", "させて" to "使役 · て形", "させない" to "使役 · 否定",
            "させられる" to "使役被动", "させられた" to "使役被动 · 过去", "できた" to "可能 · 过去",
            "できない" to "可能 · 否定", "できなかった" to "可能 · 否定 · 过去", "できます" to "可能 · 礼貌体",
            "できました" to "可能 · 礼貌体 · 过去", "できません" to "可能 · 礼貌体 · 否定")) {
            rule(suffix, "する", note)
        }
        for ((suffix, note) in listOf("そう" to "い形容词 · 样态", "くて" to "い形容词 · て形", "く" to "い形容词 · 连用")) {
            rule(suffix, "い", note)
        }
        rule("そう", "", "形容动词 · 样态")
        // The stem a short tap selects before そう (悲し|そう, おいし|そう).
        rule("し", "しい", "い形容词词干（后接そう、すぎる等）")
        // てしまう and でしまう said short (読ん|じゃった, 食べ|ちゃう).
        for ((suffix, note) in listOf("ちゃう" to "「てしまう」的缩约", "ちゃった" to "「てしまう」的缩约 · 过去",
            "ちゃって" to "「てしまう」的缩约 · て形", "じゃう" to "「でしまう」的缩约",
            "じゃった" to "「でしまう」的缩约 · 过去", "じゃって" to "「でしまう」的缩约 · て形")) {
            rule(suffix, "しまう", note)
        }
        for ((suffix, note) in listOf("すぎる" to "过度（すぎる）", "すぎた" to "过度 · 过去", "すぎて" to "过度 · て形",
            "すぎない" to "过度 · 否定", "すぎます" to "过度 · 礼貌体", "すぎました" to "过度 · 礼貌体 · 过去", "すぎ" to "过度")) {
            for ((stem, ending) in politeStems.zip(dictionary)) rule(stem + suffix, ending, note)
            rule(suffix, "る", note)
            rule("し" + suffix, "する", note)
            rule(suffix, "い", "い形容词 · $note")
            rule(suffix, "", "形容动词 · $note")
        }
        for ((suffix, note) in listOf("やすい" to "容易……（やすい）", "やすく" to "容易……（やすい） · 连用",
            "にくい" to "难以……（にくい）", "にくく" to "难以……（にくい） · 连用", "づらい" to "难以……（づらい）")) {
            for ((stem, ending) in politeStems.zip(dictionary)) rule(stem + suffix, ending, note)
            rule(suffix, "る", note)
            rule("し" + suffix, "する", note)
        }
        for ((suffix, ending, note) in listOf(Triple("だった", "だ", "过去"), Triple("だったら", "だ", "条件（たら）"),
            Triple("だって", "だ", "て形"), Triple("だろう", "だ", "推量"), Triple("でしょう", "です", "推量"),
            Triple("じゃない", "だ", "否定"), Triple("じゃなかった", "だ", "否定 · 过去"), Triple("ではない", "だ", "否定"),
            Triple("ではなかった", "だ", "否定 · 过去"))) {
            rule(suffix, ending, note)
        }
        // 来る written in kana, as after a te-form (帰ってきます).
        for ((form, note) in listOf("きます" to "礼貌体", "きました" to "礼貌体 · 过去", "きません" to "礼貌体 · 否定",
            "きた" to "过去", "きて" to "て形", "きたら" to "条件（たら）", "こない" to "否定",
            "こなかった" to "否定 · 过去", "こよう" to "意志 / 劝诱", "くれば" to "条件（ば）")) {
            rule(form, "来る", note)
        }
        // A bare continuative stem before すぎ, そう or a compound (食べ → 食べる, 飲み → 飲む).
        for ((stem, ending) in politeStems.zip(dictionary)) rule(stem, ending, "连用形")
        for (stem in "いきぎしじちにびみりえけげせぜてでねべぺめれ".map { it.toString() }) rule(stem, stem + "る", "连用形")
    }

    internal val allRules: List<Triple<String, String, String>> = (rules + extendedRules).sortedByDescending { it.first.length }
}
