package com.jerreader.android.translation

import com.jerreader.shared.domain.LanguageCode.*
import com.jerreader.shared.domain.LanguageCode
import com.jerreader.shared.translation.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class OnDeviceTranslationServiceTest {
    private class Backend : LocalTranslationBackend {
        val installed = mutableSetOf<LanguageCode>()
        var downloads = 0
        var calls = 0
        var active = 0
        var peak = 0
        var failDownload = false
        var downloadDelay = 0L
        override suspend fun installedLanguages() = installed.toSet()
        override suspend fun download(language: LanguageCode, wifiOnly: Boolean) {
            downloads++; delay(downloadDelay)
            check(!failDownload)
            installed += language
        }
        override suspend fun delete(language: LanguageCode) { installed -= language }
        override suspend fun translate(text: String, source: LanguageCode, target: LanguageCode): String {
            calls++; active++; peak = maxOf(peak, active)
            try { delay(10); return "译文" } finally { active-- }
        }
    }
    private val request = TranslationRequest("This is a sentence.", ENGLISH)

    @Test fun missingModelsNeverStartDownloadOrInference() = runBlocking {
        val backend = Backend()
        val service = OnDeviceTranslationService(backend)
        try { service.translate(request); fail("Expected missing model") }
        catch (error: TranslationFailure.LocalModelsMissing) { assertEquals("中文", error.languages) }
        assertEquals(0, backend.downloads); assertEquals(0, backend.calls)
    }
    @Test fun builtInDictionaryWorksWithoutModels() = runBlocking {
        val backend = Backend()
        val expected = TranslationResult("书", ENGLISH, CHINESE_SIMPLIFIED, "dictionary")
        val service = OnDeviceTranslationService(backend, { expected })
        assertEquals(expected, service.translate(request.copy(text = "book")))
        assertEquals(0, backend.calls); assertEquals(0, backend.downloads)
    }
    @Test fun translationsAreSerializedAndUsePreparedModels() = runBlocking {
        val backend = Backend().apply { installed += CHINESE_SIMPLIFIED }
        val service = OnDeviceTranslationService(backend)
        val results = List(3) { async { service.translate(request) } }.awaitAll()
        assertEquals(3, results.size); assertEquals(1, backend.peak)
        assertEquals(0, backend.downloads)
    }
    @Test fun chineseJapanesePairRequiresBothModels() {
        assertEquals(setOf(CHINESE_SIMPLIFIED, JAPANESE), OnDeviceTranslationService.requiredModels(JAPANESE, CHINESE_SIMPLIFIED))
        assertEquals(setOf(CHINESE_SIMPLIFIED), OnDeviceTranslationService.requiredModels(ENGLISH, CHINESE_SIMPLIFIED))
    }
    @Test fun downloadFailureAndTimeoutRetainPreparedModels() = runBlocking {
        val backend = Backend().apply { installed += CHINESE_SIMPLIFIED; failDownload = true }
        val service = OnDeviceTranslationService(backend, modelTimeoutMillis = 20)
        service.refreshModels(); service.downloadModel(JAPANESE, true)
        assertEquals(setOf(CHINESE_SIMPLIFIED), service.modelState.value.installed)
        assertTrue(service.modelState.value.message!!.contains("未完成"))
        backend.failDownload = false; backend.downloadDelay = 1000
        service.downloadModel(JAPANESE, true)
        assertTrue(service.modelState.value.message!!.contains("超时"))
        assertNull(service.modelState.value.workingLanguage)
        assertEquals("译文", service.translate(request).translatedText)
    }
    @Test fun explicitPrepareAndDeleteRefreshVerifiedState() = runBlocking {
        val backend = Backend(); val service = OnDeviceTranslationService(backend)
        service.downloadModel(CHINESE_SIMPLIFIED, false)
        assertEquals(setOf(CHINESE_SIMPLIFIED), service.modelState.value.installed)
        service.deleteModel(CHINESE_SIMPLIFIED)
        assertTrue(service.modelState.value.installed.isEmpty())
    }
    @Test fun cancellationReleasesInferenceQueue() = runBlocking {
        val backend = Backend().apply { installed += CHINESE_SIMPLIFIED }
        val service = OnDeviceTranslationService(backend)
        val job = launch { service.translate(request) }
        yield(); job.cancelAndJoin()
        assertEquals("译文", service.translate(request).translatedText)
    }
}
