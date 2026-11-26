package com.jerreader.android.reader

import com.jerreader.shared.domain.LanguageCode
import com.jerreader.shared.translation.StreamingTranslationService
import com.jerreader.shared.translation.TranslationFailure
import com.jerreader.shared.translation.TranslationRequest
import com.jerreader.shared.translation.TranslationResult
import com.jerreader.shared.ui.TranslationCardState
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tap translation card's streamed translation (2.1.0): partial text in
 * the success phase, one final state, and no late piece of a request the
 * card has moved on from.
 */
class TranslationCardStreamingTest {
    @Test fun partialTextKeepsTheSuccessPhaseUntilTheFinalState() = runBlocking<Unit> {
        val published = Collections.synchronizedList(mutableListOf<TranslationCardState>())
        val service = ScriptedService(listOf("她", "她回", "她回家"), gapMillis = 80, final = "她回家了。")
        val final = TranslationCardStreaming.translate(
            request = request("She went home."),
            service = service,
            isCurrent = { true },
            publish = { published += it },
            unavailableMessage = UNAVAILABLE,
            dispatcher = Dispatchers.Default
        )
        delay(150)
        val states = published.toList()
        assertTrue("got $final", final is TranslationCardState.Success && !final.isStreaming)
        assertEquals("她回家了。", (final as TranslationCardState.Success).result.translatedText)
        // Partial text first, all of it streaming, then the final state once and last.
        assertEquals(final, states.last())
        val partials = states.dropLast(1)
        assertTrue("no partial text was shown: $states", partials.isNotEmpty())
        partials.forEach { state ->
            assertTrue("$state", state is TranslationCardState.Success && state.isStreaming)
            state as TranslationCardState.Success
            assertTrue("$state", "她回家了。".startsWith(state.result.translatedText))
            assertEquals("She went home.", state.sourceText)
        }
        assertEquals(1, states.count { it is TranslationCardState.Success && !it.isStreaming })
    }

    @Test fun aClosedCardGetsNoLatePiece() = runBlocking<Unit> {
        val published = Collections.synchronizedList(mutableListOf<TranslationCardState>())
        val firstPiece = CompletableDeferred<Unit>()
        val current = AtomicBoolean(true)
        val service = ScriptedService(listOf("一", "一二", "一二三"), gapMillis = 80, final = "一二三四")
        val running = async {
            TranslationCardStreaming.translate(
                request = request("One two three four."),
                service = service,
                isCurrent = { current.get() },
                publish = {
                    published += it
                    firstPiece.complete(Unit)
                },
                unavailableMessage = UNAVAILABLE,
                intervalMillis = 10,
                dispatcher = Dispatchers.Default
            )
        }
        firstPiece.await()
        // The card is closed: whatever the request still sends must be dropped.
        current.set(false)
        val shownWhenClosed = published.size
        val final = running.await()
        delay(150)
        assertEquals(shownWhenClosed, published.size)
        assertTrue(published.all { it is TranslationCardState.Success && it.isStreaming })
        // The request still ends as it would have: only the card ignores it.
        assertTrue(final is TranslationCardState.Success && !final.isStreaming)
    }

    @Test fun aNewSentenceWinsOverTheOldOne() = runBlocking<Unit> {
        // The controllers' guard: a token that only the newest request holds.
        var requestToken = 0L
        val card = Collections.synchronizedList(mutableListOf<TranslationCardState>())
        val oldFirstPiece = CompletableDeferred<Unit>()

        val oldToken = ++requestToken
        val old = async {
            TranslationCardStreaming.translate(
                request = request("The old sentence."),
                service = ScriptedService(listOf("旧", "旧的", "旧的句"), gapMillis = 120, final = "旧的句子。"),
                isCurrent = { oldToken == requestToken },
                publish = {
                    if (oldToken == requestToken) card += it
                    oldFirstPiece.complete(Unit)
                },
                unavailableMessage = UNAVAILABLE,
                intervalMillis = 10,
                dispatcher = Dispatchers.Default
            )
        }
        oldFirstPiece.await()
        val newToken = ++requestToken
        val switchedAt = card.size
        val new = TranslationCardStreaming.translate(
            request = request("The new sentence."),
            service = ScriptedService(listOf("新"), gapMillis = 20, final = "新的句子。"),
            isCurrent = { newToken == requestToken },
            publish = { card += it },
            unavailableMessage = UNAVAILABLE,
            intervalMillis = 10,
            dispatcher = Dispatchers.Default
        )
        old.await()
        delay(200)
        val afterSwitch = card.toList().drop(switchedAt)
        assertTrue("the new card showed nothing", afterSwitch.isNotEmpty())
        assertTrue("an old piece reached the new card: $afterSwitch", afterSwitch.all { it.sourceText == "The new sentence." })
        assertEquals(new, card.last())
    }

    @Test fun aQuietTranslationTimesOut() = runBlocking<Unit> {
        val published = Collections.synchronizedList(mutableListOf<TranslationCardState>())
        val final = TranslationCardStreaming.translate(
            request = request("Silence."),
            service = HangingService(),
            isCurrent = { true },
            publish = { published += it },
            unavailableMessage = UNAVAILABLE,
            idleMillis = 150,
            totalMillis = 5_000,
            dispatcher = Dispatchers.Default
        )
        assertEquals(TranslationCardState.Failure("Silence.", TranslationCardStreaming.QUIET_MESSAGE), final)
        assertEquals(listOf(final), published.toList())
    }

    @Test fun aSteadyTranslationStopsAtTheTotalLimit() = runBlocking<Unit> {
        val pieces = (1..40).map { "字".repeat(it) }
        val final = TranslationCardStreaming.translate(
            request = request("A long one."),
            service = ScriptedService(pieces, gapMillis = 40, final = pieces.last()),
            isCurrent = { true },
            publish = {},
            unavailableMessage = UNAVAILABLE,
            idleMillis = 150,
            totalMillis = 400,
            intervalMillis = 10,
            dispatcher = Dispatchers.Default
        )
        assertEquals(TranslationCardState.Failure("A long one.", TranslationCardStreaming.TOO_LONG_MESSAGE), final)
    }

    @Test fun failuresKeepTheirMessages() = runBlocking<Unit> {
        val rejected = TranslationCardStreaming.translate(
            request = request("Rejected."),
            service = ScriptedService(listOf("半"), gapMillis = 10, failure = TranslationFailure.ProviderRejected("翻译服务返回错误：busy")),
            isCurrent = { true },
            publish = {},
            unavailableMessage = UNAVAILABLE,
            dispatcher = Dispatchers.Default
        )
        assertEquals(TranslationCardState.Failure("Rejected.", "翻译服务返回错误：busy", canRetry = true), rejected)

        val broken = TranslationCardStreaming.translate(
            request = request("Broken."),
            service = ScriptedService(emptyList(), gapMillis = 10, failure = IllegalStateException("boom")),
            isCurrent = { true },
            publish = {},
            unavailableMessage = UNAVAILABLE,
            dispatcher = Dispatchers.Default
        )
        assertEquals(TranslationCardState.Failure("Broken.", UNAVAILABLE), broken)
        assertFalse(broken is TranslationCardState.Success)
    }

    private fun request(text: String) = TranslationRequest(text, LanguageCode.ENGLISH)

    /** Sends [pieces] [gapMillis] apart, then answers [final] or fails with [failure]. */
    private class ScriptedService(
        private val pieces: List<String>,
        private val gapMillis: Long,
        private val final: String = pieces.lastOrNull().orEmpty(),
        private val failure: Exception? = null
    ) : StreamingTranslationService {
        override val identifier: String = "scripted"

        override suspend fun translate(request: TranslationRequest): TranslationResult = translateStreaming(request) {}

        override suspend fun translateStreaming(request: TranslationRequest, onPartial: (String) -> Unit): TranslationResult {
            pieces.forEach { piece ->
                delay(gapMillis)
                onPartial(piece)
            }
            delay(gapMillis)
            failure?.let { throw it }
            return TranslationResult(final, LanguageCode.ENGLISH, request.targetLanguage, identifier)
        }
    }

    private class HangingService : StreamingTranslationService {
        override val identifier: String = "hanging"

        override suspend fun translate(request: TranslationRequest): TranslationResult = awaitCancellation()

        override suspend fun translateStreaming(request: TranslationRequest, onPartial: (String) -> Unit): TranslationResult =
            awaitCancellation()
    }

    private companion object {
        const val UNAVAILABLE = "翻译服务暂时不可用，请稍后重试。"
    }
}
