package com.jerreader.unified.lexical

/** A compact entry decoded from Jerreader's generated JMdict common-word asset. */
data class JmdictTsvEntry(
    val lemma: String,
    val reading: String,
    val partOfSpeech: String?,
    val definitions: List<String>
)

/**
 * Platform-neutral decoder and index for the bundled JMdict asset.
 *
 * The asset is generated at release time instead of teaching either mobile
 * host to parse the 100+ MB upstream JSON/XML document. Lines are escaped TSV:
 * forms, lemma, reading, part of speech, and English glosses. Multiple forms
 * and glosses use U+001F. Both hosts therefore use the same data and matching
 * rules while retaining native resource loading.
 */
class JmdictTsvLexicon private constructor(
    private val entriesByForm: Map<String, List<JmdictTsvEntry>>
) {
    fun firstEntry(terms: List<String>): JmdictTsvEntry? {
        terms.forEach { term ->
            val normalizedTerm = normalize(term)
            entriesByForm[normalizedTerm]
                ?.minByOrNull { entry ->
                    when {
                        normalize(entry.lemma) == normalizedTerm -> 0
                        normalize(entry.reading) == normalizedTerm -> 1
                        else -> 2
                    }
                }
                ?.let { return it }
        }
        return null
    }

    fun entries(term: String): List<JmdictTsvEntry> =
        entriesByForm[normalize(term)].orEmpty()

    companion object {
        const val PROVIDER_IDENTIFIER: String = "jmdict-common-2026-07-20"
        private const val LIST_SEPARATOR: String = "\u001F"

        fun parse(raw: String): JmdictTsvLexicon {
            val index = linkedMapOf<String, MutableList<JmdictTsvEntry>>()
            raw.lineSequence().forEach { line ->
                if (line.isBlank() || line.startsWith("#")) return@forEach
                val columns = splitEscapedTsv(line)
                if (columns.size != 5) return@forEach
                val forms = columns[0].split(LIST_SEPARATOR).map(::unescape).filter(String::isNotBlank)
                val entry = JmdictTsvEntry(
                    lemma = unescape(columns[1]),
                    reading = unescape(columns[2]),
                    partOfSpeech = unescape(columns[3]).takeIf(String::isNotBlank),
                    definitions = columns[4].split(LIST_SEPARATOR)
                        .map(::unescape)
                        .filter(String::isNotBlank)
                )
                if (forms.isEmpty() || entry.definitions.isEmpty()) return@forEach
                forms.forEach { form ->
                    index.getOrPut(normalize(form)) { mutableListOf() }.apply {
                        if (none { it.lemma == entry.lemma && it.reading == entry.reading }) add(entry)
                    }
                }
            }
            return JmdictTsvLexicon(index)
        }

        private fun splitEscapedTsv(line: String): List<String> {
            val columns = mutableListOf<String>()
            val current = StringBuilder()
            var escaped = false
            line.forEach { character ->
                when {
                    escaped -> {
                        current.append('\\')
                        current.append(character)
                        escaped = false
                    }
                    character == '\\' -> escaped = true
                    character == '\t' -> {
                        columns += current.toString()
                        current.clear()
                    }
                    else -> current.append(character)
                }
            }
            if (escaped) current.append('\\')
            columns += current.toString()
            return columns
        }

        private fun unescape(value: String): String {
            val output = StringBuilder(value.length)
            var escaped = false
            value.forEach { character ->
                if (escaped) {
                    output.append(
                        when (character) {
                            't' -> '\t'
                            'n' -> '\n'
                            'r' -> '\r'
                            else -> character
                        }
                    )
                    escaped = false
                } else if (character == '\\') {
                    escaped = true
                } else {
                    output.append(character)
                }
            }
            if (escaped) output.append('\\')
            return output.toString()
        }

        private fun normalize(value: String): String = value.trim().lowercase()
    }
}
