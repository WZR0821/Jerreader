package com.jerreader.android.translation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jerreader.shared.domain.LanguageCode
import com.jerreader.shared.translation.ContextExplanationRequest
import com.jerreader.shared.translation.TranslationFailure
import com.jerreader.shared.translation.TranslationPreferences
import com.jerreader.shared.translation.TranslationProviderMode
import com.jerreader.shared.translation.TranslationRequest
import com.jerreader.shared.translation.TranslationResult
import com.jerreader.shared.translation.TranslationService
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The AI 大模型 settings as the app keeps them: what carries over from the
 * settings before providers kept lists, the translation cache identity, backups
 * without keys, and the request each format sends. Every store here uses its
 * own preferences file, so the app's own settings are not touched.
 */
class AIProviderSettingsStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val names = mutableListOf<String>()

    @After fun removeSettings() {
        names.forEach(context::deleteSharedPreferences)
    }

    private fun preferencesName(): String =
        ("test_ai_providers_" + UUID.randomUUID()).also(names::add)

    private fun store(keys: ProviderKeyStore = ProviderKeyStore(), name: String = preferencesName()) =
        AndroidTranslationSettingsStore(context, keys, name)

    @Test fun freshInstallKeepsTheCacheIdentityItHadBefore() {
        val settings = store()
        assertEquals(AIProviderSettings.fresh, settings.aiProviders.value)
        assertTrue(settings.keyedProviderIDs.value.isEmpty())
        val expected = listOf(
            "ON_DEVICE", "NONE",
            "DEEPSEEK", "https://api.deepseek.com/chat/completions", "deepseek-v4-flash",
            "", "",
            TranslationPreferences.DEFAULT_TRANSLATION_PROMPT,
            TranslationPreferences.DEFAULT_GRAMMAR_PROMPT
        ).joinToString("\u001F")
        assertEquals(expected, settings.cacheNamespace())
        assertEquals("DeepSeek 还没有填 API Key。", settings.directAPIConfigurationMessage())
        assertNull(settings.directConfiguration())
    }

    @Test fun oldSettingsCarryOverWithTheirKeysAndCache() {
        val name = preferencesName()
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            .putString("provider.mode", "DIRECT_API")
            .putString("direct.provider", "KIMI")
            .putString("direct.model.KIMI", "kimi-k2.6-custom")
            .commit()
        val keys = ProviderKeyStore(mutableMapOf("direct-api-KIMI" to "kimi-key", "direct-api-DEEPSEEK" to "ds-key"))
        val settings = store(keys, name)

        val providers = settings.aiProviders.value
        assertEquals("kimi", providers.activeID)
        assertEquals(listOf("deepSeek", "kimi"), providers.entries.map { it.id })
        assertEquals(setOf("deepSeek", "kimi"), settings.keyedProviderIDs.value)
        assertEquals("kimi-key", settings.apiKey("kimi"))
        val parts = settings.cacheNamespace().split("\u001F")
        assertEquals(9, parts.size)
        assertEquals(listOf("KIMI", "https://api.moonshot.cn/v1/chat/completions", "kimi-k2.6-custom"), parts.subList(2, 5))

        val direct = settings.currentServiceConfiguration().direct
        assertNotNull(direct)
        assertEquals("https://api.moonshot.cn/v1/chat/completions", direct!!.endpoint)
        assertEquals("kimi-k2.6-custom", direct.model)
        assertEquals("kimi-key", direct.apiKey)
        assertEquals(AIAPIStyle.CHAT_COMPLETIONS, direct.style)
        assertEquals("Kimi · kimi-k2.6-custom", settings.directAPIConfigurationMessage())

        // The migrated list is saved, so it reads back the same.
        assertEquals(providers, store(keys, name).aiProviders.value)
        // A key saved again moves to the provider's own account.
        settings.saveKey(" new-key ", "kimi")
        assertEquals("new-key", keys.values["direct-api-kimi"])
        assertFalse("direct-api-KIMI" in keys.values)
        assertEquals("new-key", settings.apiKey("kimi"))
    }

    @Test fun formatAndReasoningTakePartInTheCacheIdentity() {
        val settings = store()
        settings.saveKey("ds-key", "deepSeek")
        val plain = settings.cacheNamespace().split("\u001F")
        assertEquals(listOf("DEEPSEEK", "https://api.deepseek.com/chat/completions", "deepseek-flash"), plain.subList(2, 5))
        assertEquals(9, plain.size)

        settings.changeProviders { providers ->
            providers.updating("deepSeek") { entry ->
                entry.copy(models = entry.models.map {
                    if (it.id == entry.model) it.copy(style = AIAPIStyle.ANTHROPIC_MESSAGES, reasoning = AIReasoningEffort.HIGH) else it
                })
            }
        }
        val tuned = settings.cacheNamespace().split("\u001F")
        assertEquals("https://api.deepseek.com/messages", tuned[3])
        assertEquals(listOf("style:anthropicMessages", "reasoning:high"), tuned.drop(9))
    }

    @Test fun addingAndRemovingProvidersFollowsTheKeys() {
        val keys = ProviderKeyStore()
        val settings = store(keys)
        // The default has no key, so the new provider takes its place.
        assertTrue(settings.addProvider(AIProviderEntry.preset(AIProviderKind.ANTHROPIC), "ant-key"))
        assertEquals(listOf("anthropic"), settings.aiProviders.value.entries.map { it.id })
        assertEquals(setOf("anthropic"), settings.keyedProviderIDs.value)
        // Now a key is in use: the next one is kept beside it.
        assertFalse(settings.addProvider(AIProviderEntry.preset(AIProviderKind.OPENAI), "oa-key"))
        assertEquals("anthropic", settings.aiProviders.value.activeID)
        assertTrue(runCatching { settings.removeProvider("anthropic") }.exceptionOrNull() is AIProviderSettingsError.RemovingActiveProvider)
        assertTrue(runCatching {
            settings.addProvider(AIProviderEntry.preset(AIProviderKind.KIMI).copy(baseURL = "http://api.moonshot.cn/v1"), "k")
        }.exceptionOrNull() is AIProviderSettingsError.InvalidAddress)

        settings.saveProvider("openAI", name = "ignored", baseURL = " https://proxy.example.test/v1 ", key = "oa-key-2")
        val openAI = settings.aiProviders.value.entry("openAI")!!
        assertEquals("https://proxy.example.test/v1", openAI.baseURL)
        assertNull(openAI.name)
        assertEquals("oa-key-2", keys.values["direct-api-openAI"])

        settings.removeProvider("openAI")
        assertNull(settings.aiProviders.value.entry("openAI"))
        assertFalse("direct-api-openAI" in keys.values)
        assertEquals(setOf("anthropic"), settings.keyedProviderIDs.value)
    }

    @Test fun backupsCarryProvidersButNoKeys() {
        val source = store(ProviderKeyStore())
        source.addProvider(AIProviderEntry.preset(AIProviderKind.ANTHROPIC), "sk-ant-secret")
        source.updateProviderMode(TranslationProviderMode.DIRECT_API)
        val backup = source.backupJson()
        assertFalse(backup.toString().contains("sk-ant-secret"))
        assertNotNull(backup.optJSONObject("aiProviders"))
        assertEquals("ANTHROPIC", backup.getString("directProvider"))
        assertEquals("https://api.anthropic.com/v1/messages", backup.getString("directEndpoint"))
        assertEquals("claude-haiku-5-5", backup.getString("directModel"))

        val restored = store(ProviderKeyStore())
        restored.restoreFromBackup(JSONObject(backup.toString()))
        assertEquals(source.aiProviders.value, restored.aiProviders.value)
        assertEquals(TranslationProviderMode.DIRECT_API, restored.preferences.value.providerMode)
        assertTrue(restored.keyedProviderIDs.value.isEmpty())
        assertEquals("Claude 还没有填 API Key。", restored.directAPIConfigurationMessage())
    }

    @Test fun anOlderBackupPutsItsProviderInUse() {
        val settings = store()
        settings.restoreFromBackup(
            JSONObject()
                .put("providerMode", "DIRECT_API")
                .put("directProvider", "OPENAI")
                .put("directEndpoint", "https://api.openai.com/v1/responses")
                .put("directModel", "gpt-5.2")
        )
        val providers = settings.aiProviders.value
        assertEquals(listOf("openAI"), providers.entries.map { it.id })
        assertEquals(AIModelEntry("gpt-5.2", AIAPIStyle.RESPONSES), providers.active.activeModel)
        assertEquals(TranslationProviderMode.DIRECT_API, settings.preferences.value.providerMode)
    }

    @Test fun eachFormatSendsItsOwnRequest() = runBlocking {
        val settings = store()
        val transport = RecordingTransport()
        val service = ConfiguredTranslationService(settings, NoOnDeviceTranslation, transport)
        settings.updateProviderMode(TranslationProviderMode.DIRECT_API)
        val request = TranslationRequest("Hello.", LanguageCode.ENGLISH, LanguageCode.CHINESE_SIMPLIFIED)

        assertTrue(runCatching { service.translate(request) }.exceptionOrNull() is TranslationFailure.AIModelNotReady)
        assertTrue(transport.calls.isEmpty())

        // Claude: a thinking block before the answer has no text.
        settings.addProvider(AIProviderEntry.preset(AIProviderKind.ANTHROPIC), "ant-key")
        transport.reply = """{"content":[{"type":"thinking","thinking":"…"},{"type":"text","text":"你好。"}]}"""
        val claude = service.translate(request)
        assertEquals("你好。", claude.translatedText)
        assertEquals("Claude", claude.providerIdentifier)
        transport.last.let { call ->
            assertEquals("https://api.anthropic.com/v1/messages", call.url)
            assertEquals("ant-key", call.headers["x-api-key"])
            assertEquals("2023-06-01", call.headers["anthropic-version"])
            assertEquals("application/json", call.headers["Accept"])
            val body = JSONObject(call.body)
            assertEquals("claude-haiku-5-5", body.getString("model"))
            assertEquals(AIRequestTuning.ANTHROPIC_OUTPUT_TOKENS, body.getInt("max_tokens"))
            assertEquals("low", body.getJSONObject("output_config").getString("effort"))
            assertEquals("Hello.", body.getJSONArray("messages").getJSONObject(0).getString("content"))
        }

        // Gemini's own format: the model is in the path and the key in a header.
        val gemini = AIProviderEntry(
            id = "gemini", kind = AIProviderKind.GEMINI, name = null,
            baseURL = "https://generativelanguage.googleapis.com/v1beta", model = "gemini-3.6-flash",
            models = listOf(AIModelEntry("gemini-3.6-flash", AIAPIStyle.GEMINI_NATIVE))
        )
        settings.saveKey("gem-key", "gemini")
        settings.changeProviders { it.copy(entries = it.entries + gemini).activating("gemini") }
        transport.reply = """{"candidates":[{"content":{"parts":[{"text":"你"},{"text":"好"}]}}]}"""
        assertEquals("你好", service.translate(request).translatedText)
        transport.last.let { call ->
            assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:generateContent", call.url)
            assertEquals("gem-key", call.headers["x-goog-api-key"])
            assertFalse(call.url.contains("key="))
        }

        // OpenAI compatible: DeepSeek turns thinking off for a translation.
        settings.addProvider(AIProviderEntry.preset(AIProviderKind.DEEPSEEK), "ds-key")
        settings.changeProviders { it.activating("deepSeek") }
        transport.reply = """{"choices":[{"message":{"role":"assistant","content":"你好"}}]}"""
        assertEquals("你好", service.translate(request).translatedText)
        transport.last.let { call ->
            assertEquals("https://api.deepseek.com/chat/completions", call.url)
            assertEquals("Bearer ds-key", call.headers["Authorization"])
            val body = JSONObject(call.body)
            assertFalse(body.getBoolean("stream"))
            assertFalse(body.has("temperature"))
            assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
        }

        // Responses: a reasoning item without content comes before the message.
        settings.addProvider(AIProviderEntry.preset(AIProviderKind.OPENAI), "oa-key")
        settings.changeProviders { it.activating("openAI") }
        transport.reply = """{"output":[{"type":"reasoning","summary":[]},{"type":"message","content":[{"type":"output_text","text":"你好"}]}]}"""
        assertEquals("你好", service.translate(request).translatedText)
        assertEquals("https://api.openai.com/v1/responses", transport.last.url)
        assertEquals("none", JSONObject(transport.last.body).getJSONObject("reasoning").getString("effort"))

        // A connection test uses the configuration it is given, saved or not.
        val unsaved = AIProviderEntry.preset(AIProviderKind.KIMI)
        val direct = settings.configuration(unsaved, unsaved.activeModel, " kimi-key ")
        transport.reply = """{"choices":[{"message":{"content":"你好"}}]}"""
        assertTrue(service.testConnection(direct) >= 0.0)
        assertEquals("https://api.moonshot.cn/v1/chat/completions", transport.last.url)
        assertEquals("Bearer kimi-key", transport.last.headers["Authorization"])
        assertTrue(runCatching { settings.configuration(unsaved, unsaved.activeModel, " ") }.exceptionOrNull() is AIProviderSettingsError.MissingKey)
    }

    /** The AI 代理 gets the requests iOS sends, from the settings the app keeps. */
    @Test fun theProxyGetsTheRequestsIOSSends() = runBlocking {
        val settings = store()
        val transport = RecordingTransport()
        val service = ConfiguredTranslationService(settings, NoOnDeviceTranslation, transport)
        settings.updateProviderMode(TranslationProviderMode.BACKEND_PROXY)
        settings.updateBackendEndpoint("https://proxy.example.test/translate")
        settings.updateBackendModel("house-model")
        settings.updateBackendAccessToken("proxy-token")

        transport.reply = """{"translatedText":"你好。"}"""
        val translated = service.translate(TranslationRequest("Hello.", LanguageCode.ENGLISH, LanguageCode.CHINESE_SIMPLIFIED))
        assertEquals("你好。", translated.translatedText)
        assertEquals("AI 代理", translated.providerIdentifier)
        transport.last.let { call ->
            assertEquals("https://proxy.example.test/translate", call.url)
            assertEquals("Bearer proxy-token", call.headers["Authorization"])
            assertEquals("1", call.headers[AIProxyProtocol.VERSION_HEADER])
            assertNotNull(call.headers[AIProxyProtocol.IDEMPOTENCY_HEADER])
            val body = JSONObject(call.body)
            assertEquals("Hello.", body.getString("text"))
            assertEquals("house-model", body.getString("model"))
            assertFalse(body.has("operation"))
        }

        // An explanation sends the selection and its context apart, and the
        // answer is read where iOS reads it.
        transport.reply = """{"translatedText":"不是这个","explanation":"这是解析。"}"""
        val explained = service.explain(
            ContextExplanationRequest("猫が鳴いた。", "朝、猫が鳴いた。", LanguageCode.JAPANESE)
        )
        assertEquals("这是解析。", explained.explanation)
        transport.last.let { call ->
            assertNull(call.headers[AIProxyProtocol.IDEMPOTENCY_HEADER])
            val body = JSONObject(call.body)
            assertEquals("explain", body.getString("task"))
            assertEquals("grammar", body.getString("analysisMode"))
            assertEquals("猫が鳴いた。", body.getString("text"))
            assertEquals("朝、猫が鳴いた。", body.getString("context"))
            assertFalse(body.has("operation"))
        }
    }
}

class ProviderKeyStore(val values: MutableMap<String, String> = mutableMapOf()) : TranslationCredentialStore {
    override fun read(account: String): String? = values[account]
    override fun save(account: String, secret: String?) {
        if (secret.isNullOrBlank()) values.remove(account) else values[account] = secret.trim()
    }
}

private class RecordingTransport : TranslationHttpTransport {
    data class Call(val url: String, val headers: Map<String, String>, val body: String)

    val calls = mutableListOf<Call>()
    var reply = "{}"
    val last: Call get() = calls.last()

    override suspend fun post(url: String, headers: Map<String, String>, body: String): TranslationHttpResponse {
        calls += Call(url, headers, body)
        return TranslationHttpResponse(200, reply)
    }
}

private object NoOnDeviceTranslation : TranslationService {
    override val identifier = "none"
    override suspend fun translate(request: TranslationRequest): TranslationResult =
        throw TranslationFailure.ServiceUnavailable
}
