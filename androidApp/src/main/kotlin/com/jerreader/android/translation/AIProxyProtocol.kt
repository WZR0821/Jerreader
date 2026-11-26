package com.jerreader.android.translation

import com.jerreader.shared.domain.LanguageCode
import com.jerreader.shared.translation.AIReadingLensMode
import com.jerreader.shared.translation.ContextExplanationRequest
import java.security.MessageDigest
import java.text.Normalizer
import org.json.JSONObject

/**
 * What the AI 代理 service sends to the reader's own HTTPS proxy, and where it
 * finds the answer: the requests iOS sends (BackendTranslationService.swift),
 * so one proxy serves both apps. README「AI 代理协议」 describes them for
 * whoever writes the proxy.
 */
internal object AIProxyProtocol {
    const val VERSION_HEADER = "X-Jerreader-Translation-Protocol"
    const val IDEMPOTENCY_HEADER = "X-Jerreader-Idempotency-Key"

    /**
     * A translation, with a key that is the same whenever the request is.
     * [context] is the text around the sentence (2.1.0), left out when there
     * is none, as iOS does; it takes part in the key.
     */
    fun translation(
        configuration: TranslationServiceConfiguration,
        text: String,
        source: LanguageCode,
        target: LanguageCode,
        prompt: String,
        context: String? = null
    ): PreparedRequest {
        val sentContext = context?.let(::normalized)?.takeIf { it.isNotEmpty() && it != normalized(text) }
        val body = JSONObject()
            .put("text", text)
            .put("sourceLanguage", source.tag)
            .put("targetLanguage", target.tag)
            .put("prompt", prompt)
        sentContext?.let { body.put("context", it) }
        model(configuration)?.let { body.put("model", it) }
        val headers = headers(configuration.credential) +
            (IDEMPOTENCY_HEADER to idempotencyKey(configuration, text, source, target, sentContext))
        return PreparedRequest(configuration.endpoint, headers, body.toString())
    }

    /**
     * One AI action of the translation card (直译, 追问) for a proxy that takes
     * a single turn: the whole conversation flattened into `text`, the
     * instruction in `prompt`. No idempotency key, as for an explanation.
     */
    fun action(
        configuration: TranslationServiceConfiguration,
        actionId: String,
        flattened: String,
        source: LanguageCode,
        prompt: String
    ): PreparedRequest {
        val body = JSONObject()
            .put("task", "explain")
            .put("analysisMode", actionId)
            .put("text", flattened)
            .put("sourceLanguage", source.tag)
            .put("responseLanguage", LanguageCode.CHINESE_SIMPLIFIED.tag)
            .put("prompt", prompt)
        model(configuration)?.let { body.put("model", it) }
        return PreparedRequest(configuration.endpoint, headers(configuration.credential), body.toString())
    }

    /**
     * An explanation. The selection goes alone in `text` and what helps to
     * read it in `context`, where a model called directly gets both in one
     * message. iOS sends no idempotency key here either.
     */
    fun explanation(
        configuration: TranslationServiceConfiguration,
        request: ContextExplanationRequest,
        focused: String,
        source: LanguageCode,
        prompt: String
    ): PreparedRequest {
        val body = JSONObject()
            .put("task", "explain")
            .put("analysisMode", (request.lens?.mode ?: AIReadingLensMode.GRAMMAR).storageId)
            .put("text", focused)
            .put("sourceLanguage", source.tag)
            .put("responseLanguage", LanguageCode.CHINESE_SIMPLIFIED.tag)
            .put("prompt", prompt)
        explanationContext(request, focused)?.let { body.put("context", it) }
        model(configuration)?.let { body.put("model", it) }
        return PreparedRequest(configuration.endpoint, headers(configuration.credential), body.toString())
    }

    /** The translation, from the fields iOS reads it from. */
    fun translatedText(body: String): String =
        firstText(JSONObject(body), "translatedText", "translation")

    /** The explanation, from the fields iOS reads it from, in its order. */
    fun explanationText(body: String): String =
        firstText(JSONObject(body), "explanation", "answer", "translatedText", "translation")

    /** Only text counts: a null is passed over, as on iOS, and so is anything else that is not text. */
    private fun firstText(json: JSONObject, vararg fields: String): String =
        fields.firstNotNullOfOrNull { field ->
            (json.opt(field) as? String)?.takeIf(String::isNotBlank)
        }.orEmpty()

    private fun headers(credential: String?): Map<String, String> = buildMap {
        put("Content-Type", "application/json; charset=utf-8")
        put("Accept", "application/json")
        put(VERSION_HEADER, "1")
        credential?.trim()?.takeIf(String::isNotEmpty)?.let { put("Authorization", "Bearer $it") }
    }

    /** The model the proxy settings name, sent as `model` and shown on the source badge (2.1.0). */
    fun model(configuration: TranslationServiceConfiguration): String? =
        configuration.model.trim().takeIf(String::isNotEmpty)

    /**
     * The nearby text, then for a word lookup the dictionary's candidates, as
     * iOS joins them; each is cut to the length a model called directly gets.
     */
    private fun explanationContext(request: ContextExplanationRequest, focused: String): String? =
        listOfNotNull(
            request.contextText?.trim()?.takeIf { it.isNotEmpty() && it != focused }?.take(2_000),
            request.dictionaryEntry?.trim()?.takeIf { request.isWordLookup && it.isNotEmpty() }
                ?.let { "词典候选资料（仅供参考，若不适合原句请指出）：\n" + it.take(1_000) }
        ).joinToString("\n\n").ifEmpty { null }

    /**
     * Made the way iOS makes it (`TranslationCacheStore.cacheKey` over
     * `BackendTranslationService.providerVersion`): the same text, languages
     * and proxy settings give the same key, so the proxy can answer a retry
     * with what the first attempt got. The access token takes no part.
     */
    private fun idempotencyKey(
        configuration: TranslationServiceConfiguration,
        text: String,
        source: LanguageCode,
        target: LanguageCode,
        context: String?
    ): String {
        val providerVersion = sha256(
            listOf(
                configuration.endpoint,
                model(configuration) ?: "default",
                configuration.preferences.translationPromptTemplate,
                configuration.preferences.grammarAnalysisPromptTemplate,
                "jerreader-proxy-v1"
            ).joinToString(SEPARATOR)
        ).take(24)
        // iOS adds the context as one more part, only when there is one.
        return sha256(
            (listOf("ai-backend-proxy", providerVersion, source.tag, target.tag, normalized(text)) +
                listOfNotNull(context?.let { "context:$it" }))
                .joinToString(SEPARATOR)
        )
    }

    /** iOS's form of the text before it hashes: each run of whitespace one space. */
    private fun normalized(text: String): String =
        Normalizer.normalize(text.replace(WHITESPACE, " ").trim(), Normalizer.Form.NFC)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }

    private const val SEPARATOR = "\u001F"

    /** What `\s` matches in the ICU expressions iOS uses. */
    private val WHITESPACE = Regex("[\\t\\n\\f\\r\\p{Z}]+")
}
