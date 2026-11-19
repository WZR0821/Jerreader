package com.jerreader.android.translation

import com.jerreader.android.translation.chatgptplan.ChatGPTPlan
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanCompletion
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanCredential
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanHttpResponse
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanServiceFailure
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanSession
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One translation paid by the plan, with a fake client: success only on
 * `response.completed`, one retry with a fresh token, and usage limits and
 * ended sign-ins that stop the request instead of retrying it.
 */
class ChatGPTPlanCompletionTest {
    private val clock = 1_800_000_000_000L

    @Test fun aCompletedStreamIsTheAnswer() = runBlocking<Unit> {
        val client = FakePlanClient { completed("  你好  ") }
        val answer = completion(client).complete(direct(), "Translate into Chinese.", "Hello", quick = true)
        assertEquals("你好", answer)
        val sent = client.requests.single()
        assertEquals(ChatGPTPlan.RESPONSES_URL, sent.url)
        assertEquals("Bearer access-1", sent.headers["Authorization"])
        assertEquals(30_000, sent.timeoutMillis)
        val body = JSONObject(sent.body!!)
        assertEquals("gpt-test", body.getString("model"))
        assertEquals("Translate into Chinese.", body.getString("instructions"))
        assertEquals("Hello", body.getString("input"))
        assertEquals(false, body.getBoolean("store"))
        assertEquals(true, body.getBoolean("stream"))
        assertTrue(ALLOWED_FIELDS.containsAll(body.keys().asSequence().toSet()))

        // An explanation may take longer.
        val slow = FakePlanClient { completed("x") }
        completion(slow).complete(direct(), "i", "t", quick = false)
        assertEquals(60_000, slow.requests.single().timeoutMillis)
    }

    @Test fun theChosenReasoningIsSent() = runBlocking<Unit> {
        val high = FakePlanClient { completed("x") }
        completion(high).complete(direct(reasoning = AIReasoningEffort.HIGH), "i", "t", quick = true)
        val body = JSONObject(high.requests.single().body!!)
        assertEquals("high", body.getJSONObject("reasoning").getString("effort"))
        assertTrue(ALLOWED_FIELDS.containsAll(body.keys().asSequence().toSet()))

        val off = FakePlanClient { completed("x") }
        completion(off).complete(direct(reasoning = AIReasoningEffort.OFF), "i", "t", quick = true)
        assertEquals("none", JSONObject(off.requests.single().body!!).getJSONObject("reasoning").getString("effort"))
    }

    @Test fun aRefusedTokenIsRefreshedAndTheRequestSentOnceMore() = runBlocking<Unit> {
        val client = FakePlanClient { request ->
            when {
                request.url == ChatGPTPlan.TOKEN_URL -> ChatGPTPlanHttpResponse(200, """{"access_token":"access-2","refresh_token":"refresh-2"}""")
                request.headers["Authorization"] == "Bearer access-1" -> ChatGPTPlanHttpResponse(401, "")
                else -> completed("好")
            }
        }
        assertEquals("好", completion(client).complete(direct(), "i", "t", quick = true))
        assertEquals(listOf(ChatGPTPlan.RESPONSES_URL, ChatGPTPlan.TOKEN_URL, ChatGPTPlan.RESPONSES_URL), client.requests.map { it.url })
        assertEquals("Bearer access-2", client.requests[2].headers["Authorization"])
    }

    @Test fun aSecondRefusalNeedsANewSignIn() = runBlocking<Unit> {
        val client = FakePlanClient { request ->
            if (request.url == ChatGPTPlan.TOKEN_URL) {
                ChatGPTPlanHttpResponse(200, """{"access_token":"access-2","refresh_token":"refresh-2"}""")
            } else {
                ChatGPTPlanHttpResponse(401, "")
            }
        }
        assertEquals(ChatGPTPlanServiceFailure.SignInRequired(), failure(client))
        assertEquals(3, client.requests.size)
    }

    @Test fun usageLimitsStopTheRequest() = runBlocking<Unit> {
        for (response in listOf(
            ChatGPTPlanHttpResponse(429, """{"error":{"code":"subscription_sharing_usage_limit_exceeded"}}"""),
            ChatGPTPlanHttpResponse(403, """{"detail":"subscription_sharing_usage_unavailable"}"""),
            completedWith("""{"type":"response.failed","response":{"error":{"code":"subscription_sharing_usage_limit_exceeded"}}}""")
        )) {
            val client = FakePlanClient { response }
            assertEquals(ChatGPTPlanServiceFailure.UsageLimitReached(), failure(client))
            // Nothing retried it.
            assertEquals(1, client.requests.size)
        }
    }

    @Test fun eachRefusalIsToldApart() = runBlocking<Unit> {
        val rejected = """{"error":{"message":"unknown model"}}"""
        val cases = listOf(
            ChatGPTPlanHttpResponse(403, "{}") to ChatGPTPlanServiceFailure.SignInRequired(),
            ChatGPTPlanHttpResponse(400, rejected) to ChatGPTPlanServiceFailure.Rejected(400, rejected),
            ChatGPTPlanHttpResponse(422, "") to ChatGPTPlanServiceFailure.Rejected(422, ""),
            ChatGPTPlanHttpResponse(429, """{"error":{"code":"rate_limit_exceeded"}}""") to ChatGPTPlanServiceFailure.Temporary(),
            ChatGPTPlanHttpResponse(500, "") to ChatGPTPlanServiceFailure.Temporary(),
            completedWith("""{"type":"response.failed","response":{"error":{"code":"server_error"}}}""") to
                ChatGPTPlanServiceFailure.Temporary(),
            completedWith("""{"type":"response.incomplete","response":{"incomplete_details":{"reason":"max_output_tokens"}}}""") to
                ChatGPTPlanServiceFailure.ResultNotFound(),
            completed(" \n ") to ChatGPTPlanServiceFailure.ResultNotFound(),
            completedWith("""{"type":"response.output_text.delta","delta":"half"}""") to ChatGPTPlanServiceFailure.Temporary()
        )
        for ((response, expected) in cases) {
            val error = failure(FakePlanClient { response })
            assertEquals(expected, error)
            if (expected is ChatGPTPlanServiceFailure.Rejected) {
                assertEquals(expected.status, (error as ChatGPTPlanServiceFailure.Rejected).status)
                assertEquals(expected.body, error.body)
            }
        }
    }

    @Test fun signedOutOrOfflineSendsNothingOrFailsQuietly() = runBlocking<Unit> {
        val signedOut = FakePlanClient { completed("x") }
        val session = ChatGPTPlanSession(MemoryPlanStore(), signedOut, now = { clock })
        assertEquals(
            ChatGPTPlanServiceFailure.SignInRequired(),
            runCatching { ChatGPTPlanCompletion(session).complete(direct(), "i", "t", quick = true) }.exceptionOrNull()
        )
        assertTrue(signedOut.requests.isEmpty())

        assertEquals(ChatGPTPlanServiceFailure.Temporary(), failure(FakePlanClient { throw java.io.IOException("offline") }))
    }

    private suspend fun failure(client: FakePlanClient): Throwable? =
        runCatching { completion(client).complete(direct(), "i", "t", quick = true) }.exceptionOrNull()

    private fun completion(client: FakePlanClient): ChatGPTPlanCompletion {
        val credential = ChatGPTPlanCredential(
            clientID = "app_saved",
            accessToken = "access-1",
            refreshToken = "refresh-1",
            expiresAtMillis = clock + 3_600_000,
            email = null,
            planType = "plus",
            signedInAtMillis = clock
        )
        return ChatGPTPlanCompletion(ChatGPTPlanSession(MemoryPlanStore(credential), client, now = { clock }))
    }

    private fun direct(reasoning: AIReasoningEffort? = null) = DirectAIConfiguration(
        kind = AIProviderKind.CHATGPT_PLAN,
        displayName = AIProviderKind.CHATGPT_PLAN.title,
        endpoint = ChatGPTPlan.RESPONSES_URL,
        model = "gpt-test",
        apiKey = "chatgpt-plan-session",
        style = AIAPIStyle.RESPONSES,
        reasoning = reasoning
    )

    private companion object {
        /** All a plan request may carry; [ChatGPTPlanProtocolTest] checks the fields left out. */
        val ALLOWED_FIELDS = setOf("model", "instructions", "input", "store", "stream", "reasoning")

        fun completed(text: String): ChatGPTPlanHttpResponse = completedWith(
            JSONObject()
                .put("type", "response.completed")
                .put(
                    "response",
                    JSONObject().put(
                        "output",
                        org.json.JSONArray().put(
                            JSONObject()
                                .put("type", "message")
                                .put(
                                    "content",
                                    org.json.JSONArray().put(JSONObject().put("type", "output_text").put("text", text))
                                )
                        )
                    )
                )
                .toString()
        )

        fun completedWith(vararg events: String): ChatGPTPlanHttpResponse =
            ChatGPTPlanHttpResponse(200, ChatGPTPlanProtocolTest.sse(*events))
    }
}
