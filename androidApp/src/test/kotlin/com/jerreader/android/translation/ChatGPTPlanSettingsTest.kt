package com.jerreader.android.translation

import com.jerreader.android.translation.chatgptplan.ChatGPTPlan
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanEntries
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanModel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The AI provider settings across the ChatGPT plan: what 1.7.x saved reads
 * back and saves again unchanged, and a plan entry is saved in the same
 * shape, which an app without the plan skips.
 */
class ChatGPTPlanSettingsTest {
    @Test fun settingsSavedBy17ReadBackAndSaveUnchanged() {
        val read = AIProviderSettings.fromJson(JSONObject(SAVED_BY_1_7))!!
        assertEquals("anthropic", read.activeID)
        assertEquals(listOf("anthropic", "openAI", "deepSeek", "gemini", "custom-1"), read.entries.map { it.id })
        assertFalse(read.entries.any { it.kind == AIProviderKind.CHATGPT_PLAN })
        assertEquals(AIReasoningEffort.MEDIUM, read.active.models[0].reasoning)
        assertNull(read.active.models[1].reasoning)
        assertEquals(AIReasoningEffort.OFF, read.entry("openAI")!!.models.single().reasoning)
        assertEquals(AIAPIStyle.GEMINI_NATIVE, read.entry("gemini")!!.models.single().style)
        val custom = read.entry("custom-1")!!
        assertEquals("我的服务", custom.name)
        assertEquals("我的服务", custom.displayName)
        assertNull(read.active.name)

        assertEquals(canonical(JSONObject(SAVED_BY_1_7)), canonical(read.toJson()))
        assertEquals(read, AIProviderSettings.fromJson(JSONObject(read.toJson().toString())))
    }

    @Test fun thePlanIsSavedLikeAnyProviderAndSkippedWhereItIsUnknown() {
        val older = AIProviderSettings.fromJson(JSONObject(SAVED_BY_1_7))!!
        val merged = ChatGPTPlanEntries.merged(null, listOf(ChatGPTPlanModel("gpt-a", "GPT A"), ChatGPTPlanModel("gpt-b", "GPT B")))
        val plan = merged.copy(
            models = merged.models.map { if (it.id == "gpt-b") it.copy(reasoning = AIReasoningEffort.LOW) else it }
        )
        val settings = AIProviderSettings(plan.id, older.entries + plan)
        val json = settings.toJson()
        assertEquals(settings, AIProviderSettings.fromJson(JSONObject(json.toString())))

        val rows = json.getJSONArray("entries")
        val row = (0 until rows.length()).map { rows.getJSONObject(it) }.single { it.getString("id") == ChatGPTPlan.PROVIDER_ID }
        assertEquals("chatGPTPlan", row.getString("kind"))
        assertEquals("https://api.openai.com/v1", row.getString("baseURL"))
        assertEquals("gpt-a", row.getString("model"))
        // Only the keys 1.7.x reads; the account and its tokens are never in the settings.
        assertEquals(setOf("id", "kind", "baseURL", "model", "models"), row.keys().asSequence().toSet())
        val model = row.getJSONArray("models").getJSONObject(1)
        assertEquals(setOf("id", "style", "reasoning"), model.keys().asSequence().toSet())
        assertEquals("responses", model.getString("style"))
        assertEquals("low", model.getString("reasoning"))

        // An app that does not know the plan leaves it out and falls back to the first provider.
        val withoutPlan = AIProviderSettings.fromJson(
            JSONObject(json.toString().replace("\"kind\":\"chatGPTPlan\"", "\"kind\":\"notKnownHere\""))
        )!!
        assertEquals(older.entries, withoutPlan.entries)
        assertEquals("anthropic", withoutPlan.active.id)
    }

    private fun canonical(value: Any?): Any? = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().associateWith { canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).map { canonical(value.get(it)) }
        else -> value
    }

    private companion object {
        /** The shape 1.7.x saved: every format, reasoning set and unset, and a named custom service. */
        const val SAVED_BY_1_7 = """{"activeID":"anthropic","entries":[
            {"id":"anthropic","kind":"anthropic","baseURL":"https://api.anthropic.com/v1","model":"claude-test",
             "models":[{"id":"claude-test","style":"anthropicMessages","reasoning":"medium"},{"id":"claude-other","style":"anthropicMessages"}]},
            {"id":"openAI","kind":"openAI","baseURL":"https://api.openai.com/v1","model":"gpt-test",
             "models":[{"id":"gpt-test","style":"responses","reasoning":"off"}]},
            {"id":"deepSeek","kind":"deepSeek","baseURL":"https://api.deepseek.com","model":"deepseek-flash",
             "models":[{"id":"deepseek-flash","style":"chatCompletions"}]},
            {"id":"gemini","kind":"gemini","baseURL":"https://generativelanguage.googleapis.com/v1beta","model":"gemini-test",
             "models":[{"id":"gemini-test","style":"geminiNative"}]},
            {"id":"custom-1","kind":"openAICompatible","name":"我的服务","baseURL":"https://llm.example.test/v1","model":"m",
             "models":[{"id":"m","style":"chatCompletions","reasoning":"high"}]}
        ]}"""
    }
}
