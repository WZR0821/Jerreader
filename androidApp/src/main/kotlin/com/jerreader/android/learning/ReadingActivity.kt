package com.jerreader.android.learning

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

data class ReadingDay(val seconds: Double = 0.0, val queries: Int = 0)
data class ReadingActivity(val days: Map<String, ReadingDay> = emptyMap()) {
    fun record(start: Long, end: Long, zone: ZoneId = ZoneId.systemDefault()): ReadingActivity {
        if (end <= start) return this
        val result = days.toMutableMap()
        var cursor = maxOf(start, end - 21_600_000)
        while (cursor < end) {
            val day = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
            val stop = minOf(day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), end)
            val existing = result[day.toString()] ?: ReadingDay()
            result[day.toString()] = existing.copy(seconds = existing.seconds + (stop - cursor) / 1000.0)
            cursor = stop
        }
        return ReadingActivity(result.toSortedMap().entries.toList().takeLast(730).associate { it.toPair() })
    }
    fun query(now: Long, zone: ZoneId = ZoneId.systemDefault()): ReadingActivity {
        val key = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString()
        val existing = days[key] ?: ReadingDay()
        return ReadingActivity((days + (key to existing.copy(queries = existing.queries + 1)))
            .toSortedMap().entries.toList().takeLast(730).associate { it.toPair() })
    }
    fun encode(): String = JSONObject().apply {
        days.forEach { (key, value) -> put(key, JSONObject().put("seconds", value.seconds).put("queries", value.queries)) }
    }.toString()
    companion object {
        fun decode(raw: String?): ReadingActivity = runCatching {
            val json = JSONObject(raw ?: "{}")
            ReadingActivity(json.keys().asSequence().associateWith { key ->
                val day = json.getJSONObject(key)
                ReadingDay(day.optDouble("seconds", 0.0).takeIf { it.isFinite() && it >= 0 } ?: 0.0,
                    day.optInt("queries").coerceAtLeast(0))
            })
        }.getOrDefault(ReadingActivity())
    }
}
