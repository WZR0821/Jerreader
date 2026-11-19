package com.jerreader.android.translation

import com.jerreader.android.translation.chatgptplan.ChatGPTPlan
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanAccount
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanAuthorization
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanCallback
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanCredential
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanEncoding
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanEntries
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanError
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanIDToken
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanLoopbackServer
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanMessageContext
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanMessages
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanModel
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanModels
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanPKCE
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanRequests
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanServiceFailure
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanSession
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanStream
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanStreamOutcome
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanTokenResponse
import com.jerreader.shared.translation.TranslationFailure
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket

/**
 * Sign in with ChatGPT without a browser or the network: PKCE, the authorize
 * URL, the callback, the token and Responses requests, streamed answers,
 * the account's labels, the loopback listener (on 127.0.0.1 only) and the
 * texts a stopped plan is reported with.
 */
class ChatGPTPlanProtocolTest {
    @Test fun pkceMatchesTheRfc7636Example() {
        val pkce = ChatGPTPlanPKCE(VERIFIER)
        assertEquals(CHALLENGE, pkce.challenge)
        assertFalse(pkce.toString().contains(VERIFIER))
        val random = ChatGPTPlanPKCE.random()
        // 32 random bytes, base64url without padding.
        assertEquals(43, random.verifier.length)
        assertTrue(random.verifier.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertFalse(random.verifier == ChatGPTPlanPKCE.random().verifier)
    }

    @Test fun base64URLHasNoPaddingAndReadsBothAlphabets() {
        assertEquals("-_8", ChatGPTPlanEncoding.base64URL(byteArrayOf(0xFB.toByte(), 0xFF.toByte())))
        assertEquals("", ChatGPTPlanEncoding.base64URL(ByteArray(0)))
        for (size in 0..10) {
            val bytes = ByteArray(size) { (it * 37 + 250).toByte() }
            val text = ChatGPTPlanEncoding.base64URL(bytes)
            assertFalse(text.contains('='))
            assertTrue(bytes.contentEquals(ChatGPTPlanEncoding.base64URLDecoded(text)))
        }
        assertTrue(byteArrayOf(0xFB.toByte(), 0xFF.toByte()).contentEquals(ChatGPTPlanEncoding.base64URLDecoded("+/8=")))
        assertNull(ChatGPTPlanEncoding.base64URLDecoded("ab!c"))
        assertNull(ChatGPTPlanEncoding.base64URLDecoded("abcde"))
        assertEquals(32, ChatGPTPlanEncoding.randomToken(24).length)
    }

    @Test fun percentEncodingIsStrictAndDecodingRefusesBrokenInput() {
        assertEquals("a%20b%2Bc%2F~-._%C3%A9", ChatGPTPlanEncoding.percentEncoded("a b+c/~-._é"))
        assertEquals("a=1&b=x%26y", ChatGPTPlanEncoding.formEncoded(listOf("a" to "1", "b" to "x&y")))
        assertEquals("あ+b", ChatGPTPlanEncoding.percentDecoded("%E3%81%82+b"))
        assertNull(ChatGPTPlanEncoding.percentDecoded("%ZZ"))
        assertNull(ChatGPTPlanEncoding.percentDecoded("%4"))
        assertNull(ChatGPTPlanEncoding.percentDecoded("é"))
        assertNull(ChatGPTPlanEncoding.percentDecoded("%C3"))
    }

    @Test fun firstSignInRegistersThisInstall() {
        val authorization = ChatGPTPlanAuthorization(
            clientID = null,
            hostID = HOST_ID,
            port = 51234,
            state = "state-1",
            nonce = "nonce-1",
            pkce = ChatGPTPlanPKCE(VERIFIER)
        )
        assertTrue(authorization.isRegistration)
        assertEquals(
            listOf(
                "response_type", "client_id", "redirect_uri", "scope", "resource", "state", "nonce",
                "code_challenge", "code_challenge_method", "ext_agent_host_id", "agent_name_hint"
            ),
            authorization.queryPairs.map { it.first }
        )
        val query = authorization.queryPairs.toMap()
        assertEquals("code", query["response_type"])
        assertEquals("dynamic_agent_client", query["client_id"])
        assertEquals("http://127.0.0.1:51234/auth/callback", query["redirect_uri"])
        assertEquals("openid profile email offline_access resource.invoke chatgpt.tokens.use.direct", query["scope"])
        assertEquals("https://api.openai.com/v1", query["resource"])
        assertEquals(CHALLENGE, query["code_challenge"])
        assertEquals("S256", query["code_challenge_method"])
        assertEquals(HOST_ID, query["ext_agent_host_id"])
        assertEquals("Jerreader", query["agent_name_hint"])
        assertTrue(
            authorization.url.startsWith(
                "https://auth.openai.com/api/accounts/authorize?response_type=code&client_id=dynamic_agent_client" +
                    "&redirect_uri=http%3A%2F%2F127.0.0.1%3A51234%2Fauth%2Fcallback" +
                    "&scope=openid%20profile%20email%20offline_access%20resource.invoke%20chatgpt.tokens.use.direct"
            )
        )
        // The state, nonce and verifier stay out of logs.
        assertFalse(authorization.toString().contains("state-1"))
        assertFalse(authorization.toString().contains(VERIFIER))
    }

    @Test fun laterSignInsReuseTheIssuedClient() {
        val authorization = ChatGPTPlanAuthorization(
            clientID = " app_test ",
            hostID = HOST_ID,
            port = 40000,
            loginHint = " reader@example.com "
        )
        assertFalse(authorization.isRegistration)
        assertEquals("app_test", authorization.clientID)
        val query = authorization.queryPairs.toMap()
        assertEquals("app_test", query["client_id"])
        assertFalse(query.containsKey("agent_name_hint"))
        assertEquals("reader@example.com", query["login_hint"])
        assertEquals(43, authorization.pkce.verifier.length)
        assertEquals(32, authorization.state.length)
        assertEquals(32, authorization.nonce.length)
        // A blank client id is the first sign-in again; a blank hint is left out.
        val blank = ChatGPTPlanAuthorization(clientID = "  ", hostID = HOST_ID, port = 1, loginHint = " ")
        assertTrue(blank.isRegistration)
        assertFalse(blank.queryPairs.toMap().containsKey("login_hint"))
    }

    @Test fun callbackIsCheckedAgainstTheAttempt() {
        val first = authorization(clientID = null)
        assertEquals(
            ChatGPTPlanCallback("c0de", "app_issued"),
            ChatGPTPlanCallback.parse("/auth/callback?code=c0de&state=state-1&client_id=app_issued", first)
        )
        // A first sign-in must come back with the client id it registered.
        assertEquals(
            ChatGPTPlanError.RegistrationIncomplete(),
            thrown { ChatGPTPlanCallback.parse("/auth/callback?code=c0de&state=state-1", first) }
        )

        val later = authorization(clientID = "app_issued")
        assertEquals(ChatGPTPlanCallback("c", null), ChatGPTPlanCallback.parse("/auth/callback?state=state-1&code=c", later))
        assertEquals("a/b", ChatGPTPlanCallback.parse("/auth/callback?code=a%2Fb&state=state-1", later).code)
        assertEquals("first", ChatGPTPlanCallback.parse("/auth/callback?code=first&code=second&state=state-1", later).code)
        assertEquals("c", ChatGPTPlanCallback.parse("/auth/callback?code=c&state=state-1#frag", later).code)

        assertEquals(ChatGPTPlanError.StateMismatch(), thrown { ChatGPTPlanCallback.parse("/auth/callback?code=c&state=other", later) })
        assertEquals(ChatGPTPlanError.StateMismatch(), thrown { ChatGPTPlanCallback.parse("/auth/callback?code=c", later) })
        assertEquals(
            ChatGPTPlanError.Cancelled(),
            thrown { ChatGPTPlanCallback.parse("/auth/callback?error=access_denied&state=state-1", later) }
        )
        val refused = thrown { ChatGPTPlanCallback.parse("/auth/callback?error=server_error&state=state-1", later) }
        assertTrue(refused is ChatGPTPlanError.AuthorizationFailed)
        assertEquals("server_error", (refused as ChatGPTPlanError.AuthorizationFailed).reason)
        for (target in listOf(
            "/other?code=c&state=state-1",
            "http://127.0.0.1/auth/callback?code=c&state=state-1",
            "/auth/callback?code=&state=state-1",
            "/auth/callback?code=%ZZ&state=state-1"
        )) {
            assertEquals(target, ChatGPTPlanError.InvalidCallback(), thrown { ChatGPTPlanCallback.parse(target, later) })
        }
        assertFalse(ChatGPTPlanCallback("c0de", null).toString().contains("c0de"))
    }

    @Test fun tokenRequestsAreFormsWithoutASecret() {
        val exchange = ChatGPTPlanRequests.authorizationCode("c0de", VERIFIER, "http://127.0.0.1:5/auth/callback", "app_test")
        assertEquals("POST", exchange.method)
        assertEquals(ChatGPTPlan.TOKEN_URL, exchange.url)
        assertEquals("application/x-www-form-urlencoded", exchange.headers["Content-Type"])
        assertEquals(
            "grant_type=authorization_code&client_id=app_test&code=c0de&code_verifier=$VERIFIER" +
                "&redirect_uri=http%3A%2F%2F127.0.0.1%3A5%2Fauth%2Fcallback&resource=https%3A%2F%2Fapi.openai.com%2Fv1",
            exchange.body
        )
        assertFalse(exchange.toString().contains("c0de"))

        val refresh = ChatGPTPlanRequests.refresh("refresh-test", "app_test")
        assertEquals(
            "grant_type=refresh_token&client_id=app_test&refresh_token=refresh-test&resource=https%3A%2F%2Fapi.openai.com%2Fv1",
            refresh.body
        )

        val revoke = ChatGPTPlanRequests.revoke("https://auth.openai.com/oauth/revoke", "refresh-test", "app_test")
        assertEquals("https://auth.openai.com/oauth/revoke", revoke.url)
        assertEquals("token=refresh-test&token_type_hint=refresh_token&client_id=app_test", revoke.body)

        for (request in listOf(exchange, refresh, revoke)) {
            assertFalse(request.body.orEmpty().contains("secret"))
            assertFalse(request.body.orEmpty().contains("scope"))
        }

        val models = ChatGPTPlanRequests.models("access-test")
        assertEquals("GET", models.method)
        assertEquals(ChatGPTPlan.MODELS_URL, models.url)
        assertEquals("Bearer access-test", models.headers["Authorization"])
        assertNull(models.body)
        assertEquals(ChatGPTPlan.DISCOVERY_URL, ChatGPTPlanRequests.discovery().url)
    }

    @Test fun responsesRequestKeepsOnlyWhatThePlanTakes() {
        val extra = JSONObject()
            .put("reasoning", JSONObject().put("effort", "low"))
            .put("temperature", 0.2)
            .put("max_output_tokens", 10)
        val request = ChatGPTPlanRequests.responses("access-test", "gpt-test", "Translate.", "こんにちは", extra, 45_000)
        assertEquals("POST", request.method)
        assertEquals(ChatGPTPlan.RESPONSES_URL, request.url)
        assertEquals("Bearer access-test", request.headers["Authorization"])
        assertEquals("text/event-stream", request.headers["Accept"])
        assertEquals(45_000, request.timeoutMillis)
        val body = JSONObject(request.body!!)
        assertEquals(setOf("model", "instructions", "input", "store", "stream", "reasoning"), body.keys().asSequence().toSet())
        assertEquals("gpt-test", body.getString("model"))
        assertEquals("Translate.", body.getString("instructions"))
        assertEquals("こんにちは", body.getString("input"))
        assertFalse(body.getBoolean("store"))
        assertTrue(body.getBoolean("stream"))
        assertEquals("low", body.getJSONObject("reasoning").getString("effort"))
        for (field in ChatGPTPlanRequests.OMITTED_FIELDS) assertFalse(field, body.has(field))

        val plain = JSONObject(ChatGPTPlanRequests.responses("access-test", "gpt-test", "i", "t").body!!)
        assertFalse(plain.has("reasoning"))
        assertFalse(request.toString().contains("access-test"))
    }

    @Test fun onlyAGoneGrantNeedsANewSignIn() {
        assertTrue(ChatGPTPlanRequests.isDeadGrant(400, """{"error":"invalid_grant"}"""))
        assertTrue(ChatGPTPlanRequests.isDeadGrant(401, """{"error":{"code":"invalid_client"}}"""))
        assertTrue(ChatGPTPlanRequests.isDeadGrant(400, """{"error":"unauthorized_client"}"""))
        assertFalse(ChatGPTPlanRequests.isDeadGrant(500, """{"error":"invalid_grant"}"""))
        assertFalse(ChatGPTPlanRequests.isDeadGrant(400, """{"error":"invalid_request"}"""))
        assertFalse(ChatGPTPlanRequests.isDeadGrant(400, "not json"))
    }

    @Test fun onlyAnHttpsOpenAIAddressGetsTheRefreshToken() {
        assertTrue(ChatGPTPlanSession.isRevocationEndpoint("https://auth.openai.com/oauth/revoke"))
        assertTrue(ChatGPTPlanSession.isRevocationEndpoint("https://openai.com/revoke"))
        for (text in listOf(
            "http://auth.openai.com/oauth/revoke",
            "https://auth.openai.com.example.test/revoke",
            "https://exampleopenai.com/revoke",
            "https://127.0.0.1/revoke",
            "https://example.test/?openai.com",
            "not a url",
            ""
        )) {
            assertFalse(text, ChatGPTPlanSession.isRevocationEndpoint(text))
        }
    }

    @Test fun streamSucceedsOnlyOnCompleted() {
        val completed = sse(
            """{"type":"response.output_text.delta","delta":"你"}""",
            """{"type":"response.completed","response":{"output":[{"type":"reasoning"},{"type":"message","content":[{"type":"output_text","text":"你好"}]}]}}"""
        )
        assertEquals(ChatGPTPlanStreamOutcome.Completed("你好"), ChatGPTPlanStream.parse(completed))

        // Without output in the final event, the deltas are the answer.
        val deltas = sse(
            """{"type":"response.output_text.delta","delta":"A"}""",
            """{"type":"response.output_text.delta","delta":"B"}""",
            """{"type":"response.completed","response":{}}"""
        )
        assertEquals(ChatGPTPlanStreamOutcome.Completed("AB"), ChatGPTPlanStream.parse(deltas))
        assertEquals(ChatGPTPlanStreamOutcome.Completed("AB"), ChatGPTPlanStream.parse(deltas.replace("\n", "\r\n")))

        // The type may come from the event line alone.
        val named = "event: response.completed\ndata: {\"response\":{\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":\"x\"}]}]}}\n\n"
        assertEquals(ChatGPTPlanStreamOutcome.Completed("x"), ChatGPTPlanStream.parse(named))

        val cut = sse("""{"type":"response.output_text.delta","delta":"A"}""") + "data: [DONE]\n\n"
        assertEquals(ChatGPTPlanStreamOutcome.Interrupted, ChatGPTPlanStream.parse(cut))
        assertEquals(ChatGPTPlanStreamOutcome.Interrupted, ChatGPTPlanStream.parse(""))

        val failed = sse("""{"type":"response.failed","response":{"error":{"code":"subscription_sharing_usage_limit_exceeded"}}}""")
        assertEquals(ChatGPTPlanStreamOutcome.Failed("subscription_sharing_usage_limit_exceeded"), ChatGPTPlanStream.parse(failed))
        val error = sse("""{"type":"error","code":"subscription_sharing_usage_unavailable"}""")
        assertEquals(ChatGPTPlanStreamOutcome.Failed("subscription_sharing_usage_unavailable"), ChatGPTPlanStream.parse(error))
        val incomplete = sse("""{"type":"response.incomplete","response":{"incomplete_details":{"reason":"max_output_tokens"}}}""")
        assertEquals(ChatGPTPlanStreamOutcome.Incomplete("max_output_tokens"), ChatGPTPlanStream.parse(incomplete))

        assertTrue(ChatGPTPlanStream.isUsageLimit("subscription_sharing_usage_limit_exceeded"))
        assertTrue(ChatGPTPlanStream.isUsageLimit("subscription_sharing_usage_unavailable"))
        assertFalse(ChatGPTPlanStream.isUsageLimit("server_error"))
        assertFalse(ChatGPTPlanStream.isUsageLimit(null))
        assertEquals(
            "subscription_sharing_usage_limit_exceeded",
            ChatGPTPlanStream.usageLimitCode("""{"detail":"subscription_sharing_usage_limit_exceeded"}""")
        )
        assertNull(ChatGPTPlanStream.usageLimitCode("""{"error":{"code":"rate_limit_exceeded"}}"""))
    }

    @Test fun modelsAreTheListedOnesInOrder() {
        val body = """{"models":[
            {"slug":"gpt-a","display_name":"GPT A","visibility":"list"},
            {"slug":"hidden","display_name":"Hidden","visibility":"hide"},
            {"slug":"gpt-a","display_name":"Again","visibility":"list"},
            {"id":"gpt-b","visibility":"list"},
            {"slug":"  ","visibility":"list"}
        ]}"""
        assertEquals(
            listOf(ChatGPTPlanModel("gpt-a", "GPT A"), ChatGPTPlanModel("gpt-b", "gpt-b")),
            ChatGPTPlanModels.parse(body)
        )
        assertEquals(emptyList<ChatGPTPlanModel>(), ChatGPTPlanModels.parse("""{"data":[]}"""))
        assertEquals(emptyList<ChatGPTPlanModel>(), ChatGPTPlanModels.parse("{}"))
        assertEquals(ChatGPTPlanError.UnexpectedResponse(), thrown { ChatGPTPlanModels.parse("not json") })
    }

    @Test fun idTokenOnlyLabelsTheAccount() {
        val token = jwt("""{"email":"reader@example.com","https://api.openai.com/auth":{"chatgpt_plan_type":"plus"}}""")
        val account = ChatGPTPlanIDToken.account(token)
        assertEquals(ChatGPTPlanAccount("reader@example.com", "plus"), account)
        assertEquals("Plus", account.planTitle)
        assertEquals(ChatGPTPlanAccount(), ChatGPTPlanIDToken.account(null))
        assertEquals(ChatGPTPlanAccount(), ChatGPTPlanIDToken.account("garbage"))
        assertEquals(ChatGPTPlanAccount(), ChatGPTPlanIDToken.account("a.!!!.c"))
        assertEquals(ChatGPTPlanAccount(), ChatGPTPlanIDToken.account(jwt("""{"email":""}""")))
    }

    @Test fun planTitles() {
        assertEquals("Plus", ChatGPTPlanAccount(planType = "plus").planTitle)
        assertEquals("Pro", ChatGPTPlanAccount(planType = "PRO").planTitle)
        assertEquals("Team", ChatGPTPlanAccount(planType = " team ").planTitle)
        assertEquals("K12", ChatGPTPlanAccount(planType = "k12").planTitle)
        assertNull(ChatGPTPlanAccount(planType = " ").planTitle)
        assertNull(ChatGPTPlanAccount().planTitle)
    }

    @Test fun tokenResponseAndCredentialJson() {
        val token = ChatGPTPlanTokenResponse.parse(
            """{"access_token":"access-test","refresh_token":"refresh-test","id_token":"id-test","expires_in":3600}"""
        )!!
        assertEquals("access-test", token.accessToken)
        assertEquals("refresh-test", token.refreshToken)
        assertEquals("id-test", token.idToken)
        assertEquals(3600.0, token.expiresIn!!, 0.0)
        assertFalse(token.toString().contains("access-test"))
        assertNull(ChatGPTPlanTokenResponse.parse("""{"refresh_token":"refresh-test"}"""))
        assertNull(ChatGPTPlanTokenResponse.parse("x"))

        val credential = ChatGPTPlanCredential(
            clientID = "app_test",
            accessToken = "access-test",
            refreshToken = "refresh-test",
            expiresAtMillis = 1_800_000_000_000,
            email = "reader@example.com",
            planType = "plus",
            signedInAtMillis = 1_799_999_000_000
        )
        assertEquals(credential, ChatGPTPlanCredential.fromJson(credential.toJson()))
        val anonymous = credential.copy(email = null, planType = null)
        assertEquals(anonymous, ChatGPTPlanCredential.fromJson(anonymous.toJson()))
        assertFalse(JSONObject(anonymous.toJson()).has("email"))
        assertEquals(ChatGPTPlanAccount("reader@example.com", "plus"), credential.account)
        // Nothing secret in logs.
        assertFalse(credential.toString().contains("access-test"))
        assertFalse(credential.toString().contains("refresh-test"))

        assertNull(ChatGPTPlanCredential.fromJson(null))
        assertNull(ChatGPTPlanCredential.fromJson(""))
        assertNull(ChatGPTPlanCredential.fromJson("{}"))
        assertNull(ChatGPTPlanCredential.fromJson("""{"clientID":"a","accessToken":"b","expiresAt":1}"""))
        val older = ChatGPTPlanCredential.fromJson("""{"clientID":"a","accessToken":"b","refreshToken":"c","expiresAt":5}""")!!
        assertEquals(5L, older.signedInAtMillis)
        assertNull(older.email)
    }

    @Test fun entryKeepsTheChosenReasoningAndModel() {
        val fresh = ChatGPTPlanEntries.merged(null, listOf(ChatGPTPlanModel("gpt-a", "A"), ChatGPTPlanModel("gpt-b", "B")))
        assertEquals(ChatGPTPlan.PROVIDER_ID, fresh.id)
        assertEquals(AIProviderKind.CHATGPT_PLAN, fresh.kind)
        assertEquals(AIProviderKind.CHATGPT_PLAN.defaultBaseURL, fresh.baseURL)
        assertNull(fresh.name)
        assertEquals("gpt-a", fresh.model)
        assertEquals(listOf(AIModelEntry("gpt-a", AIAPIStyle.RESPONSES), AIModelEntry("gpt-b", AIAPIStyle.RESPONSES)), fresh.models)

        val tuned = fresh.copy(
            model = "gpt-b",
            models = listOf(AIModelEntry("gpt-b", AIAPIStyle.RESPONSES, AIReasoningEffort.HIGH), AIModelEntry("old", AIAPIStyle.RESPONSES))
        )
        val refreshed = ChatGPTPlanEntries.merged(tuned, listOf(ChatGPTPlanModel("gpt-a", "A"), ChatGPTPlanModel("gpt-b", "B")))
        assertEquals("gpt-b", refreshed.model)
        assertEquals(
            listOf(AIModelEntry("gpt-a", AIAPIStyle.RESPONSES), AIModelEntry("gpt-b", AIAPIStyle.RESPONSES, AIReasoningEffort.HIGH)),
            refreshed.models
        )
        // A model the account no longer offers gives way to the first one.
        val gone = ChatGPTPlanEntries.merged(tuned.copy(model = "old"), listOf(ChatGPTPlanModel("gpt-a", "A")))
        assertEquals("gpt-a", gone.model)
        // An empty list keeps the models there were.
        assertEquals(tuned, ChatGPTPlanEntries.merged(tuned, emptyList()))
    }

    @Test fun aStoppedPlanIsToldInThePagesWords() {
        assertEquals(
            TranslationFailure.ProviderRejected(ChatGPTPlanMessages.USAGE_LIMIT_TRANSLATION),
            ChatGPTPlanMessages.translationFailure(ChatGPTPlanServiceFailure.UsageLimitReached(), ChatGPTPlanMessageContext.TRANSLATION)
        )
        assertEquals(
            TranslationFailure.ProviderRejected(ChatGPTPlanMessages.SIGN_IN_EXPLANATION),
            ChatGPTPlanMessages.translationFailure(ChatGPTPlanServiceFailure.SignInRequired(), ChatGPTPlanMessageContext.EXPLANATION)
        )
        assertEquals(
            TranslationFailure.ProviderRejected("翻译服务返回错误：unknown model"),
            ChatGPTPlanMessages.translationFailure(
                ChatGPTPlanServiceFailure.Rejected(400, """{"error":{"message":"unknown model"}}"""),
                ChatGPTPlanMessageContext.TRANSLATION
            )
        )
        assertEquals(
            TranslationFailure.ProviderRejected("翻译服务返回 HTTP 422。"),
            ChatGPTPlanMessages.translationFailure(ChatGPTPlanServiceFailure.Rejected(422, ""), ChatGPTPlanMessageContext.TRANSLATION)
        )
        assertEquals(
            TranslationFailure.ServiceUnavailable,
            ChatGPTPlanMessages.translationFailure(ChatGPTPlanServiceFailure.Temporary(), ChatGPTPlanMessageContext.TRANSLATION)
        )
        assertEquals(
            TranslationFailure.ServiceUnavailable,
            ChatGPTPlanMessages.translationFailure(ChatGPTPlanServiceFailure.ResultNotFound(), ChatGPTPlanMessageContext.EXPLANATION)
        )
        assertEquals(ChatGPTPlanMessages.USAGE_LIMIT_SETTINGS, ChatGPTPlanServiceFailure.UsageLimitReached().message)

        for (context in ChatGPTPlanMessageContext.entries) {
            assertTrue(ChatGPTPlanMessages.isStop(TranslationFailure.ProviderRejected(ChatGPTPlanMessages.usageLimit(context))))
            assertTrue(ChatGPTPlanMessages.isStop(TranslationFailure.ProviderRejected(ChatGPTPlanMessages.signIn(context))))
        }
        assertFalse(ChatGPTPlanMessages.isStop(TranslationFailure.ProviderRejected("翻译服务返回 HTTP 422。")))
        assertFalse(ChatGPTPlanMessages.isStop(TranslationFailure.ServiceUnavailable))
        assertFalse(ChatGPTPlanMessages.isStop(null))

        assertTrue(ChatGPTPlanServiceFailure.Temporary().isTransient)
        assertTrue(ChatGPTPlanServiceFailure.ResultNotFound().isTransient)
        assertFalse(ChatGPTPlanServiceFailure.UsageLimitReached().isTransient)
        assertFalse(ChatGPTPlanServiceFailure.SignInRequired().isTransient)
        assertFalse(ChatGPTPlanServiceFailure.Rejected(400, "").isTransient)
    }

    @Test fun loopbackHelpers() {
        assertEquals(
            ChatGPTPlanLoopbackServer.RequestHead("GET", "/auth/callback?code=1"),
            ChatGPTPlanLoopbackServer.requestHead("GET /auth/callback?code=1 HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
        )
        assertNull(ChatGPTPlanLoopbackServer.requestHead("GET / HTTP/1.1\r\nHost: x".toByteArray()))
        assertEquals(
            ChatGPTPlanLoopbackServer.RequestHead("", ""),
            ChatGPTPlanLoopbackServer.requestHead("nonsense\r\n\r\n".toByteArray())
        )
        assertEquals("/auth/callback", ChatGPTPlanLoopbackServer.path("/auth/callback?x=1"))

        val reply = String(ChatGPTPlanLoopbackServer.response("302 Found", "intent://x", "<p>"), Charsets.UTF_8)
        assertTrue(reply.startsWith("HTTP/1.1 302 Found\r\nLocation: intent://x\r\n"))
        assertTrue(reply.contains("\r\nContent-Length: 3\r\n"))
        assertTrue(reply.contains("\r\nCache-Control: no-store\r\n"))
        assertTrue(reply.endsWith("\r\n\r\n<p>"))

        val link = ChatGPTPlanLoopbackServer.intentURL("com.example.app")
        assertEquals("intent://chatgpt-auth/done#Intent;scheme=jerreader;package=com.example.app;end", link)
        assertTrue(ChatGPTPlanLoopbackServer.finishedPage(link).contains("href=\"$link\""))
        assertTrue(ChatGPTPlanLoopbackServer.finishedPage("a&b\"<c>").contains("href=\"a&amp;b&quot;&lt;c&gt;\""))
        assertEquals(16 * 1024, ChatGPTPlanLoopbackServer.MAXIMUM_REQUEST_BYTES)
    }

    @Test fun loopbackHandsOverTheFirstCallbackThenStopsListening() = runBlocking<Unit> {
        val link = ChatGPTPlanLoopbackServer.intentURL("com.example.app")
        val server = ChatGPTPlanLoopbackServer(link, "<p>ok</p>")
        val port = server.start()
        try {
            assertTrue(port > 0)
            // Anything else is refused and the wait goes on.
            assertTrue(exchange(port, "GET /favicon.ico HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n").startsWith("HTTP/1.1 404 "))
            assertTrue(
                exchange(port, "POST /auth/callback HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 0\r\n\r\n")
                    .startsWith("HTTP/1.1 405 ")
            )
            assertTrue(exchange(port, "nonsense\r\n\r\n").startsWith("HTTP/1.1 400 "))

            val reply = exchange(port, "GET /auth/callback?code=c&state=s HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
            assertTrue(reply.startsWith("HTTP/1.1 302 Found\r\n"))
            assertTrue(reply.contains("\r\nLocation: $link\r\n"))
            assertTrue(reply.endsWith("<p>ok</p>"))
            assertEquals("/auth/callback?code=c&state=s", withTimeout(5_000) { server.callbackTarget() })
            // The listener closed with the callback.
            assertTrue(runCatching { Socket("127.0.0.1", port).close() }.isFailure)
        } finally {
            server.stop()
        }
    }

    @Test fun loopbackWaitEndsWithStopOrFailure() = runBlocking<Unit> {
        val stopped = ChatGPTPlanLoopbackServer("x", "")
        stopped.start()
        stopped.stop()
        assertEquals(ChatGPTPlanError.Cancelled(), runCatching { stopped.callbackTarget() }.exceptionOrNull())
        // A listener is started once.
        assertEquals(ChatGPTPlanError.ListenerUnavailable(), runCatching { stopped.start() }.exceptionOrNull())

        val failed = ChatGPTPlanLoopbackServer("x", "")
        failed.start()
        try {
            failed.fail(ChatGPTPlanError.Network())
            assertEquals(ChatGPTPlanError.Network(), runCatching { failed.callbackTarget() }.exceptionOrNull())
        } finally {
            failed.stop()
        }
    }

    private fun authorization(clientID: String?) = ChatGPTPlanAuthorization(
        clientID = clientID,
        hostID = HOST_ID,
        port = 4321,
        state = "state-1",
        nonce = "nonce-1",
        pkce = ChatGPTPlanPKCE(VERIFIER)
    )

    private fun exchange(port: Int, request: String): String =
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().apply {
                write(request.toByteArray(Charsets.UTF_8))
                flush()
            }
            String(socket.getInputStream().readBytes(), Charsets.UTF_8)
        }

    companion object {
        /** RFC 7636, appendix B. */
        const val VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        const val CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        const val HOST_ID = "urn:uuid:00000000-0000-4000-8000-000000000000"

        fun thrown(block: () -> Unit): Throwable? = try {
            block()
            null
        } catch (error: Throwable) {
            error
        }

        fun sse(vararg payloads: String): String = payloads.joinToString("") { "data: $it\n\n" }

        fun jwt(payload: String): String =
            ChatGPTPlanEncoding.base64URL("""{"alg":"none"}""".toByteArray(Charsets.UTF_8)) + "." +
                ChatGPTPlanEncoding.base64URL(payload.toByteArray(Charsets.UTF_8)) + ".signature"
    }
}
