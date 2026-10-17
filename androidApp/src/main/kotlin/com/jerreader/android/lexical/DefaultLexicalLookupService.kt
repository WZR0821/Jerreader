package com.jerreader.android.lexical

import com.jerreader.shared.lexical.BundledLexicalLookupService
import com.jerreader.shared.lexical.LexicalLookupFailure
import com.jerreader.shared.lexical.LexicalLookupService
import com.jerreader.shared.lexical.WordExplanation
import com.jerreader.shared.domain.LanguageCode
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Production lookup chain: an instant bundled core, followed by a key-free real dictionary. */
class DefaultLexicalLookupService(
    private val offline: LexicalLookupService? = null,
    private val bundled: LexicalLookupService = BundledLexicalLookupService(),
    private val remote: LexicalLookupService = WiktionaryLexicalLookupService()
) : LexicalLookupService {
    override suspend fun lookup(
        word: String,
        sentenceContext: String?,
        language: LanguageCode,
        candidates: List<String>
    ): WordExplanation {
        currentCoroutineContext().ensureActive()
        if (offline != null) {
            try {
                val result = offline.lookup(word, sentenceContext, language, candidates)
                currentCoroutineContext().ensureActive()
                return result
            }
            catch (_: LexicalLookupFailure.ResultNotFound) { /* Continue through the existing lookup chain. */ }
        }
        currentCoroutineContext().ensureActive()
        try {
            val result = bundled.lookup(word, sentenceContext, language, candidates)
            currentCoroutineContext().ensureActive()
            return result
        } catch (_: LexicalLookupFailure.ResultNotFound) {
            currentCoroutineContext().ensureActive()
            val result = remote.lookup(word, sentenceContext, language, candidates)
            currentCoroutineContext().ensureActive()
            return result
        }
    }
}
