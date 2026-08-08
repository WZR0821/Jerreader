package com.jerreader.android.translation

import com.jerreader.shared.translation.ContextExplanationRequest
import com.jerreader.shared.translation.ContextExplanationResult
import com.jerreader.shared.translation.ContextExplanationService
import com.jerreader.shared.translation.TranslationRequest
import com.jerreader.shared.translation.TranslationResult
import com.jerreader.shared.translation.TranslationService
import com.jerreader.shared.translation.TranslationInputPolicy
import com.jerreader.shared.translation.TranslationFailure

class CachedTranslationService(
    private val delegate: TranslationService,
    private val cache: TranslationCacheRepository,
    private val settings: AndroidTranslationSettingsStore
) : TranslationService, ContextExplanationService {
    override val identifier: String = "cached-${delegate.identifier}"

    override suspend fun translate(request: TranslationRequest): TranslationResult =
        translate(request, override = null)

    /**
     * The standalone page still gets the cache, but under its own namespace, so
     * a word looked up through one provider is never served back as the answer
     * of another.
     */
    suspend fun translate(
        request: TranslationRequest,
        override: StandaloneTranslationOverride?
    ): TranslationResult {
        val namespace = settings.cacheNamespace(override?.providerMode, override?.promptTemplate)
        cache.find(request, namespace)?.let { return it }
        val translated = when {
            override == null -> delegate.translate(request)
            delegate is ConfiguredTranslationService -> delegate.translate(request, override)
            else -> delegate.translate(request)
        }
        if (!TranslationInputPolicy.isVisiblyNonEmpty(translated.translatedText)) {
            throw TranslationFailure.ServiceUnavailable
        }
        cache.save(request, namespace, translated)
        return translated
    }

    override suspend fun explain(request: ContextExplanationRequest): ContextExplanationResult {
        val service = delegate as? ContextExplanationService
            ?: error("当前翻译服务不支持 AI 解析。")
        val namespace = "${settings.cacheNamespace()}|explain"
        cache.findExplanation(request, namespace)?.let { return it }
        return service.explain(request).also { result ->
            if (!TranslationInputPolicy.isVisiblyNonEmpty(result.explanation)) {
                throw TranslationFailure.ServiceUnavailable
            }
            cache.saveExplanation(request, namespace, result)
        }
    }
}
