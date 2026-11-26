package com.jerreader.android.reader

import com.jerreader.android.translation.AIStreamTimeoutException
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanMessages
import com.jerreader.android.translation.streamAnswer
import com.jerreader.shared.domain.LanguageCode
import com.jerreader.shared.translation.AIActionRequest
import com.jerreader.shared.translation.AIAnswerSource
import com.jerreader.shared.translation.AIFollowUpTurn
import com.jerreader.shared.translation.AIReadingAction
import com.jerreader.shared.translation.AIReadingActionPolicy
import com.jerreader.shared.translation.AIReadingActionService
import com.jerreader.shared.translation.AIReadingLensMode
import com.jerreader.shared.translation.AIStreamingPolicy
import com.jerreader.shared.translation.ContextExplanationRequest
import com.jerreader.shared.translation.ContextExplanationService
import com.jerreader.shared.translation.ReadingLensOptions
import com.jerreader.shared.translation.TranslationFailure
import com.jerreader.shared.translation.TranslationService
import com.jerreader.shared.translation.explainShowingPartials
import com.jerreader.shared.ui.AIFollowUpEntry
import com.jerreader.shared.ui.AIResultState
import com.jerreader.shared.ui.TranslationAIBarState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The AI chips of the reader's translation card (2.1.0 §4–§5): 结构 is the
 * sentence-structure analysis, 读懂 and 指代 the reading lens, 直译 and 追问
 * the reading actions of the service; 查词 opens the lookup sheet and never
 * comes here.
 *
 * One chip's answer is open at a time. Choosing another cancels the answer
 * still being written; finished answers stay, per chip, until the card closes
 * or shows another sentence. 追问 keeps its conversation for the card.
 *
 * Everything here runs on [scope]'s thread, the main thread in the reader;
 * only the service call moves to [dispatcher]. Each partial or final text is
 * written only while its request is still the newest one, so a closed card,
 * another sentence or another chip never gets a late piece of an old answer.
 */
internal class ReaderAIActions(
    private val scope: CoroutineScope,
    private val service: () -> TranslationService,
    private val lensOptions: suspend (AIReadingLensMode) -> ReadingLensOptions,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val idleMillis: Long = AIStreamingPolicy.AI_IDLE_MILLIS,
    private val totalMillis: Long = AIStreamingPolicy.TOTAL_MILLIS,
    private val intervalMillis: Long = AIStreamingPolicy.PARTIAL_INTERVAL_MILLIS
) {
    /** The sentence of one translation card, as its chips ask about it. */
    data class Card(
        val sourceText: String,
        val translation: String,
        /** The text around the sentence that went with its translation. */
        val context: String?,
        val sourceLanguage: LanguageCode?
    )

    /** What the chips of [card] have answered so far. */
    data class Snapshot(
        val card: Card? = null,
        val selected: AIReadingAction? = null,
        val results: Map<AIReadingAction, AIResultState> = emptyMap(),
        val followUps: List<AIFollowUpEntry> = emptyList()
    )

    private val current = MutableStateFlow(Snapshot())

    /** Collect this to redraw the bar; [barState] turns it into what the bar shows. */
    val snapshots: StateFlow<Snapshot> = current.asStateFlow()

    private var running: Job? = null
    private var generation = 0L

    /** The bar of [card]; a card the chips have not answered for shows them all closed. */
    fun barState(
        card: Card,
        snapshot: Snapshot = current.value,
        noteUnavailableReason: String? = null
    ): TranslationAIBarState {
        val shown = snapshot.takeIf { it.card == card } ?: Snapshot(card)
        val selected = shown.selected
        return TranslationAIBarState(
            actions = AIReadingActionPolicy.actions(card.sourceLanguage),
            selected = selected,
            result = selected?.let(shown.results::get),
            followUps = shown.followUps,
            presets = AIReadingActionPolicy.presets(card.sourceLanguage),
            noteUnavailableReason = noteUnavailableReason
        )
    }

    /** Opens the answer of [action], or closes it when it is the open one. */
    fun select(card: Card, action: AIReadingAction) {
        if (!action.needsAI) return
        adopt(card)
        if (current.value.selected == action) {
            interrupt()
            current.value = current.value.copy(selected = null)
            return
        }
        interrupt()
        current.value = current.value.copy(selected = action)
        if (action == AIReadingAction.FOLLOW_UP) return
        if (current.value.results[action] is AIResultState.Done) return
        start(card, action)
    }

    /** Asks [question] about the card's sentence, after the questions already answered. */
    fun ask(card: Card, question: String) {
        val asked = question.trim()
        if (asked.isEmpty()) return
        adopt(card)
        interrupt()
        current.value = current.value.let { snapshot ->
            snapshot.copy(
                selected = AIReadingAction.FOLLOW_UP,
                // Only the newest question can be asked again.
                followUps = snapshot.followUps.map { entry -> entry.withoutRetry() } +
                    AIFollowUpEntry(asked, AIResultState.Generating())
            )
        }
        startFollowUp(card)
    }

    /** 停止: the answer keeps the text it has and can be asked again. */
    fun stop() {
        cancel()
        current.value = current.value.let { snapshot ->
            snapshot.copy(
                results = snapshot.results.mapValues { (_, result) -> result.stopped() },
                followUps = snapshot.followUps.map { entry -> entry.copy(answer = entry.answer.stopped()) }
            )
        }
    }

    /** 重试 of the open chip, or of the newest 追问 when it failed. */
    fun retry(card: Card) {
        if (current.value.card != card) return
        val selected = current.value.selected ?: return
        when (selected) {
            AIReadingAction.LOOKUP -> return
            AIReadingAction.FOLLOW_UP -> {
                val last = current.value.followUps.lastOrNull() ?: return
                if (last.answer !is AIResultState.Failed) return
                interrupt()
                current.value = current.value.let { snapshot ->
                    snapshot.copy(followUps = snapshot.followUps.dropLast(1) + last.copy(answer = AIResultState.Generating()))
                }
                startFollowUp(card)
            }
            else -> {
                interrupt()
                start(card, selected)
            }
        }
    }

    /** The card closed or moved on: every answer goes, and late ones are dropped. */
    fun reset() {
        cancel()
        current.value = Snapshot()
    }

    private fun adopt(card: Card) {
        if (current.value.card == card) return
        cancel()
        current.value = Snapshot(card)
    }

    private fun cancel() {
        running?.cancel()
        running = null
        generation += 1
    }

    /**
     * Another chip or question takes over: an unfinished chip answer is
     * dropped, so coming back asks again; an unfinished 追问 stays as stopped.
     */
    private fun interrupt() {
        cancel()
        current.value = current.value.let { snapshot ->
            snapshot.copy(
                results = snapshot.results.filterValues { it !is AIResultState.Generating },
                followUps = snapshot.followUps.map { entry -> entry.copy(answer = entry.answer.stopped()) }
            )
        }
    }

    private fun start(card: Card, action: AIReadingAction) {
        current.value = current.value.let { snapshot ->
            snapshot.copy(results = snapshot.results + (action to AIResultState.Generating()))
        }
        launchAnswer(
            write = { snapshot, result -> snapshot.copy(results = snapshot.results + (action to result)) },
            prepare = { chipQuestion(card, action) }
        )
    }

    /** The newest question, with the answered ones before it as the conversation. */
    private fun startFollowUp(card: Card) {
        val entries = current.value.followUps
        val index = entries.lastIndex
        if (index < 0) return
        val turns = entries.dropLast(1).mapNotNull { entry ->
            (entry.answer as? AIResultState.Done)?.let { done -> AIFollowUpTurn(entry.question, done.text) }
        } + AIFollowUpTurn(entries[index].question, null)
        val request = AIActionRequest(
            action = AIReadingAction.FOLLOW_UP,
            source = card.sourceText,
            translation = card.translation,
            context = card.context,
            sourceLanguage = card.sourceLanguage,
            turns = turns
        )
        launchAnswer(
            write = { snapshot, result ->
                snapshot.copy(
                    followUps = snapshot.followUps.mapIndexed { position, entry ->
                        if (position == index) entry.copy(answer = result) else entry
                    }
                )
            },
            prepare = { performing(request) }
        )
    }

    /**
     * Sends the question [prepare] gives and writes its answer with [write]:
     * the text so far while it arrives, then the whole answer or the failure.
     */
    private fun launchAnswer(
        write: (Snapshot, AIResultState) -> Snapshot,
        prepare: suspend () -> (suspend ((String) -> Unit) -> Answer)
    ) {
        val id = ++generation
        fun publish(result: AIResultState) {
            if (id == generation) current.value = write(current.value, result)
        }
        running = scope.launch {
            var shown = ""
            val outcome = try {
                val question = prepare()
                val answer = streamAnswer(
                    idleMillis = idleMillis,
                    onPartial = { text ->
                        shown = text
                        publish(AIResultState.Generating(text))
                    },
                    totalMillis = totalMillis,
                    intervalMillis = intervalMillis
                ) { emit -> withContext(dispatcher) { question(emit) } }
                val text = answer.text.trim()
                if (text.isEmpty()) AIResultState.Failed(UNAVAILABLE_MESSAGE, shown) else AIResultState.Done(text, answer.source)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure(error, shown)
            }
            publish(outcome)
            if (id == generation) running = null
        }
    }

    private suspend fun chipQuestion(card: Card, action: AIReadingAction): suspend ((String) -> Unit) -> Answer = when (action) {
        // 结构 sends the card's context as iOS does, so its badge says 含上下文
        // like the other chips' (2.1.0 §6).
        AIReadingAction.STRUCTURE -> explaining(ContextExplanationRequest(card.sourceText, card.context, card.sourceLanguage))
        AIReadingAction.COMPREHENSION -> explaining(lensRequest(card, AIReadingLensMode.COMPREHENSION))
        AIReadingAction.REFERENCE -> explaining(lensRequest(card, AIReadingLensMode.REFERENCE))
        AIReadingAction.LITERAL -> performing(
            AIActionRequest(
                action = AIReadingAction.LITERAL,
                source = card.sourceText,
                translation = card.translation,
                context = card.context,
                sourceLanguage = card.sourceLanguage
            )
        )
        AIReadingAction.LOOKUP, AIReadingAction.FOLLOW_UP -> error("${action.id} has no answer of its own")
    }

    private suspend fun lensRequest(card: Card, mode: AIReadingLensMode) = ContextExplanationRequest(
        focusedText = card.sourceText,
        contextText = card.context,
        sourceLanguage = card.sourceLanguage,
        lens = lensOptions(mode)
    )

    /** The question, ready to send: it hands each longer text so far to its `emit`. */
    private fun explaining(request: ContextExplanationRequest): suspend ((String) -> Unit) -> Answer {
        val explainer = service() as? ContextExplanationService ?: error("The translation service cannot explain")
        return { emit ->
            val result = explainer.explainShowingPartials(request, emit)
            Answer(result.explanation, result.answerSource)
        }
    }

    private fun performing(request: AIActionRequest): suspend ((String) -> Unit) -> Answer {
        val performer = service() as? AIReadingActionService ?: error("The translation service has no reading actions")
        return { emit ->
            val result = performer.perform(request, emit)
            Answer(result.text, result.answerSource)
        }
    }

    private fun failure(error: Exception, shown: String): AIResultState.Failed = when (error) {
        is AIStreamTimeoutException -> AIResultState.Failed(if (error.wentQuiet) QUIET_MESSAGE else TOO_LONG_MESSAGE, shown)
        is TranslationFailure.ServiceUnavailable -> AIResultState.Failed(UNAVAILABLE_MESSAGE, shown)
        is TranslationFailure -> AIResultState.Failed(
            message = error.message?.takeIf(String::isNotBlank) ?: UNAVAILABLE_MESSAGE,
            text = shown,
            // A ChatGPT plan out of quota, signed out or refused: asking again cannot help.
            canRetry = !ChatGPTPlanMessages.isStop(error)
        )
        else -> AIResultState.Failed(UNAVAILABLE_MESSAGE, shown)
    }

    private fun AIResultState.stopped(): AIResultState =
        if (this is AIResultState.Generating) AIResultState.Failed(STOPPED_MESSAGE, text) else this

    private fun AIFollowUpEntry.withoutRetry(): AIFollowUpEntry {
        val failed = answer as? AIResultState.Failed ?: return this
        return copy(answer = failed.copy(canRetry = false))
    }

    private class Answer(val text: String, val source: AIAnswerSource?)

    companion object {
        const val STOPPED_MESSAGE = "已停止生成。"
        const val QUIET_MESSAGE = "AI 超过 70 秒未返回，请重试或更换 AI 服务。"
        const val TOO_LONG_MESSAGE = "AI 回答超过 3 分钟仍未完成，请重试。"
        const val UNAVAILABLE_MESSAGE = "AI 暂时不可用，请稍后重试。"
    }
}
