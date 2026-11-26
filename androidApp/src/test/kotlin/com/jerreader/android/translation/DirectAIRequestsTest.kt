package com.jerreader.android.translation

import com.jerreader.shared.translation.AIChatMessage
import com.jerreader.shared.translation.AIChatRole
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 2.1.0 requests of a model called directly, offline: a streamed request
 * in each of the four formats, a follow-up conversation as each format's
 * turns, and an unstreamed single question exactly as before.
 */
class DirectAIRequestsTest {
    private val single = listOf(
        AIChatMessage(AIChatRole.SYSTEM, "Translate."),
        AIChatMessage(AIChatRole.USER, "SOURCE:\nHello.")
    )
    private val conversation = listOf(
        AIChatMessage(AIChatRole.SYSTEM, "Answer about the sentence."),
        AIChatMessage(AIChatRole.USER, "SELECTED TEXT:\nHe left.\n\nQUESTION:\nWho left?"),
        AIChatMessage(AIChatRole.ASSISTANT, "He did."),
        AIChatMessage(AIChatRole.USER, "QUESTION:\nWhy?")
    )

    @Test fun responsesStreamAndSendAConversationAsMessages() {
        val streamed = DirectAIRequests.prepared(direct(AIProviderKind.OPENAI, AIAPIStyle.RESPONSES), single, quick = true, stream = true)
        assertEquals("text/event-stream", streamed.headers["Accept"])
        val body = JSONObject(streamed.body)
        assertTrue(body.getBoolean("stream"))
        assertEquals("Translate.", body.getString("instructions"))
        assertEquals("SOURCE:\nHello.", body.getString("input"))

        val turns = JSONObject(
            DirectAIRequests.prepared(direct(AIProviderKind.OPENAI, AIAPIStyle.RESPONSES), conversation, quick = false, stream = true).body
        ).getJSONArray("input")
        assertEquals(3, turns.length())
        assertEquals(listOf("user", "assistant", "user"), (0 until 3).map { turns.getJSONObject(it).getString("role") })
        assertEquals("He did.", turns.getJSONObject(1).getString("content"))

        // Not streamed: the body of 2.0, without `stream`.
        val whole = DirectAIRequests.prepared(direct(AIProviderKind.OPENAI, AIAPIStyle.RESPONSES), single, quick = true, stream = false)
        assertEquals("application/json", whole.headers["Accept"])
        assertFalse(JSONObject(whole.body).has("stream"))
    }

    @Test fun chatCompletionsStreamAndKeepTheSystemMessageFirst() {
        val prepared = DirectAIRequests.prepared(direct(AIProviderKind.DEEPSEEK, AIAPIStyle.CHAT_COMPLETIONS), conversation, quick = false, stream = true)
        assertEquals("text/event-stream", prepared.headers["Accept"])
        val body = JSONObject(prepared.body)
        assertTrue(body.getBoolean("stream"))
        val messages = body.getJSONArray("messages")
        assertEquals(listOf("system", "user", "assistant", "user"), (0 until 4).map { messages.getJSONObject(it).getString("role") })
        assertEquals("Answer about the sentence.", messages.getJSONObject(0).getString("content"))

        val whole = JSONObject(
            DirectAIRequests.prepared(direct(AIProviderKind.DEEPSEEK, AIAPIStyle.CHAT_COMPLETIONS), single, quick = true, stream = false).body
        )
        assertFalse(whole.getBoolean("stream"))
        assertEquals(2, whole.getJSONArray("messages").length())
    }

    @Test fun anthropicStreamsWithTheSystemPromptApart() {
        val prepared = DirectAIRequests.prepared(direct(AIProviderKind.ANTHROPIC, AIAPIStyle.ANTHROPIC_MESSAGES), conversation, quick = false, stream = true)
        assertEquals("text/event-stream", prepared.headers["Accept"])
        assertEquals("2023-06-01", prepared.headers["anthropic-version"])
        val body = JSONObject(prepared.body)
        assertTrue(body.getBoolean("stream"))
        assertEquals("Answer about the sentence.", body.getString("system"))
        val messages = body.getJSONArray("messages")
        assertEquals(listOf("user", "assistant", "user"), (0 until 3).map { messages.getJSONObject(it).getString("role") })

        assertFalse(
            JSONObject(
                DirectAIRequests.prepared(direct(AIProviderKind.ANTHROPIC, AIAPIStyle.ANTHROPIC_MESSAGES), single, quick = true, stream = false).body
            ).has("stream")
        )
    }

    @Test fun geminiStreamsFromItsOwnAddressAndCallsTheModelModel() {
        val gemini = direct(AIProviderKind.GEMINI, AIAPIStyle.GEMINI_NATIVE, endpoint = "https://generativelanguage.googleapis.com/v1beta/")
        val prepared = DirectAIRequests.prepared(gemini, conversation, quick = false, stream = true)
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-test:streamGenerateContent?alt=sse",
            prepared.url
        )
        assertEquals("text/event-stream", prepared.headers["Accept"])
        val body = JSONObject(prepared.body)
        // Gemini takes no `stream` field: the address decides.
        assertFalse(body.has("stream"))
        val contents = body.getJSONArray("contents")
        assertEquals(listOf("user", "model", "user"), (0 until 3).map { contents.getJSONObject(it).getString("role") })
        assertEquals(
            "Answer about the sentence.",
            body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
        )

        assertEquals(
            "https://example.test/v1/models/gemini-test:generateContent",
            DirectAIRequests.geminiURL("https://example.test/v1", "gemini-test", stream = false)
        )
        // A query the address already has is kept, before alt=sse.
        assertEquals(
            "https://example.test/v1/models/gemini-test:streamGenerateContent?region=eu&alt=sse",
            DirectAIRequests.geminiURL("https://example.test/v1?region=eu", "gemini-test", stream = true)
        )
    }

    private fun direct(kind: AIProviderKind, style: AIAPIStyle, endpoint: String = "https://example.test/v1/endpoint") =
        DirectAIConfiguration(
            kind = kind,
            displayName = kind.title,
            endpoint = endpoint,
            model = if (style == AIAPIStyle.GEMINI_NATIVE) "gemini-test" else "model-test",
            apiKey = "test-key",
            style = style,
            reasoning = null
        )
}
