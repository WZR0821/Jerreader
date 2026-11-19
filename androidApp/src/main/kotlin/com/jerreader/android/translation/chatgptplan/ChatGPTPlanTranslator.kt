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
 */
class ChatGPTPlanCompletion(private val session: ChatGPTPlanSession) {
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
        val status = response.status
        val usageLimit = ChatGPTPlanStream.usageLimitCode(response.body) != null
        when {
            status in 200..299 -> Unit
            status == 429 && usageLimit -> throw ChatGPTPlanServiceFailure.UsageLimitReached()
            status == 401 -> throw ChatGPTPlanServiceFailure.SignInRequired()
            status == 403 && usageLimit -> throw ChatGPTPlanServiceFailure.UsageLimitReached()
            status == 403 -> throw ChatGPTPlanServiceFailure.SignInRequired()
            status in REJECTED_STATUSES -> throw ChatGPTPlanServiceFailure.Rejected(status, response.body)
            else -> throw ChatGPTPlanServiceFailure.Temporary()
        }
        return when (val outcome = ChatGPTPlanStream.parse(response.body)) {
            is ChatGPTPlanStreamOutcome.Completed -> {
                val answer = outcome.text.trim()
                if (!TranslationInputPolicy.isVisiblyNonEmpty(answer)) throw ChatGPTPlanServiceFailure.ResultNotFound()
                answer
            }
            is ChatGPTPlanStreamOutcome.Failed -> throw if (ChatGPTPlanStream.isUsageLimit(outcome.code)) {
                ChatGPTPlanServiceFailure.UsageLimitReached()
            } else {
                ChatGPTPlanServiceFailure.Temporary()
            }
            is ChatGPTPlanStreamOutcome.Incomplete -> throw ChatGPTPlanServiceFailure.ResultNotFound()
            ChatGPTPlanStreamOutcome.Interrupted -> throw ChatGPTPlanServiceFailure.Temporary()
        }
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
        private val REJECTED_STATUSES = setOf(400, 404, 405, 415, 422)
    }
}
