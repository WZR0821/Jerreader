package com.jerreader.android.translation

import com.jerreader.shared.translation.AIStreamingPolicy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * A streamed answer went [AIStreamingPolicy] too long without new text, or
 * ran past the total limit (2.1.0). Not a cancellation: the request was
 * stopped by its own watchdog, and the card says so.
 */
class AIStreamTimeoutException(
    /** False when the answer kept coming but ran past the total limit. */
    val wentQuiet: Boolean
) : Exception(if (wentQuiet) "The answer went quiet." else "The answer took too long.")

/**
 * Runs [block], which calls its `touch` whenever new text arrives. Fails with
 * [AIStreamTimeoutException] once [idleMillis] pass without new text, counted
 * from the start until the first text, so that a service that cannot stream
 * keeps the limit it had, or once [totalMillis] have passed in all.
 */
suspend fun <T> withStreamTimeouts(
    idleMillis: Long,
    totalMillis: Long = AIStreamingPolicy.TOTAL_MILLIS,
    clock: () -> Long = { System.nanoTime() / 1_000_000 },
    block: suspend (touch: () -> Unit) -> T
): T {
    val started = clock()
    val latest = AtomicLong(started)
    return coroutineScope {
        // A failing child fails the scope: the request is cancelled and the
        // timeout is what this function throws.
        val watchdog = launch {
            while (true) {
                val now = clock()
                val quietUntil = latest.get() + idleMillis
                val endsAt = started + totalMillis
                if (now >= minOf(quietUntil, endsAt)) {
                    throw AIStreamTimeoutException(wentQuiet = quietUntil <= endsAt)
                }
                delay(minOf(quietUntil, endsAt) - now)
            }
        }
        try {
            block { latest.accumulateAndGet(clock()) { previous, now -> maxOf(previous, now) } }
        } finally {
            watchdog.cancel()
        }
    }
}

/**
 * Runs [work], which hands its `emit` the whole text so far each time it
 * grows, possibly on another thread. The newest text reaches [onPartial] at
 * most every [intervalMillis], on the caller's dispatcher, and never once
 * [work] has returned or failed: whatever the caller then shows is the
 * final state.
 */
suspend fun <T> streamWithPartials(
    onPartial: (String) -> Unit,
    intervalMillis: Long = AIStreamingPolicy.PARTIAL_INTERVAL_MILLIS,
    work: suspend (emit: (String) -> Unit) -> T
): T = coroutineScope {
    val latest = MutableStateFlow<String?>(null)
    val open = AtomicBoolean(true)
    val presenter = launch {
        // A StateFlow keeps only the newest text while the presenter waits.
        latest.filterNotNull().collect { text ->
            if (!open.get()) return@collect
            onPartial(text)
            delay(intervalMillis)
        }
    }
    try {
        work { text -> if (open.get()) latest.value = text }
    } finally {
        open.set(false)
        presenter.cancel()
    }
}

/**
 * [streamWithPartials] under [withStreamTimeouts]: each new text both shows
 * and keeps the request alive.
 */
suspend fun <T> streamAnswer(
    idleMillis: Long,
    onPartial: (String) -> Unit,
    totalMillis: Long = AIStreamingPolicy.TOTAL_MILLIS,
    intervalMillis: Long = AIStreamingPolicy.PARTIAL_INTERVAL_MILLIS,
    work: suspend (emit: (String) -> Unit) -> T
): T = withStreamTimeouts(idleMillis, totalMillis) { touch ->
    streamWithPartials(onPartial, intervalMillis) { emit ->
        work { text ->
            touch()
            emit(text)
        }
    }
}
