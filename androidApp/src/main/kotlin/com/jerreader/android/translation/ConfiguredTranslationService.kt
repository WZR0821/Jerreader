package com.jerreader.android.translation

import com.jerreader.unified.domain.LanguageCode
import com.jerreader.unified.translation.DirectAIProtocol
import com.jerreader.unified.translation.ContextExplanationRequest
import com.jerreader.unified.translation.ContextExplanationResult
import com.jerreader.unified.translation.ContextExplanationService
import com.jerreader.unified.translation.TranslationFailure
import com.jerreader.unified.translation.TranslationInputPolicy
import com.jerreader.unified.translation.TranslationProviderMode
import com.jerreader.unified.translation.TranslationRequest
import com.jerreader.unified.translation.TranslationResult
import com.jerreader.unified.translation.TranslationService
import com.jerreader.unified.translation.renderedPrompt
import com.jerreader.unified.translation.renderedGrammarPrompt
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class TranslationHttpResponse(val statusCode: Int, val body: String)

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
        val plan = settings.providerPlan()
        val mode = override?.providerMode ?: plan.primary
        return try {
            translateUsing(mode, normalizedRequest, override?.promptTemplate)
        } catch (error: CancellationException) {
            throw error
        } catch (error: TranslationFailure) {
            if (override != null) throw error
            if (error is TranslationFailure.EmptyInput ||
                error is TranslationFailure.TextTooLong ||
                error is TranslationFailure.UnsupportedLanguage
            ) {
                throw error
            }
            val fallback = plan.fallback
            if (fallback == null || fallback == mode) throw error
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
        val translated = performTextRequest(
            configuration = configuration,
            text = request.text,
            source = source,
            target = request.targetLanguage,
            prompt = configuration.preferences.renderedPrompt(source),
            operation = "translate"
        )
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
        val userText = buildString {
            append("需要解析的内容：\n")
            append(focused)
            request.contextText?.trim()?.takeIf(String::isNotBlank)?.let { context ->
                append("\n\n上下文：\n")
                append(context.take(2_000))
            }
        }
        val explanation = performTextRequest(
            configuration = configuration,
            text = userText,
            source = source,
            target = LanguageCode.CHINESE_SIMPLIFIED,
            prompt = configuration.preferences.renderedGrammarPrompt(source),
            operation = "explain"
        )
        return ContextExplanationResult(explanation, providerIdentifier(configuration))
    }

    private suspend fun performTextRequest(
        configuration: TranslationServiceConfiguration,
        text: String,
        source: LanguageCode,
        target: LanguageCode,
        prompt: String,
        operation: String
    ): String {
        val prepared = prepareRequest(configuration, text, source, target, prompt, operation)
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
                val translated = parseTranslation(configuration, response.body).trim()
                if (!TranslationInputPolicy.isVisiblyNonEmpty(translated)) {
                    throw TranslationFailure.ServiceUnavailable
                }
                return translated
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

    private fun validateConfiguration(configuration: TranslationServiceConfiguration) {
        if (configuration.endpoint.isBlank() || configuration.model.isBlank() &&
            configuration.preferences.providerMode == TranslationProviderMode.DIRECT_API
        ) {
            throw TranslationFailure.ConfigurationMissing
        }
        if (configuration.preferences.providerMode == TranslationProviderMode.DIRECT_API &&
            configuration.credential.isNullOrBlank()
        ) {
            throw TranslationFailure.ConfigurationMissing
        }
        val uri = runCatching { URI(configuration.endpoint) }.getOrNull()
        if (uri?.scheme?.lowercase() != "https" || uri.host.isNullOrBlank()) {
            throw TranslationFailure.InvalidConfiguration
        }
    }

    private fun prepareRequest(
        configuration: TranslationServiceConfiguration,
        text: String,
        source: LanguageCode,
        target: LanguageCode,
        prompt: String,
        operation: String
    ): PreparedRequest {
        if (configuration.preferences.providerMode == TranslationProviderMode.BACKEND_PROXY) {
            val body = JSONObject()
                .put("text", text)
                .put("sourceLanguage", source.tag)
                .put("targetLanguage", target.tag)
                .put("operation", operation)
                .put("prompt", prompt)
            if (configuration.model.isNotBlank()) body.put("model", configuration.model)
            return PreparedRequest(
                url = configuration.endpoint,
                headers = jsonHeaders(configuration.credential),
                body = body.toString()
            )
        }

        val provider = configuration.preferences.directProvider
        return when (provider.protocol) {
            DirectAIProtocol.OPENAI_CHAT_COMPLETIONS -> PreparedRequest(
                url = configuration.endpoint,
                headers = jsonHeaders(configuration.credential),
                body = JSONObject()
                    .put("model", configuration.model)
                    .put("temperature", 0.2)
                    .put(
                        "messages",
                        JSONArray()
                            .put(JSONObject().put("role", "system").put("content", prompt))
                            .put(JSONObject().put("role", "user").put("content", text))
                    )
                    .toString()
            )

            DirectAIProtocol.OPENAI_RESPONSES -> PreparedRequest(
                url = configuration.endpoint,
                headers = jsonHeaders(configuration.credential),
                body = JSONObject()
                    .put("model", configuration.model)
                    .put("instructions", prompt)
                    .put("input", text)
                    .toString()
            )

            DirectAIProtocol.ANTHROPIC_MESSAGES -> PreparedRequest(
                url = configuration.endpoint,
                headers = mapOf(
                    "Content-Type" to "application/json; charset=utf-8",
                    "x-api-key" to configuration.credential.orEmpty(),
                    "anthropic-version" to "2023-06-01"
                ),
                body = JSONObject()
                    .put("model", configuration.model)
                    .put("max_tokens", 2048)
                    .put("system", prompt)
                    .put(
                        "messages",
                        JSONArray().put(JSONObject().put("role", "user").put("content", text))
                    )
                    .toString()
            )

            DirectAIProtocol.GEMINI_GENERATE_CONTENT -> {
                val base = configuration.endpoint.trimEnd('/')
                val url = "$base/models/${configuration.model}:generateContent?key=${configuration.credential}"
                PreparedRequest(
                    url = url,
                    headers = mapOf("Content-Type" to "application/json; charset=utf-8"),
                    body = JSONObject()
                        .put(
                            "systemInstruction",
                            JSONObject().put(
                                "parts",
                                JSONArray().put(JSONObject().put("text", prompt))
                            )
                        )
                        .put(
                            "contents",
                            JSONArray().put(
                                JSONObject()
                                    .put("role", "user")
                                    .put("parts", JSONArray().put(JSONObject().put("text", text)))
                            )
                        )
                        .toString()
                )
            }
        }
    }

    private fun parseTranslation(
        configuration: TranslationServiceConfiguration,
        body: String
    ): String {
        val json = JSONObject(body)
        if (configuration.preferences.providerMode == TranslationProviderMode.BACKEND_PROXY) {
            return json.optString("translatedText")
                .ifBlank { json.optString("translation") }
                .ifBlank { json.optString("explanation") }
        }
        return when (configuration.preferences.directProvider.protocol) {
            DirectAIProtocol.OPENAI_CHAT_COMPLETIONS ->
                json.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    .orEmpty()

            DirectAIProtocol.ANTHROPIC_MESSAGES ->
                json.optJSONArray("content")?.optJSONObject(0)?.optString("text").orEmpty()

            DirectAIProtocol.GEMINI_GENERATE_CONTENT ->
                json.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.let { parts ->
                        buildString {
                            repeat(parts.length()) { index ->
                                append(parts.optJSONObject(index)?.optString("text").orEmpty())
                            }
                        }
                    }
                    .orEmpty()

            DirectAIProtocol.OPENAI_RESPONSES -> {
                json.optString("output_text").ifBlank {
                    val output = json.optJSONArray("output") ?: return@ifBlank ""
                    buildString {
                        repeat(output.length()) { outputIndex ->
                            val content = output.optJSONObject(outputIndex)?.optJSONArray("content")
                                ?: return@repeat
                            repeat(content.length()) { contentIndex ->
                                append(content.optJSONObject(contentIndex)?.optString("text").orEmpty())
                            }
                        }
                    }
                }
            }
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

    private fun jsonHeaders(credential: String?): Map<String, String> = buildMap {
        put("Content-Type", "application/json; charset=utf-8")
        credential?.let { put("Authorization", "Bearer $it") }
    }

    private fun providerIdentifier(configuration: TranslationServiceConfiguration): String =
        when (configuration.preferences.providerMode) {
            TranslationProviderMode.ON_DEVICE -> "Android 本机翻译"
            TranslationProviderMode.BACKEND_PROXY -> "AI 代理"
            TranslationProviderMode.DIRECT_API -> configuration.preferences.directProvider.displayName
        }

    private fun detectLanguage(text: String): LanguageCode = when {
        text.any { it in '\u3040'..'\u30ff' } -> LanguageCode.JAPANESE
        text.any { it in '\u3400'..'\u9fff' } -> LanguageCode.CHINESE_SIMPLIFIED
        else -> LanguageCode.ENGLISH
    }

    private data class PreparedRequest(
        val url: String,
        val headers: Map<String, String>,
        val body: String
    )
}
