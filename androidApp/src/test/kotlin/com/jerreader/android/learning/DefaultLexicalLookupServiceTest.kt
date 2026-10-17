package com.jerreader.android.learning

import com.jerreader.android.lexical.DefaultLexicalLookupService
import com.jerreader.shared.domain.LanguageCode
import com.jerreader.shared.lexical.LexicalLookupFailure
import com.jerreader.shared.lexical.LexicalLookupService
import com.jerreader.shared.lexical.WordExplanation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DefaultLexicalLookupServiceTest {
    private val entry = WordExplanation(surfaceForm = "apple", lemma = "apple", reading = null,
        language = LanguageCode.ENGLISH, partOfSpeech = "n", definitions = listOf("苹果"),
        providerIdentifier = "test")
    private fun service(block: suspend () -> WordExplanation) = object : LexicalLookupService {
        override suspend fun lookup(word: String, sentenceContext: String?, language: LanguageCode,
                                    candidates: List<String>): WordExplanation = block()
    }

    @Test fun cancellationBetweenProvidersNeverStartsTheNextProvider() = runBlocking {
        for (cancelOffline in listOf(true, false)) {
            var remoteCalls = 0
            var bundledCalls = 0
            val chain = DefaultLexicalLookupService(
                offline = service {
                    if (cancelOffline) currentCoroutineContext().cancel()
                    throw LexicalLookupFailure.ResultNotFound
                }, bundled = service {
                    bundledCalls++
                    currentCoroutineContext().cancel()
                    throw LexicalLookupFailure.ResultNotFound
                }, remote = service { remoteCalls++; entry })
            val work = launch { chain.lookup("apple", null, LanguageCode.ENGLISH, emptyList()); fail("stale result") }
            work.join()
            assertTrue(work.isCancelled)
            assertEquals(if (cancelOffline) 0 else 1, bundledCalls)
            assertEquals(0, remoteCalls)
        }
    }

    @Test fun providerReturningAfterCancellationIsDiscarded() = runBlocking {
        var returned = false
        val stub = service { currentCoroutineContext().cancel(); entry }
        val chain = DefaultLexicalLookupService(offline = stub, bundled = stub, remote = stub)
        val work = launch { chain.lookup("apple", null, LanguageCode.ENGLISH, emptyList()); returned = true }
        work.join()
        assertTrue(work.isCancelled)
        assertFalse(returned)
    }

    @Test fun ordinaryMissStillFallsBackToTheRemoteDictionary() = runBlocking {
        var remoteCalls = 0
        val missing = service { throw LexicalLookupFailure.ResultNotFound }
        val chain = DefaultLexicalLookupService(missing, missing, service { remoteCalls++; entry })
        assertEquals(entry, chain.lookup("apple", null, LanguageCode.ENGLISH, emptyList()))
        assertEquals(1, remoteCalls)
    }
}
