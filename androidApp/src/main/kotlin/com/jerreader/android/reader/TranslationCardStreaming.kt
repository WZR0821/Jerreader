package com.jerreader.android.reader

import com.jerreader.android.translation.AIStreamTimeoutException
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanMessages
import com.jerreader.android.translation.streamAnswer
import com.jerreader.shared.domain.LanguageCode
import com.jerreader.shared.translation.AIStreamingPolicy
import com.jerreader.shared.translation.StandaloneLanguageDetector
import com.jerreader.shared.translation.TranslationFailure
import com.jerreader.shared.translation.TranslationRequest
import com.jerreader.shared.translation.TranslationResult
import com.jerreader.shared.translation.TranslationService
import com.jerreader.shared.translation.translateShowingPartials
import com.jerreader.shared.ui.TranslationCardState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The translation of a tap translation card, EPUB or PDF (2.1.0). The text
 * shows while the service writes it, as `Success(isStreaming = true)` states
 * of the same phase, so the card neither fades in again nor plays the success
 * haptic until the final state.
 */
internal object TranslationCardStreaming {
    const val QUIET_MESSAGE = "翻译超过 30 秒未返回，请重试或更换翻译服务。"
    const val TOO_LONG_MESSAGE = "翻译超过 3 分钟仍未完成，请重试或更换翻译服务。"

    /**
     * Translates [request] and returns its final state. [publish] gets each
     * state only while [isCurrent] says the request is still the card's: a
     * closed card, or a card that moved on to another sentence, never gets a
     * late piece of this one. The translation may go 30 seconds without new
     * text (before the first, that is the whole wait it had before streaming)
     * and three minutes in all.
     */
    suspend fun translate(
        request: TranslationRequest,
        service: TranslationService,
        isCurrent: () -> Boolean,
        publish: (TranslationCardState) -> Unit,
        unavailableMessage: String,
        idleMillis: Long = AIStreamingPolicy.TRANSLATION_IDLE_MILLIS,
        totalMillis: Long = AIStreamingPolicy.TOTAL_MILLIS,
        intervalMillis: Long = AIStreamingPolicy.PARTIAL_INTERVAL_MILLIS,
        // The service reaches disk and the network — ML Kit blocks the
        // calling thread while it prepares a model — so it must not run on
        // the main dispatcher, or the reader stops answering input.
        dispatcher: CoroutineDispatcher = Dispatchers.IO
    ): TranslationCardState {
        val state = try {
            val result = streamAnswer(
                idleMillis = idleMillis,
                onPartial = { text ->
                    if (isCurrent()) {
                        publish(
                            TranslationCardState.Success(
                                sourceText = request.text,
                                result = partialResult(request, service, text),
                                isStreaming = true,
                                context = request.context
                            )
                        )
                    }
                },
                totalMillis = totalMillis,
                intervalMillis = intervalMillis
            ) { emit ->
                withContext(dispatcher) { service.translateShowingPartials(request, emit) }
            }
            // The chips under the card send the same context.
            TranslationCardState.Success(request.text, result, context = request.context)
        } catch (timeout: AIStreamTimeoutException) {
            TranslationCardState.Failure(request.text, if (timeout.wentQuiet) QUIET_MESSAGE else TOO_LONG_MESSAGE)
        } catch (error: CancellationException) {
            throw error
        } catch (error: TranslationFailure) {
            TranslationCardState.Failure(
                sourceText = request.text,
                message = error.message ?: "翻译失败。",
                canRetry = !ChatGPTPlanMessages.isStop(error)
            )
        } catch (_: Exception) {
            TranslationCardState.Failure(request.text, unavailableMessage)
        }
        if (isCurrent()) publish(state)
        return state
    }

    /** The text so far, in the direction the request names; never cached, never saved. */
    private fun partialResult(request: TranslationRequest, service: TranslationService, text: String) = TranslationResult(
        translatedText = text,
        sourceLanguage = request.sourceLanguage
            ?: StandaloneLanguageDetector.detect(request.text)
            ?: LanguageCode.ENGLISH,
        targetLanguage = request.targetLanguage,
        providerIdentifier = service.identifier
    )
}
