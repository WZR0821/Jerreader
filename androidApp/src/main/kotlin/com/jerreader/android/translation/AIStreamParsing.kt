package com.jerreader.android.translation

import com.jerreader.shared.translation.AIUsage
import org.json.JSONArray
import org.json.JSONObject

/** How a streamed answer ended (2.1.0). */
internal sealed class AIStreamOutcome {
    /** The service said the answer is whole. */
    data object Completed : AIStreamOutcome()

    /** The answer stopped early: a length limit, a safety stop. */
    data class Incomplete(val reason: String?) : AIStreamOutcome()

    data class Failed(val code: String?, val message: String?) : AIStreamOutcome()

    /** The stream ended without saying how. */
    data object Interrupted : AIStreamOutcome()
}

/**
 * Reads one streamed answer of a model called directly, a line at a time, as
 * iOS's `AIStreamDecoder` does (2.1.0): every `data:` line is an event of its
 * own, named by the `event:` line just before it; comments, `id:` and
 * `retry:` are passed over. Thinking (`thinking_delta`,
 * `response.reasoning_*`, Gemini's `thought` parts, `reasoning_content`) is
 * never part of the text. The ChatGPT plan streams in the Responses format
 * and is read with the same decoder.
 */
internal class AIStreamDecoder(private val style: AIAPIStyle) {
    private val deltas = StringBuilder()
    private var eventName: String? = null

    /** Responses: the text of the final event, which wins over the deltas. */
    private var finalText: String? = null
    private var stopReason: String? = null
    private var inputTokens: Int? = null
    private var outputTokens: Int? = null

    /** Set by the event that ends the stream; later lines are ignored. */
    var outcome: AIStreamOutcome? = null
        private set

    /** The visible answer so far. */
    val text: String get() = deltas.toString()

    /** The answer: the final event's text when there is one, else the deltas. */
    val answer: String get() = finalText ?: deltas.toString()

    /** What the service said the answer cost; null when it said nothing. */
    val usage: AIUsage? get() = AIUsage(inputTokens, outputTokens).takeUnless(AIUsage::isEmpty)

    /** Reads one line, without its line break. True when the visible answer grew. */
    fun feed(line: String): Boolean {
        if (outcome != null) return false
        if (line.isEmpty()) {
            eventName = null
            return false
        }
        if (line.startsWith("event:")) {
            eventName = line.substring(6).trim()
            return false
        }
        if (!line.startsWith("data:")) return false
        val payload = line.substring(5).removePrefix(" ")
        val name = eventName
        eventName = null
        return dispatch(payload, name)
    }

    /** How the stream ended, once its body has ended without an error. */
    fun finish(): AIStreamOutcome {
        outcome?.let { return it }
        val reason = stopReason
        return when (style) {
            AIAPIStyle.CHAT_COMPLETIONS -> when (reason) {
                null -> AIStreamOutcome.Interrupted
                // A length or safety stop leaves the answer cut short, as iOS chatOutcome.
                "length", "content_filter" -> AIStreamOutcome.Incomplete(reason)
                else -> AIStreamOutcome.Completed
            }

            AIAPIStyle.GEMINI_NATIVE -> when (reason) {
                null -> AIStreamOutcome.Interrupted
                "STOP" -> AIStreamOutcome.Completed
                else -> AIStreamOutcome.Incomplete(reason)
            }

            AIAPIStyle.RESPONSES, AIAPIStyle.ANTHROPIC_MESSAGES -> AIStreamOutcome.Interrupted
        }
    }

    private fun dispatch(payload: String, eventName: String?): Boolean {
        if (payload == "[DONE]") {
            if (style == AIAPIStyle.CHAT_COMPLETIONS) {
                outcome = if (stopReason == "length" || stopReason == "content_filter") {
                    AIStreamOutcome.Incomplete(stopReason)
                } else {
                    AIStreamOutcome.Completed
                }
            }
            return false
        }
        val json = jsonObject(payload) ?: return false
        return when (style) {
            AIAPIStyle.RESPONSES -> responses(json, eventName)
            AIAPIStyle.CHAT_COMPLETIONS -> chatCompletions(json)
            AIAPIStyle.ANTHROPIC_MESSAGES -> anthropic(json, eventName)
            AIAPIStyle.GEMINI_NATIVE -> gemini(json)
        }
    }

    private fun responses(json: JSONObject, eventName: String?): Boolean {
        when (val type = (json.opt("type") as? String) ?: eventName ?: "") {
            "response.output_text.delta" -> return append(json.opt("delta") as? String)

            "response.completed", "response.incomplete" -> {
                val response = json.opt("response") as? JSONObject
                outputText(response).takeIf(String::isNotEmpty)?.let { finalText = it }
                readUsage(response?.opt("usage"), "input_tokens", "output_tokens")
                outcome = if (type == "response.completed") {
                    AIStreamOutcome.Completed
                } else {
                    val details = response?.opt("incomplete_details") as? JSONObject
                    AIStreamOutcome.Incomplete(details?.opt("reason") as? String)
                }
            }

            "response.failed" -> {
                val error = (json.opt("response") as? JSONObject)?.opt("error") as? JSONObject
                outcome = AIStreamOutcome.Failed(code(error?.opt("code")), error?.opt("message") as? String)
            }

            "error" -> {
                val nested = json.opt("error") as? JSONObject
                outcome = AIStreamOutcome.Failed(
                    code(json.opt("code")) ?: code(nested?.opt("code")) ?: (nested?.opt("type") as? String),
                    (json.opt("message") as? String) ?: (nested?.opt("message") as? String)
                )
            }
        }
        return false
    }

    private fun chatCompletions(json: JSONObject): Boolean {
        val error = json.opt("error") as? JSONObject
        if (error != null) {
            outcome = AIStreamOutcome.Failed(
                code(error.opt("code")) ?: (error.opt("type") as? String),
                error.opt("message") as? String
            )
            return false
        }
        val choice = (json.opt("choices") as? JSONArray)?.opt(0) as? JSONObject
        // The last chunk may carry the usage, at the top or in the choice.
        readUsage(json.opt("usage"), "prompt_tokens", "completion_tokens")
        readUsage(choice?.opt("usage"), "prompt_tokens", "completion_tokens")
        (choice?.opt("finish_reason") as? String)?.let { stopReason = it }
        return when (val content = (choice?.opt("delta") as? JSONObject)?.opt("content")) {
            is String -> append(content)
            // Some compatible services send a list of parts.
            is JSONArray -> append(joinedText(content))
            else -> false
        }
    }

    private fun anthropic(json: JSONObject, eventName: String?): Boolean {
        when ((json.opt("type") as? String) ?: eventName ?: "") {
            "message_start" ->
                readUsage((json.opt("message") as? JSONObject)?.opt("usage"), "input_tokens", "output_tokens")

            "content_block_start" -> {
                val block = json.opt("content_block") as? JSONObject
                if (block != null && block.opt("type") == "text") return append(block.opt("text") as? String)
            }

            "content_block_delta" -> {
                val delta = json.opt("delta") as? JSONObject
                if (delta != null && delta.opt("type") == "text_delta") return append(delta.opt("text") as? String)
            }

            "message_delta" -> {
                readUsage(json.opt("usage"), "input_tokens", "output_tokens")
                ((json.opt("delta") as? JSONObject)?.opt("stop_reason") as? String)?.let { stopReason = it }
            }

            "message_stop" -> outcome = if (stopReason == "max_tokens") {
                AIStreamOutcome.Incomplete(stopReason)
            } else {
                AIStreamOutcome.Completed
            }

            "error" -> {
                val error = json.opt("error") as? JSONObject
                outcome = AIStreamOutcome.Failed(error?.opt("type") as? String, error?.opt("message") as? String)
            }
        }
        return false
    }

    private fun gemini(json: JSONObject): Boolean {
        val error = json.opt("error") as? JSONObject
        if (error != null) {
            outcome = AIStreamOutcome.Failed(
                (error.opt("status") as? String) ?: code(error.opt("code")),
                error.opt("message") as? String
            )
            return false
        }
        readUsage(json.opt("usageMetadata"), "promptTokenCount", "candidatesTokenCount")
        val blockReason = (json.opt("promptFeedback") as? JSONObject)?.opt("blockReason") as? String
        if (blockReason != null) {
            outcome = AIStreamOutcome.Incomplete(blockReason)
            return false
        }
        var grew = false
        val candidates = json.opt("candidates") as? JSONArray ?: return false
        for (index in 0 until candidates.length()) {
            val candidate = candidates.opt(index) as? JSONObject ?: continue
            val parts = (candidate.opt("content") as? JSONObject)?.opt("parts") as? JSONArray
            for (partIndex in 0 until (parts?.length() ?: 0)) {
                val part = parts?.opt(partIndex) as? JSONObject ?: continue
                if (part.opt("thought") == true) continue
                if (append(part.opt("text") as? String)) grew = true
            }
            (candidate.opt("finishReason") as? String)?.let { stopReason = it }
        }
        return grew
    }

    private fun append(delta: String?): Boolean {
        if (delta.isNullOrEmpty()) return false
        deltas.append(delta)
        return true
    }

    private fun readUsage(value: Any?, input: String, output: String) {
        val fields = value as? JSONObject ?: return
        (fields.opt(input) as? Number)?.let { inputTokens = it.toInt() }
        (fields.opt(output) as? Number)?.let { outputTokens = it.toInt() }
    }

    private companion object {
        fun jsonObject(text: String): JSONObject? = try {
            JSONObject(text)
        } catch (_: Exception) {
            null
        }

        /** The output text of a Responses `response` object: message items only. */
        fun outputText(response: JSONObject?): String {
            val output = response?.opt("output") as? JSONArray ?: return ""
            return buildString {
                for (index in 0 until output.length()) {
                    val item = output.opt(index) as? JSONObject ?: continue
                    if (((item.opt("type") as? String) ?: "message") != "message") continue
                    val content = item.opt("content") as? JSONArray ?: continue
                    for (part in 0 until content.length()) {
                        val piece = content.opt(part) as? JSONObject ?: continue
                        if (piece.opt("type") != "output_text") continue
                        append(piece.opt("text") as? String ?: "")
                    }
                }
            }
        }

        /** The `text` of every part that has one, joined. */
        fun joinedText(parts: JSONArray): String = buildString {
            for (index in 0 until parts.length()) {
                val part = parts.opt(index) as? JSONObject ?: continue
                (part.opt("text") as? String)?.let(::append)
            }
        }

        /** An error code, which some services send as a number. */
        fun code(value: Any?): String? = when (value) {
            is String -> value
            is Number -> value.toLong().toString()
            else -> null
        }
    }
}

/**
 * The lines of one 2xx answer to a streamed request (2.1.0). A service that
 * ignores `stream: true` answers with one JSON body instead; its first line
 * that is not blank tells which, as on iOS, and such a body is kept whole
 * (up to [MAXIMUM_PLAIN_BODY_CHARACTERS]) for the old parser.
 */
internal class AIStreamReader(
    style: AIAPIStyle,
    private val onText: (String) -> Unit
) {
    val decoder = AIStreamDecoder(style)
    private var isEventStream: Boolean? = null
    private val plainBody = StringBuilder()

    /** True once any text has been shown, so a retry would show it twice. */
    var hasShownText: Boolean = false
        private set

    fun line(line: String) {
        if (isEventStream == null) {
            if (line.isBlank()) return
            isEventStream = isEventLine(line)
        }
        if (isEventStream == true) {
            if (decoder.feed(line)) {
                hasShownText = true
                onText(decoder.text)
            }
        } else if (plainBody.length < MAXIMUM_PLAIN_BODY_CHARACTERS) {
            if (plainBody.isNotEmpty()) plainBody.append('\n')
            plainBody.append(line)
        }
    }

    /** The whole body of a service that did not stream; null when it streamed or sent nothing. */
    val plainResponse: String?
        get() = if (isEventStream == false) plainBody.toString() else null

    companion object {
        const val MAXIMUM_PLAIN_BODY_CHARACTERS = 1_000_000

        /** Whether a line belongs to an event stream rather than to a whole JSON answer. */
        fun isEventLine(line: String): Boolean =
            listOf("data:", "event:", ":", "id:", "retry:").any(line::startsWith)
    }
}
