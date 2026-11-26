package com.jerreader.android.translation

import com.jerreader.android.translation.chatgptplan.ChatGPTPlanCompletion
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanMessageContext
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanMessages
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanServiceFailure
import com.jerreader.shared.domain.LanguageCode
import com.jerreader.shared.translation.AIActionRequest
import com.jerreader.shared.translation.AIActionResult
import com.jerreader.shared.translation.AIAnswerSource
import com.jerreader.shared.translation.AIChatMessage
import com.jerreader.shared.translation.AIChatRole
import com.jerreader.shared.translation.AIReadingAction
import com.jerreader.shared.translation.AIReadingActionPolicy
import com.jerreader.shared.translation.AIReadingActionService
import com.jerreader.shared.translation.AIReadingLensPromptPolicy
import com.jerreader.shared.translation.AIUsage
import com.jerreader.shared.translation.ContextExplanationRequest
import com.jerreader.shared.translation.ContextExplanationResult
import com.jerreader.shared.translation.ReaderTranslationContext
import com.jerreader.shared.translation.StreamingContextExplanationService
import com.jerreader.shared.translation.StreamingTranslationService
import com.jerreader.shared.translation.TranslationFailure
import com.jerreader.shared.translation.TranslationInputPolicy
import com.jerreader.shared.translation.TranslationPreferences
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
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

    /**
     * Posts a streamed request (2.1.0) and hands a 2xx body to [onLine] a line
     * at a time, without line breaks, as it arrives; the response then has an
     * empty body. Any other answer comes back whole. A transport that cannot
     * stream, such as a test's, answers at once and splits the whole body.
     */
    suspend fun postStreaming(
        url: String,
        headers: Map<String, String>,
        body: String,
        onLine: (String) -> Unit
    ): TranslationHttpResponse {
        val response = post(url, headers, body)
        if (response.statusCode !in 200..299) return response
        response.body.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach(onLine)
        return TranslationHttpResponse(response.statusCode, "")
    }
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

    /**
     * Reads the answer line by line while it arrives (2.1.0). The read timeout
     * is longer than the callers' own quiet limits (30 s and 70 s), so theirs
     * decide; cancelling the request closes the connection at once, which
     * also ends a read that is waiting for the next line.
     */
    override suspend fun postStreaming(
        url: String,
        headers: Map<String, String>,
        body: String,
        onLine: (String) -> Unit
    ): TranslationHttpResponse = coroutineScope {
        val connection = URL(url).openConnection() as HttpURLConnection
        val closer = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                connection.disconnect()
            }
        }
        try {
            withContext(Dispatchers.IO) {
                connection.requestMethod = "POST"
                connection.connectTimeout = 20_000
                connection.readTimeout = STREAM_READ_TIMEOUT_MILLIS
                connection.doOutput = true
                headers.forEach(connection::setRequestProperty)
                connection.outputStream.use { output -> output.write(body.encodeToByteArray()) }
                val status = connection.responseCode
                if (status !in 200..299) {
                    return@withContext TranslationHttpResponse(
                        status,
                        connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    )
                }
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        ensureActive()
                        onLine(line)
                    }
                }
                TranslationHttpResponse(status, "")
            }
        } finally {
            closer.cancel()
        }
    }

    private companion object {
        const val STREAM_READ_TIMEOUT_MILLIS = 80_000
    }
}

class ConfiguredTranslationService(
    private val settings: AndroidTranslationSettingsStore,
    private val onDeviceService: TranslationService = OnDeviceTranslationService(),
    private val transport: TranslationHttpTransport = UrlConnectionTranslationTransport()
) : StreamingTranslationService, StreamingContextExplanationService, AIReadingActionService {
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
            // A cancelled request never fails over to the fallback.
            currentCoroutineContext().ensureActive()
            if (override != null) throw error
            val fallback = fallbackMode(error, preferences) ?: throw error
            translateUsing(fallback, normalizedRequest, null).copy(fallbackProvider = fallback)
        }
    }

    /**
     * The reader's translation, shown while the model writes it (2.1.0). When
     * the configured service fails the fallback answers as before, also after
     * text was shown; its answer then replaces that text.
     */
    override suspend fun translateStreaming(
        request: TranslationRequest,
        onPartial: (String) -> Unit
    ): TranslationResult {
        val normalizedRequest = request.copy(text = TranslationInputPolicy.validate(request.text))
        val preferences = settings.preferences.value
        return try {
            translateUsing(preferences.providerMode, normalizedRequest, null, onPartial)
        } catch (error: CancellationException) {
            throw error
        } catch (error: TranslationFailure) {
            // A cancelled request never fails over to the fallback.
            currentCoroutineContext().ensureActive()
            val fallback = fallbackMode(error, preferences) ?: throw error
            translateUsing(fallback, normalizedRequest, null, onPartial).copy(fallbackProvider = fallback)
        }
    }

    /** The service that answers after [error], or null when nothing may answer in its place. */
    private fun fallbackMode(
        error: TranslationFailure,
        preferences: TranslationPreferences
    ): TranslationProviderMode? {
        if (error is TranslationFailure.EmptyInput ||
            error is TranslationFailure.TextTooLong ||
            error is TranslationFailure.UnsupportedLanguage ||
            error is TranslationFailure.LocalModelsMissing ||
            // A ChatGPT plan out of quota, signed out or refused stops; nothing answers in its place.
            ChatGPTPlanMessages.isStop(error)
        ) {
            return null
        }
        return preferences.fallbackMode.providerMode?.takeIf { it != preferences.providerMode }
    }

    /**
     * One translation by [mode]. With [onPartial] a model called directly or
     * through the ChatGPT plan streams; the AI 代理 and the on-device
     * translator answer once either way.
     */
    private suspend fun translateUsing(
        mode: TranslationProviderMode,
        request: TranslationRequest,
        promptTemplate: String?,
        onPartial: ((String) -> Unit)? = null
    ): TranslationResult {
        if (mode == TranslationProviderMode.ON_DEVICE) {
            val translated = onDeviceService.translate(request)
            return translated.copy(answerSource = translated.answerSource ?: ON_DEVICE_SOURCE)
        }
        val configuration = settings.currentServiceConfiguration(mode, promptTemplate)
        validateConfiguration(configuration)
        val source = request.sourceLanguage ?: detectLanguage(request.text)
        val prompt = configuration.preferences.renderedPrompt(source)
        // 2.1.0: the sentence and the text around it, tagged the way iOS's
        // TranslationPrompt tags them, so the model translates only the one.
        val context = ReaderTranslationContext.meaningfulContext(request.context, request.text)
        val userContent = TranslationPrompt.userContent(ReaderTranslationContext.normalizedText(request.text), context)
        val plan = configuration.planDirect
        val answer = when {
            plan != null && onPartial != null -> planStreaming(
                configuration,
                plan,
                prompt,
                listOf(AIChatRole.USER.id to userContent),
                ChatGPTPlanMessageContext.TRANSLATION,
                onPartial
            )
            plan != null -> ModelAnswer(
                planText(configuration, plan, prompt, userContent, ChatGPTPlanMessageContext.TRANSLATION)
            )
            configuration.isProxy -> ModelAnswer(
                performTextRequest(
                    configuration,
                    AIProxyProtocol.translation(configuration, request.text, source, request.targetLanguage, prompt, context),
                    isExplanation = false
                )
            )
            onPartial != null -> performStreamingRequest(
                configuration,
                directRequest(configuration.requireDirect(), instructedMessages(prompt, userContent), quick = true, stream = true),
                isExplanation = false,
                onPartial = onPartial
            )
            else -> ModelAnswer(
                performTextRequest(
                    configuration,
                    directRequest(configuration.requireDirect(), userContent, prompt, quick = true),
                    isExplanation = false
                )
            )
        }
        return TranslationResult(
            translatedText = answer.text,
            sourceLanguage = source,
            targetLanguage = request.targetLanguage,
            providerIdentifier = providerIdentifier(configuration),
            answerSource = answerSource(configuration, context),
            usage = answer.usage
        )
    }

    override suspend fun explain(request: ContextExplanationRequest): ContextExplanationResult =
        explaining(request, onPartial = null)

    /** [explain], shown while it arrives from a model called directly or the ChatGPT plan (2.1.0). */
    override suspend fun explainStreaming(
        request: ContextExplanationRequest,
        onPartial: (String) -> Unit
    ): ContextExplanationResult = explaining(request, onPartial)

    private suspend fun explaining(
        request: ContextExplanationRequest,
        onPartial: ((String) -> Unit)?
    ): ContextExplanationResult {
        val focused = TranslationInputPolicy.validate(request.focusedText)
        // 2.1.0 §7: a signed-in ChatGPT plan answers when nothing else is set up.
        val configuration = settings.aiFeatureConfiguration()
            ?: throw TranslationFailure.ConfigurationMissing
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
            // The lens prompt ends with the guard already; the sentence
            // structure prompt gets it as its last line here (2.1.0).
            AIReadingActionPolicy.guarded(
                grammarPrompt +
                    if (request.isWordLookup) "\n本次是语境查词。先给出该词在原句中的简短中文含义，再说明词典形与关键活用。区分推测和确定信息；缺少原句或无法消歧时明确说明，不编造读音、词源或词典依据。" else ""
            )
        }
        val userText = if (lens != null) {
            lensUserText(focused, request.contextText)
        } else {
            grammarUserText(focused, request)
        }
        val plan = configuration.planDirect
        val answer = when {
            plan != null && onPartial != null -> planStreaming(
                configuration,
                plan,
                prompt,
                listOf(AIChatRole.USER.id to userText),
                ChatGPTPlanMessageContext.EXPLANATION,
                onPartial
            )
            plan != null -> ModelAnswer(
                planText(configuration, plan, prompt, userText, ChatGPTPlanMessageContext.EXPLANATION)
            )
            configuration.isProxy -> ModelAnswer(
                performTextRequest(
                    configuration,
                    AIProxyProtocol.explanation(configuration, request, focused, source, prompt),
                    isExplanation = true
                )
            )
            onPartial != null -> performStreamingRequest(
                configuration,
                directRequest(configuration.requireDirect(), instructedMessages(prompt, userText), quick = false, stream = true),
                isExplanation = true,
                onPartial = onPartial
            )
            else -> ModelAnswer(
                performTextRequest(
                    configuration,
                    directRequest(configuration.requireDirect(), userText, prompt, quick = false),
                    isExplanation = true
                )
            )
        }
        return ContextExplanationResult(
            explanation = answer.text,
            providerIdentifier = providerIdentifier(configuration),
            answerSource = answerSource(configuration, explanationContext(request, focused)),
            usage = answer.usage
        )
    }

    /**
     * 直译 and 追问 (2.1.0), with instructions of their own and the sentence's
     * material. 追问 sends the conversation so far as messages to a service
     * that takes several turns, and as one flattened text to the AI 代理.
     */
    override suspend fun perform(request: AIActionRequest, onPartial: (String) -> Unit): AIActionResult {
        val action = request.action
        require(action == AIReadingAction.LITERAL || action == AIReadingAction.FOLLOW_UP) {
            "${action.id} is answered by explain, not perform"
        }
        val sourceText = TranslationInputPolicy.validate(request.source)
        val configuration = settings.aiFeatureConfiguration()
            ?: throw TranslationFailure.ConfigurationMissing
        validateConfiguration(configuration)
        val source = request.sourceLanguage ?: detectLanguage(sourceText)
        val context = request.context?.trim()?.takeIf { it.isNotEmpty() && it != sourceText }
        val instruction = AIReadingActionPolicy.instruction(action, request.sourceLanguage, request.responseLanguage)
        val messages = if (action == AIReadingAction.FOLLOW_UP) {
            AIReadingActionPolicy.followUpMessages(
                source = sourceText,
                translation = request.translation,
                context = context,
                sourceLanguage = request.sourceLanguage,
                responseLanguage = request.responseLanguage,
                turns = request.turns
            )
        } else {
            instructedMessages(instruction, AIReadingActionPolicy.material(sourceText, request.translation, context))
        }
        val plan = configuration.planDirect
        val answer = when {
            plan != null -> planStreaming(
                configuration,
                plan,
                instructions = messages.filter { it.role == AIChatRole.SYSTEM }.joinToString("\n\n") { it.text },
                input = messages.filter { it.role != AIChatRole.SYSTEM }.map { it.role.id to it.text },
                context = ChatGPTPlanMessageContext.EXPLANATION,
                onPartial = onPartial
            )
            configuration.isProxy -> {
                val text = if (action == AIReadingAction.FOLLOW_UP) {
                    AIReadingActionPolicy.followUpFlattened(sourceText, request.translation, context, request.turns)
                } else {
                    AIReadingActionPolicy.material(sourceText, request.translation, context)
                }
                ModelAnswer(
                    performTextRequest(
                        configuration,
                        AIProxyProtocol.action(configuration, action.id, text, source, instruction),
                        isExplanation = true
                    )
                )
            }
            else -> performStreamingRequest(
                configuration,
                directRequest(configuration.requireDirect(), messages, quick = false, stream = true),
                isExplanation = true,
                onPartial = onPartial
            )
        }
        return AIActionResult(answer.text, answerSource(configuration, context), answer.usage)
    }

    /**
     * The badge of a cached translation (2.1.0): the service that would answer
     * it now, which is the one the cache namespace belongs to, and the context
     * that went with it.
     */
    fun cachedTranslationSource(
        request: TranslationRequest,
        overrideMode: TranslationProviderMode?
    ): AIAnswerSource? {
        val mode = overrideMode ?: settings.preferences.value.providerMode
        if (mode == TranslationProviderMode.ON_DEVICE) return ON_DEVICE_SOURCE
        val configuration = settings.currentServiceConfiguration(mode)
        if (!configuration.isProxy && configuration.direct == null) return null
        return answerSource(configuration, ReaderTranslationContext.meaningfulContext(request.context, request.text))
    }

    /** The badge of a cached explanation, as [cachedTranslationSource]. */
    fun cachedExplanationSource(request: ContextExplanationRequest): AIAnswerSource? {
        val configuration = settings.aiFeatureConfiguration() ?: return null
        if (!configuration.isProxy && configuration.direct == null) return null
        return answerSource(configuration, explanationContext(request, request.focusedText.trim()))
    }

    /**
     * Who answers through [configuration], for the source badge: the model id
     * of a model called directly, the account's name for a ChatGPT plan model,
     * the AI 代理's own model or 「AI 代理」.
     */
    private fun answerSource(configuration: TranslationServiceConfiguration, sentContext: String?): AIAnswerSource {
        val name = if (configuration.isProxy) {
            AIProxyProtocol.model(configuration) ?: PROXY_NAME
        } else {
            configuration.direct?.let { direct ->
                if (direct.kind.usesAccountSignIn) settings.chatGPTPlanModelName(direct.model) else direct.model
            }.orEmpty()
        }
        return AIAnswerSource(modelName = name, isAIModel = true, sentContext = sentContext)
    }

    /** The reading context an explanation sends with the selection, as the 「发送给模型的内容」 sheet shows it. */
    private fun explanationContext(request: ContextExplanationRequest, focused: String): String? =
        request.contextText?.trim()?.takeIf { it.isNotEmpty() && it != focused }?.take(2_000)

    /** An instruction and one user message, the shape of every single-turn request. */
    private fun instructedMessages(instruction: String, userText: String): List<AIChatMessage> = listOf(
        AIChatMessage(AIChatRole.SYSTEM, instruction),
        AIChatMessage(AIChatRole.USER, userText)
    )

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
                // Cancelling closes the connection, so the read fails with an
                // IOException; the caller must still see the cancellation.
                currentCoroutineContext().ensureActive()
                lastFailure = error
                if (attempt == attempts - 1) throw TranslationFailure.ServiceUnavailable
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                throw TranslationFailure.ServiceUnavailable
            }
        }
        throw lastFailure ?: TranslationFailure.ServiceUnavailable
    }

    /**
     * Sends a streamed [prepared] request and shows its text while it arrives
     * (2.1.0). The answer counts only when the stream ends the way its format
     * ends a whole answer: a stream cut off, stopped at a limit or refused
     * fails, so its text was shown but is never returned or cached. A service
     * that ignores `stream` and answers with one JSON body is read as before.
     * Like [performTextRequest] it tries once more after a server error or a
     * dropped connection, but only while no text has been shown.
     */
    private suspend fun performStreamingRequest(
        configuration: TranslationServiceConfiguration,
        prepared: PreparedRequest,
        isExplanation: Boolean,
        onPartial: (String) -> Unit
    ): ModelAnswer {
        val style = configuration.requireDirect().style
        val attempts = if (configuration.preferences.automaticRetryEnabled) 2 else 1
        repeat(attempts) { attempt ->
            val isLastAttempt = attempt == attempts - 1
            val reader = AIStreamReader(style, onPartial)
            try {
                val response = transport.postStreaming(prepared.url, prepared.headers, prepared.body, reader::line)
                if (response.statusCode !in 200..299) {
                    if (response.statusCode !in 500..599 || isLastAttempt) {
                        throw TranslationFailure.ProviderRejected(providerErrorMessage(response))
                    }
                    return@repeat
                }
                reader.plainResponse?.let { body ->
                    val text = parseTranslation(configuration, body, isExplanation).trim()
                    if (!TranslationInputPolicy.isVisiblyNonEmpty(text)) throw TranslationFailure.ServiceUnavailable
                    return ModelAnswer(text)
                }
                when (val outcome = reader.decoder.finish()) {
                    AIStreamOutcome.Completed -> {
                        val text = reader.decoder.answer.trim()
                        if (!TranslationInputPolicy.isVisiblyNonEmpty(text)) throw TranslationFailure.ServiceUnavailable
                        return ModelAnswer(text, reader.decoder.usage)
                    }
                    is AIStreamOutcome.Failed -> throw TranslationFailure.ProviderRejected(streamFailureMessage(outcome))
                    // Stopped at the output limit or by a safety filter: what came is not the whole answer.
                    is AIStreamOutcome.Incomplete -> throw TranslationFailure.ServiceUnavailable
                    AIStreamOutcome.Interrupted -> throw IOException("The stream ended before its last event.")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: TranslationFailure) {
                throw error
            } catch (error: IOException) {
                // Cancelling closes the connection, so the read fails with an
                // IOException: that is a cancellation, which must neither fail
                // over to the fallback nor be retried.
                currentCoroutineContext().ensureActive()
                if (isLastAttempt || reader.hasShownText) throw TranslationFailure.ServiceUnavailable
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                throw TranslationFailure.ServiceUnavailable
            }
        }
        throw TranslationFailure.ServiceUnavailable
    }

    private fun streamFailureMessage(failure: AIStreamOutcome.Failed): String {
        val reason = failure.message?.trim()?.takeIf(String::isNotEmpty)
            ?: failure.code?.trim()?.takeIf(String::isNotEmpty)
        return reason?.let { "翻译服务返回错误：$it" } ?: "翻译服务返回错误。"
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
    ): String = planRetrying(configuration, context, canRetry = { true }) { quick ->
        planCompletion.complete(direct, instructions, input, quick)
    }

    /**
     * [planText] for (role, text) [input], shown while it arrives (2.1.0). A
     * passing failure is retried only while nothing has been shown.
     */
    private suspend fun planStreaming(
        configuration: TranslationServiceConfiguration,
        direct: DirectAIConfiguration,
        instructions: String,
        input: List<Pair<String, String>>,
        context: ChatGPTPlanMessageContext,
        onPartial: (String) -> Unit
    ): ModelAnswer {
        var hasShownText = false
        val answer = planRetrying(configuration, context, canRetry = { !hasShownText }) { quick ->
            planCompletion.completeStreaming(direct, instructions, input, quick) { text ->
                hasShownText = true
                onPartial(text)
            }
        }
        return ModelAnswer(answer.text, answer.usage)
    }

    private suspend fun <T> planRetrying(
        configuration: TranslationServiceConfiguration,
        context: ChatGPTPlanMessageContext,
        canRetry: () -> Boolean,
        request: suspend (quick: Boolean) -> T
    ): T {
        val attempts = if (configuration.preferences.automaticRetryEnabled) 2 else 1
        val quick = context != ChatGPTPlanMessageContext.EXPLANATION
        var failure: ChatGPTPlanServiceFailure = ChatGPTPlanServiceFailure.Temporary()
        for (attempt in 0 until attempts) {
            if (attempt > 0) {
                if (!canRetry()) break
                delay(PLAN_RETRY_DELAY_MILLIS)
            }
            try {
                return request(quick)
            } catch (error: CancellationException) {
                throw error
            } catch (error: ChatGPTPlanServiceFailure) {
                failure = error
                if (!error.isTransient) break
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
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
    ): PreparedRequest = directRequest(direct, instructedMessages(prompt, text), quick, stream = false)

    /** [messages] in the format of [direct]; see [DirectAIRequests]. */
    private fun directRequest(
        direct: DirectAIConfiguration,
        messages: List<AIChatMessage>,
        quick: Boolean,
        stream: Boolean
    ): PreparedRequest = DirectAIRequests.prepared(direct, messages, quick, stream)

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

    /** A checked, complete answer and what it cost, when the provider said. */
    private data class ModelAnswer(val text: String, val usage: AIUsage? = null)

    private companion object {
        /** The pause before the ChatGPT plan's one retry. */
        const val PLAN_RETRY_DELAY_MILLIS = 450L

        /** The badge of the AI 代理 when its settings name no model (2.1.0). */
        const val PROXY_NAME = "AI 代理"

        /** The system translator's badge, which never gets 「含上下文」. */
        val ON_DEVICE_SOURCE = AIAnswerSource(modelName = "本机翻译", isAIModel = false)
    }
}
