package com.jerreader.android.translation.chatgptplan

import com.jerreader.android.translation.AIAPIStyle
import com.jerreader.android.translation.AIRequestTuning
import com.jerreader.android.translation.AIStreamDecoder
import com.jerreader.android.translation.AIStreamOutcome
import com.jerreader.android.translation.DirectAIConfiguration
import com.jerreader.shared.translation.AIUsage
import com.jerreader.shared.translation.TranslationInputPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A streamed answer of the plan (2.1.0) and what it cost, when OpenAI said. */
data class ChatGPTPlanAnswer(val text: String, val usage: AIUsage? = null)

/**
 * One request paid by a ChatGPT plan: a current access token, one retry
 * with a fresh token when it is refused, and success only on
 * `response.completed`, so a cut-off answer is never shown or cached.
 * Throws [ChatGPTPlanServiceFailure]; the caller tells it in its own words.
 * [onConsentNeeded] runs when OpenAI says the sign-in lacks the plan's grant,
 * so the next sign-in shows the consent page again.
 */
class ChatGPTPlanCompletion(
    private val session: ChatGPTPlanSession,
    private val onConsentNeeded: () -> Unit = {}
) {
    suspend fun complete(
        direct: DirectAIConfiguration,
        instructions: String,
        input: String,
        quick: Boolean
    ): String {
        val timeout = if (quick) QUICK_TIMEOUT_MILLIS else EXPLANATION_TIMEOUT_MILLIS
        val extra = AIRequestTuning.extraFields(direct.kind, AIAPIStyle.RESPONSES, direct.model, direct.reasoning, quick)
        var token = accessToken(rejected = null)
        var response = send(ChatGPTPlanRequests.responses(token, direct.model, instructions, input, extra, timeout))
        if (response.status == 401) {
            token = accessToken(rejected = token)
            response = send(ChatGPTPlanRequests.responses(token, direct.model, instructions, input, extra, timeout))
        }
        if (response.status !in 200..299) {
            if (ChatGPTPlanErrorBody.parse(response.body).needsConsent) onConsentNeeded()
            throw failure(ChatGPTPlanRefusal.classify(response.status, response.body, response.requestID))
        }
        return when (val outcome = ChatGPTPlanStream.parse(response.body)) {
            is ChatGPTPlanStreamOutcome.Completed -> {
                val answer = outcome.text.trim()
                if (!TranslationInputPolicy.isVisiblyNonEmpty(answer)) throw ChatGPTPlanServiceFailure.ResultNotFound()
                answer
            }
            is ChatGPTPlanStreamOutcome.Failed ->
                throw failure(ChatGPTPlanRefusal.streamFailure(outcome.code, outcome.message))
            is ChatGPTPlanStreamOutcome.Incomplete -> throw ChatGPTPlanServiceFailure.ResultNotFound()
            ChatGPTPlanStreamOutcome.Interrupted -> throw ChatGPTPlanServiceFailure.Temporary()
        }
    }

    /**
     * [complete] for a conversation, with the text shown while it arrives
     * (2.1.0). [input] is (role, text) pairs, oldest first. [onPartial] gets the
     * whole visible text so far; the answer still counts only once
     * `response.completed` arrives, so a cut-off text is shown but never
     * returned or cached.
     */
    suspend fun completeStreaming(
        direct: DirectAIConfiguration,
        instructions: String,
        input: List<Pair<String, String>>,
        quick: Boolean,
        onPartial: (String) -> Unit
    ): ChatGPTPlanAnswer {
        // Longer than the callers' own quiet limits (30 s and 70 s), so theirs
        // decide, as for a model called directly; a slow reasoning model used
        // to hit the 60 s read timeout first and fail as a passing error.
        val timeout = STREAM_READ_TIMEOUT_MILLIS
        val extra = AIRequestTuning.extraFields(direct.kind, AIAPIStyle.RESPONSES, direct.model, direct.reasoning, quick)
        suspend fun stream(token: String): Pair<ChatGPTPlanHttpResponse, AIStreamDecoder> {
            val decoder = AIStreamDecoder(AIAPIStyle.RESPONSES)
            val request = ChatGPTPlanRequests.responses(token, direct.model, instructions, input, extra, timeout)
            val response = sendStreaming(request) { line -> if (decoder.feed(line)) onPartial(decoder.text) }
            return response to decoder
        }
        var token = accessToken(rejected = null)
        var (response, decoder) = stream(token)
        if (response.status == 401) {
            token = accessToken(rejected = token)
            val again = stream(token)
            response = again.first
            decoder = again.second
        }
        if (response.status !in 200..299) {
            if (ChatGPTPlanErrorBody.parse(response.body).needsConsent) onConsentNeeded()
            throw failure(ChatGPTPlanRefusal.classify(response.status, response.body, response.requestID))
        }
        return when (val outcome = decoder.finish()) {
            AIStreamOutcome.Completed -> {
                val answer = decoder.answer.trim()
                if (!TranslationInputPolicy.isVisiblyNonEmpty(answer)) throw ChatGPTPlanServiceFailure.ResultNotFound()
                ChatGPTPlanAnswer(answer, decoder.usage)
            }
            is AIStreamOutcome.Failed -> throw failure(ChatGPTPlanRefusal.streamFailure(outcome.code, outcome.message))
            is AIStreamOutcome.Incomplete -> throw ChatGPTPlanServiceFailure.ResultNotFound()
            AIStreamOutcome.Interrupted -> throw ChatGPTPlanServiceFailure.Temporary()
        }
    }

    private fun failure(refusal: ChatGPTPlanRefusal): ChatGPTPlanServiceFailure = when (refusal) {
        ChatGPTPlanRefusal.SignInRequired -> ChatGPTPlanServiceFailure.SignInRequired()
        ChatGPTPlanRefusal.UsageLimit -> ChatGPTPlanServiceFailure.UsageLimitReached()
        ChatGPTPlanRefusal.Temporary -> ChatGPTPlanServiceFailure.Temporary()
        is ChatGPTPlanRefusal.Rejected -> ChatGPTPlanServiceFailure.Rejected(refusal.message)
    }

    private suspend fun accessToken(rejected: String?): String = try {
        session.accessToken(rejected)
    } catch (error: CancellationException) {
        throw error
    } catch (_: ChatGPTPlanError.SignInRequired) {
        throw ChatGPTPlanServiceFailure.SignInRequired()
    } catch (_: Exception) {
        // A token refresh cancelled mid-request fails its read the same way.
        currentCoroutineContext().ensureActive()
        throw ChatGPTPlanServiceFailure.Temporary()
    }

    private suspend fun send(request: ChatGPTPlanHttpRequest): ChatGPTPlanHttpResponse = try {
        session.client.send(request)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        // A cancelled request fails its read when its connection is closed.
        currentCoroutineContext().ensureActive()
        throw ChatGPTPlanServiceFailure.Temporary()
    }

    private suspend fun sendStreaming(
        request: ChatGPTPlanHttpRequest,
        onLine: (String) -> Unit
    ): ChatGPTPlanHttpResponse = try {
        session.client.sendStreaming(request, onLine)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        // A cancelled request fails its read when its connection is closed.
        currentCoroutineContext().ensureActive()
        throw ChatGPTPlanServiceFailure.Temporary()
    }

    companion object {
        const val QUICK_TIMEOUT_MILLIS = 30_000
        const val EXPLANATION_TIMEOUT_MILLIS = 60_000
        const val STREAM_READ_TIMEOUT_MILLIS = 80_000
    }
}
