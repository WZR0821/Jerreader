package com.jerreader.android.translation.chatgptplan

import com.jerreader.android.translation.AIAPIStyle
import com.jerreader.android.translation.AIRequestTuning
import com.jerreader.android.translation.DirectAIConfiguration
import com.jerreader.shared.translation.TranslationInputPolicy
import kotlinx.coroutines.CancellationException

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
        throw ChatGPTPlanServiceFailure.Temporary()
    }

    private suspend fun send(request: ChatGPTPlanHttpRequest): ChatGPTPlanHttpResponse = try {
        session.client.send(request)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        throw ChatGPTPlanServiceFailure.Temporary()
    }

    companion object {
        const val QUICK_TIMEOUT_MILLIS = 30_000
        const val EXPLANATION_TIMEOUT_MILLIS = 60_000
    }
}
