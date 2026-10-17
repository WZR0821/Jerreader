package com.jerreader.android.translation

import com.jerreader.shared.translation.DirectAIProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AIModelCatalogServiceTest {
    private fun config(provider: DirectAIProvider) = AIModelCatalogConfiguration(provider,
        if (provider.usesCustomEndpoint) "https://example.test/custom/chat/completions" else provider.defaultEndpoint, "fixture")
    @Test fun endpointsAndCredentialsStayWithConfiguredProvider() {
        DirectAIProvider.entries.forEach { provider ->
            val (url, headers) = config(provider).request()
            assertTrue(url.startsWith("https://")); assertTrue(url.substringBefore('?').endsWith("/models"))
            assertFalse(url.contains("fixture"))
            assertEquals("fixture", when (provider) {
                DirectAIProvider.ANTHROPIC -> headers["x-api-key"]
                DirectAIProvider.GEMINI -> headers["x-goog-api-key"]
                else -> headers["Authorization"]?.removePrefix("Bearer ")
            })
        }
    }
    @Test fun unknownFutureModelsAndNewestMetadataAreNotHardCoded() = runBlocking {
        val service = RemoteAIModelCatalogService { _, _ -> TranslationHttpResponse(200,
            """{"data":[{"id":"legacy","created":10},{"id":"future-text-v99","created":20},{"id":"legacy","created":10}]}""") }
        assertEquals(listOf("future-text-v99", "legacy"), service.fetch(config(DirectAIProvider.OPENAI)).models.map { it.id })
    }
    @Test fun claudePagesAndNamesArePreserved() = runBlocking {
        var calls = 0
        val service = RemoteAIModelCatalogService { url, headers ->
            assertEquals("2023-06-01", headers["anthropic-version"])
            calls++
            if (calls == 1) TranslationHttpResponse(200, """{"data":[{"id":"one","display_name":"Display name"}],"has_more":true,"last_id":"one"}""")
            else { assertTrue(url.contains("after_id=one")); TranslationHttpResponse(200,"""{"data":[{"id":"two"}],"has_more":false}""") }
        }
        val result = service.fetch(config(DirectAIProvider.ANTHROPIC))
        assertEquals(2, calls); assertEquals("Display name", result.models.first().displayName)
    }
    @Test fun geminiOnlyListsGenerateContentAndEncodesCursor() {
        val parsed = RemoteAIModelCatalogService.parse("""{"models":[{"name":"models/future","displayName":"Future","supportedGenerationMethods":["generateContent"]},{"name":"models/embed","supportedGenerationMethods":["embedContent"]}],"nextPageToken":"two"}""", DirectAIProvider.GEMINI)
        assertEquals(listOf("future"), parsed.first.map { it.id }); assertEquals("two", parsed.second)
        assertTrue(config(DirectAIProvider.GEMINI).request("a+b&c").first.contains("pageToken=a%2Bb%26c"))
    }
    @Test fun failuresNeverExposeResponseBodies() = runBlocking {
        listOf(401,403,404,429,500,302).forEach { status ->
            val service = RemoteAIModelCatalogService { _, _ -> TranslationHttpResponse(status,"sensitive upstream error") }
            val error = runCatching { service.fetch(config(DirectAIProvider.OPENAI)) }.exceptionOrNull()
            assertTrue(error is AIModelCatalogException); assertFalse(error!!.message!!.contains("sensitive"))
        }
    }
    @Test fun invalidEndpointAndInvalidResponseAreRejected() {
        listOf("http://example.test/v1/responses", "https://user:secret@example.test/v1/responses", "https://example.test/v1/responses?key=secret", "https://example.test/arbitrary").forEach {
            assertTrue(runCatching { config(DirectAIProvider.OPENAI).copy(endpoint = it).request() }.isFailure)
        }
        assertTrue(runCatching { RemoteAIModelCatalogService.parse("{}", DirectAIProvider.OPENAI) }.isFailure)
    }
    @Test fun oversizedSinglePageIsMarkedPartial() = runBlocking {
        val json = "{\"data\":[" + (0..5000).joinToString(",") { "{\"id\":\"model-$it\"}" } + "]}"
        val result = RemoteAIModelCatalogService { _, _ -> TranslationHttpResponse(200, json) }.fetch(config(DirectAIProvider.OPENAI))
        assertEquals(5000, result.models.size); assertTrue(result.truncated)
    }
    @Test fun repeatedPaginationFailsRatherThanLooping() = runBlocking {
        var calls = 0
        val service = RemoteAIModelCatalogService { _, _ -> calls++; TranslationHttpResponse(200,"""{"data":[],"has_more":true,"last_id":"same"}""") }
        assertTrue(runCatching { service.fetch(config(DirectAIProvider.ANTHROPIC)) }.isFailure)
        assertEquals(2, calls)
    }
}
