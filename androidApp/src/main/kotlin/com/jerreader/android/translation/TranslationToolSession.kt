package com.jerreader.android.translation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** All work attached to the currently displayed input/result shares a lifetime. */
internal class TranslationToolSession(parent: CoroutineScope) {
    private val scope = CoroutineScope(parent.coroutineContext + Job(parent.coroutineContext[Job]))

    fun invalidate() = scope.coroutineContext.cancelChildren()

    fun launch(
        timeoutMillis: Long? = null,
        onTimeout: () -> Unit = {},
        onFinished: () -> Unit = {},
        block: suspend CoroutineScope.() -> Unit
    ): Job {
        val work = scope.launch {
            try {
                block()
            } finally {
                // An invalidated operation must not reset the next operation's spinner.
                if (isActive) onFinished()
            }
        }
        if (timeoutMillis != null) {
            val deadline = scope.launch {
                delay(timeoutMillis)
                if (work.isActive) {
                    work.cancel()
                    onTimeout()
                    onFinished()
                }
            }
            work.invokeOnCompletion { deadline.cancel() }
        }
        return work
    }
}

/** Some providers return even after cancellation; neither that result nor its error is current. */
internal suspend fun <T> runCatchingActive(block: suspend () -> T): Result<T> {
    currentCoroutineContext().ensureActive()
    return try {
        val value = block()
        currentCoroutineContext().ensureActive()
        Result.success(value)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        Result.failure(error)
    }
}
