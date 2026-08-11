package com.jerreader.android.lexical

import android.content.Context
import com.jerreader.unified.domain.LanguageCode
import com.jerreader.unified.lexical.JmdictTsvLexicon
import com.jerreader.unified.lexical.LexicalLookupFailure
import com.jerreader.unified.lexical.LexicalLookupPolicy
import com.jerreader.unified.lexical.LexicalLookupService
import com.jerreader.unified.lexical.WordExplanation

/** Native resource loading around the shared JMdict decoder and lookup rules. */
class JmdictOfflineLexicalLookupService(
    context: Context
) : LexicalLookupService {
    private val applicationContext = context.applicationContext
    private val lexicon: JmdictTsvLexicon by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val raw = applicationContext.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
        JmdictTsvLexicon.parse(raw)
    }

    override suspend fun lookup(
        word: String,
        sentenceContext: String?,
        language: LanguageCode,
        candidates: List<String>
    ): WordExplanation {
        val surface = LexicalLookupPolicy.validate(word, language)
        if (language != LanguageCode.JAPANESE) throw LexicalLookupFailure.ResultNotFound
        val entry = lexicon.firstEntry(listOf(surface) + candidates)
            ?: throw LexicalLookupFailure.ResultNotFound
        return WordExplanation(
            surfaceForm = surface,
            lemma = entry.lemma.takeIf { it != surface },
            reading = entry.reading,
            language = language,
            partOfSpeech = entry.partOfSpeech,
            definitions = entry.definitions,
            usageNote = "离线 JMdict 兜底词条，释义为英文；联网时优先显示中文维基词典。",
            sentenceContext = sentenceContext,
            providerIdentifier = JmdictTsvLexicon.PROVIDER_IDENTIFIER
        )
    }

    private companion object {
        const val ASSET_PATH = "dictionaries/jmdict-common.tsv"
    }
}
