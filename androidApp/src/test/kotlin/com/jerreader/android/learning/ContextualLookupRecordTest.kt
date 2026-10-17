package com.jerreader.android.learning

import com.jerreader.android.data.LearningDao
import com.jerreader.android.data.WordLookupEntity
import com.jerreader.shared.domain.LanguageCode
import com.jerreader.shared.lexical.WordExplanation
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ContextualLookupRecordTest {
    @Test fun changingSentenceSurfaceSenseOrBookDropsOldAIAndPreservesFavorites() = runBlocking {
        val rows = mutableMapOf<String, WordLookupEntity>()
        val dao = Proxy.newProxyInstance(LearningDao::class.java.classLoader, arrayOf(LearningDao::class.java)) { _, method, args ->
            when (method.name) {
                "word" -> rows[args[0] as String]
                "upsertWord" -> { val row = args[0] as WordLookupEntity; rows[row.lookupKey] = row; Unit }
                else -> error("Unexpected DAO call: ${method.name}")
            }
        } as LearningDao
        var clock = 1L
        val repository = LearningRecordRepository(dao) { clock++ }
        val word = WordExplanation("食べた", "食べる", "たべる", LanguageCode.JAPANESE, "动词",
            listOf("吃"), sentenceContext = "パンを食べた。", providerIdentifier = "mock-offline")
        var saved = repository.recordLookup(word, "book-a", favorite = true)
        fun seedAnalysis() { rows[saved.lookupKey] = checkNotNull(rows[saved.lookupKey]).copy(aiAnalysis = "旧原句", aiProviderIdentifier = "mock-ai") }
        seedAnalysis()
        saved = repository.recordLookup(word, "book-a")
        assertEquals("旧原句", saved.aiAnalysis)
        for ((next, book) in listOf(
            word.copy(sentenceContext = "ご飯を食べた。") to "book-a",
            word.copy(surfaceForm = "食べる") to "book-a",
            word.copy(definitions = listOf("维持生计")) to "book-a",
            word to "book-b",
            word.copy(sentenceContext = null) to "book-b"
        )) {
            seedAnalysis()
            saved = repository.recordLookup(next, book)
            assertNull(saved.aiAnalysis)
            assertNull(saved.aiProviderIdentifier)
            assertEquals(next.definitions.joinToString(LearningRecordRepository.DEFINITION_SEPARATOR), saved.definitionsText)
            assertTrue(saved.isFavorite)
        }
        assertEquals(1, rows.size)
    }
}
