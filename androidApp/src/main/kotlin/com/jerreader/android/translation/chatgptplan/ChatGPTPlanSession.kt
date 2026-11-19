package com.jerreader.android.translation.chatgptplan

import com.jerreader.android.translation.TranslationCredentialStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale

/** Sends the plan's requests with HttpURLConnection: no redirects, no cache. */
class UrlConnectionChatGPTPlanHttpClient : ChatGPTPlanHttpClient {
    override suspend fun send(request: ChatGPTPlanHttpRequest): ChatGPTPlanHttpResponse = withContext(Dispatchers.IO) {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = request.method
            connection.connectTimeout = minOf(request.timeoutMillis, CONNECT_TIMEOUT_MILLIS)
            connection.readTimeout = request.timeoutMillis
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            for ((name, value) in request.headers) connection.setRequestProperty(name, value)
            val body = request.body
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { output -> output.write(bytes) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { input -> input.readBytes().decodeToString() }.orEmpty()
            ChatGPTPlanHttpResponse(status, text)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 20_000
    }
}

/** Where the signed-in credential is kept. */
interface ChatGPTPlanCredentialStoring {
    fun load(): ChatGPTPlanCredential?

    /** `null` removes the credential. Throws [ChatGPTPlanError.KeychainUnavailable] when it cannot be kept. */
    fun save(credential: ChatGPTPlanCredential?)
}

/**
 * The credential as JSON in the keystore-backed store, beside the API keys
 * and under its own account. That store is not backed up.
 */
class KeystoreChatGPTPlanCredentialStore(
    private val credentials: TranslationCredentialStore
) : ChatGPTPlanCredentialStoring {
    override fun load(): ChatGPTPlanCredential? {
        val text = try {
            credentials.read(ChatGPTPlan.CREDENTIAL_ACCOUNT)
        } catch (_: Exception) {
            null
        }
        return ChatGPTPlanCredential.fromJson(text)
    }

    override fun save(credential: ChatGPTPlanCredential?) {
        try {
            credentials.save(ChatGPTPlan.CREDENTIAL_ACCOUNT, credential?.toJson())
        } catch (_: Exception) {
            throw ChatGPTPlanError.KeychainUnavailable()
        }
    }
}

/**
 * Owns the signed-in credential: hands out a current access token,
 * refreshes it one request at a time and keeps the newest refresh token.
 * Port of the iOS actor; a lock guards the state instead.
 *
 * [onChange] hears every sign-in, refresh and sign-out, never under the lock.
 */
class ChatGPTPlanSession(
    private val store: ChatGPTPlanCredentialStoring,
    internal val client: ChatGPTPlanHttpClient = UrlConnectionChatGPTPlanHttpClient(),
    private val now: () -> Long = { System.currentTimeMillis() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val onChange: (ChatGPTPlanCredential?) -> Unit = {}
) {
    private val lock = Any()
    private var loaded = false
    private var credential: ChatGPTPlanCredential? = null
    private var refreshing: Deferred<ChatGPTPlanCredential>? = null

    /** The saved credential, read from the store once. */
    fun current(): ChatGPTPlanCredential? = synchronized(lock) {
        if (!loaded) {
            credential = try {
                store.load()
            } catch (_: Exception) {
                null
            }
            loaded = true
        }
        credential
    }

    /**
     * A token that is valid now. Pass the token a request was refused with to
     * get a fresh one; a token another request already replaced is returned
     * without a second refresh.
     */
    suspend fun accessToken(rejected: String? = null): String {
        val saved = current() ?: throw ChatGPTPlanError.SignInRequired()
        if (saved.accessToken != rejected && saved.expiresAtMillis - now() > ChatGPTPlan.REFRESH_MARGIN_MILLIS) {
            return saved.accessToken
        }
        return refreshed(saved).accessToken
    }

    /** Exchanges the code of a finished sign-in and saves the account. */
    suspend fun completeSignIn(
        code: String,
        authorization: ChatGPTPlanAuthorization,
        issuedClientID: String?
    ): ChatGPTPlanCredential {
        val clientID = issuedClientID ?: authorization.clientID
        if (clientID == ChatGPTPlan.DYNAMIC_CLIENT_ID) throw ChatGPTPlanError.RegistrationIncomplete()
        val response = send(
            ChatGPTPlanRequests.authorizationCode(
                code = code,
                verifier = authorization.pkce.verifier,
                redirectURI = authorization.redirectURI,
                clientID = clientID
            )
        )
        if (!response.isSuccess) {
            if (ChatGPTPlanRequests.isDeadGrant(response.status, response.body)) {
                throw ChatGPTPlanError.AuthorizationFailed("invalid_grant")
            }
            throw ChatGPTPlanError.Network()
        }
        val token = ChatGPTPlanTokenResponse.parse(response.body) ?: throw ChatGPTPlanError.UnexpectedResponse()
        if (token.accessToken.isEmpty()) throw ChatGPTPlanError.UnexpectedResponse()
        val refresh = token.refreshToken?.takeIf { it.isNotEmpty() } ?: throw ChatGPTPlanError.UnexpectedResponse()
        val account = ChatGPTPlanIDToken.account(token.idToken)
        val time = now()
        val made = ChatGPTPlanCredential(
            clientID = clientID,
            accessToken = token.accessToken,
            refreshToken = refresh,
            expiresAtMillis = time + lifetimeMillis(token.expiresIn),
            email = account.email,
            planType = account.planType,
            signedInAtMillis = time
        )
        val stale = synchronized(lock) {
            store.save(made)
            val previous = refreshing
            refreshing = null
            credential = made
            loaded = true
            previous
        }
        stale?.cancel()
        onChange(made)
        return made
    }

    /**
     * Forgets the account here first, then revokes its refresh token as a
     * courtesy; a failed revocation changes nothing on this device.
     */
    suspend fun signOut() {
        val saved = forget() ?: return
        revoke(saved)
    }

    /** [signOut] for callers that cannot wait: the revocation goes on in the background. */
    fun signOutInBackground() {
        val saved = forget() ?: return
        scope.launch { revoke(saved) }
    }

    /** The models the signed-in account may use. */
    suspend fun models(): List<ChatGPTPlanModel> {
        var token = accessToken()
        var response = send(ChatGPTPlanRequests.models(token))
        if (response.status == 401) {
            token = accessToken(rejected = token)
            response = send(ChatGPTPlanRequests.models(token))
        }
        if (!response.isSuccess) {
            if (response.status == 401 || response.status == 403) throw ChatGPTPlanError.SignInRequired()
            throw ChatGPTPlanError.Network()
        }
        val models = ChatGPTPlanModels.parse(response.body)
        if (models.isEmpty()) throw ChatGPTPlanError.NoModels()
        return models
    }

    private fun forget(): ChatGPTPlanCredential? {
        val saved = current()
        val stale = synchronized(lock) {
            try {
                store.save(null)
            } catch (_: Exception) {
                // Forgotten in memory all the same.
            }
            credential = null
            loaded = true
            val previous = refreshing
            refreshing = null
            previous
        }
        stale?.cancel()
        onChange(null)
        return saved
    }

    /** Joins the refresh in flight, or starts one that outlives the caller. */
    private suspend fun refreshed(saved: ChatGPTPlanCredential): ChatGPTPlanCredential {
        val task = synchronized(lock) {
            val running = refreshing?.takeIf { it.isActive }
            if (running != null) {
                running
            } else {
                val started = scope.async { refresh(saved) }
                refreshing = started
                started.invokeOnCompletion {
                    synchronized(lock) {
                        if (refreshing === started) refreshing = null
                    }
                }
                started
            }
        }
        return try {
            task.await()
        } catch (_: CancellationException) {
            // Either this caller was cancelled, or a sign-in or sign-out
            // replaced the refresh; then the account as it is now decides.
            currentCoroutineContext().ensureActive()
            synchronized(lock) { credential } ?: throw ChatGPTPlanError.SignInRequired()
        }
    }

    private suspend fun refresh(saved: ChatGPTPlanCredential): ChatGPTPlanCredential {
        val response = send(ChatGPTPlanRequests.refresh(saved.refreshToken, saved.clientID))
        if (!response.isSuccess) {
            if (ChatGPTPlanRequests.isDeadGrant(response.status, response.body)) {
                // A sign-in that happened meanwhile keeps its own credential.
                val cleared = synchronized(lock) {
                    if (credential?.refreshToken == saved.refreshToken) {
                        try {
                            store.save(null)
                        } catch (_: Exception) {
                            // Forgotten in memory all the same.
                        }
                        credential = null
                        true
                    } else {
                        false
                    }
                }
                if (cleared) onChange(null)
                throw ChatGPTPlanError.SignInRequired()
            }
            throw ChatGPTPlanError.Network()
        }
        val token = ChatGPTPlanTokenResponse.parse(response.body) ?: throw ChatGPTPlanError.UnexpectedResponse()
        if (token.accessToken.isEmpty()) throw ChatGPTPlanError.UnexpectedResponse()
        currentCoroutineContext().ensureActive()
        val idToken = token.idToken?.takeIf { it.isNotEmpty() }
        val account = if (idToken != null) ChatGPTPlanIDToken.account(idToken) else ChatGPTPlanAccount()
        val expiresAt = now() + lifetimeMillis(token.expiresIn)
        val outcome: Pair<ChatGPTPlanCredential, Boolean>? = synchronized(lock) {
            val latest = credential
            when {
                // A sign-out while the refresh was in flight wins.
                latest == null -> null
                // So does a new sign-in.
                latest.refreshToken != saved.refreshToken -> latest to false
                else -> {
                    val updated = saved.copy(
                        accessToken = token.accessToken,
                        refreshToken = token.refreshToken?.takeIf { it.isNotEmpty() } ?: saved.refreshToken,
                        email = account.email ?: saved.email,
                        planType = account.planType ?: saved.planType,
                        expiresAtMillis = expiresAt
                    )
                    // The old refresh token no longer works; keep the new one in
                    // memory even if the store refuses it, so this run can go on.
                    try {
                        store.save(updated)
                    } catch (_: Exception) {
                        // Kept in memory.
                    }
                    credential = updated
                    updated to true
                }
            }
        }
        val result = outcome ?: throw ChatGPTPlanError.SignInRequired()
        if (result.second) onChange(result.first)
        return result.first
    }

    private suspend fun revoke(saved: ChatGPTPlanCredential) {
        try {
            val endpoint = revocationEndpoint() ?: return
            client.send(ChatGPTPlanRequests.revoke(endpoint, saved.refreshToken, saved.clientID))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // A courtesy only.
        }
    }

    private suspend fun revocationEndpoint(): String? {
        val response = client.send(ChatGPTPlanRequests.discovery())
        if (!response.isSuccess) return null
        val text = planJsonObject(response.body)?.opt("revocation_endpoint") as? String ?: return null
        return text.takeIf { isRevocationEndpoint(it) }
    }

    private suspend fun send(request: ChatGPTPlanHttpRequest): ChatGPTPlanHttpResponse = try {
        client.send(request)
    } catch (error: CancellationException) {
        throw error
    } catch (error: ChatGPTPlanError) {
        throw error
    } catch (_: Exception) {
        throw ChatGPTPlanError.Network()
    }

    private fun lifetimeMillis(expiresIn: Double?): Long =
        ((expiresIn ?: ChatGPTPlan.DEFAULT_LIFETIME_SECONDS) * 1_000.0).toLong()

    companion object {
        /** Only an HTTPS address on openai.com or one of its subdomains gets the refresh token. */
        fun isRevocationEndpoint(text: String): Boolean {
            val uri = try {
                URI(text)
            } catch (_: Exception) {
                return false
            }
            val host = uri.host?.lowercase(Locale.ROOT) ?: return false
            return uri.scheme?.lowercase(Locale.ROOT) == "https" &&
                (host == "openai.com" || host.endsWith(".openai.com"))
        }
    }
}
