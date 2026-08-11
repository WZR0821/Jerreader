package com.jerreader.android.lexical

import android.net.Uri
import com.jerreader.unified.domain.LanguageCode
import com.jerreader.unified.lexical.LexicalLookupFailure
import com.jerreader.unified.lexical.LexicalLookupPolicy
import com.jerreader.unified.lexical.LexicalLookupService
import com.jerreader.unified.lexical.WordExplanation
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.coroutineContext

/** Key-free real dictionary adapter. Third-party JSON and wiki markup stop at this boundary. */
class WiktionaryLexicalLookupService(
    private val client: DictionaryHttpClient = UrlConnectionDictionaryHttpClient()
) : LexicalLookupService {
    override suspend fun lookup(
        word: String,
        sentenceContext: String?,
        language: LanguageCode,
        candidates: List<String>
    ): WordExplanation {
        val surface = LexicalLookupPolicy.validate(word, language)
        val lookupTerms = buildList {
            add(surface)
            addAll(candidates)
        }.map { if (language == LanguageCode.ENGLISH) it.lowercase() else it }
            .filter(String::isNotBlank)
            .distinct()

        var receivedResponse = false
        for (term in lookupTerms) {
            coroutineContext.ensureActive()
            try {
                val wikitext = decodeWikitext(client.get(requestUrl(term))) ?: continue
                receivedResponse = true
                val parsed = WiktionaryEntryParser.parse(wikitext, language) ?: continue
                return WordExplanation(
                    surfaceForm = surface,
                    lemma = term.takeIf { it != surface && it.lowercase() != surface.lowercase() },
                    reading = parsed.reading,
                    language = language,
                    partOfSpeech = parsed.partOfSpeech,
                    definitions = parsed.definitions,
                    inflectionNote = term.takeIf { it != surface }?.let {
                        "该词为活用或变化形式；词典形候选为「$it」。"
                    },
                    usageNote = "释义来自中文维基词典，请结合原句选择符合语境的义项。",
                    sentenceContext = sentenceContext,
                    providerIdentifier = "zh-wiktionary-v1"
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: DictionaryNotFoundException) {
                receivedResponse = true
            } catch (_: IOException) {
                throw LexicalLookupFailure.ServiceUnavailable
            } catch (_: Exception) {
                throw LexicalLookupFailure.ServiceUnavailable
            }
        }
        throw if (receivedResponse) {
            LexicalLookupFailure.ResultNotFound
        } else {
            LexicalLookupFailure.ServiceUnavailable
        }
    }

    private fun requestUrl(term: String): URL = URL(
        Uri.Builder()
            .scheme("https")
            .authority("zh.wiktionary.org")
            .path("w/api.php")
            .appendQueryParameter("action", "parse")
            .appendQueryParameter("page", term)
            .appendQueryParameter("prop", "wikitext")
            .appendQueryParameter("format", "json")
            .appendQueryParameter("formatversion", "2")
            .build()
            .toString()
    )

    private fun decodeWikitext(payload: String): String? {
        val root = JSONObject(payload)
        if (root.has("error")) throw DictionaryNotFoundException()
        return root.optJSONObject("parse")?.optString("wikitext")?.takeIf(String::isNotBlank)
    }

}

fun interface DictionaryHttpClient {
    @Throws(IOException::class, DictionaryNotFoundException::class)
    suspend fun get(url: URL): String
}

class UrlConnectionDictionaryHttpClient : DictionaryHttpClient {
    override suspend fun get(url: URL): String = withContext(Dispatchers.IO) {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Jerreader/1.0.3 (Android dictionary lookup)")
        }
        try {
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_NOT_FOUND) throw DictionaryNotFoundException()
            if (status !in 200..299) throw IOException("Dictionary HTTP $status")
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}

class DictionaryNotFoundException : Exception()

internal data class ParsedDictionaryEntry(
    val reading: String?,
    val partOfSpeech: String?,
    val definitions: List<String>
)

internal object WiktionaryEntryParser {
    fun parse(wikitext: String, language: LanguageCode): ParsedDictionaryEntry? {
        val acceptedHeadings = when (language) {
            LanguageCode.JAPANESE -> setOf("日语", "日語")
            LanguageCode.ENGLISH -> setOf("英语", "英語")
            LanguageCode.CHINESE_SIMPLIFIED -> emptySet()
        }
        var inRequestedLanguage = false
        var currentPartOfSpeech: String? = null
        var resultPartOfSpeech: String? = null
        var reading: String? = null
        val definitions = mutableListOf<String>()

        for (rawLine in wikitext.lineSequence()) {
            val line = rawLine.trim()
            val heading = heading(line)
            if (heading != null) {
                val (level, title) = heading
                if (level == 2) {
                    if (inRequestedLanguage) break
                    inRequestedLanguage = title.replace(" ", "") in acceptedHeadings
                    currentPartOfSpeech = null
                } else if (inRequestedLanguage) {
                    currentPartOfSpeech = canonicalPartOfSpeech(title)
                }
                continue
            }
            if (!inRequestedLanguage) continue
            if (reading == null) reading = extractReading(line, language)
            if (!line.startsWith("#") || line.startsWith("##") || line.startsWith("#:") || line.startsWith("#*")) {
                continue
            }
            val definition = cleanWikiMarkup(line.drop(1))
            if (definition.isNotBlank() && definition !in definitions) {
                definitions += definition
                if (resultPartOfSpeech == null) resultPartOfSpeech = currentPartOfSpeech
            }
            if (definitions.size == 3) break
        }
        if (definitions.isEmpty()) return null
        return ParsedDictionaryEntry(reading, resultPartOfSpeech, definitions)
    }

    private fun heading(line: String): Pair<Int, String>? {
        val leading = line.takeWhile { it == '=' }.length
        val trailing = line.takeLastWhile { it == '=' }.length
        if (leading !in 2..6 || leading != trailing || line.length <= leading + trailing) return null
        return leading to line.substring(leading, line.length - trailing).trim()
    }

    private fun canonicalPartOfSpeech(heading: String): String? {
        val mappings = listOf(
            listOf("动词", "動詞", "verb") to "动词",
            listOf("名词", "名詞", "noun") to "名词",
            listOf("形容词", "形容詞", "adjective") to "形容词",
            listOf("副词", "副詞", "adverb") to "副词",
            listOf("介词", "介詞", "preposition") to "介词",
            listOf("助词", "助詞", "particle") to "助词",
            listOf("代词", "代詞", "pronoun") to "代词",
            listOf("连词", "連詞", "conjunction") to "连词",
            listOf("感叹词", "感嘆詞", "interjection") to "感叹词"
        )
        val normalized = heading.lowercase()
        return mappings.firstOrNull { (tokens, _) -> tokens.any(normalized::contains) }?.second
    }

    private fun extractReading(line: String, language: LanguageCode): String? {
        val regex = when (language) {
            LanguageCode.JAPANESE -> Regex("""\{\{ja-pron\|([^|}]+)""")
            LanguageCode.ENGLISH -> Regex("""\{\{IPA\|en\|([^|}]+)""")
            LanguageCode.CHINESE_SIMPLIFIED -> return null
        }
        return regex.find(line)?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotBlank)
    }

    private fun cleanWikiMarkup(raw: String): String {
        var value = raw
        repeat(12) {
            val replaced = value.replace(Regex("""\{\{[^{}]*\}\}"""), "")
            if (replaced == value) return@repeat
            value = replaced
        }
        value = value
            .replace(Regex("""\[\[([^\]|]+)\|([^\]]+)\]\]"""), "$2")
            .replace(Regex("""\[\[([^\]]+)\]\]"""), "$1")
            .replace(Regex("""<!--.*?-->"""), "")
            .replace(Regex("""<[^>]+>"""), "")
            .replace("'''", "")
            .replace("''", "")
            .replace("-{", "")
            .replace("}-", "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace(Regex("""\s+"""), " ")
        return value.trim(' ', '；', ';', ',', '，')
    }
}
