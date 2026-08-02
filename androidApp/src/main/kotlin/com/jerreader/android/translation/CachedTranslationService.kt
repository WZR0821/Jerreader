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

    override suspend fun translate(request: TranslationRequest): TranslationResult {
        val namespace = settings.cacheNamespace()
        cache.find(request, namespace)?.let { return it }
        return delegate.translate(request).also { result ->
            if (!TranslationInputPolicy.isVisiblyNonEmpty(result.translatedText)) {
                throw TranslationFailure.ServiceUnavailable
            }
            cache.save(request, namespace, result)
        }
    }

    override suspend fun explain(request: ContextExplanationRequest): ContextExplanationResult {
        val service = delegate as? ContextExplanationService
            ?: error("当前翻译服务不支持 AI 解析。")
        return service.explain(request)
    }
}
