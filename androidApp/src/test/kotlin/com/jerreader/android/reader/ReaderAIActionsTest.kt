package com.jerreader.android.reader

import com.jerreader.shared.domain.LanguageCode
import com.jerreader.shared.translation.AIActionRequest
import com.jerreader.shared.translation.AIActionResult
import com.jerreader.shared.translation.AIAnswerSource
import com.jerreader.shared.translation.AIFollowUpTurn
import com.jerreader.shared.translation.AIReadingAction
import com.jerreader.shared.translation.AIReadingActionService
import com.jerreader.shared.translation.AIReadingLensMode
import com.jerreader.shared.translation.ContextExplanationRequest
import com.jerreader.shared.translation.ContextExplanationResult
import com.jerreader.shared.translation.ReadingLensOptions
import com.jerreader.shared.translation.StreamingContextExplanationService
import com.jerreader.shared.translation.TranslationRequest
import com.jerreader.shared.translation.TranslationResult
import com.jerreader.shared.translation.TranslationService
import com.jerreader.shared.ui.AIResultState
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The AI chips of the translation card, offline (2.1.0 §4–§5): an answer
 * streams in and stays per card and chip, a late piece never reaches a closed
 * or newer card, 停止 keeps the text, 追问 sends the answered questions before
 * it, and a quiet answer times out.
 */
class ReaderAIActionsTest {
    private val card = ReaderAIActions.Card(
        sourceText = "彼は駅へ向かった。",
        translation = "他朝车站走去。",
        context = "雨が降っていた。彼は駅へ向かった。",
        sourceLanguage = LanguageCode.JAPANESE
    )

    @Test fun anAnswerStreamsInAndStaysForItsCardAndChip() = runBlocking<Unit> {
        val service = FakeService()
        val actions = controller(service)
        actions.select(card, AIReadingAction.COMPREHENSION)
        val partial = awaitResult(actions) { it is AIResultState.Generating && it.text.isNotEmpty() }
        assertTrue(partial.text.startsWith("读懂"))
        service.release()
        val done = awaitResult(actions) { it is AIResultState.Done }
        assertEquals("读懂：彼は駅へ向かった。", done.text)
        assertEquals("Fake 模型", (done as AIResultState.Done).source?.modelName)
        // The lens request carries the card's context, as its translation did.
        val request = service.explanations.single()
        assertEquals(card.context, request.contextText)
        assertEquals(AIReadingLensMode.COMPREHENSION, request.lens?.mode)

        // Another chip and back: the finished answer is shown again, not asked again.
        actions.select(card, AIReadingAction.LITERAL)
        awaitResult(actions) { it is AIResultState.Done }
        actions.select(card, AIReadingAction.COMPREHENSION)
        assertEquals(done, actions.barState(card).result)
        assertEquals(1, service.explanations.size)

        // Again on the open chip closes it.
        actions.select(card, AIReadingAction.COMPREHENSION)
        assertNull(actions.barState(card).selected)
        assertNull(actions.barState(card).result)
    }

    @Test fun aLatePieceNeverReachesAClosedOrNewerCard() = runBlocking<Unit> {
        val service = FakeService()
        val actions = controller(service)
        actions.select(card, AIReadingAction.STRUCTURE)
        awaitResult(actions) { it is AIResultState.Generating && it.text.isNotEmpty() }

        // The card closes while the answer is still being written.
        actions.reset()
        service.release()
        delay(150)
        assertNull(actions.barState(card).selected)
        assertNull(actions.barState(card).result)

        // A new sentence gets only its own answer.
        val other = card.copy(sourceText = "彼女は笑った。", translation = "她笑了。")
        val fresh = FakeService()
        val next = controller(fresh)
        next.select(card, AIReadingAction.STRUCTURE)
        awaitResult(next) { it is AIResultState.Generating && it.text.isNotEmpty() }
        next.select(other, AIReadingAction.STRUCTURE)
        fresh.release()
        val done = awaitResult(next, other) { it is AIResultState.Done }
        assertEquals("结构：彼女は笑った。", done.text)
        // The first card's answer never landed anywhere.
        assertEquals(other, next.snapshots.value.card)
        assertEquals(mapOf(AIReadingAction.STRUCTURE to done), next.snapshots.value.results)
        assertNull(next.barState(card).result)
        assertNull(fresh.explanations.first().lens)
        // 结构 carries the card's context, as on iOS.
        assertEquals(card.context, fresh.explanations.first().contextText)
    }

    @Test fun stopKeepsTheTextAndCanAskAgain() = runBlocking<Unit> {
        val service = FakeService()
        val actions = controller(service)
        actions.select(card, AIReadingAction.LITERAL)
        val partial = awaitResult(actions) { it is AIResultState.Generating && it.text.isNotEmpty() }
        actions.stop()
        val stopped = actions.barState(card).result as AIResultState.Failed
        assertEquals(ReaderAIActions.STOPPED_MESSAGE, stopped.message)
        assertEquals(partial.text, stopped.text)
        assertTrue(stopped.canRetry)

        service.release()
        actions.retry(card)
        val done = awaitResult(actions) { it is AIResultState.Done }
        assertEquals("直译：彼は駅へ向かった。", done.text)
        assertEquals(2, service.actions.size)
    }

    @Test fun followUpsSendTheAnsweredQuestionsBeforeThem() = runBlocking<Unit> {
        val service = FakeService().apply { release() }
        val actions = controller(service)
        actions.select(card, AIReadingAction.FOLLOW_UP)
        assertEquals(listOf("主语是谁？", "这句话是什么语气？", "换个更自然的译法"), actions.barState(card).presets)
        actions.ask(card, "主语是谁？")
        awaitFollowUp(actions, 0)
        actions.ask(card, "  为什么用へ？  ")
        val second = awaitFollowUp(actions, 1)
        assertEquals("追问：为什么用へ？", second.text)

        val turns = service.actions.last().turns
        assertEquals(
            listOf(AIFollowUpTurn("主语是谁？", "追问：主语是谁？"), AIFollowUpTurn("为什么用へ？", null)),
            turns
        )
        assertEquals(card.context, service.actions.last().context)
        assertEquals(card.translation, service.actions.last().translation)
    }

    @Test fun aQuietAnswerTimesOutWithTheTextItHad() = runBlocking<Unit> {
        val service = FakeService()
        val actions = controller(service, idleMillis = 300)
        actions.select(card, AIReadingAction.REFERENCE)
        val failed = awaitResult(actions) { it is AIResultState.Failed } as AIResultState.Failed
        assertEquals(ReaderAIActions.QUIET_MESSAGE, failed.message)
        assertTrue(failed.text.startsWith("指代"))
        assertTrue(failed.canRetry)
    }

    private fun CoroutineScope.controller(service: FakeService, idleMillis: Long = 5_000) = ReaderAIActions(
        scope = this,
        service = { service },
        lensOptions = { mode -> ReadingLensOptions(mode) },
        dispatcher = Dispatchers.Default,
        idleMillis = idleMillis,
        totalMillis = 10_000,
        intervalMillis = 10
    )

    private suspend fun awaitResult(
        actions: ReaderAIActions,
        shownCard: ReaderAIActions.Card = card,
        condition: (AIResultState) -> Boolean
    ): AIResultState = withTimeout(5_000) {
        var found: AIResultState? = null
        while (found == null) {
            found = actions.barState(shownCard).result?.takeIf(condition)
            if (found == null) delay(10)
        }
        checkNotNull(found)
    }

    private suspend fun awaitFollowUp(actions: ReaderAIActions, index: Int): AIResultState = withTimeout(5_000) {
        var found: AIResultState? = null
        while (found == null) {
            found = actions.barState(card).followUps.getOrNull(index)?.answer?.takeIf { it is AIResultState.Done }
            if (found == null) delay(10)
        }
        checkNotNull(found)
    }

    /**
     * Writes "<chip>：" at once, then waits for [release] before the rest, so
     * a test can act while an answer is half written.
     */
    private class FakeService : TranslationService, StreamingContextExplanationService, AIReadingActionService {
        private val gate = CompletableDeferred<Unit>()
        val explanations: MutableList<ContextExplanationRequest> = Collections.synchronizedList(mutableListOf())
        val actions: MutableList<AIActionRequest> = Collections.synchronizedList(mutableListOf())
        private val source = AIAnswerSource("Fake 模型", isAIModel = true)

        fun release() {
            gate.complete(Unit)
        }

        override val identifier: String = "fake"

        override suspend fun translate(request: TranslationRequest): TranslationResult =
            TranslationResult(request.text, LanguageCode.JAPANESE, request.targetLanguage, identifier)

        override suspend fun explain(request: ContextExplanationRequest): ContextExplanationResult = explainStreaming(request) {}

        override suspend fun explainStreaming(
            request: ContextExplanationRequest,
            onPartial: (String) -> Unit
        ): ContextExplanationResult {
            explanations += request
            val title = when (request.lens?.mode) {
                null -> "结构"
                AIReadingLensMode.COMPREHENSION -> "读懂"
                AIReadingLensMode.REFERENCE -> "指代"
                else -> "透镜"
            }
            val text = written("$title：", request.focusedText, onPartial)
            return ContextExplanationResult(text, identifier, answerSource = source)
        }

        override suspend fun perform(request: AIActionRequest, onPartial: (String) -> Unit): AIActionResult {
            actions += request
            val text = when (request.action) {
                AIReadingAction.FOLLOW_UP -> written("追问：", request.turns.last().question, onPartial)
                else -> written("直译：", request.source, onPartial)
            }
            return AIActionResult(text, source)
        }

        private suspend fun written(head: String, rest: String, onPartial: (String) -> Unit): String {
            onPartial(head)
            gate.await()
            onPartial(head + rest)
            return head + rest
        }
    }
}
