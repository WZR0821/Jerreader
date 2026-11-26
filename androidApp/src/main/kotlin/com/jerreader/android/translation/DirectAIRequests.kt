package com.jerreader.android.translation

import com.jerreader.shared.translation.AIChatMessage
import com.jerreader.shared.translation.AIChatRole
import org.json.JSONArray
import org.json.JSONObject

/**
 * The request of a model called directly, in its own format: the same body
 * iOS sends. The system messages become the format's instructions and the
 * rest its turns, oldest first (2.1.0). One user turn without streaming gives
 * exactly the body sent before 2.1.0.
 */
internal object DirectAIRequests {
    fun prepared(
        direct: DirectAIConfiguration,
        messages: List<AIChatMessage>,
        quick: Boolean,
        stream: Boolean
    ): PreparedRequest {
        val instructions = messages.filter { it.role == AIChatRole.SYSTEM }.joinToString("\n\n") { it.text }
        val turns = messages.filter { it.role != AIChatRole.SYSTEM }
        val body = when (direct.style) {
            AIAPIStyle.RESPONSES -> JSONObject()
                .put("model", direct.model)
                .put("instructions", instructions)
                .put(
                    "input",
                    // One question goes as plain text, as before; a conversation as its messages.
                    turns.singleOrNull()?.takeIf { it.role == AIChatRole.USER }?.text ?: JSONArray().apply {
                        turns.forEach { turn -> put(JSONObject().put("role", turn.role.id).put("content", turn.text)) }
                    }
                )
                .apply { if (stream) put("stream", true) }

            AIAPIStyle.CHAT_COMPLETIONS -> JSONObject()
                .put("model", direct.model)
                .put(
                    "messages",
                    JSONArray().apply {
                        put(JSONObject().put("role", "system").put("content", instructions))
                        turns.forEach { turn -> put(JSONObject().put("role", turn.role.id).put("content", turn.text)) }
                    }
                )
                .put("stream", stream)

            AIAPIStyle.GEMINI_NATIVE -> JSONObject()
                .put(
                    "systemInstruction",
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", instructions)))
                )
                .put(
                    "contents",
                    JSONArray().apply {
                        turns.forEach { turn ->
                            put(
                                JSONObject()
                                    // Gemini calls the model's turns "model".
                                    .put("role", if (turn.role == AIChatRole.ASSISTANT) "model" else "user")
                                    .put("parts", JSONArray().put(JSONObject().put("text", turn.text)))
                            )
                        }
                    }
                )

            AIAPIStyle.ANTHROPIC_MESSAGES -> JSONObject()
                .put("model", direct.model)
                .put("max_tokens", AIRequestTuning.ANTHROPIC_OUTPUT_TOKENS)
                .put("system", instructions)
                .put(
                    "messages",
                    JSONArray().apply {
                        turns.forEach { turn -> put(JSONObject().put("role", turn.role.id).put("content", turn.text)) }
                    }
                )
                .apply { if (stream) put("stream", true) }
        }
        // Reasoning and output fields of the chosen model; none for most
        // models on 自动, which leaves the body as it is.
        val extra = AIRequestTuning.extraFields(direct.kind, direct.style, direct.model, direct.reasoning, quick)
        extra.keys().forEach { key -> body.put(key, extra.get(key)) }
        val headers = buildMap {
            put("Content-Type", "application/json; charset=utf-8")
            put("Accept", if (stream) "text/event-stream" else "application/json")
            when (direct.style) {
                AIAPIStyle.RESPONSES, AIAPIStyle.CHAT_COMPLETIONS -> put("Authorization", "Bearer ${direct.apiKey}")
                AIAPIStyle.GEMINI_NATIVE -> put("x-goog-api-key", direct.apiKey)
                AIAPIStyle.ANTHROPIC_MESSAGES -> {
                    put("x-api-key", direct.apiKey)
                    put("anthropic-version", "2023-06-01")
                }
            }
        }
        val url = if (direct.style == AIAPIStyle.GEMINI_NATIVE) {
            geminiURL(direct.endpoint, direct.model, stream)
        } else {
            direct.endpoint
        }
        return PreparedRequest(url = url, headers = headers, body = body.toString())
    }

    /**
     * Gemini's own format names the model in the path, before any query; a
     * streamed request asks for server-sent events with `alt=sse`.
     */
    fun geminiURL(endpoint: String, model: String, stream: Boolean): String {
        val queryStart = endpoint.indexOf('?')
        val base = (if (queryStart >= 0) endpoint.substring(0, queryStart) else endpoint).trimEnd('/')
        val query = if (queryStart >= 0) endpoint.substring(queryStart) else ""
        if (!stream) return "$base/models/$model:generateContent$query"
        val streamQuery = if (query.isEmpty() || query == "?") "?alt=sse" else "$query&alt=sse"
        return "$base/models/$model:streamGenerateContent$streamQuery"
    }
}
