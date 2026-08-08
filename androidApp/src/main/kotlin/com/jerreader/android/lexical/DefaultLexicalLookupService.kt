package com.jerreader.android.lexical

import com.jerreader.shared.lexical.BundledLexicalLookupService
import com.jerreader.shared.lexical.LexicalLookupFailure
import com.jerreader.shared.lexical.LexicalLookupService
import com.jerreader.shared.lexical.WordExplanation
import com.jerreader.shared.domain.LanguageCode

/** Production lookup chain: an instant bundled core, followed by a key-free real dictionary. */
class DefaultLexicalLookupService(
    private val bundled: LexicalLookupService = BundledLexicalLookupService(),
    private val remote: LexicalLookupService = WiktionaryLexicalLookupService()
) : LexicalLookupService {
    override suspend fun lookup(
        word: String,
        sentenceContext: String?,
        language: LanguageCode,
        candidates: List<String>
    ): WordExplanation {
        try {
            return bundled.lookup(word, sentenceContext, language, candidates)
        } catch (_: LexicalLookupFailure.ResultNotFound) {
            return remote.lookup(word, sentenceContext, language, candidates)
        }
    }
}
