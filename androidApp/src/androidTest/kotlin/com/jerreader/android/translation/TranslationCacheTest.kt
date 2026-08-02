package com.jerreader.android.translation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jerreader.android.data.JerreaderDatabase
import com.jerreader.shared.domain.LanguageCode
import com.jerreader.shared.translation.TranslationRequest
import com.jerreader.shared.translation.TranslationResult
import com.jerreader.shared.translation.TranslationService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationCacheTest {
    @Test
    fun repeatedRequestUsesPersistentCacheWithoutCallingProviderAgain() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, JerreaderDatabase::class.java).build()
        val settings = AndroidTranslationSettingsStore(context, MemoryCredentialStore())
        var calls = 0
        val provider = object : TranslationService {
            override val identifier = "test-provider"

            override suspend fun translate(request: TranslationRequest): TranslationResult {
                calls += 1
                return TranslationResult(
                    translatedText = "你好",
                    sourceLanguage = request.sourceLanguage ?: LanguageCode.ENGLISH,
                    targetLanguage = request.targetLanguage,
                    providerIdentifier = "离线测试"
                )
            }
        }
        val service = CachedTranslationService(
            provider,
            TranslationCacheRepository(database.translationCacheDao()) { 1_700_000_000_000 },
            settings
        )
        val request = TranslationRequest(
            text = "Hello",
            sourceLanguage = LanguageCode.ENGLISH,
            targetLanguage = LanguageCode.CHINESE_SIMPLIFIED
        )

        try {
            val first = service.translate(request)
            val second = service.translate(request)
            assertEquals(1, calls)
            assertFalse(first.isFromCache)
            assertTrue(second.isFromCache)
            assertEquals(first.translatedText, second.translatedText)
            assertEquals(first.providerIdentifier, second.providerIdentifier)
        } finally {
            database.close()
        }
    }
}

private class MemoryCredentialStore : TranslationCredentialStore {
    private val values = mutableMapOf<String, String>()
    override fun read(account: String): String? = values[account]
    override fun save(account: String, secret: String?) {
        if (secret.isNullOrBlank()) values.remove(account) else values[account] = secret
    }
}
