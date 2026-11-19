package com.jerreader.android.translation

import com.jerreader.android.translation.chatgptplan.ChatGPTPlanCompletion
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanMessageContext
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanMessages
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanServiceFailure
import com.jerreader.shared.domain.LanguageCode
import com.jerreader.shared.translation.AIReadingLensPromptPolicy
import com.jerreader.shared.translation.ContextExplanationRequest
import com.jerreader.shared.translation.ContextExplanationResult
import com.jerreader.shared.translation.ContextExplanationService
import com.jerreader.shared.translation.TranslationFailure
import com.jerreader.shared.translation.TranslationInputPolicy
import com.jerreader.shared.translation.TranslationProviderMode
import com.jerreader.shared.translation.TranslationRequest
import com.jerreader.shared.translation.TranslationResult
import com.jerreader.shared.translation.TranslationService
import com.jerreader.shared.translation.renderedPrompt
import com.jerreader.shared.translation.renderedGrammarPrompt
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class TranslationHttpResponse(val statusCode: Int, val body: String)

/** One request, already in the format of the service it goes to. */
internal data class PreparedRequest(
    val url: String,
    val headers: Map<String, String>,
    val body: String
)

fun interface TranslationHttpTransport {
    suspend fun post(url: String, headers: Map<String, String>, body: String): TranslationHttpResponse
}

class UrlConnectionTranslationTransport : TranslationHttpTransport {
    override suspend fun post(
        url: String,
        headers: Map<String, String>,
        body: String
    ): TranslationHttpResponse = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            connection.readTimeout = 45_000
            connection.doOutput = true
            headers.forEach(connection::setRequestProperty)
            connection.outputStream.use { output -> output.write(body.encodeToByteArray()) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            TranslationHttpResponse(status, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }
}

class ConfiguredTranslationService(
    private val settings: AndroidTranslationSettingsStore,
    private val onDeviceService: TranslationService = OnDeviceTranslationService(),
    private val transport: TranslationHttpTransport = UrlConnectionTranslationTransport()
) : TranslationService, ContextExplanationService {
    override val identifier: String = "configured-ai"

    /**
     * Requests paid by the ChatGPT plan, through its signed-in session. A
     * refusal for a missing grant makes the next sign-in ask for it again.
     */
    private val planCompletion = ChatGPTPlanCompletion(settings.chatGPTPlanSession) {
        settings.chatGPTPlanConsentNeeded = true
    }

    override suspend fun translate(request: TranslationRequest): TranslationResult =
        translate(request, override = null)

    /**
     * [override] is the standalone translate page choosing a provider and a
     * prompt for one request only. An overridden provider is used as picked: it
     * would be surprising for the page to quietly answer from the service the
     * reader is configured with, so no fallback switch happens there.
     */
    suspend fun translate(
        request: TranslationRequest,
        override: StandaloneTranslationOverride?
    ): TranslationResult {
        val text = TranslationInputPolicy.validate(request.text)
        val normalizedRequest = request.copy(text = text)
        val preferences = settings.preferences.value
        val mode = override?.providerMode ?: preferences.providerMode
        return try {
            translateUsing(mode, normalizedRequest, override?.promptTemplate)
        } catch (error: CancellationException) {
            throw error
        } catch (error: TranslationFailure) {
            if (override != null) throw error
            if (error is TranslationFailure.EmptyInput ||
                error is TranslationFailure.TextTooLong ||
                error is TranslationFailure.UnsupportedLanguage ||
                error is TranslationFailure.LocalModelsMissing ||
                // A ChatGPT plan out of quota, signed out or refused stops; nothing answers in its place.
                ChatGPTPlanMessages.isStop(error)
            ) {
                throw error
            }
            val fallback = preferences.fallbackMode.providerMode
            if (fallback == null || fallback == preferences.providerMode) throw error
            translateUsing(fallback, normalizedRequest, null)
        }
    }

    private suspend fun translateUsing(
        mode: TranslationProviderMode,
        request: TranslationRequest,
        promptTemplate: String?
    ): TranslationResult {
        if (mode == TranslationProviderMode.ON_DEVICE) {
            return onDeviceService.translate(request)
        }
        val configuration = settings.currentServiceConfiguration(mode, promptTemplate)
        validateConfiguration(configuration)
        val source = request.sourceLanguage ?: detectLanguage(request.text)
        val prompt = configuration.preferences.renderedPrompt(source)
        val plan = configuration.planDirect
        if (plan != null) {
            return TranslationResult(
                translatedText = planText(configuration, plan, prompt, request.text, ChatGPTPlanMessageContext.TRANSLATION),
                sourceLanguage = source,
                targetLanguage = request.targetLanguage,
                providerIdentifier = providerIdentifier(configuration)
            )
        }
        val prepared = if (configuration.isProxy) {
            AIProxyProtocol.translation(configuration, request.text, source, request.targetLanguage, prompt)
        } else {
            directRequest(configuration.requireDirect(), request.text, prompt, quick = true)
        }
        val translated = performTextRequest(configuration, prepared, isExplanation = false)
        return TranslationResult(
            translatedText = translated,
            sourceLanguage = source,
            targetLanguage = request.targetLanguage,
            providerIdentifier = providerIdentifier(configuration)
        )
    }

    override suspend fun explain(request: ContextExplanationRequest): ContextExplanationResult {
        val focused = TranslationInputPolicy.validate(request.focusedText)
        val mode = settings.preferredAIProviderMode()
            ?: throw TranslationFailure.ConfigurationMissing
        val configuration = settings.currentServiceConfiguration(mode)
        validateConfiguration(configuration)
        val source = request.sourceLanguage ?: detectLanguage(focused)
        val grammarPrompt = configuration.preferences.renderedGrammarPrompt(source)
        val lens = request.lens
        val prompt = if (lens != null) {
            AIReadingLensPromptPolicy.rendered(
                options = lens,
                sourceLanguage = source,
                responseLanguage = LanguageCode.CHINESE_SIMPLIFIED,
                grammarPrompt = grammarPrompt
            )
        } else {
            grammarPrompt +
                if (request.isWordLookup) "\n本次是语境查词。先给出该词在原句中的简短中文含义，再说明词典形与关键活用。区分推测和确定信息；缺少原句或无法消歧时明确说明，不编造读音、词源或词典依据。" else ""
        }
        val plan = configuration.planDirect
        if (plan != null) {
            val userText = if (lens != null) {
                lensUserText(focused, request.contextText)
            } else {
                grammarUserText(focused, request)
            }
            val explanation = planText(configuration, plan, prompt, userText, ChatGPTPlanMessageContext.EXPLANATION)
            return ContextExplanationResult(explanation, providerIdentifier(configuration))
        }
        val prepared = if (configuration.isProxy) {
            AIProxyProtocol.explanation(configuration, request, focused, source, prompt)
        } else {
            val userText = if (lens != null) {
                lensUserText(focused, request.contextText)
            } else {
                grammarUserText(focused, request)
            }
            directRequest(configuration.requireDirect(), userText, prompt, quick = false)
        }
        val explanation = performTextRequest(configuration, prepared, isExplanation = true)
        return ContextExplanationResult(explanation, providerIdentifier(configuration))
    }

    /**
     * Sends “Hello.” to one model, saved or not, and returns how long the
     * answer took in seconds. Throws the provider's reason when it fails.
     */
    suspend fun testConnection(direct: DirectAIConfiguration): Double {
        val preferences = settings.preferences.value.copy(
            providerMode = TranslationProviderMode.DIRECT_API,
            // One try, so the time shown is the time of one request.
            automaticRetryEnabled = false
        )
        val configuration = TranslationServiceConfiguration(
            preferences = preferences,
            endpoint = direct.endpoint,
            model = direct.model,
            credential = direct.apiKey,
            direct = direct
        )
        val prompt = preferences.copy(targetLanguage = LanguageCode.CHINESE_SIMPLIFIED)
            .renderedPrompt(LanguageCode.ENGLISH)
        if (direct.kind.usesAccountSignIn) {
            val started = System.nanoTime()
            planText(configuration, direct, prompt, "Hello.", ChatGPTPlanMessageContext.SETTINGS)
            return (System.nanoTime() - started) / 1_000_000_000.0
        }
        val prepared = directRequest(direct, "Hello.", prompt, quick = true)
        val started = System.nanoTime()
        performTextRequest(configuration, prepared, isExplanation = false)
        return (System.nanoTime() - started) / 1_000_000_000.0
    }

    /** The labels the lens prompt refers to; the same ones iOS sends. */
    private fun lensUserText(focused: String, context: String?): String = buildString {
        append("SELECTED TEXT:\n")
        append(focused)
        context?.trim()?.takeIf(String::isNotBlank)?.takeIf { it != focused }?.let {
            append("\n\nREADING CONTEXT:\n")
            append(it.take(2_000))
        }
    }

    /** The sentence analysis as a model called directly is sent it. */
    private fun grammarUserText(focused: String, request: ContextExplanationRequest): String = buildString {
        append("需要解析的内容：\n")
        append(focused)
        if (request.isWordLookup) request.dictionaryEntry?.let {
            append("\n\n词典候选资料（仅供参考，若不适合原句请指出）：\n")
            append(it.take(1_000))
        }
        request.contextText?.trim()?.takeIf(String::isNotBlank)?.let { context ->
            append("\n\n上下文：\n")
            append(context.take(2_000))
        }
    }

    /**
     * Sends [prepared], built once so that a retry is the same request, with
     * the same idempotency key for the AI 代理.
     */
    private suspend fun performTextRequest(
        configuration: TranslationServiceConfiguration,
        prepared: PreparedRequest,
        isExplanation: Boolean
    ): String {
        val attempts = if (configuration.preferences.automaticRetryEnabled) 2 else 1
        var lastFailure: Throwable? = null
        repeat(attempts) { attempt ->
            try {
                val response = transport.post(prepared.url, prepared.headers, prepared.body)
                if (response.statusCode !in 200..299) {
                    val message = providerErrorMessage(response)
                    if (response.statusCode !in 500..599 || attempt == attempts - 1) {
                        throw TranslationFailure.ProviderRejected(message)
                    }
                    lastFailure = TranslationFailure.ProviderRejected(message)
                    return@repeat
                }
                val translated = parseTranslation(configuration, response.body, isExplanation).trim()
                if (!TranslationInputPolicy.isVisiblyNonEmpty(translated)) {
                    throw TranslationFailure.ServiceUnavailable
                }
                return translated
            } catch (error: CancellationException) {
                // A cancelled or timed-out request must reach the caller as
                // that, not as 服务不可用.
                throw error
            } catch (error: TranslationFailure) {
                throw error
            } catch (error: IOException) {
                lastFailure = error
                if (attempt == attempts - 1) throw TranslationFailure.ServiceUnavailable
            } catch (_: Exception) {
                throw TranslationFailure.ServiceUnavailable
            }
        }
        throw lastFailure ?: TranslationFailure.ServiceUnavailable
    }

    /**
     * One answer paid by the ChatGPT plan. A usage limit, an ended sign-in or
     * a refusal stops at once and is never retried; passing failures are
     * retried like any provider's.
     */
    private suspend fun planText(
        configuration: TranslationServiceConfiguration,
        direct: DirectAIConfiguration,
        instructions: String,
        input: String,
        context: ChatGPTPlanMessageContext
    ): String {
        val attempts = if (configuration.preferences.automaticRetryEnabled) 2 else 1
        val quick = context != ChatGPTPlanMessageContext.EXPLANATION
        var failure: ChatGPTPlanServiceFailure = ChatGPTPlanServiceFailure.Temporary()
        for (attempt in 0 until attempts) {
            if (attempt > 0) delay(PLAN_RETRY_DELAY_MILLIS)
            try {
                return planCompletion.complete(direct, instructions, input, quick)
            } catch (error: CancellationException) {
                throw error
            } catch (error: ChatGPTPlanServiceFailure) {
                failure = error
                if (!error.isTransient) break
            } catch (_: Exception) {
                failure = ChatGPTPlanServiceFailure.Temporary()
            }
        }
        throw ChatGPTPlanMessages.translationFailure(failure, context)
    }

    private fun validateConfiguration(configuration: TranslationServiceConfiguration) {
        if (configuration.preferences.providerMode == TranslationProviderMode.DIRECT_API) {
            if (configuration.direct != null) return
            throw when (configuration.directIssue) {
                is AIProviderSettingsError.InvalidAddress -> TranslationFailure.InvalidConfiguration
                // The ChatGPT plan is in use but signed out.
                is AIProviderSettingsError.SignInRequired ->
                    TranslationFailure.ProviderRejected(ChatGPTPlanMessages.SIGN_IN_TRANSLATION)
                else -> TranslationFailure.AIModelNotReady
            }
        }
        if (configuration.endpoint.isBlank()) throw TranslationFailure.ConfigurationMissing
        val uri = runCatching { URI(configuration.endpoint) }.getOrNull()
        if (uri?.scheme?.lowercase() != "https" || uri.host.isNullOrBlank()) {
            throw TranslationFailure.InvalidConfiguration
        }
    }

    /** The AI 代理 gets the requests iOS sends; see [AIProxyProtocol]. */
    private val TranslationServiceConfiguration.isProxy: Boolean
        get() = preferences.providerMode == TranslationProviderMode.BACKEND_PROXY

    private fun TranslationServiceConfiguration.requireDirect(): DirectAIConfiguration =
        direct ?: throw TranslationFailure.AIModelNotReady

    /** The ChatGPT plan in use, which is called through its signed-in session instead of a key. */
    private val TranslationServiceConfiguration.planDirect: DirectAIConfiguration?
        get() = if (isProxy) null else direct?.takeIf { it.kind.usesAccountSignIn }

    /** The request of one model in its format, the same body iOS sends. */
    private fun directRequest(
        direct: DirectAIConfiguration,
        text: String,
        prompt: String,
        quick: Boolean
    ): PreparedRequest {
        val body = when (direct.style) {
            AIAPIStyle.RESPONSES -> JSONObject()
                .put("model", direct.model)
                .put("instructions", prompt)
                .put("input", text)

            AIAPIStyle.CHAT_COMPLETIONS -> JSONObject()
                .put("model", direct.model)
                .put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", prompt))
                        .put(JSONObject().put("role", "user").put("content", text))
                )
                .put("stream", false)

            AIAPIStyle.GEMINI_NATIVE -> JSONObject()
                .put(
                    "systemInstruction",
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                )
                .put(
                    "contents",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put("parts", JSONArray().put(JSONObject().put("text", text)))
                    )
                )

            AIAPIStyle.ANTHROPIC_MESSAGES -> JSONObject()
                .put("model", direct.model)
                .put("max_tokens", AIRequestTuning.ANTHROPIC_OUTPUT_TOKENS)
                .put("system", prompt)
                .put(
                    "messages",
                    JSONArray().put(JSONObject().put("role", "user").put("content", text))
                )
        }
        // Reasoning and output fields of the chosen model; none for most
        // models on 自动, which leaves the body as it is.
        val extra = AIRequestTuning.extraFields(direct.kind, direct.style, direct.model, direct.reasoning, quick)
        extra.keys().forEach { key -> body.put(key, extra.get(key)) }
        val headers = buildMap {
            put("Content-Type", "application/json; charset=utf-8")
            put("Accept", "application/json")
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
            geminiURL(direct.endpoint, direct.model)
        } else {
            direct.endpoint
        }
        return PreparedRequest(url = url, headers = headers, body = body.toString())
    }

    /** Gemini's own format names the model in the path, before any query. */
    private fun geminiURL(endpoint: String, model: String): String {
        val queryStart = endpoint.indexOf('?')
        val base = if (queryStart >= 0) endpoint.substring(0, queryStart) else endpoint
        val query = if (queryStart >= 0) endpoint.substring(queryStart) else ""
        return base.trimEnd('/') + "/models/" + model + ":generateContent" + query
    }

    private fun parseTranslation(
        configuration: TranslationServiceConfiguration,
        body: String,
        isExplanation: Boolean
    ): String {
        if (configuration.isProxy) {
            return if (isExplanation) {
                AIProxyProtocol.explanationText(body)
            } else {
                AIProxyProtocol.translatedText(body)
            }
        }
        val json = JSONObject(body)
        val style = configuration.direct?.style ?: throw TranslationFailure.AIModelNotReady
        return when (style) {
            AIAPIStyle.CHAT_COMPLETIONS -> {
                val content = json.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.opt("content")
                when (content) {
                    is String -> content
                    // Some compatible services answer with a list of parts.
                    is JSONArray -> joinedText(content)
                    else -> ""
                }
            }

            // A thinking block comes before the answer and has no text.
            AIAPIStyle.ANTHROPIC_MESSAGES -> joinedText(json.optJSONArray("content"))

            AIAPIStyle.GEMINI_NATIVE -> {
                val candidates = json.optJSONArray("candidates")
                buildString {
                    repeat(candidates?.length() ?: 0) { index ->
                        append(joinedText(candidates?.optJSONObject(index)?.optJSONObject("content")?.optJSONArray("parts")))
                    }
                }
            }

            AIAPIStyle.RESPONSES -> json.optString("output_text").ifBlank {
                // A reasoning item without content can come before the message.
                val output = json.optJSONArray("output") ?: return@ifBlank ""
                buildString {
                    repeat(output.length()) { index ->
                        append(joinedText(output.optJSONObject(index)?.optJSONArray("content")))
                    }
                }
            }
        }
    }

    /** The `text` of every part that has one, joined. */
    private fun joinedText(parts: JSONArray?): String = buildString {
        repeat(parts?.length() ?: 0) { index ->
            val part = parts?.optJSONObject(index) ?: return@repeat
            if (part.opt("text") is String) append(part.optString("text"))
        }
    }

    private fun providerErrorMessage(response: TranslationHttpResponse): String {
        val providerMessage = runCatching {
            val error = JSONObject(response.body).opt("error")
            when (error) {
                is JSONObject -> error.optString("message")
                is String -> error
                else -> ""
            }
        }.getOrDefault("")
        return providerMessage.takeIf(String::isNotBlank)
            ?.let { "翻译服务返回错误：$it" }
            ?: "翻译服务返回 HTTP ${response.statusCode}。"
    }

    private fun providerIdentifier(configuration: TranslationServiceConfiguration): String =
        when (configuration.preferences.providerMode) {
            TranslationProviderMode.ON_DEVICE -> "Android 本机翻译"
            TranslationProviderMode.BACKEND_PROXY -> "AI 代理"
            TranslationProviderMode.DIRECT_API -> configuration.direct?.displayName ?: "AI 大模型"
        }

    private fun detectLanguage(text: String): LanguageCode = when {
        text.any { it in '\u3040'..'\u30ff' } -> LanguageCode.JAPANESE
        text.any { it in '\u3400'..'\u9fff' } -> LanguageCode.CHINESE_SIMPLIFIED
        else -> LanguageCode.ENGLISH
    }

    private companion object {
        /** The pause before the ChatGPT plan's one retry. */
        const val PLAN_RETRY_DELAY_MILLIS = 450L
    }
}
