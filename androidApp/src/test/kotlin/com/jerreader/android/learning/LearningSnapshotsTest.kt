package com.jerreader.android.learning

import com.jerreader.shared.learning.ReviewAnswer
import com.jerreader.shared.learning.WordReviewState
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class LearningSnapshotsTest {
    @Test fun reviewDecodesEachRecordOnceAndKeepsStableOrder() {
        var decodes = 0
        val input = (0 until 1500).map { "word-$it" to (it % 17).toString() }
        val snapshot = WordReviewSnapshot.create(input) { raw ->
            decodes++
            WordReviewState(dueAt = raw!!.toLong(), paused = raw == "3")
        }
        assertEquals(1500, decodes)
        val due = snapshot.dueKeys(4)
        assertEquals(listOf("word-0", "word-17", "word-34"), due.take(3))
        assertTrue(due.none { snapshot.states.getValue(it).paused })
        assertTrue(due.all { snapshot.states.getValue(it).dueAt <= 4 })
        snapshot.dueKeys(100)
        assertEquals("Clock ticks must not decode again", 1500, decodes)
    }

    @Test fun revisedReviewDataReplacesOldPauseAndSchedule() {
        val before = WordReviewSnapshot.create(listOf("word" to WordReviewCodec.encode(WordReviewState(paused = true))))
        val after = WordReviewSnapshot.create(listOf("word" to WordReviewCodec.encode(WordReviewState(dueAt = 12))))
        assertTrue(before.dueKeys(12).isEmpty())
        assertTrue(after.dueKeys(11).isEmpty())
        assertEquals(listOf("word"), after.dueKeys(12))
    }

    @Test fun cancelledSnapshotStopsBeforeDecodingRemainingRecords() {
        var visited = 0
        var decoded = 0
        try {
            WordReviewSnapshot.create((0 until 1500).map { "$it" to null }, checkpoint = {
                visited++
                if (visited == 3) throw java.util.concurrent.CancellationException()
            }, decode = { decoded++; WordReviewState() })
            fail("Cancelled work must stop")
        } catch (_: java.util.concurrent.CancellationException) {
            assertEquals(3, visited)
            assertEquals(2, decoded)
        }
    }

    @Test fun growthKeepsWeekBoundariesAndOriginalAnswerBook() {
        val zone = ZoneId.of("America/New_York")
        val start = LocalDate.of(2026, 3, 2)
        val from = start.atStartOfDay(zone).toInstant().toEpochMilli()
        val until = start.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        val review = WordReviewCodec.encode(WordReviewState(answers = listOf(
            ReviewAnswer(from - 1, true, "a"), ReviewAnswer(from, true, "a"),
            ReviewAnswer(until - 1, false, "a"), ReviewAnswer(from, true, "b"),
            ReviewAnswer(until, true, "a"))))
        val activity = ReadingActivity(mapOf("2026-03-02" to ReadingDay(600.0, 2),
            "2026-03-08" to ReadingDay(1200.0, 3), "2026-03-09" to ReadingDay(9999.0, 9))).encode()
        val report = ReadingGrowthSnapshot.create(listOf(activity), listOf(review), start, "a", zone)
        assertEquals(7, report.rows.size)
        assertEquals(1800.0, report.seconds, 0.0)
        assertEquals(5, report.queries)
        assertEquals(2, report.readingDays)
        assertEquals(2, report.answerCount)
        assertEquals(1, report.knownAnswers)
        assertEquals(1200.0, report.maxSeconds, 0.0)
        assertEquals(3, ReadingGrowthSnapshot.create(emptyList(), listOf(review), start, null, zone).answerCount)
    }
}
