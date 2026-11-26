package com.jerreader.android.learning

import com.jerreader.android.data.LearningDao
import com.jerreader.android.data.WordLookupEntity
import com.jerreader.shared.domain.LanguageCode
import com.jerreader.shared.lexical.WordExplanation
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.1.0 §8: 查词 from a one-word translation card that already counted the
 * word saves the lookup sheet's entry without counting the word again.
 */
class SheetLookupCountTest {
    private val rows = mutableMapOf<String, WordLookupEntity>()
    private val dao = Proxy.newProxyInstance(LearningDao::class.java.classLoader, arrayOf(LearningDao::class.java)) { _, method, args ->
        when (method.name) {
            "word" -> rows[args[0] as String]
            "upsertWord" -> { val row = args[0] as WordLookupEntity; rows[row.lookupKey] = row; Unit }
            else -> error("Unexpected DAO call: ${method.name}")
        }
    } as LearningDao
    private var clock = 100L
    private val repository = LearningRecordRepository(dao) { clock }
    private val word = WordExplanation("食べた", "食べる", "たべる", LanguageCode.JAPANESE, "动词",
        listOf("吃"), sentenceContext = null, providerIdentifier = "mock-offline")

    @Test fun anUncountedRecordKeepsTheCountAndLastLookupButSavesTheEntry() = runBlocking {
        val card = repository.recordLookup(word, "book-a", favorite = true)
        assertEquals(1, card.lookupCount)
        assertEquals(100L, card.lastLookedUpAtEpochMillis)

        clock = 200L
        val sheet = repository.recordLookup(word.copy(sentenceContext = "パンを食べた。"), "book-a", counted = false)
        assertEquals(1, sheet.lookupCount)
        assertEquals(100L, sheet.lastLookedUpAtEpochMillis)
        assertEquals(100L, sheet.createdAtEpochMillis)
        assertEquals("パンを食べた。", sheet.sentenceContext)
        assertTrue(sheet.isFavorite)
        assertTrue(sheet.isInHistory)
        assertEquals(sheet, rows.getValue(card.lookupKey))

        clock = 300L
        val next = repository.recordLookup(word, "book-a")
        assertEquals(2, next.lookupCount)
        assertEquals(300L, next.lastLookedUpAtEpochMillis)
        assertEquals(1, rows.size)
    }

    @Test fun aWordNotRecordedBeforeStillStartsAtOne() = runBlocking {
        val saved = repository.recordLookup(word, "book-a", counted = false)
        assertEquals(1, saved.lookupCount)
        assertEquals(100L, saved.lastLookedUpAtEpochMillis)
        assertEquals(100L, saved.createdAtEpochMillis)
    }

    @Test fun onlyTheWordTheCardCountedIsNotCountedAgain() {
        val counted = LearningRecordRepository.wordKey(word)
        // Opened from the word card, the lookup page, or a card that counted nothing.
        assertTrue(LearningRecordRepository.sheetLookupCounts(word, null))
        assertFalse(LearningRecordRepository.sheetLookupCounts(word, counted))
        // The same dictionary form is the same record, whatever the sentence.
        assertFalse(LearningRecordRepository.sheetLookupCounts(word.copy(surfaceForm = "食べ", sentenceContext = "パンを食べ"), counted))
        assertTrue(
            LearningRecordRepository.sheetLookupCounts(
                WordExplanation("飲んだ", "飲む", "のむ", LanguageCode.JAPANESE, "动词", listOf("喝"), providerIdentifier = "mock-offline"),
                counted
            )
        )
    }
}
