package com.jerreader.android.translation

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The AI provider list, as `scripts/test_ai_providers.swift` checks it on iOS:
 * the order of the presets, request URLs, migration from the settings kept
 * before providers had a list of models, editing the list, and the fields each
 * model family is sent. Migration differs where the Android app's old defaults
 * did.
 */
class AIProviderSettingsTest {
    @Test fun presetsPutClaudeChatGPTAndDeepSeekFirst() {
        val presets = AIProviderKind.presets
        assertEquals(listOf(AIProviderKind.ANTHROPIC, AIProviderKind.OPENAI, AIProviderKind.DEEPSEEK), presets.take(3))
        assertEquals(presets.size, presets.toSet().size)
        assertFalse(AIProviderKind.OPENAI_COMPATIBLE in presets)
        // The ChatGPT plan is added from the 套餐 tab, not from the presets.
        assertFalse(AIProviderKind.CHATGPT_PLAN in presets)
        assertEquals(
            AIProviderKind.entries.toSet(),
            (presets + AIProviderKind.OPENAI_COMPATIBLE + AIProviderKind.CHATGPT_PLAN).toSet()
        )
        for (kind in presets) {
            assertTrue("$kind has no models", kind.modelPresets.isNotEmpty())
            assertEquals(kind.modelPresets[0].id, kind.defaultModel)
            assertTrue("$kind address", AIEndpoint.isValid(kind.defaultBaseURL))
        }
        // The plan is ChatGPT too and draws the same letter; every other mark differs.
        val marks = AIProviderKind.entries.filter { it != AIProviderKind.CHATGPT_PLAN }.mapNotNull { it.monogram }
        assertEquals(marks.size, marks.toSet().size)
        assertEquals("G", AIProviderKind.CHATGPT_PLAN.monogram)
        assertEquals(AIProviderKind.entries.size, AIProviderKind.entries.map { it.tint }.toSet().size)
        assertEquals(listOf(AIProviderKind.DEEPSEEK), AIProviderKind.entries.filter { it.isRecommended })

        val fresh = AIProviderSettings.fresh
        assertEquals("deepSeek", fresh.activeID)
        assertEquals("deepseek-flash", fresh.active.model)
        assertEquals(listOf("deepseek-flash", "deepseek-v4-pro"), fresh.active.models.map { it.id })
        assertTrue(fresh.isUntouchedDefault(fresh.active))
    }

    @Test fun requestURLsJoinTheAddressAndTheFormat() {
        fun url(base: String, style: AIAPIStyle) = AIEndpoint.url(base, style)
        assertEquals("https://api.openai.com/v1/responses", url("https://api.openai.com/v1", AIAPIStyle.RESPONSES))
        assertEquals("https://api.deepseek.com/chat/completions", url("https://api.deepseek.com/", AIAPIStyle.CHAT_COMPLETIONS))
        assertEquals("https://api.anthropic.com/v1/messages", url("https://api.anthropic.com/v1", AIAPIStyle.ANTHROPIC_MESSAGES))
        // A pasted full request URL is taken without its route.
        assertEquals("https://example.test/v1/responses", url("https://example.test/v1/chat/completions", AIAPIStyle.RESPONSES))
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta",
            url("https://generativelanguage.googleapis.com/v1beta", AIAPIStyle.GEMINI_NATIVE)
        )
        assertNull(url("http://example.test/v1", AIAPIStyle.CHAT_COMPLETIONS))
        assertNull(url("example.test/v1", AIAPIStyle.CHAT_COMPLETIONS))
        assertNull(url("https://user:secret@example.test/v1", AIAPIStyle.CHAT_COMPLETIONS))
        assertNull(url("https://example.test/v1#part", AIAPIStyle.CHAT_COMPLETIONS))
        assertNull(url("https://exa mple.test/v1", AIAPIStyle.CHAT_COMPLETIONS))

        val split = AIEndpoint.split(" https://example.test/v1/messages ")
        assertEquals("https://example.test/v1", split.base)
        assertEquals(AIAPIStyle.ANTHROPIC_MESSAGES, split.style)
        assertNull(AIEndpoint.split("https://example.test/v1").style)
    }

    @Test fun oldSettingsMigrateWithTheRequestsTheyMade() {
        assertEquals(AIProviderSettings.fresh, AIProviderSettings.migrated(null, emptySet(), emptyMap(), emptyMap()))

        // Before a provider was chosen, the Android app used DeepSeek.
        val untouched = AIProviderSettings.migrated(
            selected = null,
            keyed = setOf(AIProviderKind.DEEPSEEK, AIProviderKind.OPENAI),
            storedModels = mapOf(AIProviderKind.OPENAI to "gpt-legacy"),
            storedEndpoints = emptyMap()
        )
        assertEquals(listOf("openAI", "deepSeek"), untouched.entries.map { it.id })
        assertEquals("deepSeek", untouched.activeID)
        assertEquals(AIModelEntry("gpt-legacy", AIAPIStyle.RESPONSES), untouched.entry("openAI")?.activeModel)
        assertEquals(AIModelEntry("deepseek-v4-flash", AIAPIStyle.CHAT_COMPLETIONS), untouched.active.activeModel)
        // Without DeepSeek among them, the first provider found is used.
        val onlyClaude = AIProviderSettings.migrated(null, setOf(AIProviderKind.ANTHROPIC), emptyMap(), emptyMap())
        assertEquals("anthropic", onlyClaude.activeID)
        assertEquals(AIModelEntry("claude-sonnet-4-20250514", AIAPIStyle.ANTHROPIC_MESSAGES), onlyClaude.active.activeModel)

        val gemini = AIProviderSettings.migrated(AIProviderKind.GEMINI, emptySet(), emptyMap(), emptyMap())
        assertEquals("gemini", gemini.activeID)
        assertEquals("https://generativelanguage.googleapis.com/v1beta", gemini.active.baseURL)
        assertEquals(AIModelEntry("gemini-3.6-flash", AIAPIStyle.GEMINI_NATIVE), gemini.active.activeModel)

        val custom = AIProviderSettings.migrated(
            selected = AIProviderKind.OPENAI_COMPATIBLE,
            keyed = setOf(AIProviderKind.OPENAI_COMPATIBLE),
            storedModels = mapOf(AIProviderKind.OPENAI_COMPATIBLE to " my-model "),
            storedEndpoints = mapOf(AIProviderKind.OPENAI_COMPATIBLE to "https://example.test/v1/responses")
        )
        assertEquals("https://example.test/v1", custom.active.baseURL)
        assertEquals(AIModelEntry("my-model", AIAPIStyle.RESPONSES), custom.active.activeModel)

        // Every provider keeps the request URL, model and format it had.
        val all = AIProviderSettings.migrated(
            selected = AIProviderKind.KIMI,
            keyed = setOf(AIProviderKind.OPENAI, AIProviderKind.ANTHROPIC, AIProviderKind.KIMI, AIProviderKind.DEEPSEEK, AIProviderKind.GEMINI),
            storedModels = emptyMap(),
            storedEndpoints = emptyMap()
        )
        assertEquals("kimi", all.activeID)
        assertEquals(5, all.entries.size)
        for (entry in all.entries) {
            assertEquals(entry.id, entry.kind.legacyEndpoint, AIEndpoint.url(entry.baseURL, entry.activeModel.style))
            assertEquals(entry.kind.legacyStyle, entry.activeModel.style)
            assertEquals(entry.kind.legacyDefaultModel, entry.model)
        }
    }

    @Test fun editingTheListKeepsTheProviderInUse() {
        // A provider added while the default has no key takes the default's place.
        val (replaced, replacedInUse) = AIProviderSettings.fresh.adding(AIProviderEntry.preset(AIProviderKind.ANTHROPIC), activeHasKey = false)
        assertTrue(replacedInUse)
        assertEquals(listOf("anthropic"), replaced.entries.map { it.id })
        assertEquals("anthropic", replaced.activeID)
        // With a key in use, a new provider is kept but not put in use.
        val (kept, keptInUse) = replaced.adding(AIProviderEntry.preset(AIProviderKind.OPENAI), activeHasKey = true)
        assertFalse(keptInUse)
        assertEquals("anthropic", kept.activeID)

        // A model not kept yet is added in the format of the provider's models.
        val switched = kept.activating("openAI", model = "gpt-new")
        assertEquals("openAI", switched.activeID)
        assertEquals(AIModelEntry("gpt-new", AIAPIStyle.RESPONSES), switched.active.activeModel)
        assertTrue(switched.isInUse("gpt-new", "openAI"))
        assertEquals(switched, switched.activating("missing"))
        assertTrue(runCatching { switched.removingModel("gpt-new", "openAI") }.exceptionOrNull() is AIProviderSettingsError.RemovingActiveModel)
        assertTrue(runCatching { switched.removing("openAI") }.exceptionOrNull() is AIProviderSettingsError.RemovingActiveProvider)
        val trimmed = switched.removingModel("gpt-6-luna", "openAI").removing("anthropic")
        assertFalse(trimmed.active.models.any { it.id == "gpt-6-luna" })
        assertEquals(listOf("openAI"), trimmed.entries.map { it.id })

        // Removing the model a provider uses hands over to its next one.
        assertEquals("kimi-k3.1-pro-highspeed", AIProviderEntry.preset(AIProviderKind.KIMI).removingModel("kimi-k3.1-pro").model)

        val custom = AIProviderEntry(AIProviderEntry.newCustomID(), AIProviderKind.OPENAI_COMPATIBLE, "  ", "https://example.test/v1", "", emptyList())
        assertTrue(custom.id.startsWith("custom-"))
        assertEquals("example.test", custom.host)
        assertEquals(AIProviderKind.OPENAI_COMPATIBLE.title, custom.displayName)
        assertEquals("My Lab", custom.copy(name = "My Lab").displayName)
        assertEquals("m1", custom.upserting(AIModelEntry("m1", AIAPIStyle.CHAT_COMPLETIONS)).model)
        assertEquals("未填写", custom.copy(baseURL = "").host)

        // A preset shows as added once it has a key or was changed.
        val fresh = AIProviderSettings.fresh
        assertFalse(fresh.hasSetUp(AIProviderKind.DEEPSEEK, emptySet()))
        assertTrue(fresh.hasSetUp(AIProviderKind.DEEPSEEK, setOf("deepSeek")))
        assertFalse(fresh.hasSetUp(AIProviderKind.OPENAI, setOf("openAI")))
    }

    @Test fun modelFamiliesGetTheirDocumentedFields() {
        fun fields(
            kind: AIProviderKind,
            style: AIAPIStyle,
            model: String,
            reasoning: AIReasoningEffort? = null,
            quick: Boolean = true
        ) = AIRequestTuning.extraFields(kind, style, model, reasoning, quick)

        // 自动 sends nothing to a model it does not know.
        assertEquals(0, fields(AIProviderKind.OPENAI_COMPATIBLE, AIAPIStyle.CHAT_COMPLETIONS, "anything").length())
        assertEquals(0, fields(AIProviderKind.OPENAI, AIAPIStyle.RESPONSES, "gpt-test").length())
        assertEquals(0, fields(AIProviderKind.GEMINI, AIAPIStyle.GEMINI_NATIVE, "gemini-3.5-flash", AIReasoningEffort.HIGH).length())

        val flash = fields(AIProviderKind.DEEPSEEK, AIAPIStyle.CHAT_COMPLETIONS, "deepseek-flash")
        assertEquals("disabled", flash.getJSONObject("thinking").getString("type"))
        assertFalse(flash.has("reasoning_effort"))
        val explained = fields(AIProviderKind.DEEPSEEK, AIAPIStyle.CHAT_COMPLETIONS, "deepseek-flash", quick = false)
        assertEquals("enabled", explained.getJSONObject("thinking").getString("type"))
        assertEquals("low", explained.getString("reasoning_effort"))

        assertEquals("none", fields(AIProviderKind.OPENAI, AIAPIStyle.RESPONSES, "gpt-6-luna").getJSONObject("reasoning").getString("effort"))
        assertEquals("high", fields(AIProviderKind.OPENAI, AIAPIStyle.RESPONSES, "gpt-6-luna", AIReasoningEffort.HIGH).getJSONObject("reasoning").getString("effort"))
        assertEquals("low", fields(AIProviderKind.ANTHROPIC, AIAPIStyle.ANTHROPIC_MESSAGES, "claude-haiku-5-5").getJSONObject("output_config").getString("effort"))
        assertEquals(0, fields(AIProviderKind.ANTHROPIC, AIAPIStyle.ANTHROPIC_MESSAGES, "claude-haiku-5-5", quick = false).length())

        val kimi = fields(AIProviderKind.KIMI, AIAPIStyle.CHAT_COMPLETIONS, "kimi-k3.1-pro")
        assertEquals(16_000, kimi.getInt("max_tokens"))
        assertEquals("low", kimi.getString("reasoning_effort"))
        assertEquals("max", fields(AIProviderKind.KIMI, AIAPIStyle.CHAT_COMPLETIONS, "kimi-k3.1-pro", AIReasoningEffort.HIGH).getString("reasoning_effort"))

        assertFalse(fields(AIProviderKind.QWEN, AIAPIStyle.CHAT_COMPLETIONS, "qwen-plus", AIReasoningEffort.OFF).getBoolean("enable_thinking"))
        // 通义千问 offers 关闭 alone; any other level is treated as 自动.
        assertEquals(0, fields(AIProviderKind.QWEN, AIAPIStyle.CHAT_COMPLETIONS, "qwen-plus", AIReasoningEffort.HIGH).length())

        val older = fields(AIProviderKind.ANTHROPIC, AIAPIStyle.ANTHROPIC_MESSAGES, "claude-sonnet-4-5", AIReasoningEffort.MEDIUM)
        assertEquals(4_096, older.getJSONObject("thinking").getInt("budget_tokens"))
        assertEquals(AIRequestTuning.ANTHROPIC_OUTPUT_TOKENS + 4_096, older.getInt("max_tokens"))
        assertEquals(AIModelCatalogAPI.ANTHROPIC, AIModelCatalogAPI.forStyle(AIAPIStyle.ANTHROPIC_MESSAGES))
        assertEquals(AIModelCatalogAPI.STANDARD, AIModelCatalogAPI.forStyle(AIAPIStyle.RESPONSES))
    }

    @Test fun savedSettingsReadBackInTheIOSShape() {
        val settings = AIProviderSettings.fresh.updating("deepSeek") { entry ->
            entry.copy(models = entry.models.mapIndexed { index, model ->
                if (index == 1) model.copy(reasoning = AIReasoningEffort.HIGH) else model
            })
        }
        val json = settings.toJson()
        assertEquals(settings, AIProviderSettings.fromJson(JSONObject(json.toString())))
        val row = json.getJSONArray("entries").getJSONObject(0)
        assertFalse(row.has("name"))
        assertEquals("chatCompletions", row.getJSONArray("models").getJSONObject(0).getString("style"))
        assertFalse(row.getJSONArray("models").getJSONObject(0).has("reasoning"))
        assertEquals("high", row.getJSONArray("models").getJSONObject(1).getString("reasoning"))

        // Settings saved without the optional fields still load.
        val stored = """{"activeID":"deepSeek","entries":[{"id":"deepSeek","kind":"deepSeek","baseURL":"https://api.deepseek.com","model":"deepseek-flash","models":[{"id":"deepseek-flash","style":"chatCompletions"}]}]}"""
        val older = AIProviderSettings.fromJson(JSONObject(stored))!!
        assertEquals(AIModelEntry("deepseek-flash", AIAPIStyle.CHAT_COMPLETIONS), older.active.activeModel)
        assertNull(older.active.name)

        // A provider or format this version does not know is left out.
        val future = """{"activeID":"x","entries":[{"id":"x","kind":"future","baseURL":"https://x.test","model":"m","models":[]},{"id":"kimi","kind":"kimi","baseURL":"https://api.moonshot.cn/v1","model":"k","models":[{"id":"k","style":"future"},{"id":"k2","style":"chatCompletions"}]}]}"""
        val read = AIProviderSettings.fromJson(JSONObject(future))!!
        assertEquals(listOf("kimi"), read.entries.map { it.id })
        assertEquals(listOf("k2"), read.active.models.map { it.id })
        assertEquals("kimi", read.active.id)
        assertNull(AIProviderSettings.fromJson(JSONObject("""{"activeID":"x","entries":[]}""")))
        assertNull(AIProviderSettings.fromJson(null))
    }
}
