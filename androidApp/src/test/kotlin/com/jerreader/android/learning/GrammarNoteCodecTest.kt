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
    // 2.0.1: every tap on 保存到语法知识库 is a new draft with a new id.
    @Test fun savingTheSameNoteAgainUpdatesItInsteadOfAddingATwin() {
        val saved = GrammarNoteEntity(id = "kept", title = "〜てしまう", sentence = "忘れてしまった。",
            explanation = "完成或遗憾的语气。", language = "ja", tags = "语气")
        val again = saved.copy(id = "fresh", tags = "完成")
        assertEquals("kept", grammarNoteToSave(again, listOf(saved)).id)
        assertEquals("完成", grammarNoteToSave(again, listOf(saved)).tags)
        // An edited explanation is another note; an existing id is an edit of that note.
        assertEquals("fresh", grammarNoteToSave(again.copy(explanation = "另一种讲法。"), listOf(saved)).id)
        assertEquals("kept", grammarNoteToSave(saved.copy(title = "改了标题"), listOf(saved)).id)
        assertEquals("fresh", grammarNoteToSave(again, emptyList()).id)
    }
    @Test fun standaloneNoteKeepsMissingLocationNull() {
        val note = GrammarNoteEntity(title = "Passive", sentence = "It was written.", explanation = "被动语态", language = "en")
        assertEquals(note, GrammarNoteCodec.decode(GrammarNoteCodec.encode(note)))
    }
}
