package com.jerreader.android.translation

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.jerreader.shared.domain.LanguageCode
import com.jerreader.shared.translation.TranslationFailure
import com.jerreader.shared.translation.TranslationInputPolicy
import com.jerreader.shared.translation.TranslationRequest
import com.jerreader.shared.translation.TranslationResult
import com.jerreader.shared.translation.TranslationService
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Android counterpart to iOS system translation. Language models are downloaded
 * on demand and inference runs on the device; book files are never provided to it.
 */
class OnDeviceTranslationService : TranslationService {
    override val identifier: String = "android-on-device-mlkit"

    override suspend fun translate(request: TranslationRequest): TranslationResult {
        val text = TranslationInputPolicy.validate(request.text)
        val source = request.sourceLanguage ?: detectLanguage(text)
        if (source == request.targetLanguage) throw TranslationFailure.UnsupportedLanguage
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(source.mlKitTag())
            .setTargetLanguage(request.targetLanguage.mlKitTag())
            .build()
        val translator = Translation.getClient(options)
        try {
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            val translated = translator.translate(text).await().trim()
            if (!TranslationInputPolicy.isVisiblyNonEmpty(translated)) {
                throw TranslationFailure.ServiceUnavailable
            }
            return TranslationResult(
                translatedText = translated,
                sourceLanguage = source,
                targetLanguage = request.targetLanguage,
                providerIdentifier = "Android 本机翻译"
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: TranslationFailure) {
            throw error
        } catch (_: Exception) {
            throw TranslationFailure.ServiceUnavailable
        } finally {
            translator.close()
        }
    }

    private fun LanguageCode.mlKitTag(): String = when (this) {
        LanguageCode.CHINESE_SIMPLIFIED -> TranslateLanguage.CHINESE
        LanguageCode.ENGLISH -> TranslateLanguage.ENGLISH
        LanguageCode.JAPANESE -> TranslateLanguage.JAPANESE
    }

    private fun detectLanguage(text: String): LanguageCode = when {
        text.any { it in '\u3040'..'\u30ff' } -> LanguageCode.JAPANESE
        text.any { it in '\u3400'..'\u9fff' } -> LanguageCode.CHINESE_SIMPLIFIED
        else -> LanguageCode.ENGLISH
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { result -> continuation.resume(result) }
    addOnFailureListener { error -> continuation.resumeWithException(error) }
    addOnCanceledListener { continuation.cancel() }
}
