package com.jerreader.android.translation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationToolSessionTest {
    @Test
    fun deadlineRestoresControlsBeforeAnUncooperativeProviderReturns() = runBlocking {
        val parent = CoroutineScope(coroutineContext + Job())
        val response = CompletableDeferred<String>()
        try {
            val session = TranslationToolSession(parent)
            val expired = CompletableDeferred<Unit>()
            var loading = true
            var displayed = ""
            val work = session.launch(
                timeoutMillis = 10,
                onTimeout = { expired.complete(Unit) },
                onFinished = { loading = false }
            ) {
                runCatchingActive { withContext(NonCancellable) { response.await() } }
                    .onSuccess { displayed = it }
            }
            withTimeout(2_000) { expired.await() }
            assertFalse(loading)
            response.complete("late response")
            work.join()
            assertEquals("", displayed)
        } finally {
            response.complete("cleanup")
            parent.cancel()
        }
    }

    @Test
    fun completingOrInvalidatingInputCancelsItsDeadline() = runBlocking {
        val parent = CoroutineScope(coroutineContext + Job())
        try {
            val session = TranslationToolSession(parent)
            var expired = 0
            session.launch(timeoutMillis = 20, onTimeout = { expired++ }) {}.join()
            session.launch(timeoutMillis = 20, onTimeout = { expired++ }) {
                CompletableDeferred<Unit>().await()
            }
            session.invalidate()
            delay(50)
            assertEquals(0, expired)
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun lateTranslationCannotReplaceNewInputResult() = runBlocking {
        val parent = CoroutineScope(coroutineContext + Job())
        try {
            val session = TranslationToolSession(parent)
            val response = CompletableDeferred<String>()
            var displayed = ""
            var staleFinished = false
            val old = session.launch(onFinished = { staleFinished = true }) {
                runCatchingActive {
                    // Models a provider that ignores cancellation while completing its callback.
                    withContext(NonCancellable) { response.await() }
                }.onSuccess { displayed = it }
            }
            yield()
            session.invalidate()
            session.launch { displayed = "new result" }.join()
            response.complete("old result")
            old.join()
            assertEquals("new result", displayed)
            assertFalse(staleFinished)
            assertTrue(old.isCancelled)
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun clearingResultDiscardsDictionaryAndAnalysisFailures() = runBlocking {
        val parent = CoroutineScope(coroutineContext + Job())
        try {
            val session = TranslationToolSession(parent)
            val response = CompletableDeferred<Unit>()
            val visibleErrors = mutableListOf<String>()
            val jobs = listOf("dictionary", "analysis").map { operation ->
                session.launch {
                    runCatchingActive {
                        withContext(NonCancellable) {
                            response.await()
                            throw IllegalStateException(operation)
                        }
                    }.onFailure { visibleErrors += it.message.orEmpty() }
                }
            }
            yield()
            session.invalidate()
            response.complete(Unit)
            jobs.forEach { it.join() }
            assertTrue(visibleErrors.isEmpty())
            assertTrue(jobs.all { it.isCancelled })
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun providerCancellationEndsLoadingWithoutShowingAnError() = runBlocking {
        val parent = CoroutineScope(coroutineContext + Job())
        try {
            val session = TranslationToolSession(parent)
            var loading = true
            var showedError = false
            session.launch(onFinished = { loading = false }) {
                runCatchingActive<String> { throw CancellationException("cancelled") }
                    .onFailure { showedError = true }
            }.join()
            assertFalse(loading)
            assertFalse(showedError)
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun currentFailureIsVisibleAndPageDisposalCancelsPendingWork() = runBlocking {
        val parent = CoroutineScope(coroutineContext + Job())
        val session = TranslationToolSession(parent)
        try {
            var error: String? = null
            session.launch {
                runCatchingActive<String> { throw IllegalStateException("offline") }
                    .onFailure { error = it.message }
            }.join()
            assertEquals("offline", error)
            val pending = session.launch { CompletableDeferred<Unit>().await() }
            yield()
            parent.cancel()
            pending.join()
            assertTrue(pending.isCancelled)
        } finally {
            parent.cancel()
        }
    }
}
