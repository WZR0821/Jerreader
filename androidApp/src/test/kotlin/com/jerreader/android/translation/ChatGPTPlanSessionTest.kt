package com.jerreader.android.translation

import com.jerreader.android.translation.chatgptplan.ChatGPTPlan
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanAccount
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanAuthorization
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanCredential
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanCredentialStoring
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanError
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanHttpClient
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanHttpRequest
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanHttpResponse
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanModel
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanPKCE
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanSession
import com.jerreader.android.translation.chatgptplan.KeystoreChatGPTPlanCredentialStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * The signed-in credential with a fake HTTP client and an in-memory store:
 * the code exchange, refreshes (one at a time, rotated, refused), sign-out
 * with revocation only to OpenAI, and the account's models.
 */
class ChatGPTPlanSessionTest {
    private var clock = 1_800_000_000_000L

    @Test fun signInExchangesTheCodeAndKeepsTheAccount() = runBlocking<Unit> {
        val idToken = ChatGPTPlanProtocolTest.jwt(
            """{"email":"reader@example.com","https://api.openai.com/auth":{"chatgpt_plan_type":"pro"}}"""
        )
        val client = FakePlanClient {
            ok("""{"access_token":"access-1","refresh_token":"refresh-1","id_token":"$idToken","expires_in":600}""")
        }
        val store = MemoryPlanStore()
        val changes = Collections.synchronizedList(mutableListOf<ChatGPTPlanCredential?>())
        val session = ChatGPTPlanSession(store, client, now = { clock }, onChange = { changes.add(it) })

        val credential = session.completeSignIn("code-1", authorization(clientID = null), issuedClientID = "app_issued")
        assertEquals("app_issued", credential.clientID)
        assertEquals("access-1", credential.accessToken)
        assertEquals("refresh-1", credential.refreshToken)
        assertEquals(clock + 600_000, credential.expiresAtMillis)
        assertEquals(clock, credential.signedInAtMillis)
        assertEquals(ChatGPTPlanAccount("reader@example.com", "pro"), credential.account)
        assertEquals(credential, store.saved)
        assertEquals(listOf<ChatGPTPlanCredential?>(credential), changes.toList())

        val sent = client.requests.single()
        assertEquals(ChatGPTPlan.TOKEN_URL, sent.url)
        val body = sent.body.orEmpty()
        assertTrue(body.contains("grant_type=authorization_code"))
        assertTrue(body.contains("client_id=app_issued"))
        assertTrue(body.contains("code=code-1"))
        assertTrue(body.contains("code_verifier=${ChatGPTPlanProtocolTest.VERIFIER}"))
        assertTrue(body.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A4321%2Fauth%2Fcallback"))

        // A token far from expiry is used as it is.
        assertEquals("access-1", session.accessToken())
        assertEquals(1, client.requests.size)
        assertEquals(credential, session.current())
    }

    @Test fun signInWithoutARegisteredClientSendsNothing() = runBlocking<Unit> {
        val client = FakePlanClient { ok("{}") }
        val session = ChatGPTPlanSession(MemoryPlanStore(), client, now = { clock })
        assertEquals(
            ChatGPTPlanError.RegistrationIncomplete(),
            runCatching { session.completeSignIn("code-1", authorization(clientID = null), issuedClientID = null) }.exceptionOrNull()
        )
        assertTrue(client.requests.isEmpty())
        // A returning install signs in with the client id it was issued.
        val returning = FakePlanClient { ok("""{"access_token":"access-1","refresh_token":"refresh-1"}""") }
        val later = ChatGPTPlanSession(MemoryPlanStore(), returning, now = { clock })
        val credential = later.completeSignIn("code-1", authorization(clientID = "app_saved"), issuedClientID = null)
        assertEquals("app_saved", credential.clientID)
        // Without expires_in the token is taken to last an hour.
        assertEquals(clock + 3_600_000, credential.expiresAtMillis)
        assertEquals(ChatGPTPlanAccount(), credential.account)
    }

    @Test fun refusedOrOddExchangesSaveNothing() = runBlocking<Unit> {
        val cases = listOf(
            ChatGPTPlanHttpResponse(400, """{"error":"invalid_grant"}""") to ChatGPTPlanError.AuthorizationFailed("invalid_grant"),
            ChatGPTPlanHttpResponse(500, "") to ChatGPTPlanError.Network(),
            ChatGPTPlanHttpResponse(200, """{"access_token":"access-1"}""") to ChatGPTPlanError.UnexpectedResponse(),
            ChatGPTPlanHttpResponse(200, "not json") to ChatGPTPlanError.UnexpectedResponse()
        )
        for ((response, expected) in cases) {
            val store = MemoryPlanStore()
            val session = ChatGPTPlanSession(store, FakePlanClient { response }, now = { clock })
            val error = runCatching { session.completeSignIn("code-1", authorization(clientID = "app_saved"), null) }.exceptionOrNull()
            assertEquals(expected.message, error?.message)
            assertEquals(expected.javaClass, error?.javaClass)
            assertNull(store.saved)
            assertNull(session.current())
        }
        val offline = ChatGPTPlanSession(MemoryPlanStore(), FakePlanClient { throw java.io.IOException("offline") }, now = { clock })
        assertEquals(
            ChatGPTPlanError.Network(),
            runCatching { offline.completeSignIn("code-1", authorization(clientID = "app_saved"), null) }.exceptionOrNull()
        )
    }

    @Test fun aTokenNearExpiryIsRefreshedAndTheNewRefreshTokenKept() = runBlocking<Unit> {
        val store = MemoryPlanStore(credential(expiresAt = clock + 60_000))
        val client = FakePlanClient { ok("""{"access_token":"access-2","refresh_token":"refresh-2","expires_in":3600}""") }
        val changes = Collections.synchronizedList(mutableListOf<ChatGPTPlanCredential?>())
        val session = ChatGPTPlanSession(store, client, now = { clock }, onChange = { changes.add(it) })

        assertEquals("access-2", session.accessToken())
        val sent = client.requests.single()
        val body = sent.body.orEmpty()
        assertEquals(ChatGPTPlan.TOKEN_URL, sent.url)
        assertTrue(body.contains("grant_type=refresh_token"))
        assertTrue(body.contains("refresh_token=refresh-1"))
        assertTrue(body.contains("client_id=app_saved"))
        assertTrue(!body.contains("scope"))
        assertEquals("refresh-2", store.saved?.refreshToken)
        assertEquals(clock + 3_600_000, store.saved?.expiresAtMillis)
        // Without a new ID token the account stays as it was.
        assertEquals(ChatGPTPlanAccount("reader@example.com", "plus"), store.saved?.account)
        assertEquals(listOf<ChatGPTPlanCredential?>(store.saved), changes.toList())

        // Now valid, so it is used as it is.
        assertEquals("access-2", session.accessToken())
        assertEquals(1, client.requests.size)
    }

    @Test fun aRefreshWithoutANewRefreshTokenKeepsTheOldOne() = runBlocking<Unit> {
        val store = MemoryPlanStore(credential(expiresAt = clock - 1))
        val session = ChatGPTPlanSession(store, FakePlanClient { ok("""{"access_token":"access-2"}""") }, now = { clock })
        assertEquals("access-2", session.accessToken())
        assertEquals("refresh-1", store.saved?.refreshToken)
    }

    @Test fun aRefusedTokenIsReplacedOnce() = runBlocking<Unit> {
        val store = MemoryPlanStore(credential(expiresAt = clock + 3_600_000))
        val client = FakePlanClient { ok("""{"access_token":"access-2","refresh_token":"refresh-2"}""") }
        val session = ChatGPTPlanSession(store, client, now = { clock })
        assertEquals("access-1", session.accessToken())
        assertEquals("access-2", session.accessToken(rejected = "access-1"))
        // Another request refused with the old token gets the new one without a second refresh.
        assertEquals("access-2", session.accessToken(rejected = "access-1"))
        assertEquals(1, client.requests.size)
    }

    @Test fun refreshesAtTheSameTimeShareOneRequest() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val count = AtomicInteger()
        val client = ChatGPTPlanHttpClient {
            count.incrementAndGet()
            gate.await()
            ok("""{"access_token":"access-2","refresh_token":"refresh-2"}""")
        }
        val session = ChatGPTPlanSession(MemoryPlanStore(credential(expiresAt = clock - 1)), client, now = { clock })
        val first = async { session.accessToken() }
        val second = async { session.accessToken() }
        withTimeout(5_000) {
            while (count.get() == 0) delay(10)
        }
        gate.complete(Unit)
        assertEquals("access-2", first.await())
        assertEquals("access-2", second.await())
        assertEquals(1, count.get())
    }

    @Test fun aGrantThatIsGoneSignsOut() = runBlocking<Unit> {
        val store = MemoryPlanStore(credential(expiresAt = clock - 1))
        val changes = Collections.synchronizedList(mutableListOf<ChatGPTPlanCredential?>())
        val session = ChatGPTPlanSession(
            store,
            FakePlanClient { ChatGPTPlanHttpResponse(400, """{"error":"invalid_grant"}""") },
            now = { clock },
            onChange = { changes.add(it) }
        )
        assertEquals(ChatGPTPlanError.SignInRequired(), runCatching { session.accessToken() }.exceptionOrNull())
        assertNull(store.saved)
        assertNull(session.current())
        assertEquals(listOf<ChatGPTPlanCredential?>(null), changes.toList())
        // Signed out now: nothing more is sent.
        assertEquals(ChatGPTPlanError.SignInRequired(), runCatching { session.accessToken() }.exceptionOrNull())
    }

    @Test fun aRefreshThatFailsForAnotherReasonKeepsTheAccount() = runBlocking<Unit> {
        val saved = credential(expiresAt = clock - 1)
        val store = MemoryPlanStore(saved)
        val session = ChatGPTPlanSession(store, FakePlanClient { ChatGPTPlanHttpResponse(503, "") }, now = { clock })
        assertEquals(ChatGPTPlanError.Network(), runCatching { session.accessToken() }.exceptionOrNull())
        assertEquals(saved, store.saved)
        assertEquals(saved, session.current())
    }

    @Test fun signOutForgetsFirstThenRevokesAtOpenAI() = runBlocking<Unit> {
        val store = MemoryPlanStore(credential(expiresAt = clock + 3_600_000))
        val client = FakePlanClient { request ->
            if (request.url == ChatGPTPlan.DISCOVERY_URL) {
                ok("""{"revocation_endpoint":"https://auth.openai.com/oauth/revoke"}""")
            } else {
                ok("{}")
            }
        }
        val changes = Collections.synchronizedList(mutableListOf<ChatGPTPlanCredential?>())
        val session = ChatGPTPlanSession(store, client, now = { clock }, onChange = { changes.add(it) })
        session.signOut()
        assertNull(store.saved)
        assertNull(session.current())
        assertEquals(listOf<ChatGPTPlanCredential?>(null), changes.toList())
        assertEquals(listOf(ChatGPTPlan.DISCOVERY_URL, "https://auth.openai.com/oauth/revoke"), client.requests.map { it.url })
        assertEquals("token=refresh-1&token_type_hint=refresh_token&client_id=app_saved", client.requests[1].body)
        assertEquals(ChatGPTPlanError.SignInRequired(), runCatching { session.accessToken() }.exceptionOrNull())
    }

    @Test fun theRefreshTokenGoesNowhereButOpenAI() = runBlocking<Unit> {
        for (discovery in listOf(
            ok("""{"revocation_endpoint":"https://example.test/revoke"}"""),
            ok("""{"revocation_endpoint":"http://auth.openai.com/oauth/revoke"}"""),
            ChatGPTPlanHttpResponse(500, ""),
            ok("not json")
        )) {
            val store = MemoryPlanStore(credential(expiresAt = clock + 3_600_000))
            val client = FakePlanClient { discovery }
            val session = ChatGPTPlanSession(store, client, now = { clock })
            session.signOut()
            assertNull(store.saved)
            assertEquals(listOf(ChatGPTPlan.DISCOVERY_URL), client.requests.map { it.url })
        }
        // An unreachable OpenAI changes nothing on this device either.
        val store = MemoryPlanStore(credential(expiresAt = clock + 3_600_000))
        ChatGPTPlanSession(store, FakePlanClient { throw java.io.IOException("offline") }, now = { clock }).signOut()
        assertNull(store.saved)
        // Nothing to revoke without an account.
        val empty = FakePlanClient { ok("{}") }
        ChatGPTPlanSession(MemoryPlanStore(), empty, now = { clock }).signOut()
        assertTrue(empty.requests.isEmpty())
    }

    @Test fun modelsTryOnceMoreWithAFreshToken() = runBlocking<Unit> {
        val client = FakePlanClient { request ->
            when (request.url) {
                ChatGPTPlan.MODELS_URL -> if (request.headers["Authorization"] == "Bearer access-1") {
                    ChatGPTPlanHttpResponse(401, "")
                } else {
                    ok("""{"models":[{"slug":"gpt-a","display_name":"GPT A","visibility":"list"}]}""")
                }
                ChatGPTPlan.TOKEN_URL -> ok("""{"access_token":"access-2","refresh_token":"refresh-2"}""")
                else -> ChatGPTPlanHttpResponse(404, "")
            }
        }
        val session = ChatGPTPlanSession(MemoryPlanStore(credential(expiresAt = clock + 3_600_000)), client, now = { clock })
        assertEquals(listOf(ChatGPTPlanModel("gpt-a", "GPT A")), session.models())
        assertEquals(listOf(ChatGPTPlan.MODELS_URL, ChatGPTPlan.TOKEN_URL, ChatGPTPlan.MODELS_URL), client.requests.map { it.url })
        assertEquals("Bearer access-2", client.requests[2].headers["Authorization"])
    }

    @Test fun modelsReportWhyThereAreNone() = runBlocking<Unit> {
        val cases = listOf(
            ChatGPTPlanHttpResponse(403, "") to ChatGPTPlanError.SignInRequired(),
            ChatGPTPlanHttpResponse(500, "") to ChatGPTPlanError.Network(),
            ok("""{"models":[]}""") to ChatGPTPlanError.NoModels(),
            ok("""{"models":[{"slug":"hidden","visibility":"hide"}]}""") to ChatGPTPlanError.NoModels()
        )
        for ((response, expected) in cases) {
            val session = ChatGPTPlanSession(
                MemoryPlanStore(credential(expiresAt = clock + 3_600_000)),
                FakePlanClient { response },
                now = { clock }
            )
            assertEquals(expected, runCatching { session.models() }.exceptionOrNull())
        }
        val signedOut = FakePlanClient { ok("{}") }
        assertEquals(
            ChatGPTPlanError.SignInRequired(),
            runCatching { ChatGPTPlanSession(MemoryPlanStore(), signedOut, now = { clock }).models() }.exceptionOrNull()
        )
        assertTrue(signedOut.requests.isEmpty())
    }

    @Test fun theCredentialIsKeptBesideTheAPIKeys() {
        val credentials = MemoryCredentials()
        val store = KeystoreChatGPTPlanCredentialStore(credentials)
        val saved = credential(expiresAt = clock)
        store.save(saved)
        assertEquals(setOf(ChatGPTPlan.CREDENTIAL_ACCOUNT), credentials.values.keys)
        assertEquals(saved, store.load())
        store.save(null)
        assertTrue(credentials.values.isEmpty())
        assertNull(store.load())

        credentials.failing = true
        assertNull(store.load())
        assertEquals(ChatGPTPlanError.KeychainUnavailable(), runCatching { store.save(saved) }.exceptionOrNull())
    }

    private fun authorization(clientID: String?) = ChatGPTPlanAuthorization(
        clientID = clientID,
        hostID = ChatGPTPlanProtocolTest.HOST_ID,
        port = 4321,
        state = "state-1",
        nonce = "nonce-1",
        pkce = ChatGPTPlanPKCE(ChatGPTPlanProtocolTest.VERIFIER)
    )

    private fun credential(expiresAt: Long) = ChatGPTPlanCredential(
        clientID = "app_saved",
        accessToken = "access-1",
        refreshToken = "refresh-1",
        expiresAtMillis = expiresAt,
        email = "reader@example.com",
        planType = "plus",
        signedInAtMillis = clock - 86_400_000
    )

    private fun ok(body: String) = ChatGPTPlanHttpResponse(200, body)
}

/** Answers every request from [handler] and remembers what was sent. */
internal class FakePlanClient(
    private val handler: (ChatGPTPlanHttpRequest) -> ChatGPTPlanHttpResponse
) : ChatGPTPlanHttpClient {
    val requests: MutableList<ChatGPTPlanHttpRequest> = Collections.synchronizedList(mutableListOf())

    override suspend fun send(request: ChatGPTPlanHttpRequest): ChatGPTPlanHttpResponse {
        requests.add(request)
        return handler(request)
    }
}

internal class MemoryPlanStore(@Volatile var saved: ChatGPTPlanCredential? = null) : ChatGPTPlanCredentialStoring {
    override fun load(): ChatGPTPlanCredential? = saved

    override fun save(credential: ChatGPTPlanCredential?) {
        saved = credential
    }
}

private class MemoryCredentials : TranslationCredentialStore {
    val values = HashMap<String, String>()
    var failing = false

    override fun read(account: String): String? {
        check(!failing) { "locked" }
        return values[account]
    }

    override fun save(account: String, secret: String?) {
        check(!failing) { "locked" }
        if (secret == null) values.remove(account) else values[account] = secret
    }
}
