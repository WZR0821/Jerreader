package com.jerreader.android.translation

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AIModelCatalogServiceTest {
    private fun config(api: AIModelCatalogAPI) = AIModelCatalogConfiguration(
        api,
        when (api) {
            AIModelCatalogAPI.STANDARD -> "https://example.test/custom/chat/completions"
            AIModelCatalogAPI.ANTHROPIC -> "https://api.anthropic.com/v1/messages"
            AIModelCatalogAPI.GEMINI -> "https://generativelanguage.googleapis.com/v1beta"
        },
        "fixture"
    )

    @Test fun endpointsAndCredentialsStayWithConfiguredProvider() {
        AIModelCatalogAPI.entries.forEach { api ->
            val (url, headers) = config(api).request()
            assertTrue(url.startsWith("https://")); assertTrue(url.substringBefore('?').endsWith("/models"))
            assertFalse(url.contains("fixture"))
            assertEquals("fixture", when (api) {
                AIModelCatalogAPI.ANTHROPIC -> headers["x-api-key"]
                AIModelCatalogAPI.GEMINI -> headers["x-goog-api-key"]
                AIModelCatalogAPI.STANDARD -> headers["Authorization"]?.removePrefix("Bearer ")
            })
        }
    }

    @Test fun providerAddressesListModelsBesideTheirFormat() {
        AIProviderKind.presets.forEach { kind ->
            val style = kind.defaultStyle
            val (url, _) = AIModelCatalogConfiguration.forProvider(kind.defaultBaseURL, style, "fixture").request()
            assertEquals(kind.name, kind.defaultBaseURL.trimEnd('/') + "/models", url.substringBefore('?'))
        }
        val (gemini, headers) = AIModelCatalogConfiguration.forProvider(
            "https://generativelanguage.googleapis.com/v1beta", AIAPIStyle.GEMINI_NATIVE, "fixture"
        ).request()
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000", gemini)
        assertEquals("fixture", headers["x-goog-api-key"])
        assertTrue(runCatching {
            AIModelCatalogConfiguration.forProvider("http://example.test/v1", AIAPIStyle.CHAT_COMPLETIONS, "fixture")
        }.exceptionOrNull() is AIProviderSettingsError.InvalidAddress)
    }

    @Test fun unknownFutureModelsAndNewestMetadataAreNotHardCoded() = runBlocking {
        val service = RemoteAIModelCatalogService { _, _ -> TranslationHttpResponse(200,
            """{"data":[{"id":"legacy","created":10},{"id":"future-text-v99","created":20},{"id":"legacy","created":10}]}""") }
        assertEquals(listOf("future-text-v99", "legacy"), service.fetch(config(AIModelCatalogAPI.STANDARD)).models.map { it.id })
    }

    @Test fun claudePagesAndNamesArePreserved() = runBlocking {
        var calls = 0
        val service = RemoteAIModelCatalogService { url, headers ->
            assertEquals("2023-06-01", headers["anthropic-version"])
            calls++
            if (calls == 1) TranslationHttpResponse(200, """{"data":[{"id":"one","display_name":"Display name"}],"has_more":true,"last_id":"one"}""")
            else { assertTrue(url.contains("after_id=one")); TranslationHttpResponse(200,"""{"data":[{"id":"two"}],"has_more":false}""") }
        }
        val result = service.fetch(config(AIModelCatalogAPI.ANTHROPIC))
        assertEquals(2, calls); assertEquals("Display name", result.models.first().displayName)
    }

    @Test fun geminiOnlyListsGenerateContentAndEncodesCursor() {
        val parsed = RemoteAIModelCatalogService.parse("""{"models":[{"name":"models/future","displayName":"Future","supportedGenerationMethods":["generateContent"]},{"name":"models/embed","supportedGenerationMethods":["embedContent"]}],"nextPageToken":"two"}""", AIModelCatalogAPI.GEMINI)
        assertEquals(listOf("future"), parsed.first.map { it.id }); assertEquals("two", parsed.second)
        assertTrue(config(AIModelCatalogAPI.GEMINI).request("a+b&c").first.contains("pageToken=a%2Bb%26c"))
    }

    @Test fun failuresNeverExposeResponseBodies() = runBlocking {
        listOf(401,403,404,429,500,302).forEach { status ->
            val service = RemoteAIModelCatalogService { _, _ -> TranslationHttpResponse(status,"sensitive upstream error") }
            val error = runCatching { service.fetch(config(AIModelCatalogAPI.STANDARD)) }.exceptionOrNull()
            assertTrue(error is AIModelCatalogException); assertFalse(error!!.message!!.contains("sensitive"))
        }
    }

    @Test fun invalidEndpointAndInvalidResponseAreRejected() {
        listOf("http://example.test/v1/responses", "https://user:secret@example.test/v1/responses", "https://example.test/v1/responses?key=secret", "https://example.test/arbitrary").forEach {
            assertTrue(runCatching { config(AIModelCatalogAPI.STANDARD).copy(endpoint = it).request() }.isFailure)
        }
        assertTrue(runCatching { RemoteAIModelCatalogService.parse("{}", AIModelCatalogAPI.STANDARD) }.isFailure)
    }

    @Test fun oversizedSinglePageIsMarkedPartial() = runBlocking {
        val json = "{\"data\":[" + (0..5000).joinToString(",") { "{\"id\":\"model-$it\"}" } + "]}"
        val result = RemoteAIModelCatalogService { _, _ -> TranslationHttpResponse(200, json) }.fetch(config(AIModelCatalogAPI.STANDARD))
        assertEquals(5000, result.models.size); assertTrue(result.truncated)
    }

    @Test fun repeatedPaginationFailsRatherThanLooping() = runBlocking {
        var calls = 0
        val service = RemoteAIModelCatalogService { _, _ -> calls++; TranslationHttpResponse(200,"""{"data":[],"has_more":true,"last_id":"same"}""") }
        assertTrue(runCatching { service.fetch(config(AIModelCatalogAPI.ANTHROPIC)) }.isFailure)
        assertEquals(2, calls)
    }
}
