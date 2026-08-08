package com.jerreader.android.translation

import com.jerreader.android.data.TranslationCacheDao
import com.jerreader.android.data.TranslationCacheEntity
import com.jerreader.shared.domain.LanguageCode
import com.jerreader.shared.translation.TranslationRequest
import com.jerreader.shared.translation.TranslationResult
import com.jerreader.shared.translation.ContextExplanationRequest
import com.jerreader.shared.translation.ContextExplanationResult
import com.jerreader.shared.translation.TranslationInputPolicy
import java.security.MessageDigest
import java.text.Normalizer

class TranslationCacheRepository(
    private val dao: TranslationCacheDao,
    private val now: () -> Long = System::currentTimeMillis
) {
    suspend fun find(request: TranslationRequest, serviceNamespace: String): TranslationResult? {
        val key = key(request, serviceNamespace)
        val record = dao.cache(key) ?: return null
        if (!TranslationInputPolicy.isVisiblyNonEmpty(record.translatedText)) {
            dao.delete(key)
            return null
        }
        dao.touch(key, now())
        return TranslationResult(
            translatedText = record.translatedText,
            sourceLanguage = language(record.sourceLanguage) ?: return null,
            targetLanguage = language(record.targetLanguage) ?: return null,
            providerIdentifier = record.providerIdentifier,
            isFromCache = true
        )
    }

    suspend fun save(
        request: TranslationRequest,
        serviceNamespace: String,
        result: TranslationResult
    ) {
        val timestamp = now()
        dao.upsert(
            TranslationCacheEntity(
                cacheKey = key(request, serviceNamespace),
                normalizedSourceText = normalized(request.text),
                sourceLanguage = result.sourceLanguage.name,
                targetLanguage = result.targetLanguage.name,
                serviceNamespace = serviceNamespace,
                translatedText = result.translatedText,
                providerIdentifier = result.providerIdentifier,
                createdAtEpochMillis = timestamp,
                lastAccessedAtEpochMillis = timestamp
            )
        )
    }

    suspend fun findExplanation(
        request: ContextExplanationRequest,
        serviceNamespace: String
    ): ContextExplanationResult? {
        val synthetic = explanationRequest(request)
        val record = dao.cache(key(synthetic, serviceNamespace)) ?: return null
        if (!TranslationInputPolicy.isVisiblyNonEmpty(record.translatedText)) {
            dao.delete(record.cacheKey)
            return null
        }
        dao.touch(record.cacheKey, now())
        return ContextExplanationResult(record.translatedText, record.providerIdentifier)
    }

    suspend fun saveExplanation(
        request: ContextExplanationRequest,
        serviceNamespace: String,
        result: ContextExplanationResult
    ) {
        val synthetic = explanationRequest(request)
        val timestamp = now()
        dao.upsert(
            TranslationCacheEntity(
                cacheKey = key(synthetic, serviceNamespace),
                normalizedSourceText = normalized(synthetic.text),
                sourceLanguage = synthetic.sourceLanguage?.name ?: "AUTOMATIC",
                targetLanguage = synthetic.targetLanguage.name,
                serviceNamespace = serviceNamespace,
                translatedText = result.explanation,
                providerIdentifier = result.providerIdentifier,
                createdAtEpochMillis = timestamp,
                lastAccessedAtEpochMillis = timestamp
            )
        )
    }

    private fun explanationRequest(request: ContextExplanationRequest): TranslationRequest =
        TranslationRequest(
            text = buildString {
                append(request.focusedText.trim())
                request.contextText?.trim()?.takeIf(String::isNotBlank)?.let {
                    append("\u001E")
                    append(it.take(2_000))
                }
            },
            sourceLanguage = request.sourceLanguage,
            targetLanguage = LanguageCode.CHINESE_SIMPLIFIED
        )

    private fun key(request: TranslationRequest, namespace: String): String = sha256(
        listOf(
            normalized(request.text),
            request.sourceLanguage?.name ?: "AUTOMATIC",
            request.targetLanguage.name,
            namespace
        ).joinToString("\u001F")
    )

    private fun normalized(text: String): String = Normalizer
        .normalize(text.trim(), Normalizer.Form.NFC)
        .replace(Regex("[\\t\\r\\n ]+"), " ")

    private fun language(raw: String): LanguageCode? =
        LanguageCode.entries.firstOrNull { it.name == raw }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }
}
