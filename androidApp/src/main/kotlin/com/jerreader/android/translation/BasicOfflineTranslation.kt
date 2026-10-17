package com.jerreader.android.translation

import com.jerreader.android.lexical.OfflineDictionaryPackageStore
import com.jerreader.shared.domain.LanguageCode
import com.jerreader.shared.lexical.LexicalLookupFailure
import com.jerreader.shared.translation.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Exact dictionary equivalents only. Never concatenate word glosses into a pretend sentence. */
class BasicOfflineTranslation(private val dictionary: OfflineDictionaryPackageStore) {
    suspend fun translate(request: TranslationRequest): TranslationResult? = withContext(Dispatchers.IO) {
        val source = request.sourceLanguage ?: return@withContext null
        if (request.text.length > 80) return@withContext null
        if (source == LanguageCode.CHINESE_SIMPLIFIED && request.targetLanguage == LanguageCode.JAPANESE) {
            return@withContext dictionary.japaneseChinese.chinese(request.text)?.let {
                TranslationResult(it, source, request.targetLanguage, "中日词典 · 离线")
            }
        }
        if (source == LanguageCode.CHINESE_SIMPLIFIED || request.targetLanguage != LanguageCode.CHINESE_SIMPLIFIED)
            return@withContext null
        val entry = try { dictionary.lookup(request.text, null, source, emptyList()) }
            catch (_: LexicalLookupFailure.ResultNotFound) { return@withContext null }
        TranslationResult(entry.definitions.joinToString("；"), source, request.targetLanguage, "本地词典 · 离线")
    }
}
