package com.jerreader.android.learning

import com.jerreader.shared.learning.ReviewAnswer
import com.jerreader.shared.learning.WordReviewState
import org.json.JSONArray
import org.json.JSONObject

object WordReviewCodec {
    fun decode(raw: String?): WordReviewState = runCatching {
        val json = JSONObject(raw ?: "{}")
        val answers = json.optJSONArray("answers") ?: JSONArray()
        WordReviewState(json.optInt("step").coerceIn(0, 5), json.optLong("dueAt"),
            json.optBoolean("paused"), (0 until answers.length()).map {
                val item = answers.getJSONObject(it)
                ReviewAnswer(item.getLong("at"), item.getBoolean("known"),
                    if (item.has("bookId") && !item.isNull("bookId")) item.getString("bookId") else null)
            }.takeLast(1000))
    }.getOrDefault(WordReviewState())

    fun encode(state: WordReviewState): String = JSONObject()
        .put("step", state.step).put("dueAt", state.dueAt).put("paused", state.paused)
        .put("answers", JSONArray().apply {
            state.answers.forEach { put(JSONObject().put("at", it.at).put("known", it.known).put("bookId", it.bookId)) }
        }).toString()
}
