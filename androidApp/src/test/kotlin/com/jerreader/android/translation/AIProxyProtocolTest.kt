package com.jerreader.android.translation

import com.jerreader.shared.domain.LanguageCode
import com.jerreader.shared.translation.AIReadingLensMode
import com.jerreader.shared.translation.ContextExplanationRequest
import com.jerreader.shared.translation.ReadingLensOptions
import com.jerreader.shared.translation.TranslationPreferences
import com.jerreader.shared.translation.TranslationProviderMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The AI 代理 gets the requests iOS sends (BackendTranslationService.swift), so
 * one proxy serves both apps. README「AI 代理协议」 lists the same fields.
 */
class AIProxyProtocolTest {
    private fun proxy(
        endpoint: String = "https://proxy.example.test/translate",
        model: String = " house-model ",
        credential: String? = "proxy-token",
        translationTemplate: String = "T"
    ) = TranslationServiceConfiguration(
        preferences = TranslationPreferences(
            providerMode = TranslationProviderMode.BACKEND_PROXY,
            translationPromptTemplate = translationTemplate,
            grammarAnalysisPromptTemplate = "G"
        ),
        endpoint = endpoint,
        model = model,
        credential = credential
    )

    private fun translation(
        configuration: TranslationServiceConfiguration = proxy(),
        text: String = "Hello world.",
        target: LanguageCode = LanguageCode.CHINESE_SIMPLIFIED
    ) = AIProxyProtocol.translation(configuration, text, LanguageCode.ENGLISH, target, "prompt")

    private fun key(request: PreparedRequest): String =
        request.headers.getValue(AIProxyProtocol.IDEMPOTENCY_HEADER)

    private fun fields(body: String): Set<String> = JSONObject(body).keys().asSequence().toSet()

    @Test fun aTranslationHasTheFieldsAndHeadersIOSSends() {
        val request = translation()
        assertEquals("https://proxy.example.test/translate", request.url)
        assertEquals(setOf("text", "sourceLanguage", "targetLanguage", "model", "prompt"), fields(request.body))
        val body = JSONObject(request.body)
        assertEquals("Hello world.", body.getString("text"))
        assertEquals("en", body.getString("sourceLanguage"))
        assertEquals("zh-Hans", body.getString("targetLanguage"))
        assertEquals("house-model", body.getString("model"))
        assertEquals("prompt", body.getString("prompt"))
        assertEquals("1", request.headers[AIProxyProtocol.VERSION_HEADER])
        assertEquals("Bearer proxy-token", request.headers["Authorization"])
        assertEquals("application/json", request.headers["Accept"])
        assertTrue(key(request).matches(Regex("[0-9a-f]{64}")))

        // A field or header without a value is left out.
        val bare = translation(proxy(model = " ", credential = null))
        assertEquals(setOf("text", "sourceLanguage", "targetLanguage", "prompt"), fields(bare.body))
        assertNull(bare.headers["Authorization"])
    }

    @Test fun theIdempotencyKeyIsTheOneIOSMakes() {
        // Worked out separately from iOS's recipe (providerVersion, then
        // TranslationCacheStore.cacheKey) for this address, model, templates and text.
        assertEquals("829bfa356d84298e910374e18fcc73a68b12405f4ee48a00dc6df3bc8ffae927", key(translation()))
    }

    @Test fun theIdempotencyKeyFollowsTheRequest() {
        val first = key(translation())
        // A retry or a second tap is the same request; runs of whitespace do not count, as on iOS.
        assertEquals(first, key(translation()))
        assertEquals(first, key(translation(text = "Hello \n  world.")))
        // The access token is not part of it.
        assertEquals(first, key(translation(proxy(credential = "another-token"))))
        // Whatever could change the answer changes the key.
        listOf(
            translation(text = "Hello there."),
            translation(target = LanguageCode.JAPANESE),
            translation(proxy(model = "other-model")),
            translation(proxy(model = "")),
            translation(proxy(endpoint = "https://proxy.example.test/other")),
            translation(proxy(translationTemplate = "T2"))
        ).forEach { other -> assertNotEquals(first, key(other)) }
    }

    @Test fun anExplanationKeepsTheSelectionAndItsContextApart() {
        val lookup = ContextExplanationRequest(
            focusedText = "鳴いた",
            contextText = "朝、猫が鳴いた。",
            sourceLanguage = LanguageCode.JAPANESE,
            isWordLookup = true,
            dictionaryEntry = "なく【鳴く】（动物）鸣叫"
        )
        val request = AIProxyProtocol.explanation(proxy(), lookup, "鳴いた", LanguageCode.JAPANESE, "prompt")
        assertEquals(
            setOf("task", "analysisMode", "text", "context", "sourceLanguage", "responseLanguage", "model", "prompt"),
            fields(request.body)
        )
        val body = JSONObject(request.body)
        assertEquals("explain", body.getString("task"))
        assertEquals("grammar", body.getString("analysisMode"))
        assertEquals("鳴いた", body.getString("text"))
        assertEquals(
            "朝、猫が鳴いた。\n\n词典候选资料（仅供参考，若不适合原句请指出）：\nなく【鳴く】（动物）鸣叫",
            body.getString("context")
        )
        assertEquals("ja", body.getString("sourceLanguage"))
        assertEquals("zh-Hans", body.getString("responseLanguage"))
        assertEquals("house-model", body.getString("model"))
        assertEquals("1", request.headers[AIProxyProtocol.VERSION_HEADER])
        assertEquals("Bearer proxy-token", request.headers["Authorization"])
        assertNull(request.headers[AIProxyProtocol.IDEMPOTENCY_HEADER])

        // Context that only repeats the selection is left out, and so is a
        // dictionary entry outside a word lookup.
        val sentence = ContextExplanationRequest(
            focusedText = "猫が鳴いた。",
            contextText = " 猫が鳴いた。 ",
            sourceLanguage = LanguageCode.JAPANESE,
            dictionaryEntry = "なく【鳴く】"
        )
        val plain = AIProxyProtocol.explanation(proxy(), sentence, "猫が鳴いた。", LanguageCode.JAPANESE, "prompt")
        assertFalse(JSONObject(plain.body).has("context"))
    }

    @Test fun eachLensAsksInItsOwnMode() {
        val modes = AIReadingLensMode.entries.map { mode ->
            val lens = ContextExplanationRequest("猫が鳴いた。", null, LanguageCode.JAPANESE, lens = ReadingLensOptions(mode))
            val request = AIProxyProtocol.explanation(proxy(), lens, "猫が鳴いた。", LanguageCode.JAPANESE, "prompt")
            JSONObject(request.body).getString("analysisMode")
        }
        assertEquals(listOf("comprehension", "context_meaning", "reference", "grammar"), modes)
    }

    @Test fun answersAreReadWhereIOSReadsThem() {
        assertEquals("译文", AIProxyProtocol.translatedText("""{"translatedText":"译文","translation":"别的"}"""))
        assertEquals("译文", AIProxyProtocol.translatedText("""{"translatedText":null,"translation":"译文"}"""))
        assertEquals("", AIProxyProtocol.translatedText("""{"explanation":"解析"}"""))

        assertEquals("解析", AIProxyProtocol.explanationText("""{"translatedText":"译文","explanation":"解析"}"""))
        assertEquals("回答", AIProxyProtocol.explanationText("""{"answer":"回答","translation":"译文"}"""))
        assertEquals("译文", AIProxyProtocol.explanationText("""{"translatedText":"译文"}"""))
    }
}
