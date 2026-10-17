package com.jerreader.android.learning
import com.jerreader.android.data.GrammarNoteEntity
import org.junit.Assert.*
import org.junit.Test

class GrammarNoteCodecTest {
    @Test fun defaultTitlePreservesJoinedEmojiAtLimit() {
        val prefix = "a".repeat(39) + "👨‍👩‍👧‍👦"
        assertEquals(prefix, com.jerreader.android.data.grammarNoteTitle(prefix + "tail"))
    }
    @Test fun backupKeepsSourceAndEditableContent() {
        val note = GrammarNoteEntity(title = "〜てしまう", sentence = "忘れてしまった。", explanation = "完成或遗憾的语气。",
            language = "ja", tags = "完成,语气", bookId = "book", bookTitle = "原书", locatorJson = "{\"href\":\"chapter.xhtml\"}")
        assertEquals(note, GrammarNoteCodec.decode(GrammarNoteCodec.encode(note)))
    }
    @Test fun standaloneNoteKeepsMissingLocationNull() {
        val note = GrammarNoteEntity(title = "Passive", sentence = "It was written.", explanation = "被动语态", language = "en")
        assertEquals(note, GrammarNoteCodec.decode(GrammarNoteCodec.encode(note)))
    }
}
