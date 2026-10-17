package com.jerreader.android.learning
import com.jerreader.android.data.GrammarNoteEntity
import org.json.JSONObject

object GrammarNoteCodec {
    fun encode(note: GrammarNoteEntity): JSONObject = JSONObject()
        .put("id", note.id).put("title", note.title).put("sentence", note.sentence)
        .put("explanation", note.explanation).put("language", note.language).put("tags", note.tags)
        .put("bookId", note.bookId).put("bookTitle", note.bookTitle).put("locatorJson", note.locatorJson)
        .put("updatedAt", note.updatedAt)
    fun decode(json: JSONObject) = GrammarNoteEntity(
        id = json.getString("id"), title = json.getString("title"), sentence = json.getString("sentence"),
        explanation = json.getString("explanation"), language = json.optString("language"), tags = json.optString("tags"),
        bookId = json.optional("bookId"), bookTitle = json.optional("bookTitle"), locatorJson = json.optional("locatorJson"),
        updatedAt = json.getLong("updatedAt"))
    private fun JSONObject.optional(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
}
