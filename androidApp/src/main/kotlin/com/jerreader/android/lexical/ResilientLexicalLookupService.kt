package com.jerreader.android.lexical

import com.jerreader.shared.domain.LanguageCode
import com.jerreader.shared.lexical.LexicalLookupFailure
import com.jerreader.shared.lexical.LexicalLookupService
import com.jerreader.shared.lexical.WordExplanation
import com.jerreader.shared.translation.ContextExplanationRequest
import com.jerreader.shared.translation.ContextExplanationService
import kotlinx.coroutines.CancellationException

/**
 * Keeps the key-free dictionary as the first choice, then uses the user's
 * configured AI service when Wikimedia is unavailable (common on mainland
 * networks). The fallback still returns only domain models to the UI.
 */
class ResilientLexicalLookupService(
    private val dictionary: LexicalLookupService,
    private val explanationService: ContextExplanationService
) : LexicalLookupService {
    override suspend fun lookup(
        word: String,
        sentenceContext: String?,
        language: LanguageCode,
        candidates: List<String>
    ): WordExplanation {
        try {
            return dictionary.lookup(word, sentenceContext, language, candidates)
        } catch (error: CancellationException) {
            throw error
        } catch (dictionaryError: LexicalLookupFailure) {
            try {
                val output = explanationService.explain(
                    ContextExplanationRequest(
                        focusedText = word,
                        contextText = sentenceContext,
                        sourceLanguage = language
                    )
                )
                return WordExplanation(
                    surfaceForm = word.trim(),
                    lemma = candidates.firstOrNull { it.isNotBlank() && it != word },
                    reading = null,
                    language = language,
                    partOfSpeech = null,
                    definitions = listOf(output.explanation),
                    inflectionNote = candidates.firstOrNull { it.isNotBlank() && it != word }
                        ?.let { "候选基本形：$it" },
                    usageNote = "中文维基词典不可用，已使用你配置的 AI 服务按当前语境解释。",
                    sentenceContext = sentenceContext,
                    providerIdentifier = output.providerIdentifier
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                throw dictionaryError
            }
        }
    }
}
