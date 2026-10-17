package com.jerreader.android.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "grammar_notes")
data class GrammarNoteEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val sentence: String,
    val explanation: String,
    val language: String,
    val tags: String = "",
    val bookId: String? = null,
    val bookTitle: String? = null,
    val locatorJson: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

/** Title truncation keeps accented letters and joined emoji intact. */
fun grammarNoteTitle(source: String): String {
    val matcher = java.util.regex.Pattern.compile("\\X").matcher(source)
    var end = 0
    repeat(40) {
        if (!matcher.find()) return source
        end = matcher.end()
    }
    return source.substring(0, end)
}
