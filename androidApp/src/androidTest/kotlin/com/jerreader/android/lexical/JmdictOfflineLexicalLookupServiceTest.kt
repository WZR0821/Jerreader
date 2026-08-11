package com.jerreader.android.lexical

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jerreader.unified.domain.LanguageCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JmdictOfflineLexicalLookupServiceTest {
    @Test
    fun bundledDictionaryReturnsARealJapaneseEntry() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val result = JmdictOfflineLexicalLookupService(context).lookup(
            word = "本",
            sentenceContext = "本を読みます。",
            language = LanguageCode.JAPANESE,
            candidates = emptyList()
        )

        assertEquals("ほん", result.reading)
        assertTrue(result.definitions.any { it.contains("book", ignoreCase = true) })
        assertEquals("本を読みます。", result.sentenceContext)
        assertTrue(result.usageNote?.contains("离线 JMdict") == true)
    }
}
