package com.jerreader.android.translation.chatgptplan

import com.jerreader.android.translation.AIAPIStyle
import com.jerreader.android.translation.AIModelEntry
import com.jerreader.android.translation.AIProviderEntry
import com.jerreader.android.translation.AIProviderKind
import com.jerreader.shared.translation.TranslationFailure
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

/**
 * Sign in with ChatGPT for open-source apps: the signed-in account's ChatGPT
 * plan pays for translations, so no API key is needed. Port of the iOS
 * `ChatGPTPlan.swift`.
 *
 * The flow follows developers.openai.com/siwc (open-source, locally hosted
 * apps): an authorization code with PKCE on auth.openai.com and a
 * `127.0.0.1` loopback redirect. The first sign-in registers this install
 * (`dynamic_agent_client` with `agent_name_hint` and `ext_agent_host_id`);
 * later sign-ins reuse the client id OpenAI issued. Inference is the
 * Responses API with `store: false` and `stream: true`, successful only on
 * `response.completed`. Usage limits stop the request; nothing switches to
 * another billing path.
 *
 * Everything in this file is plain JVM code so the unit tests can check it
 * offline. The listener is in `ChatGPTPlanLoopback.kt`, the browser part in
 * `ChatGPTPlanSignIn.kt`.
 */
object ChatGPTPlan {
    const val AUTHORIZE_URL = "https://auth.openai.com/api/accounts/authorize"
    const val TOKEN_URL = "https://auth.openai.com/api/accounts/oauth/token"
    const val DISCOVERY_URL = "https://auth.openai.com/.well-known/openid-configuration"

    /** The only API the plan's tokens are sent to. */
    const val RESOURCE = "https://api.openai.com/v1"
    const val MODELS_URL = "https://api.openai.com/v1/models"
    const val RESPONSES_URL = "https://api.openai.com/v1/responses"

    /** Where the 管理用量 links go; also where a usage-limit error sends the user. */
    const val MANAGE_USAGE_URL = "https://chatgpt.com/settings/usage"
    const val DOCUMENTATION_URL = "https://developers.openai.com/siwc/token-sharing-open-source"
    const val DYNAMIC_CLIENT_ID = "dynamic_agent_client"

    /** The name shown on OpenAI's consent page. Kept the same on every install. */
    const val AGENT_NAME = "Jerreader"
    const val SCOPE = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"

    /**
     * The grant that lets the plan pay. A sign-in whose granted scopes leave
     * it out cannot translate, so it is not kept.
     */
    const val DIRECT_USE_SCOPE = "chatgpt.tokens.use.direct"
    const val CALLBACK_PATH = "/auth/callback"

    /** Where the browser is sent once the listener has the code, so it hands back to the app. */
    const val FINISH_URL = "jerreader://chatgpt-auth/done"
    const val FINISH_SCHEME = "jerreader"
    const val FINISH_HOST = "chatgpt-auth"
    const val FINISH_PATH = "/done"

    /** The provider entry of the plan in the AI settings. */
    const val PROVIDER_ID = "chatGPTPlan"

    /** The credential store account of the signed-in credential, beside the API keys. */
    const val CREDENTIAL_ACCOUNT = "chatgpt-plan-credential"

    /**
     * The plan's usage limit is reached. Nothing retries it; the user waits
     * or checks chatgpt.com/settings/usage.
     */
    const val USAGE_LIMIT_CODE = "subscription_sharing_usage_limit_exceeded"

    /** OpenAI cannot check or serve the plan right now; a later try may work. */
    val TEMPORARY_CODES: Set<String> = setOf(
        "subscription_sharing_usage_unavailable",
        "subscription_sharing_user_unavailable"
    )

    /**
     * The token was issued without the plan's grant: only a new sign-in that
     * shows OpenAI's consent page again helps.
     */
    val CONSENT_CODES: Set<String> = setOf(
        "chatpass_v2_scope_not_authorized",
        "chatpass_v2_invalid_authorization_context"
    )

    /** Codes found in an error body even when it is not in the usual shape. */
    val KNOWN_CODES: List<String> = listOf(USAGE_LIMIT_CODE) + TEMPORARY_CODES.sorted() + CONSENT_CODES.sorted() +
        listOf(
            "subscription_sharing_user_not_eligible",
            "subscription_sharing_unsupported_capability",
            "subscription_sharing_route_not_supported",
            "subscription_sharing_invalid_user"
        )

    /** A token this close to expiry is refreshed before it is used. */
    const val REFRESH_MARGIN_MILLIS = 120_000L

    /** Used when a token response leaves out `expires_in`. */
    const val DEFAULT_LIFETIME_SECONDS = 3_600.0
}

// Errors

/** What the sign-in and the session report. Equal when of the same kind with the same text. */
sealed class ChatGPTPlanError(message: String) : Exception(message) {
    class Cancelled : ChatGPTPlanError("已取消登录。")
    class StateMismatch : ChatGPTPlanError("登录回调无效，请重新登录。")
    class InvalidCallback : ChatGPTPlanError("登录回调无效，请重新登录。")

    /** A first sign-in came back without the client id it should register. */
    class RegistrationIncomplete : ChatGPTPlanError("OpenAI 没有完成这台设备的注册，请重新登录。")
    class AuthorizationFailed(val reason: String) : ChatGPTPlanError("ChatGPT 拒绝了登录（$reason），请重新登录。")
    class SignInRequired : ChatGPTPlanError("ChatGPT 登录已失效，请重新登录。")

    /** Signed in, but the consent page did not grant the plan's use. */
    class PlanUseNotGranted : ChatGPTPlanError(
        "登录成功了，但这次没有允许 Jerreader 使用你的 ChatGPT 套餐额度。再登录一次，在 OpenAI 的授权页上允许使用套餐；不想用套餐的话，可以改用 API Key。"
    )
    class UsageLimitReached : ChatGPTPlanError(
        "ChatGPT 套餐的额度已用完。到 chatgpt.com/settings/usage 查看用量；Plus 的 5 小时额度和 ChatGPT、Codex 等共用。"
    )

    /** OpenAI cannot serve the plan right now. */
    class TemporarilyUnavailable : ChatGPTPlanError("OpenAI 暂时无法处理 ChatGPT 套餐的请求，请稍后重试。")

    /** OpenAI refused the request; the text gives its reason and the request id. */
    class RequestRejected(detail: String) : ChatGPTPlanError(detail)
    class NoModels : ChatGPTPlanError("这个 ChatGPT 账号没有可用的模型。")
    class ListenerUnavailable : ChatGPTPlanError("无法在本机接收登录回调，请重试。")

    /** No browser could show the sign-in page. */
    class BrowserUnavailable : ChatGPTPlanError("无法打开 ChatGPT 登录页，请重试。")
    class KeychainUnavailable : ChatGPTPlanError("无法把 ChatGPT 登录信息安全保存到本机安全存储。")
    class Network : ChatGPTPlanError("无法连接 OpenAI，请检查网络后重试。")
    class UnexpectedResponse : ChatGPTPlanError("OpenAI 返回了无法识别的内容，请稍后重试。")

    override fun equals(other: Any?): Boolean =
        other is ChatGPTPlanError && other.javaClass == javaClass && other.message == message

    override fun hashCode(): Int = javaClass.hashCode() * 31 + (message?.hashCode() ?: 0)
}

// Encoding, PKCE and the authorization request

object ChatGPTPlanEncoding {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private const val HEX = "0123456789ABCDEF"
    private val random = SecureRandom()

    /** Base64url without padding. */
    fun base64URL(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var index = 0
        while (index < bytes.size) {
            val remaining = bytes.size - index
            val b0 = bytes[index].toInt() and 0xFF
            val b1 = if (remaining > 1) bytes[index + 1].toInt() and 0xFF else 0
            val b2 = if (remaining > 2) bytes[index + 2].toInt() and 0xFF else 0
            val chunk = (b0 shl 16) or (b1 shl 8) or b2
            out.append(ALPHABET[(chunk shr 18) and 0x3F])
            out.append(ALPHABET[(chunk shr 12) and 0x3F])
            if (remaining > 1) out.append(ALPHABET[(chunk shr 6) and 0x3F])
            if (remaining > 2) out.append(ALPHABET[chunk and 0x3F])
            index += 3
        }
        return out.toString()
    }

    /** Reads base64url or base64, with or without padding; null when it is neither. */
    fun base64URLDecoded(text: String): ByteArray? {
        val value = text.trimEnd('=')
        if (value.length % 4 == 1) return null
        val out = ByteArrayOutputStream(value.length * 3 / 4 + 1)
        var buffer = 0
        var bits = 0
        for (character in value) {
            val digit = when (character) {
                in 'A'..'Z' -> character - 'A'
                in 'a'..'z' -> character - 'a' + 26
                in '0'..'9' -> character - '0' + 52
                '-', '+' -> 62
                '_', '/' -> 63
                else -> return null
            }
            buffer = ((buffer shl 6) or digit) and 0xFFFF
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    fun randomToken(count: Int): String {
        val bytes = ByteArray(count)
        random.nextBytes(bytes)
        return base64URL(bytes)
    }

    /** Strict form and query encoding: only unreserved ASCII characters stay as they are. */
    fun formEncoded(pairs: List<Pair<String, String>>): String =
        pairs.joinToString("&") { (name, value) -> percentEncoded(name) + "=" + percentEncoded(value) }

    fun percentEncoded(text: String): String {
        val out = StringBuilder(text.length)
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            val value = byte.toInt() and 0xFF
            val character = value.toChar()
            if (value < 0x80 && (character.isLetterOrDigit() || character in "-._~")) {
                out.append(character)
            } else {
                out.append('%').append(HEX[value shr 4]).append(HEX[value and 0x0F])
            }
        }
        return out.toString()
    }

    /**
     * Decodes `%XX` escapes as UTF-8. `+` stays a plus, as in a URL query.
     * Null for a broken escape, a raw non-ASCII character or bytes that are not UTF-8.
     */
    fun percentDecoded(text: String): String? {
        val bytes = ByteArrayOutputStream(text.length)
        var index = 0
        while (index < text.length) {
            val character = text[index]
            if (character == '%') {
                if (index + 2 >= text.length) return null
                val high = text[index + 1].digitToIntOrNull(16) ?: return null
                val low = text[index + 2].digitToIntOrNull(16) ?: return null
                bytes.write(high * 16 + low)
                index += 3
            } else {
                if (character.code >= 0x80) return null
                bytes.write(character.code)
                index += 1
            }
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray()))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }
}

class ChatGPTPlanPKCE(val verifier: String) {
    val challenge: String = ChatGPTPlanEncoding.base64URL(
        MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.UTF_8))
    )

    override fun equals(other: Any?): Boolean = other is ChatGPTPlanPKCE && other.verifier == verifier
    override fun hashCode(): Int = verifier.hashCode()

    /** Keeps the verifier out of logs. */
    override fun toString(): String = "ChatGPTPlanPKCE(S256)"

    companion object {
        fun random(): ChatGPTPlanPKCE = ChatGPTPlanPKCE(ChatGPTPlanEncoding.randomToken(32))
    }
}

/**
 * One sign-in attempt: what goes into the authorize URL and what the
 * callback and the token exchange are checked against.
 */
class ChatGPTPlanAuthorization(
    clientID: String?,
    val hostID: String,
    val port: Int,
    val loginHint: String? = null,
    /**
     * Shows OpenAI's consent page again (`prompt=consent`): only after the
     * plan's use was declined, never on every sign-in.
     */
    val forceConsent: Boolean = false,
    val state: String = ChatGPTPlanEncoding.randomToken(24),
    val nonce: String = ChatGPTPlanEncoding.randomToken(24),
    val pkce: ChatGPTPlanPKCE = ChatGPTPlanPKCE.random()
) {
    val clientID: String = clientID?.trim()?.takeIf { it.isNotEmpty() } ?: ChatGPTPlan.DYNAMIC_CLIENT_ID
    val redirectURI: String = redirectURIFor(port)

    /** The first sign-in on this install registers it. */
    val isRegistration: Boolean
        get() = clientID == ChatGPTPlan.DYNAMIC_CLIENT_ID

    /** The query of the authorize URL, in the order iOS sends it. */
    val queryPairs: List<Pair<String, String>>
        get() {
            val pairs = mutableListOf(
                "response_type" to "code",
                "client_id" to clientID,
                "redirect_uri" to redirectURI,
                "scope" to ChatGPTPlan.SCOPE,
                "resource" to ChatGPTPlan.RESOURCE,
                "state" to state,
                "nonce" to nonce,
                "code_challenge" to pkce.challenge,
                "code_challenge_method" to "S256",
                "ext_agent_host_id" to hostID
            )
            if (isRegistration) pairs.add("agent_name_hint" to ChatGPTPlan.AGENT_NAME)
            val hint = loginHint?.trim().orEmpty()
            if (hint.isNotEmpty()) pairs.add("login_hint" to hint)
            if (forceConsent) pairs.add("prompt" to "consent")
            return pairs
        }

    val url: String
        get() = ChatGPTPlan.AUTHORIZE_URL + "?" + ChatGPTPlanEncoding.formEncoded(queryPairs)

    /** Keeps the state, nonce and verifier out of logs. */
    override fun toString(): String = "ChatGPTPlanAuthorization(port=$port, registration=$isRegistration)"

    companion object {
        /** Only the port may change between sign-ins; the host is always 127.0.0.1. */
        fun redirectURIFor(port: Int): String = "http://127.0.0.1:$port${ChatGPTPlan.CALLBACK_PATH}"
    }
}

/** What the browser brought back to the loopback listener. */
class ChatGPTPlanCallback(val code: String, val issuedClientID: String?) {
    override fun equals(other: Any?): Boolean =
        other is ChatGPTPlanCallback && other.code == code && other.issuedClientID == issuedClientID

    override fun hashCode(): Int = code.hashCode() * 31 + (issuedClientID?.hashCode() ?: 0)

    /** Keeps the code out of logs. */
    override fun toString(): String = "ChatGPTPlanCallback(issuedClientID=${issuedClientID != null})"

    companion object {
        /** [target] is the request target the listener received, such as `/auth/callback?code=…&state=…`. */
        fun parse(target: String, authorization: ChatGPTPlanAuthorization): ChatGPTPlanCallback {
            if (!target.startsWith("/")) throw ChatGPTPlanError.InvalidCallback()
            val withoutFragment = target.substringBefore('#')
            val path = ChatGPTPlanEncoding.percentDecoded(withoutFragment.substringBefore('?'))
            if (path != ChatGPTPlan.CALLBACK_PATH) throw ChatGPTPlanError.InvalidCallback()
            val query = if (withoutFragment.contains('?')) withoutFragment.substringAfter('?') else ""
            val items = LinkedHashMap<String, String>()
            if (query.isNotEmpty()) {
                for (part in query.split('&')) {
                    val name = ChatGPTPlanEncoding.percentDecoded(part.substringBefore('='))
                        ?: throw ChatGPTPlanError.InvalidCallback()
                    val value = if (part.contains('=')) {
                        ChatGPTPlanEncoding.percentDecoded(part.substringAfter('='))
                            ?: throw ChatGPTPlanError.InvalidCallback()
                    } else {
                        ""
                    }
                    if (!items.containsKey(name)) items[name] = value
                }
            }
            val error = items["error"].orEmpty()
            if (error.isNotEmpty()) {
                throw if (error == "access_denied") {
                    ChatGPTPlanError.Cancelled()
                } else {
                    ChatGPTPlanError.AuthorizationFailed(error)
                }
            }
            if (items["state"] != authorization.state) throw ChatGPTPlanError.StateMismatch()
            val code = items["code"].orEmpty()
            if (code.isEmpty()) throw ChatGPTPlanError.InvalidCallback()
            val issued = items["client_id"]?.takeIf { it.isNotEmpty() }
            if (authorization.isRegistration && issued == null) throw ChatGPTPlanError.RegistrationIncomplete()
            return ChatGPTPlanCallback(code, issued)
        }
    }
}

// HTTP

class ChatGPTPlanHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String?,
    val timeoutMillis: Int
) {
    /** Keeps tokens and codes out of logs. */
    override fun toString(): String = "ChatGPTPlanHttpRequest($method $url)"
}

/** [requestID] is OpenAI's `x-request-id`, kept for the user and for a support request. */
class ChatGPTPlanHttpResponse(val status: Int, val body: String, val requestID: String? = null) {
    val isSuccess: Boolean
        get() = status in 200..299

    /** Keeps tokens out of logs. */
    override fun toString(): String = "ChatGPTPlanHttpResponse($status)"
}

/** Sends one request. Tests pass a fake; nothing here touches the network by itself. */
fun interface ChatGPTPlanHttpClient {
    suspend fun send(request: ChatGPTPlanHttpRequest): ChatGPTPlanHttpResponse

    /**
     * Sends a streamed request (2.1.0) and hands a 2xx body to [onLine] a line
     * at a time, without line breaks, as it arrives; the response then has an
     * empty body. Any other answer comes back whole. A client that cannot
     * stream, such as a test's, answers at once and splits the whole body.
     */
    suspend fun sendStreaming(
        request: ChatGPTPlanHttpRequest,
        onLine: (String) -> Unit
    ): ChatGPTPlanHttpResponse {
        val response = send(request)
        if (!response.isSuccess) return response
        response.body.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach(onLine)
        return ChatGPTPlanHttpResponse(response.status, "", response.requestID)
    }
}

internal fun planJsonObject(text: String): JSONObject? = try {
    JSONObject(text)
} catch (_: Exception) {
    null
}

/** Requests to auth.openai.com and api.openai.com. None carries a client secret: this is a public client. */
object ChatGPTPlanRequests {
    private const val FORM_TIMEOUT_MILLIS = 30_000
    private const val MODELS_TIMEOUT_MILLIS = 30_000
    private const val DISCOVERY_TIMEOUT_MILLIS = 15_000
    private val DEAD_GRANT_CODES = setOf("invalid_grant", "invalid_client", "unauthorized_client")

    fun authorizationCode(code: String, verifier: String, redirectURI: String, clientID: String): ChatGPTPlanHttpRequest =
        form(
            ChatGPTPlan.TOKEN_URL,
            listOf(
                "grant_type" to "authorization_code",
                "client_id" to clientID,
                "code" to code,
                "code_verifier" to verifier,
                "redirect_uri" to redirectURI,
                "resource" to ChatGPTPlan.RESOURCE
            )
        )

    /** No `scope`: a refresh keeps the scopes that were granted. */
    fun refresh(refreshToken: String, clientID: String): ChatGPTPlanHttpRequest =
        form(
            ChatGPTPlan.TOKEN_URL,
            listOf(
                "grant_type" to "refresh_token",
                "client_id" to clientID,
                "refresh_token" to refreshToken,
                "resource" to ChatGPTPlan.RESOURCE
            )
        )

    fun revoke(endpoint: String, refreshToken: String, clientID: String): ChatGPTPlanHttpRequest =
        form(
            endpoint,
            listOf(
                "token" to refreshToken,
                "token_type_hint" to "refresh_token",
                "client_id" to clientID
            )
        )

    fun models(accessToken: String): ChatGPTPlanHttpRequest = ChatGPTPlanHttpRequest(
        method = "GET",
        url = ChatGPTPlan.MODELS_URL,
        headers = mapOf("Authorization" to "Bearer $accessToken", "Accept" to "application/json"),
        body = null,
        timeoutMillis = MODELS_TIMEOUT_MILLIS
    )

    fun discovery(): ChatGPTPlanHttpRequest = ChatGPTPlanHttpRequest(
        method = "GET",
        url = ChatGPTPlan.DISCOVERY_URL,
        headers = mapOf("Accept" to "application/json"),
        body = null,
        timeoutMillis = DISCOVERY_TIMEOUT_MILLIS
    )

    /**
     * The Responses request of the plan: `store: false` and `stream: true`,
     * instructions apart from the input, and none of the fields the plan does
     * not take. [extra] may only add reasoning. The plan takes `input` only
     * as a list of messages, never as a bare string.
     */
    fun responses(
        accessToken: String,
        model: String,
        instructions: String,
        input: String,
        extra: JSONObject? = null,
        timeoutMillis: Int = 30_000
    ): ChatGPTPlanHttpRequest = responses(accessToken, model, instructions, listOf("user" to input), extra, timeoutMillis)

    /**
     * The same request with a conversation as its input (2.1.0 追问): each
     * (role, text) pair is one message, oldest first, the roles `user` and
     * `assistant`.
     */
    fun responses(
        accessToken: String,
        model: String,
        instructions: String,
        input: List<Pair<String, String>>,
        extra: JSONObject? = null,
        timeoutMillis: Int = 30_000
    ): ChatGPTPlanHttpRequest {
        val body = JSONObject()
        body.put("model", model)
        body.put("instructions", instructions)
        val messages = JSONArray()
        input.forEach { (role, text) -> messages.put(JSONObject().put("role", role).put("content", text)) }
        body.put("input", messages)
        body.put("store", false)
        body.put("stream", true)
        val reasoning = extra?.opt("reasoning")
        if (reasoning != null && reasoning != JSONObject.NULL) body.put("reasoning", reasoning)
        return ChatGPTPlanHttpRequest(
            method = "POST",
            url = ChatGPTPlan.RESPONSES_URL,
            headers = mapOf(
                "Authorization" to "Bearer $accessToken",
                "Content-Type" to "application/json",
                "Accept" to "text/event-stream"
            ),
            body = body.toString(),
            timeoutMillis = timeoutMillis
        )
    }

    /** Fields the plan's inference requests must leave out. */
    val OMITTED_FIELDS: Set<String> = setOf(
        "background", "conversation", "max_output_tokens", "max_tool_calls", "metadata", "moderation",
        "multi_agent", "prompt", "prompt_cache_retention", "safety_identifier", "temperature",
        "top_logprobs", "top_p", "truncation", "user", "previous_response_id"
    )

    /** An OAuth error that means the grant is gone and only a new sign-in helps. */
    fun isDeadGrant(status: Int, body: String): Boolean {
        if (status != 400 && status != 401) return false
        val error = planJsonObject(body)?.opt("error")
        val code = (error as? String) ?: ((error as? JSONObject)?.opt("code") as? String)
        return code != null && code in DEAD_GRANT_CODES
    }

    private fun form(url: String, pairs: List<Pair<String, String>>): ChatGPTPlanHttpRequest = ChatGPTPlanHttpRequest(
        method = "POST",
        url = url,
        headers = mapOf(
            "Content-Type" to "application/x-www-form-urlencoded",
            "Accept" to "application/json"
        ),
        body = ChatGPTPlanEncoding.formEncoded(pairs),
        timeoutMillis = FORM_TIMEOUT_MILLIS
    )
}

// Tokens and the account

class ChatGPTPlanTokenResponse(
    val accessToken: String,
    val refreshToken: String?,
    val idToken: String?,
    val expiresIn: Double?,
    /**
     * The granted scopes, when OpenAI lists them (a space-separated string, or
     * a list). Absent means the requested scopes were granted.
     */
    val scope: String? = null
) {
    /**
     * False only when OpenAI listed the granted scopes and the plan's use is
     * not among them, which is what a declined consent looks like.
     */
    val grantsDirectUse: Boolean
        get() = scope == null || scope.split(' ', ',', '\n', '\t').any { it == ChatGPTPlan.DIRECT_USE_SCOPE }

    /** Keeps the tokens out of logs. */
    override fun toString(): String = "ChatGPTPlanTokenResponse(…)"

    companion object {
        fun parse(body: String): ChatGPTPlanTokenResponse? {
            val json = planJsonObject(body) ?: return null
            val access = json.opt("access_token") as? String ?: return null
            return ChatGPTPlanTokenResponse(
                accessToken = access,
                refreshToken = json.opt("refresh_token") as? String,
                idToken = json.opt("id_token") as? String,
                expiresIn = (json.opt("expires_in") as? Number)?.toDouble(),
                scope = scope(json.opt("scope"))
            )
        }

        /** An odd scope never fails the whole response; it counts as not listed. */
        private fun scope(value: Any?): String? = when (value) {
            is String -> value
            is JSONArray -> {
                val parts = (0 until value.length()).map { value.opt(it) }
                if (parts.all { it is String }) parts.joinToString(" ") else null
            }
            else -> null
        }
    }
}

/** What the settings pages show of the signed-in account. */
data class ChatGPTPlanAccount(val email: String? = null, val planType: String? = null) {
    /** `plus` → Plus; an unknown plan is shown as it came. */
    val planTitle: String?
        get() {
            val plan = planType?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return when (plan.lowercase(Locale.ROOT)) {
                "plus" -> "Plus"
                "pro" -> "Pro"
                "team" -> "Team"
                "business" -> "Business"
                "enterprise" -> "Enterprise"
                "edu" -> "Edu"
                "go" -> "Go"
                "free" -> "Free"
                else -> plan.substring(0, 1).uppercase(Locale.ROOT) + plan.substring(1)
            }
        }
}

/**
 * The signed-in account. Kept in the keystore-backed store on this device
 * only; never in backups, logs or settings. The ID token is read for the
 * account's labels and not kept.
 */
data class ChatGPTPlanCredential(
    val clientID: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMillis: Long,
    val email: String?,
    val planType: String?,
    val signedInAtMillis: Long
) {
    val account: ChatGPTPlanAccount
        get() = ChatGPTPlanAccount(email, planType)

    fun toJson(): String {
        val json = JSONObject()
        json.put("clientID", clientID)
        json.put("accessToken", accessToken)
        json.put("refreshToken", refreshToken)
        json.put("expiresAt", expiresAtMillis)
        json.put("signedInAt", signedInAtMillis)
        if (email != null) json.put("email", email)
        if (planType != null) json.put("planType", planType)
        return json.toString()
    }

    /** Keeps the tokens out of logs. */
    override fun toString(): String = "ChatGPTPlanCredential(expiresAtMillis=$expiresAtMillis)"

    companion object {
        fun fromJson(text: String?): ChatGPTPlanCredential? {
            if (text.isNullOrBlank()) return null
            val json = planJsonObject(text) ?: return null
            val clientID = (json.opt("clientID") as? String)?.takeIf { it.isNotEmpty() } ?: return null
            val access = (json.opt("accessToken") as? String)?.takeIf { it.isNotEmpty() } ?: return null
            val refresh = (json.opt("refreshToken") as? String)?.takeIf { it.isNotEmpty() } ?: return null
            val expires = (json.opt("expiresAt") as? Number)?.toLong() ?: return null
            return ChatGPTPlanCredential(
                clientID = clientID,
                accessToken = access,
                refreshToken = refresh,
                expiresAtMillis = expires,
                email = (json.opt("email") as? String)?.takeIf { it.isNotEmpty() },
                planType = (json.opt("planType") as? String)?.takeIf { it.isNotEmpty() },
                signedInAtMillis = (json.opt("signedInAt") as? Number)?.toLong() ?: expires
            )
        }
    }
}

/**
 * The display claims of an ID token. The token is not verified here: it
 * only labels the account on the settings page and decides nothing.
 */
object ChatGPTPlanIDToken {
    fun account(token: String?): ChatGPTPlanAccount {
        if (token == null) return ChatGPTPlanAccount()
        val parts = token.split('.')
        if (parts.size < 2) return ChatGPTPlanAccount()
        val bytes = ChatGPTPlanEncoding.base64URLDecoded(parts[1]) ?: return ChatGPTPlanAccount()
        val json = planJsonObject(bytes.decodeToString()) ?: return ChatGPTPlanAccount()
        val auth = json.opt("https://api.openai.com/auth") as? JSONObject
        return ChatGPTPlanAccount(
            email = (json.opt("email") as? String)?.takeIf { it.isNotEmpty() },
            planType = (auth?.opt("chatgpt_plan_type") as? String)?.takeIf { it.isNotEmpty() }
        )
    }
}

// Models and streamed responses

/** [slug] is sent as `model`; [displayName] is shown in the app. */
data class ChatGPTPlanModel(val slug: String, val displayName: String)

object ChatGPTPlanModels {
    /** The account's models in the server's order, only those meant to be listed. */
    fun parse(body: String): List<ChatGPTPlanModel> {
        val json = planJsonObject(body) ?: throw ChatGPTPlanError.UnexpectedResponse()
        val items = (json.opt("data") as? JSONArray) ?: (json.opt("models") as? JSONArray) ?: return emptyList()
        val seen = HashSet<String>()
        val models = ArrayList<ChatGPTPlanModel>()
        for (index in 0 until items.length()) {
            val item = items.opt(index) as? JSONObject ?: continue
            if ((item.opt("visibility") as? String) != "list") continue
            val slug = ((item.opt("slug") as? String) ?: (item.opt("id") as? String))?.trim().orEmpty()
            if (slug.isEmpty() || !seen.add(slug)) continue
            val name = (item.opt("display_name") as? String)?.trim().orEmpty()
            models.add(ChatGPTPlanModel(slug, name.ifEmpty { slug }))
        }
        return models
    }
}

sealed class ChatGPTPlanStreamOutcome {
    data class Completed(val text: String) : ChatGPTPlanStreamOutcome()
    data class Failed(val code: String?, val message: String? = null) : ChatGPTPlanStreamOutcome()
    data class Incomplete(val reason: String?) : ChatGPTPlanStreamOutcome()

    /** The stream ended without `response.completed`, `failed` or `incomplete`. */
    data object Interrupted : ChatGPTPlanStreamOutcome()
}

/** Reads a whole Server-Sent Events body of a Responses stream. */
object ChatGPTPlanStream {
    fun parse(text: String): ChatGPTPlanStreamOutcome {
        val deltas = StringBuilder()
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        for (block in normalized.split("\n\n")) {
            var eventName: String? = null
            val lines = ArrayList<String>()
            for (line in block.split('\n')) {
                if (line.isEmpty()) continue
                if (line.startsWith("data:")) {
                    lines.add(line.substring(5).removePrefix(" "))
                } else if (line.startsWith("event:")) {
                    eventName = line.substring(6).trim()
                }
            }
            if (lines.isEmpty()) continue
            val payload = lines.joinToString("\n")
            if (payload == "[DONE]") continue
            val json = planJsonObject(payload) ?: continue
            when ((json.opt("type") as? String) ?: eventName ?: "") {
                "response.output_text.delta" -> deltas.append(json.opt("delta") as? String ?: "")
                "response.completed" -> {
                    val finalText = outputText(json.opt("response") as? JSONObject)
                    return ChatGPTPlanStreamOutcome.Completed(finalText.ifEmpty { deltas.toString() })
                }
                "response.failed" -> {
                    val error = (json.opt("response") as? JSONObject)?.opt("error") as? JSONObject
                    return ChatGPTPlanStreamOutcome.Failed(error?.opt("code") as? String, error?.opt("message") as? String)
                }
                "response.incomplete" -> {
                    val details = (json.opt("response") as? JSONObject)?.opt("incomplete_details") as? JSONObject
                    return ChatGPTPlanStreamOutcome.Incomplete(details?.opt("reason") as? String)
                }
                "error" -> {
                    val nested = json.opt("error") as? JSONObject
                    return ChatGPTPlanStreamOutcome.Failed(
                        (json.opt("code") as? String) ?: (nested?.opt("code") as? String),
                        (json.opt("message") as? String) ?: (nested?.opt("message") as? String)
                    )
                }
                else -> Unit
            }
        }
        return ChatGPTPlanStreamOutcome.Interrupted
    }

    private fun outputText(response: JSONObject?): String {
        val output = response?.opt("output") as? JSONArray ?: return ""
        val text = StringBuilder()
        for (index in 0 until output.length()) {
            val item = output.opt(index) as? JSONObject ?: continue
            if (((item.opt("type") as? String) ?: "message") != "message") continue
            val content = item.opt("content") as? JSONArray ?: continue
            for (part in 0 until content.length()) {
                val piece = content.opt(part) as? JSONObject ?: continue
                if ((piece.opt("type") as? String) != "output_text") continue
                text.append(piece.opt("text") as? String ?: "")
            }
        }
        return text.toString()
    }
}

// Refusals

/**
 * What an OpenAI error body says: `{"error": {"message", "code", "param"}}`,
 * a direct-admission `{"detail": …}`, or plain text. Never a web page.
 */
data class ChatGPTPlanErrorBody(
    val code: String? = null,
    val param: String? = null,
    val message: String? = null
) {
    /** A refusal that only a sign-in with OpenAI's consent page fixes. */
    val needsConsent: Boolean
        get() = code != null && code in ChatGPTPlan.CONSENT_CODES

    companion object {
        private const val MESSAGE_LIMIT = 300

        fun parse(body: String): ChatGPTPlanErrorBody {
            var code: String? = null
            var param: String? = null
            var message: String?
            val json = planJsonObject(body)
            if (json != null) {
                val error = json.opt("error") as? JSONObject
                val detail = json.opt("detail") as? JSONObject
                code = text(error?.opt("code")) ?: text(detail?.opt("code")) ?: text(json.opt("code"))
                param = text(error?.opt("param")) ?: text(detail?.opt("param")) ?: text(json.opt("param"))
                message = text(error?.opt("message")) ?: text(json.opt("error")) ?: text(detail?.opt("message"))
                    ?: text(json.opt("detail")) ?: text(json.opt("message"))
                // A bare code as the whole message, such as {"detail":"subscription_sharing_usage_unavailable"}.
                if (code == null && message != null && isCode(message)) code = message
            } else {
                val trimmed = body.trim()
                message = if (trimmed.isEmpty() || trimmed.startsWith("<")) null else trimmed
            }
            if (code == null) code = ChatGPTPlan.KNOWN_CODES.firstOrNull { body.contains(it) }
            return ChatGPTPlanErrorBody(code, param, message?.let(::cut))
        }

        private fun text(value: Any?): String? = (value as? String)?.trim()?.takeIf { it.isNotEmpty() }

        private fun isCode(text: String): Boolean =
            text.length <= 80 && text.all { it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '.' }

        /** Counts characters, as iOS does, so a pair of surrogates is never cut in half. */
        private fun cut(text: String): String {
            if (text.codePointCount(0, text.length) <= MESSAGE_LIMIT) return text
            return text.substring(0, text.offsetByCodePoints(0, MESSAGE_LIMIT)) + "…"
        }
    }
}

/**
 * How the plan's API refused a request, read the way OpenAI's token-sharing
 * guide describes its errors (as on iOS).
 */
sealed class ChatGPTPlanRefusal {
    /** Even a fresh token was not accepted: sign in again. */
    data object SignInRequired : ChatGPTPlanRefusal()

    /** The plan's usage limit is reached; nothing retries it. */
    data object UsageLimit : ChatGPTPlanRefusal()

    /** OpenAI cannot serve this right now; a later try may work. */
    data object Temporary : ChatGPTPlanRefusal()

    /**
     * OpenAI refused this request. The text gives OpenAI's reason with the
     * status, code and request id, for the user and for a support request.
     */
    data class Rejected(val message: String) : ChatGPTPlanRefusal()

    companion object {
        private const val NOT_ELIGIBLE = "这个 ChatGPT 账号或工作区现在不能用套餐额度。"
        private const val UNSUPPORTED = "OpenAI 的套餐接口不支持这次请求"
        private const val ROUTE = "OpenAI 不允许用套餐额度调用这个接口。"
        private const val INVALID_USER = "OpenAI 不认这次登录的账号，请退出登录后重新登录。"
        private const val CONSENT = "这次登录没有授权使用套餐额度。请退出登录后重新登录，并在 OpenAI 的授权页上允许使用套餐。"
        private const val MODEL = "这个账号现在不能用这个模型，请刷新模型后换一个再试。"
        private const val FORBIDDEN = "OpenAI 拒绝了这次请求，可能是所在地区、账号或工作区的限制。"
        private const val REFUSED = "OpenAI 拒绝了这次请求。"
        private val LEADS = setOf(NOT_ELIGIBLE, ROUTE, INVALID_USER, CONSENT, MODEL, FORBIDDEN, REFUSED)

        private val TRANSIENT_STREAM_CODES = setOf(
            "server_error", "internal_error", "rate_limit_exceeded", "overloaded", "timeout"
        )

        /** A refusal before any stream began. */
        fun classify(status: Int, body: String, requestID: String?): ChatGPTPlanRefusal {
            val error = ChatGPTPlanErrorBody.parse(body)
            if (error.code == ChatGPTPlan.USAGE_LIMIT_CODE) return UsageLimit
            if (error.code != null && error.code in ChatGPTPlan.TEMPORARY_CODES) return Temporary
            return when (status) {
                401 -> SignInRequired
                408, 409, 425, 429 -> Temporary
                in 500..Int.MAX_VALUE -> Temporary
                in 400..499 -> Rejected(message(status, error, requestID?.trim()?.takeIf { it.isNotEmpty() }))
                else -> Temporary
            }
        }

        /** A `response.failed` or `error` event of a stream that had begun. */
        fun streamFailure(code: String?, detail: String?): ChatGPTPlanRefusal {
            if (code == ChatGPTPlan.USAGE_LIMIT_CODE) return UsageLimit
            if (code.isNullOrEmpty() || code in ChatGPTPlan.TEMPORARY_CODES || code in TRANSIENT_STREAM_CODES) {
                return Temporary
            }
            val text = detail?.trim()?.takeIf { it.isNotEmpty() }
            return Rejected(message(null, ChatGPTPlanErrorBody(code = code, message = text), null))
        }

        /**
         * What the user reads: what it means, OpenAI's own words, then the facts
         * a support request needs.
         */
        fun message(status: Int?, error: ChatGPTPlanErrorBody, requestID: String?): String {
            val lines = mutableListOf(lead(status, error))
            val text = error.message
            if (text != null && text != error.code) lines.add("OpenAI 的说明：$text")
            val facts = listOfNotNull(
                status?.let { "HTTP $it" },
                error.code,
                error.param?.let { "参数 $it" },
                requestID?.let { "请求 $it" }
            )
            if (facts.isNotEmpty()) lines.add("（${facts.joinToString(" · ")}）")
            return lines.joinToString("\n")
        }

        /** True for a text made by [message]: a refusal that nothing answers in place of. */
        fun isRejection(detail: String): Boolean {
            val lead = detail.substringBefore('\n')
            return lead in LEADS || (lead.startsWith(UNSUPPORTED) && lead.endsWith("。"))
        }

        private fun lead(status: Int?, error: ChatGPTPlanErrorBody): String {
            val code = error.code.orEmpty()
            return when {
                code == "subscription_sharing_user_not_eligible" -> NOT_ELIGIBLE
                code == "subscription_sharing_unsupported_capability" ->
                    error.param?.let { "${UNSUPPORTED}里的 $it。" } ?: "${UNSUPPORTED}的某项设置。"
                code == "subscription_sharing_route_not_supported" -> ROUTE
                code == "subscription_sharing_invalid_user" -> INVALID_USER
                code in ChatGPTPlan.CONSENT_CODES -> CONSENT
                code == "model_not_found" -> MODEL
                status == 403 -> FORBIDDEN
                else -> REFUSED
            }
        }
    }
}

// The provider entry

object ChatGPTPlanEntries {
    /**
     * The plan's provider entry with the account's models. The reasoning chosen
     * for a model that is still offered is kept; an empty list keeps the models
     * there were.
     */
    fun merged(existing: AIProviderEntry?, models: List<ChatGPTPlanModel>): AIProviderEntry {
        val entry = existing ?: AIProviderEntry(
            id = ChatGPTPlan.PROVIDER_ID,
            kind = AIProviderKind.CHATGPT_PLAN,
            name = null,
            baseURL = AIProviderKind.CHATGPT_PLAN.defaultBaseURL,
            model = "",
            models = emptyList()
        )
        val kept = LinkedHashMap<String, AIModelEntry>()
        for (model in entry.models) {
            if (!kept.containsKey(model.id)) kept[model.id] = model
        }
        val merged = if (models.isEmpty()) {
            entry.models
        } else {
            models.map { kept[it.slug] ?: AIModelEntry(it.slug, AIAPIStyle.RESPONSES) }
        }
        val model = if (merged.any { it.id == entry.model }) entry.model else merged.firstOrNull()?.id.orEmpty()
        return entry.copy(models = merged, model = model)
    }
}

// What the user is told

enum class ChatGPTPlanMessageContext { TRANSLATION, EXPLANATION, SETTINGS }

/** The texts of a plan that stopped, in the words of the page that shows them (as on iOS). */
object ChatGPTPlanMessages {
    const val USAGE_LIMIT_SETTINGS =
        "ChatGPT 套餐的额度已用完，已暂停用它翻译。到 chatgpt.com/settings/usage 查看用量；Plus 的 5 小时额度和 ChatGPT、Codex 等共用。"
    const val SIGN_IN_SETTINGS = "ChatGPT 登录已失效。请到“设置 → AI 大模型”重新登录。"
    const val USAGE_LIMIT_TRANSLATION =
        "ChatGPT 套餐的额度已用完，已暂停用它翻译。到 chatgpt.com/settings/usage 查看用量，或在设置里换一个模型。"
    const val SIGN_IN_TRANSLATION = "ChatGPT 登录已失效，请到“设置 → AI 大模型”重新登录。"
    const val USAGE_LIMIT_EXPLANATION =
        "ChatGPT 套餐的额度已用完。到 chatgpt.com/settings/usage 查看用量，或在设置里换一个模型。"
    const val SIGN_IN_EXPLANATION = SIGN_IN_TRANSLATION

    private val STOP_TEXTS = setOf(
        USAGE_LIMIT_SETTINGS,
        SIGN_IN_SETTINGS,
        USAGE_LIMIT_TRANSLATION,
        SIGN_IN_TRANSLATION,
        USAGE_LIMIT_EXPLANATION,
        SIGN_IN_EXPLANATION
    )

    fun usageLimit(context: ChatGPTPlanMessageContext): String = when (context) {
        ChatGPTPlanMessageContext.TRANSLATION -> USAGE_LIMIT_TRANSLATION
        ChatGPTPlanMessageContext.EXPLANATION -> USAGE_LIMIT_EXPLANATION
        ChatGPTPlanMessageContext.SETTINGS -> USAGE_LIMIT_SETTINGS
    }

    fun signIn(context: ChatGPTPlanMessageContext): String = when (context) {
        ChatGPTPlanMessageContext.TRANSLATION -> SIGN_IN_TRANSLATION
        ChatGPTPlanMessageContext.EXPLANATION -> SIGN_IN_EXPLANATION
        ChatGPTPlanMessageContext.SETTINGS -> SIGN_IN_SETTINGS
    }

    fun translationFailure(failure: ChatGPTPlanServiceFailure, context: ChatGPTPlanMessageContext): TranslationFailure =
        when (failure) {
            is ChatGPTPlanServiceFailure.UsageLimitReached -> TranslationFailure.ProviderRejected(usageLimit(context))
            is ChatGPTPlanServiceFailure.SignInRequired -> TranslationFailure.ProviderRejected(signIn(context))
            is ChatGPTPlanServiceFailure.Rejected -> TranslationFailure.ProviderRejected(failure.detail)
            is ChatGPTPlanServiceFailure.Temporary, is ChatGPTPlanServiceFailure.ResultNotFound ->
                TranslationFailure.ServiceUnavailable
        }

    /**
     * A usage limit, an ended sign-in or a request OpenAI refused: nothing
     * retries it or answers in its place, as on iOS, where only a passing
     * failure moves on to the fallback service.
     */
    fun isStop(error: Throwable?): Boolean =
        error is TranslationFailure.ProviderRejected &&
            (error.detail in STOP_TEXTS || ChatGPTPlanRefusal.isRejection(error.detail))
}

/** Why one plan request failed, before it is told in the words of the page. */
sealed class ChatGPTPlanServiceFailure(message: String) : Exception(message) {
    /** The plan cannot pay for more requests now. Nothing retries it. */
    class UsageLimitReached : ChatGPTPlanServiceFailure(ChatGPTPlanMessages.USAGE_LIMIT_SETTINGS)

    /** The sign-in ended; only signing in again helps. */
    class SignInRequired : ChatGPTPlanServiceFailure(ChatGPTPlanMessages.SIGN_IN_SETTINGS)

    /** OpenAI refused the request: [detail] says why, with the status, code and request id. */
    class Rejected(val detail: String) : ChatGPTPlanServiceFailure(detail)
    class Temporary : ChatGPTPlanServiceFailure("翻译服务暂时不可用。")
    class ResultNotFound : ChatGPTPlanServiceFailure("翻译服务暂时不可用。")

    /** Retrying may help. */
    val isTransient: Boolean
        get() = this is Temporary || this is ResultNotFound

    override fun equals(other: Any?): Boolean =
        other is ChatGPTPlanServiceFailure && other.javaClass == javaClass && other.message == message

    override fun hashCode(): Int = javaClass.hashCode() * 31 + (message?.hashCode() ?: 0)
}
