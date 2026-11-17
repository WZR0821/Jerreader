package com.jerreader.android.reader

import com.jerreader.shared.library.LibraryBook
import com.jerreader.shared.library.LibraryRepository
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingSessionRecorderTest {
    private val repository = RecordingRepository()
    private val writes = SupervisorJob()
    private val failures = AtomicInteger()
    private var now = 0L

    private fun recorder(debounceMillis: Long = 60_000L) = ReadingSessionRecorder(
        repository = repository,
        writeScope = CoroutineScope(writes + Dispatchers.Default),
        clock = { now },
        debounceMillis = debounceMillis,
        onWriteFailure = { _, _ -> failures.incrementAndGet() }
    )

    private fun awaitWrites() = runBlocking {
        while (true) {
            val running = writes.children.toList()
            if (running.isEmpty()) break
            running.joinAll()
        }
    }

    @Test
    fun pauseSavesOnlyTheNewestPositionWithoutWaitingForTheDebounce() {
        val recorder = recorder()
        recorder.begin("book")
        recorder.recordPosition("p1", 0.1)
        recorder.recordPosition("p2", 0.2)
        recorder.pause()
        awaitWrites()
        assertEquals(listOf("book" to "p2"), repository.positions.toList())
    }

    @Test
    fun aPositionIsSavedAfterTheDebounceAndNotAgainOnPause() {
        val recorder = recorder(debounceMillis = 0L)
        recorder.begin("book")
        recorder.recordPosition("p1", 0.1)
        awaitWrites()
        recorder.pause()
        awaitWrites()
        assertEquals(listOf("book" to "p1"), repository.positions.toList())
    }

    @Test
    fun readingTimeUnderThreeSecondsWaitsForTheNextPause() {
        val recorder = recorder()
        recorder.begin("book")
        recorder.startClock()
        now = 2_000L
        recorder.pause()
        awaitWrites()
        assertEquals(emptyList<Pair<String, Double>>(), repository.times.toList())

        recorder.startClock()
        now = 4_000L
        recorder.pause()
        awaitWrites()
        assertEquals(listOf("book" to 4.0), repository.times.toList())
    }

    @Test
    fun aSecondPauseDoesNotCountTheSameSecondsTwice() {
        val recorder = recorder()
        recorder.begin("book")
        recorder.startClock()
        now = 10_000L
        recorder.pause()
        recorder.pause()
        awaitWrites()
        assertEquals(listOf("book" to 10.0), repository.times.toList())
    }

    @Test
    fun startingARunningClockKeepsTheOriginalStart() {
        val recorder = recorder()
        recorder.begin("book")
        recorder.startClock()
        now = 5_000L
        recorder.startClock()
        now = 10_000L
        recorder.pause()
        awaitWrites()
        assertEquals(listOf("book" to 10.0), repository.times.toList())
    }

    @Test
    fun failedWritesAreRetriedOnTheNextPause() {
        repository.failPositionWrites.set(1)
        repository.failTimeWrites.set(1)
        val recorder = recorder()
        recorder.begin("book")
        recorder.recordPosition("p1", 0.1)
        recorder.startClock()
        now = 5_000L
        recorder.pause()
        awaitWrites()
        assertEquals(2, failures.get())
        assertEquals(emptyList<Pair<String, String>>(), repository.positions.toList())

        recorder.pause()
        awaitWrites()
        assertEquals(listOf("book" to "p1"), repository.positions.toList())
        assertEquals(listOf("book" to 5.0), repository.times.toList())
    }

    @Test
    fun nothingIsSavedBeforeTheBookIsKnown() {
        val recorder = recorder()
        recorder.recordPosition("p1", 0.1)
        recorder.startClock()
        now = 10_000L
        recorder.pause()
        awaitWrites()
        assertEquals(emptyList<Pair<String, String>>(), repository.positions.toList())
        assertEquals(emptyList<Pair<String, Double>>(), repository.times.toList())
    }

    private class RecordingRepository : LibraryRepository {
        val positions: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
        val times: MutableList<Pair<String, Double>> = Collections.synchronizedList(mutableListOf())
        val failPositionWrites = AtomicInteger()
        val failTimeWrites = AtomicInteger()

        override suspend fun updateReadingProgress(
            id: String,
            locatorJson: String,
            openedAtEpochMillis: Long,
            progress: Double
        ) {
            if (failPositionWrites.getAndUpdate { maxOf(it - 1, 0) } > 0) error("disk full")
            positions += id to locatorJson
        }

        override suspend fun addReadingTime(id: String, seconds: Double) {
            if (failTimeWrites.getAndUpdate { maxOf(it - 1, 0) } > 0) error("disk full")
            times += id to seconds
        }

        override fun observeBooks(): Flow<List<LibraryBook>> = emptyFlow()
        override suspend fun book(id: String): LibraryBook? = null
        override suspend fun bookWithFingerprint(fingerprint: String): LibraryBook? = null
        override suspend fun bookWithSourceFingerprint(fingerprint: String): LibraryBook? = null
        override suspend fun add(book: LibraryBook) = Unit
        override suspend fun delete(id: String): Boolean = false
        override suspend fun updatePreferences(id: String, preferencesJson: String) = Unit
        override suspend fun updateOrganization(
            id: String,
            title: String,
            author: String?,
            language: String?,
            category: String,
            series: String,
            tags: List<String>
        ) = Unit
        override suspend fun updateCover(id: String, coverFileName: String?) = Unit
    }
}
