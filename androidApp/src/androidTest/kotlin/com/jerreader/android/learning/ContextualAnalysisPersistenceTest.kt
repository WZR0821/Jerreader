package com.jerreader.android.learning

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.jerreader.android.data.JerreaderDatabase
import com.jerreader.shared.domain.LanguageCode
import com.jerreader.shared.lexical.WordExplanation
import com.jerreader.shared.translation.ContextExplanationResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ContextualAnalysisPersistenceTest {
    @Test fun lateAnalysisCannotOverwriteChangedSenseInSameMillisecond() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, JerreaderDatabase::class.java).build()
        try {
            val repository = LearningRecordRepository(db.learningDao()) { 1000L }
            val original = WordExplanation("生", "生", "なま", LanguageCode.JAPANESE, "名词", listOf("生的"), sentenceContext = "同一个原句", providerIdentifier = "ui-mock")
            val first = repository.recordLookup(original)
            assertTrue(repository.updateContextualAnalysis(first, ContextExplanationResult("旧建议", "mock")))
            val changed = repository.recordLookup(original.copy(reading = "せい", definitions = listOf("生命")))
            assertNull(changed.aiAnalysis)
            assertFalse(repository.updateContextualAnalysis(first, ContextExplanationResult("迟到的建议", "mock")))
            assertTrue(repository.updateContextualAnalysis(changed, ContextExplanationResult("新建议", "mock")))
            assertEquals("新建议", db.learningDao().word(changed.lookupKey)?.aiAnalysis)
            repository.removeFromHistory(changed.lookupKey)
            assertFalse(repository.setContextualFavorite(changed.lookupKey, true))
            assertFalse(repository.updateContextualAnalysis(changed, ContextExplanationResult("删除后到达", "mock")))
        } finally { db.close() }
    }
}
