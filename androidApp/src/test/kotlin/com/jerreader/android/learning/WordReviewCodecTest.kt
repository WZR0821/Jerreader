package com.jerreader.android.learning

import com.jerreader.shared.learning.*
import org.junit.Assert.*
import org.junit.Test

class WordReviewCodecTest {
    @Test fun oldBackupAndCorruptPayloadBecomeNewWords() {
        assertEquals(WordReviewState(), WordReviewCodec.decode(null))
        assertEquals(WordReviewState(), WordReviewCodec.decode("broken"))
    }
    @Test fun restoreKeepsPauseScheduleAndAnswers() {
        val state = WordReviewState(paused = true).grade(ReviewGrade.KNOWN, 100)
        assertEquals(state, WordReviewCodec.decode(WordReviewCodec.encode(state)))
    }
}
