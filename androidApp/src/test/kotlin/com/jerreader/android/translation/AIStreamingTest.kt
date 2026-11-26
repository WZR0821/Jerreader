package com.jerreader.android.translation

import com.jerreader.android.translation.chatgptplan.ChatGPTPlan
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanCompletion
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanCredential
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanHttpClient
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanHttpResponse
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanServiceFailure
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanSession
import com.jerreader.shared.translation.AIUsage
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 2.1.0 streaming layer, offline: each format's events, thinking left
 * out, how a stream ends, usage, a service that ignores `stream`, the
 * ChatGPT plan, the timeouts and the pace of partial text.
 */
class AIStreamingTest {
    // Responses

    @Test fun responsesShowDeltasAndEndAtCompleted() {
        val shown = mutableListOf<String>()
        val decoder = decode(
            AIAPIStyle.RESPONSES,
            shown,
            "event: response.created",
            """data: {"type":"response.created","response":{}}""",
            "",
            """data: {"type":"response.reasoning_summary_text.delta","delta":"想一想"}""",
            """data: {"type":"response.output_text.delta","delta":"你"}""",
            ": keep-alive",
            "id: 7",
            "retry: 1000",
            """data: {"type":"response.output_text.delta","delta":"好"}""",
            """data: {"type":"response.completed","response":{"output":[{"type":"reasoning","summary":[]},""" +
                """{"type":"message","content":[{"type":"output_text","text":"你好。"}]}],""" +
                """"usage":{"input_tokens":12,"output_tokens":3}}}""",
            // Nothing after the end counts.
            """data: {"type":"response.output_text.delta","delta":"！"}"""
        )
        assertEquals(listOf("你", "你好"), shown)
        assertEquals(AIStreamOutcome.Completed, decoder.finish())
        // The final event's text wins over the deltas.
        assertEquals("你好。", decoder.answer)
        assertEquals(AIUsage(12, 3), decoder.usage)
    }

    @Test fun responsesNameTheirEventsEitherWay() {
        val shown = mutableListOf<String>()
        val decoder = decode(
            AIAPIStyle.RESPONSES,
            shown,
            "event: response.output_text.delta",
            """data: {"delta":"甲"}""",
            "",
            "event: response.completed",
            """data: {"response":{"output":[]}}"""
        )
        assertEquals(listOf("甲"), shown)
        assertEquals(AIStreamOutcome.Completed, decoder.finish())
        assertEquals("甲", decoder.answer)
        assertNull(decoder.usage)
    }

    @Test fun responsesFailures() {
        assertEquals(
            AIStreamOutcome.Failed("server_error", "Something broke."),
            decode(
                AIAPIStyle.RESPONSES,
                """data: {"type":"response.failed","response":{"error":{"code":"server_error","message":"Something broke."}}}"""
            ).finish()
        )
        assertEquals(
            AIStreamOutcome.Failed("rate_limit_exceeded", "Slow down."),
            decode(AIAPIStyle.RESPONSES, """data: {"type":"error","code":"rate_limit_exceeded","message":"Slow down."}""").finish()
        )
        assertEquals(
            AIStreamOutcome.Failed("429", "Nested."),
            decode(AIAPIStyle.RESPONSES, """data: {"type":"error","error":{"code":429,"message":"Nested."}}""").finish()
        )
        assertEquals(
            AIStreamOutcome.Incomplete("max_output_tokens"),
            decode(
                AIAPIStyle.RESPONSES,
                """data: {"type":"response.incomplete","response":{"incomplete_details":{"reason":"max_output_tokens"}}}"""
            ).finish()
        )
        // A stream that stops before its end event is cut off.
        assertEquals(
            AIStreamOutcome.Interrupted,
            decode(AIAPIStyle.RESPONSES, """data: {"type":"response.output_text.delta","delta":"半"}""").finish()
        )
    }

    // Chat Completions

    @Test fun chatCompletionsEndAtDoneWithUsageInTheLastChunk() {
        val shown = mutableListOf<String>()
        val decoder = decode(
            AIAPIStyle.CHAT_COMPLETIONS,
            shown,
            """data: {"choices":[{"index":0,"delta":{"role":"assistant","content":""}}]}""",
            "",
            """data: {"choices":[{"index":0,"delta":{"reasoning_content":"先想"}}]}""",
            "",
            """data: {"choices":[{"index":0,"delta":{"content":"他"}}]}""",
            "",
            """data: {"choices":[{"index":0,"delta":{"content":[{"type":"text","text":"回家"}]}}]}""",
            "",
            """data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
            "",
            """data: {"choices":[],"usage":{"prompt_tokens":40,"completion_tokens":5}}""",
            "",
            "data: [DONE]"
        )
        assertEquals(listOf("他", "他回家"), shown)
        assertEquals(AIStreamOutcome.Completed, decoder.finish())
        assertEquals("他回家", decoder.answer)
        assertEquals(AIUsage(40, 5), decoder.usage)
    }

    @Test fun chatCompletionsEndings() {
        // A finish reason without [DONE] still ends the answer.
        assertEquals(
            AIStreamOutcome.Completed,
            decode(AIAPIStyle.CHAT_COMPLETIONS, """data: {"choices":[{"delta":{"content":"a"},"finish_reason":"stop"}]}""").finish()
        )
        assertEquals(
            AIStreamOutcome.Incomplete("length"),
            decode(
                AIAPIStyle.CHAT_COMPLETIONS,
                """data: {"choices":[{"delta":{"content":"a"},"finish_reason":"length"}]}""",
                "data: [DONE]"
            ).finish()
        )
        // A safety filter cuts the answer short too, with [DONE] or without, as on iOS.
        for (ending in listOf(arrayOf("data: [DONE]"), emptyArray())) {
            assertEquals(
                AIStreamOutcome.Incomplete("content_filter"),
                decode(
                    AIAPIStyle.CHAT_COMPLETIONS,
                    """data: {"choices":[{"delta":{"content":"a"},"finish_reason":"content_filter"}]}""",
                    *ending
                ).finish()
            )
        }
        assertEquals(
            AIStreamOutcome.Interrupted,
            decode(AIAPIStyle.CHAT_COMPLETIONS, """data: {"choices":[{"delta":{"content":"a"}}]}""").finish()
        )
        assertEquals(
            AIStreamOutcome.Failed("server_error", "Overloaded."),
            decode(AIAPIStyle.CHAT_COMPLETIONS, """data: {"error":{"type":"server_error","message":"Overloaded."}}""").finish()
        )
    }

    // Anthropic Messages

    @Test fun anthropicLeavesThinkingOutAndReadsBothUsages() {
        val shown = mutableListOf<String>()
        val decoder = decode(
            AIAPIStyle.ANTHROPIC_MESSAGES,
            shown,
            "event: message_start",
            """data: {"type":"message_start","message":{"usage":{"input_tokens":25,"output_tokens":1}}}""",
            "",
            "event: content_block_start",
            """data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
            "",
            "event: content_block_delta",
            """data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"嗯"}}""",
            "",
            "event: content_block_start",
            """data: {"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
            "",
            "event: ping",
            """data: {"type":"ping"}""",
            "",
            "event: content_block_delta",
            """data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"我是"}}""",
            "",
            "event: content_block_delta",
            """data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"猫。"}}""",
            "",
            "event: message_delta",
            """data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":7}}""",
            "",
            "event: message_stop",
            """data: {"type":"message_stop"}"""
        )
        assertEquals(listOf("我是", "我是猫。"), shown)
        assertEquals(AIStreamOutcome.Completed, decoder.finish())
        assertEquals("我是猫。", decoder.answer)
        assertEquals(AIUsage(25, 7), decoder.usage)
    }

    @Test fun anthropicEndings() {
        assertEquals(
            AIStreamOutcome.Failed("overloaded_error", "Overloaded"),
            decode(
                AIAPIStyle.ANTHROPIC_MESSAGES,
                "event: error",
                """data: {"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""
            ).finish()
        )
        assertEquals(
            AIStreamOutcome.Incomplete("max_tokens"),
            decode(
                AIAPIStyle.ANTHROPIC_MESSAGES,
                """data: {"type":"message_delta","delta":{"stop_reason":"max_tokens"}}""",
                """data: {"type":"message_stop"}"""
            ).finish()
        )
        assertEquals(
            AIStreamOutcome.Interrupted,
            decode(
                AIAPIStyle.ANTHROPIC_MESSAGES,
                """data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"半"}}"""
            ).finish()
        )
    }

    // Gemini

    @Test fun geminiSkipsThoughtsAndEndsAtItsFinishReason() {
        val shown = mutableListOf<String>()
        val decoder = decode(
            AIAPIStyle.GEMINI_NATIVE,
            shown,
            """data: {"candidates":[{"content":{"role":"model","parts":[{"text":"思考中","thought":true}]}}]}""",
            "",
            """data: {"candidates":[{"content":{"role":"model","parts":[{"text":"吾輩は"}]}}]}""",
            "",
            """data: {"candidates":[{"content":{"role":"model","parts":[{"text":"猫"}]},"finishReason":"STOP"}],""" +
                """"usageMetadata":{"promptTokenCount":30,"candidatesTokenCount":4,"totalTokenCount":34}}"""
        )
        assertEquals(listOf("吾輩は", "吾輩は猫"), shown)
        assertEquals(AIStreamOutcome.Completed, decoder.finish())
        assertEquals("吾輩は猫", decoder.answer)
        assertEquals(AIUsage(30, 4), decoder.usage)
    }

    @Test fun geminiEndings() {
        assertEquals(
            AIStreamOutcome.Incomplete("MAX_TOKENS"),
            decode(
                AIAPIStyle.GEMINI_NATIVE,
                """data: {"candidates":[{"content":{"parts":[{"text":"a"}]},"finishReason":"MAX_TOKENS"}]}"""
            ).finish()
        )
        assertEquals(
            AIStreamOutcome.Incomplete("SAFETY"),
            decode(AIAPIStyle.GEMINI_NATIVE, """data: {"promptFeedback":{"blockReason":"SAFETY"}}""").finish()
        )
        assertEquals(
            AIStreamOutcome.Failed("UNAVAILABLE", "The model is overloaded."),
            decode(
                AIAPIStyle.GEMINI_NATIVE,
                """data: {"error":{"code":503,"status":"UNAVAILABLE","message":"The model is overloaded."}}"""
            ).finish()
        )
        assertEquals(
            AIStreamOutcome.Interrupted,
            decode(AIAPIStyle.GEMINI_NATIVE, """data: {"candidates":[{"content":{"parts":[{"text":"a"}]}}]}""").finish()
        )
    }

    // A service that ignores `stream`

    @Test fun aWholeJsonBodyIsKeptForTheOldParser() {
        val shown = mutableListOf<String>()
        val reader = AIStreamReader(AIAPIStyle.CHAT_COMPLETIONS) { shown += it }
        listOf("", "{", "  \"choices\": []", "}").forEach(reader::line)
        assertEquals("{\n  \"choices\": []\n}", reader.plainResponse)
        assertTrue(shown.isEmpty())
        assertFalse(reader.hasShownText)

        val streamed = AIStreamReader(AIAPIStyle.CHAT_COMPLETIONS) { shown += it }
        streamed.line("""data: {"choices":[{"delta":{"content":"流"}}]}""")
        assertNull(streamed.plainResponse)
        assertTrue(streamed.hasShownText)
        assertEquals(listOf("流"), shown)
    }

    // The ChatGPT plan

    @Test fun thePlanShowsItsAnswerWhileItArrives() = runBlocking<Unit> {
        val client = FakePlanClient {
            ChatGPTPlanHttpResponse(
                200,
                sse(
                    """{"type":"response.created","response":{}}""",
                    """{"type":"response.reasoning_summary_text.delta","delta":"想"}""",
                    """{"type":"response.output_text.delta","delta":"你"}""",
                    """{"type":"response.output_text.delta","delta":"好"}""",
                    """{"type":"response.completed","response":{"output":[],"usage":{"input_tokens":9,"output_tokens":2}}}"""
                )
            )
        }
        val shown = mutableListOf<String>()
        val answer = completion(client).completeStreaming(
            direct(),
            "Translate.",
            listOf("user" to "Hello", "assistant" to "你好", "user" to "Again?"),
            quick = true
        ) { shown += it }
        assertEquals(listOf("你", "你好"), shown)
        assertEquals("你好", answer.text)
        assertEquals(AIUsage(9, 2), answer.usage)
        // Each read may wait longer than the card's 30 s and the AI actions'
        // 70 s quiet limits, so those decide, as for a model called directly.
        assertEquals(80_000, client.requests.single().timeoutMillis)
        val input = JSONObject(client.requests.single().body!!).getJSONArray("input")
        assertEquals(3, input.length())
        assertEquals("assistant", input.getJSONObject(1).getString("role"))
        assertEquals("你好", input.getJSONObject(1).getString("content"))
    }

    @Test fun thePlanCountsOnlyACompletedStream() = runBlocking<Unit> {
        val cut = FakePlanClient { ChatGPTPlanHttpResponse(200, sse("""{"type":"response.output_text.delta","delta":"半"}""")) }
        val shown = mutableListOf<String>()
        val failure = runCatching {
            completion(cut).completeStreaming(direct(), "i", listOf("user" to "t"), quick = true) { shown += it }
        }.exceptionOrNull()
        assertTrue("got $failure", failure is ChatGPTPlanServiceFailure.Temporary)
        // The cut-off text was shown, but it is not the answer.
        assertEquals(listOf("半"), shown)

        val incomplete = FakePlanClient {
            ChatGPTPlanHttpResponse(
                200,
                sse("""{"type":"response.incomplete","response":{"incomplete_details":{"reason":"max_output_tokens"}}}""")
            )
        }
        val second = runCatching {
            completion(incomplete).completeStreaming(direct(), "i", listOf("user" to "t"), quick = true) {}
        }.exceptionOrNull()
        assertTrue("got $second", second is ChatGPTPlanServiceFailure.ResultNotFound)
    }

    @Test fun cancellingThePlanWhileItReadsIsACancellation() = runBlocking<Unit> {
        val reading = CompletableDeferred<Unit>()
        val client = ChatGPTPlanHttpClient {
            reading.complete(Unit)
            // Cancelling closes the connection, and the read waiting on it
            // fails with an IOException rather than a cancellation.
            try {
                awaitCancellation()
            } catch (_: CancellationException) {
            }
            throw java.io.IOException("Socket closed")
        }
        var thrown: Throwable? = null
        val request = launch {
            try {
                completion(client).completeStreaming(direct(), "i", listOf("user" to "t"), quick = true) {}
            } catch (error: Throwable) {
                thrown = error
                throw error
            }
        }
        reading.await()
        request.cancelAndJoin()
        // Not a passing failure, which would be retried or fail over.
        assertTrue("got $thrown", thrown is CancellationException)
    }

    // Timeouts

    @Test fun anAnswerThatGoesQuietTimesOut() = runBlocking<Unit> {
        val started = System.nanoTime()
        val failure = runCatching {
            withStreamTimeouts(idleMillis = 150, totalMillis = 5_000) { touch ->
                touch()
                awaitCancellation()
            }
        }.exceptionOrNull()
        assertTrue("got $failure", failure is AIStreamTimeoutException && failure.wentQuiet)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
    }

    @Test fun eachNewTextKeepsTheAnswerAlive() = runBlocking<Unit> {
        // Ten pieces 60 ms apart outlast a 150 ms idle limit.
        val result = withStreamTimeouts(idleMillis = 150, totalMillis = 5_000) { touch ->
            repeat(10) {
                delay(60)
                touch()
            }
            "done"
        }
        assertEquals("done", result)
    }

    @Test fun aSteadyAnswerStillStopsAtTheTotalLimit() = runBlocking<Unit> {
        val failure = runCatching {
            withStreamTimeouts(idleMillis = 150, totalMillis = 400) { touch ->
                while (true) {
                    delay(40)
                    touch()
                }
            }
        }.exceptionOrNull()
        assertTrue("got $failure", failure is AIStreamTimeoutException && !failure.wentQuiet)
    }

    @Test fun cancellingTheRequestIsNotATimeout() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val request = async {
            withStreamTimeouts(idleMillis = 5_000) {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        request.cancel()
        val failure = runCatching { request.await() }.exceptionOrNull()
        assertTrue("got $failure", failure is kotlinx.coroutines.CancellationException)
    }

    // Partial text

    @Test fun partialTextIsPacedAndNeverFollowsTheResult() = runBlocking<Unit> {
        val shown = Collections.synchronizedList(mutableListOf<Pair<Long, String>>())
        val started = System.nanoTime()
        val result = streamWithPartials(onPartial = { shown += ((System.nanoTime() - started) / 1_000_000) to it }, intervalMillis = 100) { emit ->
            // Forty pieces 5 ms apart: far more than one per interval.
            val text = StringBuilder()
            repeat(40) {
                text.append('字')
                emit(text.toString())
                delay(5)
            }
            text.toString()
        }
        val snapshot = shown.toList()
        assertTrue("too many partials: ${snapshot.size}", snapshot.size in 1..6)
        snapshot.zipWithNext().forEach { (previous, next) ->
            assertTrue("partials ${previous.first} and ${next.first} ms came too close", next.first - previous.first >= 90)
            assertTrue(next.second.startsWith(previous.second))
        }
        assertEquals(40, result.length)
        delay(250)
        assertEquals("a partial arrived after the result", snapshot.size, shown.size)
    }

    @Test fun aLateEmitIsDropped() = runBlocking<Unit> {
        val shown = Collections.synchronizedList(mutableListOf<String>())
        var late: ((String) -> Unit)? = null
        streamWithPartials(onPartial = { shown += it }, intervalMillis = 10) { emit ->
            late = emit
            emit("一")
            delay(50)
        }
        late?.invoke("二")
        yield()
        delay(50)
        assertEquals(listOf("一"), shown.toList())
    }

    @Test fun streamAnswerTouchesTheWatchdogWithEachText() = runBlocking<Unit> {
        val count = AtomicInteger()
        val result = streamAnswer(idleMillis = 150, onPartial = { count.incrementAndGet() }, totalMillis = 5_000, intervalMillis = 10) { emit ->
            repeat(6) { index ->
                delay(80)
                emit("x".repeat(index + 1))
            }
            "xxxxxx"
        }
        assertEquals("xxxxxx", result)
        assertTrue(count.get() >= 1)
    }

    // Helpers

    private fun decode(style: AIAPIStyle, vararg lines: String): AIStreamDecoder = decode(style, mutableListOf(), *lines)

    private fun decode(style: AIAPIStyle, shown: MutableList<String>, vararg lines: String): AIStreamDecoder {
        val decoder = AIStreamDecoder(style)
        lines.forEach { if (decoder.feed(it)) shown += decoder.text }
        return decoder
    }

    private fun sse(vararg payloads: String): String = payloads.joinToString("") { "data: $it\n\n" }

    private val clock = 1_800_000_000_000L

    private fun completion(client: ChatGPTPlanHttpClient): ChatGPTPlanCompletion =
        ChatGPTPlanCompletion(ChatGPTPlanSession(MemoryPlanStore(credential()), client, now = { clock }))

    private fun credential() = ChatGPTPlanCredential(
        clientID = "app_saved",
        accessToken = "access-1",
        refreshToken = "refresh-1",
        expiresAtMillis = clock + 3_600_000,
        email = null,
        planType = "plus",
        signedInAtMillis = clock
    )

    private fun direct() = DirectAIConfiguration(
        kind = AIProviderKind.CHATGPT_PLAN,
        displayName = AIProviderKind.CHATGPT_PLAN.title,
        endpoint = ChatGPTPlan.RESPONSES_URL,
        model = "gpt-test",
        apiKey = "chatgpt-plan-session",
        style = AIAPIStyle.RESPONSES,
        reasoning = null
    )
}
