package com.jerreader.android.learning

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.jerreader.android.lexical.OfflineDictionaryPackageStore
import com.jerreader.android.translation.*
import com.jerreader.shared.domain.LanguageCode
import com.jerreader.shared.domain.LanguageCode.*
import com.jerreader.shared.translation.*
import com.jerreader.android.library.PublicationIntegrity
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AndroidOfflineExperienceTest {
    @Test fun installedAssetsTranslateBasicWordsWithoutAnyModelOrApi() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val backend = object : LocalTranslationBackend {
            override suspend fun installedLanguages() = emptySet<LanguageCode>()
            override suspend fun download(language: LanguageCode, wifiOnly: Boolean) { error("Must not download") }
            override suspend fun delete(language: LanguageCode) { error("Must not delete") }
            override suspend fun translate(text: String, source: LanguageCode, target: LanguageCode): String = error("Must use bundled dictionary")
        }
        val dictionary = OfflineDictionaryPackageStore(context)
        val service = OnDeviceTranslationService(backend, BasicOfflineTranslation(dictionary)::translate)
        for ((word, language) in listOf("beautiful" to ENGLISH, "食べました" to JAPANESE, "曖昧" to JAPANESE)) {
            val result = service.translate(TranslationRequest(word, language))
            assertTrue(result.translatedText.isNotBlank())
            assertTrue(result.providerIdentifier.contains("离线"))
        }
        val reverse = service.translate(TranslationRequest("含糊", CHINESE_SIMPLIFIED, JAPANESE))
        assertTrue(reverse.translatedText.contains("曖昧"))
        try { service.translate(TranslationRequest("This is a complete sentence without a model.", ENGLISH)); fail("Must explain missing model") }
        catch (_: TranslationFailure.LocalModelsMissing) { }
    }

    @Test fun streamingCopyMaintainsBytesAndTimeWithFewerFilePasses() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "stream-benchmark").apply { mkdirs() }
        try {
            val source = File(directory, "source.bin")
            source.outputStream().use { out ->
                val block = ByteArray(64 * 1024) { (it % 251).toByte() }
                repeat(512) { out.write(block) }
            }
            val expectedSource = PublicationIntegrity.capture(source)
            val oldTimes = mutableListOf<Long>(); val newTimes = mutableListOf<Long>()
            // Reproduce the pre-change 8 KiB hashing and four post-copy reads.
            fun oldFingerprint(file: File): String {
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            }
            repeat(3) { iteration ->
                fun oldCopy() {
                    val file = File(directory, "old-$iteration.bin")
                    val start = System.nanoTime()
                    source.inputStream().use { input -> java.io.FileOutputStream(file).use { output -> input.copyTo(output); output.fd.sync() } }
                    val fingerprint = oldFingerprint(file)
                    repeat(3) { assertEquals(fingerprint, oldFingerprint(file)) }
                    oldTimes += (System.nanoTime() - start) / 1_000_000
                    assertEquals(expectedSource.sha256, fingerprint)
                    file.delete()
                }
                fun newCopy() {
                    val file = File(directory, "new-$iteration.bin")
                    val start = System.nanoTime()
                    val snapshot = source.inputStream().use { PublicationIntegrity.copyAndCapture(it, file) }
                    assertTrue(PublicationIntegrity.isUnchanged(snapshot))
                    newTimes += (System.nanoTime() - start) / 1_000_000
                    assertEquals(expectedSource.sha256, snapshot.sha256)
                    assertEquals(snapshot.lastModified, file.lastModified())
                    file.delete()
                }
                if (iteration % 2 == 0) { oldCopy(); newCopy() } else { newCopy(); oldCopy() }
            }
            assertTrue(PublicationIntegrity.isUnchanged(expectedSource))
            val report = "32 MiB copy + fingerprint/check passes (not full Readium import): before_ms=$oldTimes after_ms=$newTimes"
            val output = File(context.getExternalFilesDir(null), "learning-ui").apply { mkdirs() }
            File(output, "file-io-benchmark.txt").writeText(report)
            android.util.Log.i("JerreaderBenchmark", report)
        } finally { directory.deleteRecursively() }
    }
}
