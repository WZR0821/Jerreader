package com.jerreader.android.translation

import com.jerreader.shared.domain.LanguageCode
import com.jerreader.shared.lexical.LexicalLookupService
import com.jerreader.shared.lexical.WordExplanation
import com.jerreader.shared.translation.StandaloneTranslationInput
import com.jerreader.shared.translation.StandaloneTranslationMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class StandaloneWordLookupSessionTest {
    private val input = StandaloneTranslationInput("apple", LanguageCode.ENGLISH,
        LanguageCode.CHINESE_SIMPLIFIED, StandaloneTranslationMode.WORD)
    private val entry = WordExplanation(surfaceForm = "apple", lemma = "apple", reading = null,
        language = LanguageCode.ENGLISH, partOfSpeech = "n", definitions = listOf("苹果"),
        providerIdentifier = "offline-test")

    private fun service(block: suspend (String) -> WordExplanation) = object : LexicalLookupService {
        override suspend fun lookup(word: String, sentenceContext: String?, language: LanguageCode,
                                    candidates: List<String>): WordExplanation = block(word)
    }

    @Test fun localTranslationReusesItsEntryWithoutAnotherLookup() = runBlocking {
        val parent = CoroutineScope(coroutineContext + Job())
        try {
            var lookupCount = 0
            var saved: WordExplanation? = null
            val session = StandaloneWordLookupSession(parent, service { lookupCount++; entry }, { saved = it })
            val finished = CompletableDeferred<Unit>()
            var shown: WordExplanation? = null
            session.start(input, entry, { shown = it }, { fail(it) }, { finished.complete(Unit) })
            withTimeout(2_000) { finished.await() }
            assertEquals(0, lookupCount)
            assertEquals(entry, shown)
            assertEquals(entry, saved)
        } finally { parent.cancel() }
    }

    @Test fun unrelatedOrEmptyEntryCannotBeReused() = runBlocking {
        val parent = CoroutineScope(coroutineContext + Job())
        try {
            var calls = 0
            val session = StandaloneWordLookupSession(parent, service { calls++; entry }, {})
            for (stale in listOf(entry.copy(surfaceForm = "old"),
                entry.copy(language = LanguageCode.JAPANESE), entry.copy(definitions = listOf(" ")))) {
                val finished = CompletableDeferred<Unit>()
                session.start(input, stale, { assertEquals(entry, it) }, { fail(it) }, { finished.complete(Unit) })
                withTimeout(2_000) { finished.await() }
            }
            assertEquals(3, calls)
        } finally { parent.cancel() }
    }

    @Test fun failedHistorySaveRetainsEntryAndRetryDoesNotRepeatLookup() = runBlocking {
        val parent = CoroutineScope(coroutineContext + Job())
        try {
            var calls = 0
            var saves = 0
            var shown: WordExplanation? = null
            val issues = mutableListOf<String>()
            val session = StandaloneWordLookupSession(parent, service { calls++; entry }, {
                saves++
                if (saves == 1) error("disk unavailable")
            })
            repeat(2) {
                val finished = CompletableDeferred<Unit>()
                session.start(input, shown, { shown = it }, { issues += it }, { finished.complete(Unit) })
                withTimeout(2_000) { finished.await() }
            }
            assertEquals(entry, shown)
            assertEquals(1, calls)
            assertEquals(2, saves)
            assertEquals(1, issues.size)
            assertTrue(issues.single().contains("历史"))
        } finally { parent.cancel() }
    }

    @Test fun timeoutThenRetryDiscardsTheFirstProvidersLateResult() = runBlocking {
        val parent = CoroutineScope(coroutineContext + Job())
        val response = CompletableDeferred<WordExplanation>()
        try {
            var calls = 0
            val shown = mutableListOf<WordExplanation>()
            val saved = mutableListOf<WordExplanation>()
            val session = StandaloneWordLookupSession(parent, service {
                if (++calls == 1) withContext(NonCancellable) { response.await() } else entry
            }, { saved += it }, timeoutMillis = 10)
            val expired = CompletableDeferred<Unit>()
            session.start(input, null, { shown += it }, {}, { expired.complete(Unit) })
            withTimeout(2_000) { expired.await() }
            val retried = CompletableDeferred<Unit>()
            session.start(input, null, { shown += it }, { fail(it) }, { retried.complete(Unit) })
            withTimeout(2_000) { retried.await() }
            response.complete(entry.copy(definitions = listOf("stale")))
            parent.coroutineContext[Job]!!.children.toList().flatMap { it.children.toList() }.forEach { it.join() }
            assertEquals(listOf(entry), shown)
            assertEquals(listOf(entry), saved)
        } finally { response.complete(entry); parent.cancel() }
    }

    @Test fun changingInputDropsLateFailuresAndDoesNotResetTheNextSpinner() = runBlocking {
        val parent = CoroutineScope(coroutineContext + Job())
        val response = CompletableDeferred<Unit>()
        try {
            val session = StandaloneWordLookupSession(parent, service {
                withContext(NonCancellable) { response.await(); error("late failure") }
            }, {})
            var finished = false
            val issues = mutableListOf<String>()
            session.start(input, null, { fail("stale result") }, { issues += it }, { finished = true })
            yield()
            session.invalidate()
            response.complete(Unit)
            parent.coroutineContext[Job]!!.children.toList().flatMap { it.children.toList() }.forEach { it.join() }
            assertFalse(finished)
            assertTrue(issues.isEmpty())
        } finally { response.complete(Unit); parent.cancel() }
    }
}
