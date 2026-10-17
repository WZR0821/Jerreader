package com.jerreader.android.learning
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ReadingActivityTest {
    @Test fun splitsMidnightAndKeepsQueryCount() {
        val start = Instant.parse("2026-09-07T14:50:00Z").toEpochMilli()
        val end = Instant.parse("2026-09-07T15:10:00Z").toEpochMilli()
        val zone = ZoneId.of("Asia/Tokyo")
        val activity = ReadingActivity().record(start, end, zone).query(end, zone)
        assertEquals(600.0, activity.days.getValue("2026-09-07").seconds, 0.001)
        assertEquals(ReadingDay(600.0, 1), activity.days.getValue("2026-09-08"))
        assertEquals(activity, ReadingActivity.decode(activity.encode()))
    }
    @Test fun daylightSavingUsesElapsedTimeAndCapsLongSessions() {
        val start = Instant.parse("2026-03-08T06:30:00Z").toEpochMilli()
        val end = Instant.parse("2026-03-08T07:30:00Z").toEpochMilli()
        assertEquals(3600.0, ReadingActivity().record(start, end, ZoneId.of("America/New_York")).days.values.sumOf { it.seconds }, 0.001)
        assertEquals(21_600.0, ReadingActivity().record(0, 100_000_000).days.values.sumOf { it.seconds }, 0.001)
        assertTrue(ReadingActivity().record(end, start).days.isEmpty())
        assertTrue(ReadingActivity.decode(null).days.isEmpty())
    }
}
