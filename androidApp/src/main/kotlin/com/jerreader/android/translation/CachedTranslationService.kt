package com.jerreader.android.translation

import com.jerreader.shared.translation.AIActionRequest
import com.jerreader.shared.translation.AIActionResult
import com.jerreader.shared.translation.AIAnswerSource
import com.jerreader.shared.translation.AIReadingActionService
import com.jerreader.shared.translation.ContextExplanationRequest
import com.jerreader.shared.translation.ContextExplanationResult
import com.jerreader.shared.translation.ContextExplanationService
import com.jerreader.shared.translation.StreamingContextExplanationService
import com.jerreader.shared.translation.StreamingTranslationService
import com.jerreader.shared.translation.TranslationProviderMode
import com.jerreader.shared.translation.TranslationRequest
import com.jerreader.shared.translation.TranslationResult
import com.jerreader.shared.translation.TranslationService
import com.jerreader.shared.translation.TranslationInputPolicy
import com.jerreader.shared.translation.TranslationFailure
import com.jerreader.shared.translation.explainShowingPartials
import com.jerreader.shared.translation.translateShowingPartials

/**
 * Answers from the cache when it can, otherwise asks [delegate] and keeps
 * what it answered. Streamed text (2.1.0) passes through to the card and is
 * never cached: only a whole answer that passed the checks is kept, and a
 * stream that fails after showing text leaves nothing behind. A cached
 * answer comes back at once, without partial text, with the badge
 * [cachedAnswerSource] gives it.
 */
class CachedTranslationService internal constructor(
    private val delegate: TranslationService,
    private val cache: TranslationCacheRepository,
    /** The namespace of the provider that answers, for the standalone page's override when it has one. */
    private val translationNamespaceBase: (StandaloneTranslationOverride?) -> String,
    private val explanationNamespaceBase: () -> String,
    /**
     * The badge of a cached translation (the request and the overridden
     * mode, if any) or of a cached explanation; the cache keeps no badges.
     */
    private val cachedAnswerSource: (TranslationProviderMode?, TranslationRequest?, ContextExplanationRequest?) -> AIAnswerSource?
) : StreamingTranslationService, StreamingContextExplanationService, AIReadingActionService {
    constructor(
        delegate: TranslationService,
        cache: TranslationCacheRepository,
        settings: AndroidTranslationSettingsStore
    ) : this(
        delegate = delegate,
        cache = cache,
        translationNamespaceBase = { override -> settings.cacheNamespace(override?.providerMode, override?.promptTemplate) },
        explanationNamespaceBase = { settings.aiFeatureCacheNamespace() },
        cachedAnswerSource = { mode, translation, explanation ->
            val configured = delegate as? ConfiguredTranslationService
            when {
                configured == null -> null
                translation != null -> configured.cachedTranslationSource(translation, mode)
                explanation != null -> configured.cachedExplanationSource(explanation)
                else -> null
            }
        }
    )

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
        val namespace = translationNamespace(translationNamespaceBase(override))
        cachedTranslation(request, namespace, override)?.let { return it }
        val translated = when {
            override == null -> delegate.translate(request)
            delegate is ConfiguredTranslationService -> delegate.translate(request, override)
            else -> delegate.translate(request)
        }
        return kept(request, namespace, translated)
    }

    override suspend fun translateStreaming(
        request: TranslationRequest,
        onPartial: (String) -> Unit
    ): TranslationResult {
        val namespace = translationNamespace(translationNamespaceBase(null))
        cachedTranslation(request, namespace, override = null)?.let { return it }
        return kept(request, namespace, delegate.translateShowingPartials(request, onPartial))
    }

    override suspend fun explain(request: ContextExplanationRequest): ContextExplanationResult =
        explaining(request) { service -> service.explain(request) }

    override suspend fun explainStreaming(
        request: ContextExplanationRequest,
        onPartial: (String) -> Unit
    ): ContextExplanationResult = explaining(request) { service -> service.explainShowingPartials(request, onPartial) }

    /** 直译 and 追问 are kept only in the card's memory, never in this cache. */
    override suspend fun perform(request: AIActionRequest, onPartial: (String) -> Unit): AIActionResult {
        val service = delegate as? AIReadingActionService ?: throw TranslationFailure.ConfigurationMissing
        return service.perform(request, onPartial)
    }

    private suspend fun cachedTranslation(
        request: TranslationRequest,
        namespace: String,
        override: StandaloneTranslationOverride?
    ): TranslationResult? = cache.find(request, namespace)?.let { hit ->
        if (hit.answerSource != null) hit else hit.copy(answerSource = cachedAnswerSource(override?.providerMode, request, null))
    }

    /**
     * Keeps [translated] when it is a whole, visible answer; anything else
     * fails and is not kept. The fallback's answer is kept under the
     * fallback's namespace, as iOS keeps it under the fallback provider's
     * identity: under the configured service's it would come back with that
     * service's badge and stand in for that service's answer from then on.
     */
    private suspend fun kept(
        request: TranslationRequest,
        namespace: String,
        translated: TranslationResult
    ): TranslationResult {
        if (!TranslationInputPolicy.isVisiblyNonEmpty(translated.translatedText)) {
            throw TranslationFailure.ServiceUnavailable
        }
        val keptUnder = translated.fallbackProvider
            ?.let { fallback -> translationNamespace(translationNamespaceBase(StandaloneTranslationOverride(fallback))) }
            ?: namespace
        cache.save(request, keptUnder, translated)
        return translated
    }

    private suspend fun explaining(
        request: ContextExplanationRequest,
        answer: suspend (ContextExplanationService) -> ContextExplanationResult
    ): ContextExplanationResult {
        val service = delegate as? ContextExplanationService
            ?: error("当前翻译服务不支持 AI 解析。")
        val namespace = explanationNamespace()
        cache.findExplanation(request, namespace)?.let { hit ->
            return if (hit.answerSource != null) hit else hit.copy(answerSource = cachedAnswerSource(null, null, request))
        }
        return answer(service).also { result ->
            if (!TranslationInputPolicy.isVisiblyNonEmpty(result.explanation)) {
                throw TranslationFailure.ServiceUnavailable
            }
            cache.saveExplanation(request, namespace, result)
        }
    }

    /**
     * 2.1.0 sends the sentence with its context in tags (iOS
     * `jerreader-direct-api-v2`), and every AI instruction ends with the
     * guard, so nothing cached from the earlier prompts is reused.
     */
    private fun translationNamespace(base: String): String = "$base|$TRANSLATION_VERSION"

    private fun explanationNamespace(): String = "${explanationNamespaceBase()}|explain|$EXPLANATION_VERSION"

    private companion object {
        const val TRANSLATION_VERSION = "jerreader-direct-api-v2"
        const val EXPLANATION_VERSION = "guard-v2"
    }
}
