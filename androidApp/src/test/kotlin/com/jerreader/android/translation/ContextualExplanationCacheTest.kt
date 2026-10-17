package com.jerreader.android.translation

import com.jerreader.android.data.TranslationCacheDao
import com.jerreader.android.data.TranslationCacheEntity
import com.jerreader.shared.domain.LanguageCode
import com.jerreader.shared.translation.ContextExplanationRequest
import com.jerreader.shared.translation.ContextExplanationResult
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ContextualExplanationCacheTest {
    @Test fun wordPurposeSentenceAndChosenSenseUseSeparateCacheEntries() = runBlocking {
        val rows = mutableMapOf<String, TranslationCacheEntity>()
        val dao = Proxy.newProxyInstance(TranslationCacheDao::class.java.classLoader, arrayOf(TranslationCacheDao::class.java)) { _, method, args ->
            when (method.name) {
                "cache" -> rows[args[0] as String]
                "upsert" -> { val value = args[0] as TranslationCacheEntity; rows[value.cacheKey] = value; Unit }
                "touch" -> Unit
                else -> error("Unexpected DAO call: ${method.name}")
            }
        } as TranslationCacheDao
        val cache = TranslationCacheRepository(dao)
        val sentence = ContextExplanationRequest("食べる", "これで食べる。", LanguageCode.JAPANESE)
        val word = sentence.copy(isWordLookup = true, dictionaryEntry = "吃")
        val answer = ContextExplanationResult("本句建议", "mock")
        cache.saveExplanation(word, "mock", answer)
        assertEquals(answer, cache.findExplanation(word, "mock"))
        assertNull(cache.findExplanation(sentence, "mock"))
        assertNull(cache.findExplanation(word.copy(dictionaryEntry = "维持生计"), "mock"))
        assertNull(cache.findExplanation(word.copy(contextText = "ご飯を食べる。"), "mock"))
    }
}
