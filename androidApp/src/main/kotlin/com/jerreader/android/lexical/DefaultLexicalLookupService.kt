package com.jerreader.android.lexical

import com.jerreader.unified.lexical.BundledLexicalLookupService
import com.jerreader.unified.lexical.LexicalLookupFailure
import com.jerreader.unified.lexical.LexicalLookupService
import com.jerreader.unified.lexical.WordExplanation
import com.jerreader.unified.domain.LanguageCode
import kotlinx.coroutines.CancellationException

/** Production chain: instant Chinese core, online Chinese dictionary, then offline JMdict. */
class DefaultLexicalLookupService(
    private val bundled: LexicalLookupService = BundledLexicalLookupService(),
    private val remote: LexicalLookupService = WiktionaryLexicalLookupService(),
    private val offlineJapanese: LexicalLookupService? = null
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
            // Continue to the real dictionaries.
        }
        try {
            return remote.lookup(word, sentenceContext, language, candidates)
        } catch (error: CancellationException) {
            throw error
        } catch (remoteError: LexicalLookupFailure) {
            if (language == LanguageCode.JAPANESE && offlineJapanese != null) {
                try {
                    return offlineJapanese.lookup(word, sentenceContext, language, candidates)
                } catch (_: LexicalLookupFailure.ResultNotFound) {
                    // Preserve the more useful online failure below.
                }
            }
            throw remoteError
        }
    }
}
