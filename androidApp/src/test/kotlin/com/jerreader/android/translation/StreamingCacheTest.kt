package com.jerreader.android.translation

import com.jerreader.android.data.TranslationCacheDao
import com.jerreader.android.data.TranslationCacheEntity
import com.jerreader.shared.domain.LanguageCode
import com.jerreader.shared.translation.AIAnswerSource
import com.jerreader.shared.translation.ContextExplanationRequest
import com.jerreader.shared.translation.ContextExplanationResult
import com.jerreader.shared.translation.StreamingContextExplanationService
import com.jerreader.shared.translation.StreamingTranslationService
import com.jerreader.shared.translation.TranslationFailure
import com.jerreader.shared.translation.TranslationProviderMode
import com.jerreader.shared.translation.TranslationRequest
import com.jerreader.shared.translation.TranslationResult
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Only a whole answer that passed the checks is cached (2.1.0): partial text
 * passes through to the card and never reaches the cache, a stream that
 * fails after showing text leaves nothing behind, and the fallback's answer
 * is kept apart from the configured service's.
 */
class StreamingCacheTest {
    private val rows = mutableMapOf<String, TranslationCacheEntity>()
    private val dao = Proxy.newProxyInstance(
        TranslationCacheDao::class.java.classLoader,
        arrayOf(TranslationCacheDao::class.java)
    ) { _, method, args ->
        when (method.name) {
            "cache" -> rows[args[0] as String]
            "upsert" -> {
                val value = args[0] as TranslationCacheEntity
                rows[value.cacheKey] = value
                Unit
            }
            "touch" -> Unit
            "delete" -> {
                rows.remove(args[0] as String)
                Unit
            }
            else -> error("Unexpected DAO call: ${method.name}")
        }
    } as TranslationCacheDao

    private val badge = AIAnswerSource("gpt-test", isAIModel = true)

    private fun cached(delegate: StreamingFake) = CachedTranslationService(
        delegate = delegate,
        cache = TranslationCacheRepository(dao),
        translationNamespaceBase = { "test" },
        explanationNamespaceBase = { "test" },
        cachedAnswerSource = { _, _, _ -> badge }
    )

    @Test fun aStreamThatFailsLeavesNothingCached() = runBlocking<Unit> {
        val delegate = StreamingFake(pieces = listOf("半", "半句"), failure = TranslationFailure.ServiceUnavailable)
        val shown = mutableListOf<String>()
        val failure = runCatching { cached(delegate).translateStreaming(request, shown::add) }.exceptionOrNull()
        assertEquals(TranslationFailure.ServiceUnavailable, failure)
        assertEquals(listOf("半", "半句"), shown)
        assertTrue("a partial answer was cached: $rows", rows.isEmpty())

        val explanation = runCatching { cached(delegate).explainStreaming(explanationRequest, shown::add) }.exceptionOrNull()
        assertEquals(TranslationFailure.ServiceUnavailable, explanation)
        assertTrue("a partial explanation was cached: $rows", rows.isEmpty())
    }

    @Test fun onlyTheWholeAnswerIsCachedAndServedBack() = runBlocking<Unit> {
        val delegate = StreamingFake(pieces = listOf("她", "她回家"), final = "她回家了。")
        val service = cached(delegate)
        val shown = mutableListOf<String>()
        val first = service.translateStreaming(request, shown::add)
        assertEquals("她回家了。", first.translatedText)
        assertEquals(listOf("她回家了。"), rows.values.map { it.translatedText })

        // The second time the cache answers at once: no partial text, no request.
        shown.clear()
        val second = service.translateStreaming(request, shown::add)
        assertTrue(second.isFromCache)
        assertEquals("她回家了。", second.translatedText)
        assertEquals(badge, second.answerSource)
        assertTrue(shown.isEmpty())
        assertEquals(1, delegate.translations)
    }

    @Test fun theFallbacksAnswerIsKeptUnderTheFallbacksNamespace() = runBlocking<Unit> {
        // The configured service failed and the system translator answered.
        val delegate = StreamingFake(pieces = listOf("她"), final = "她回家了。", fallback = TranslationProviderMode.ON_DEVICE)
        val service = CachedTranslationService(
            delegate = delegate,
            cache = TranslationCacheRepository(dao),
            translationNamespaceBase = { override -> override?.providerMode?.name ?: "configured" },
            explanationNamespaceBase = { "test" },
            cachedAnswerSource = { _, _, _ -> badge }
        )
        assertEquals("她回家了。", service.translateStreaming(request) {}.translatedText)
        assertTrue(rows.values.toString(), rows.values.single().serviceNamespace.startsWith("ON_DEVICE|"))

        // The next tap asks the configured service again instead of getting the
        // fallback's answer under the configured service's badge.
        delegate.fallback = null
        assertFalse(service.translateStreaming(request) {}.isFromCache)
        assertEquals(2, delegate.translations)

        // The standalone page that picks the system translator gets it from the cache.
        assertTrue(service.translate(request, StandaloneTranslationOverride(TranslationProviderMode.ON_DEVICE)).isFromCache)
        assertEquals(2, delegate.translations)
    }

    @Test fun aBlankAnswerIsNotCached() = runBlocking<Unit> {
        val delegate = StreamingFake(pieces = listOf(" "), final = " \n ")
        val failure = runCatching { cached(delegate).translateStreaming(request) {} }.exceptionOrNull()
        assertEquals(TranslationFailure.ServiceUnavailable, failure)
        assertTrue(rows.isEmpty())
    }

    @Test fun onlyTheWholeExplanationIsCached() = runBlocking<Unit> {
        val delegate = StreamingFake(pieces = listOf("句意"), final = "句意：他回家了。")
        val service = cached(delegate)
        val shown = mutableListOf<String>()
        assertEquals("句意：他回家了。", service.explainStreaming(explanationRequest, shown::add).explanation)
        assertEquals(listOf("句意"), shown)
        assertEquals(listOf("句意：他回家了。"), rows.values.map { it.translatedText })
        val again = service.explainStreaming(explanationRequest) { error("no partial text from the cache") }
        assertEquals("句意：他回家了。", again.explanation)
        assertEquals(badge, again.answerSource)
        assertEquals(1, delegate.explanations)
    }

    private val request = TranslationRequest("She went home.", LanguageCode.ENGLISH, context = "It was late. She went home.")
    private val explanationRequest = ContextExplanationRequest("She went home.", "It was late. She went home.", LanguageCode.ENGLISH)

    private class StreamingFake(
        private val pieces: List<String>,
        private val final: String = pieces.last(),
        private val failure: Exception? = null,
        /** The fallback that answers in the configured service's place, if one does. */
        var fallback: TranslationProviderMode? = null
    ) : StreamingTranslationService, StreamingContextExplanationService {
        override val identifier: String = "fake"
        var translations = 0
        var explanations = 0

        override suspend fun translate(request: TranslationRequest): TranslationResult = translateStreaming(request) {}

        override suspend fun translateStreaming(request: TranslationRequest, onPartial: (String) -> Unit): TranslationResult {
            translations += 1
            pieces.forEach(onPartial)
            failure?.let { throw it }
            return TranslationResult(final, LanguageCode.ENGLISH, request.targetLanguage, identifier, fallbackProvider = fallback)
        }

        override suspend fun explain(request: ContextExplanationRequest): ContextExplanationResult = explainStreaming(request) {}

        override suspend fun explainStreaming(
            request: ContextExplanationRequest,
            onPartial: (String) -> Unit
        ): ContextExplanationResult {
            explanations += 1
            pieces.forEach(onPartial)
            failure?.let { throw it }
            return ContextExplanationResult(final, identifier)
        }
    }
}
