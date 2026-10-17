package com.jerreader.shared.learning
import kotlin.test.*
class SentencePracticeTest {
    @Test fun validatesAndMasksExactTextWithoutChangingSource() {
        val source = "猫は猫です。"
        assertEquals("［____］は［____］です。", SentencePractice.make(source, " 猫 ")?.prompt)
        assertEquals("猫は猫です。", source)
        assertNull(SentencePractice.make(source, "犬"))
        assertNull(SentencePractice.make(source, " "))
    }
    @Test fun supportsPunctuationAndEmojiLiterally() {
        assertEquals("［____］ is cute", SentencePractice.make("👨‍👩‍👧‍👦 is cute", "👨‍👩‍👧‍👦")?.prompt)
        assertEquals("a［____］b", SentencePractice.make("a.*b", ".*")?.prompt)
    }
}
