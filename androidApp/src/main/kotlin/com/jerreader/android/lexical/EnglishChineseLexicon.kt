package com.jerreader.android.lexical

import com.jerreader.shared.domain.LanguageCode
import com.jerreader.shared.lexical.WordExplanation
import java.text.Normalizer
import java.util.Locale

/** Pinned, attributed entries; exact headwords precede verified inflected forms. */
class EnglishChineseLexicon(tsv: String) {
    private data class Entry(val term: String, val reading: String, val pos: String,
        val definitions: List<String>, val forms: List<String>)
    private val entries = tsv.lineSequence().filterNot { it.startsWith('#') }.mapNotNull { line ->
        val fields = line.split('\t')
        if (fields.size != 5 || fields[0].isBlank() || fields[3].isBlank()) null
        else Entry(fields[0], fields[1], fields[2], fields[3].replace("\\n", "\n").lines().filter(String::isNotBlank),
            fields[4].split('|').filter(String::isNotBlank))
    }.toList()
    private val heads = entries.associateBy { normalize(it.term) }
    private val forms: Map<String, Entry> = buildMap {
        this@EnglishChineseLexicon.entries.forEach { entry ->
            entry.forms.forEach { form -> putIfAbsent(normalize(form), entry) }
        }
    }
    val headwordCount get() = heads.size

    fun lookup(word: String, context: String?, candidates: List<String>): WordExplanation? {
        val terms = (listOf(word) + candidates).map(::normalize).distinct()
        val entry = terms.firstNotNullOfOrNull { heads[it] ?: forms[it] } ?: return null
        return WordExplanation(surfaceForm = word, lemma = entry.term,
            reading = entry.reading.takeIf(String::isNotBlank), language = LanguageCode.ENGLISH,
            partOfSpeech = entry.pos.takeIf(String::isNotBlank), definitions = entry.definitions,
            sentenceContext = context, usageNote = "内置英语基础词典 · ECDICT · MIT",
            providerIdentifier = "ecdict-bundled-core-bc015ed")
    }

    private fun normalize(word: String) = Normalizer.normalize(word.trim(), Normalizer.Form.NFKC).lowercase(Locale.ROOT)
}
