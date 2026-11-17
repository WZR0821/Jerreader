package com.jerreader.android.reader

import com.jerreader.shared.library.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Saves where the reader is and how long they have read, without holding the
 * main thread.
 *
 * The reader used to flush both with `runBlocking(Dispatchers.IO)` from
 * `onPause` and `onDestroy`, so every page turn into the background waited on
 * a database write. Writes now go to [writeScope], which in the app is the
 * application scope and so outlives the Activity, and run one at a time: an
 * older position can never land after a newer one.
 *
 * Every method is safe to call from any thread and more than once.
 */
internal class ReadingSessionRecorder(
    private val repository: LibraryRepository,
    private val writeScope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val debounceMillis: Long = PROGRESS_SAVE_DEBOUNCE_MILLIS,
    private val onWriteFailure: (what: String, error: Throwable) -> Unit = { _, _ -> }
) {
    private data class Position(
        val bookId: String,
        val locatorJson: String,
        val progress: Double,
        val sequence: Long
    )

    private val lock = Any()
    private val writes = Mutex()

    // Guarded by lock.
    private var bookId: String? = null
    private var sequence = 0L
    private var pending: Position? = null
    private var debounceJob: Job? = null
    private var clockStartedAt: Long? = null
    private var unsavedSeconds = 0.0

    // Guarded by writes.
    private var lastWrittenSequence = 0L

    /** Positions and time recorded before this are dropped: there is no book to save them to. */
    fun begin(bookId: String) {
        synchronized(lock) { this.bookId = bookId }
    }

    /** Starts the reading clock unless it is already running. */
    fun startClock() {
        synchronized(lock) {
            if (bookId != null && clockStartedAt == null) clockStartedAt = clock()
        }
    }

    /** The newest position; it is saved after a short pause unless a newer one replaces it. */
    fun recordPosition(locatorJson: String, progress: Double) {
        synchronized(lock) {
            val id = bookId ?: return
            pending = Position(id, locatorJson, progress, ++sequence)
            debounceJob?.cancel()
            debounceJob = writeScope.launch {
                delay(debounceMillis)
                savePendingPosition()
            }
        }
    }

    /**
     * Stops the clock and hands the newest position and the time read so far
     * to background writes. Time under [MINIMUM_RECORDED_READING_SECONDS]
     * waits for the next pause instead of becoming its own record.
     */
    fun pause() {
        val time: Pair<String, Double>?
        synchronized(lock) {
            clockStartedAt?.let { started ->
                unsavedSeconds += (clock() - started).coerceAtLeast(0L) / 1000.0
            }
            clockStartedAt = null
            debounceJob?.cancel()
            debounceJob = null
            val id = bookId
            time = if (id != null && unsavedSeconds >= MINIMUM_RECORDED_READING_SECONDS) {
                // Taken now so a second pause before the write finishes cannot
                // count the same seconds twice; a failed write puts them back.
                (id to unsavedSeconds).also { unsavedSeconds = 0.0 }
            } else {
                null
            }
        }
        writeScope.launch { savePendingPosition() }
        if (time != null) writeScope.launch { saveReadingTime(time.first, time.second) }
    }

    private suspend fun savePendingPosition() {
        val next = synchronized(lock) { pending.also { pending = null } } ?: return
        // Once taken, the position must be written even if the debounce job
        // that took it is cancelled by a newer page turn.
        withContext(NonCancellable) {
            writes.withLock {
                // A newer position already written makes this one stale.
                if (next.sequence > lastWrittenSequence) {
                    runCatching {
                        repository.updateReadingProgress(
                            id = next.bookId,
                            locatorJson = next.locatorJson,
                            openedAtEpochMillis = clock(),
                            progress = next.progress
                        )
                    }.onSuccess {
                        lastWrittenSequence = next.sequence
                    }.onFailure { error ->
                        // Keep it for the next pause unless a newer position came in.
                        synchronized(lock) { if (pending == null) pending = next }
                        onWriteFailure("reading progress", error)
                    }
                }
            }
        }
    }

    private suspend fun saveReadingTime(bookId: String, seconds: Double) {
        withContext(NonCancellable) {
            writes.withLock {
                runCatching { repository.addReadingTime(bookId, seconds) }.onFailure { error ->
                    synchronized(lock) { if (this@ReadingSessionRecorder.bookId == bookId) unsavedSeconds += seconds }
                    onWriteFailure("reading time", error)
                }
            }
        }
    }

    companion object {
        const val PROGRESS_SAVE_DEBOUNCE_MILLIS = 500L
        const val MINIMUM_RECORDED_READING_SECONDS = 3.0
    }
}
