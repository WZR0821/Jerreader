package com.jerreader.android.translation

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.jerreader.unified.domain.LanguageCode
import com.jerreader.unified.translation.OnDeviceTranslationPlan
import com.jerreader.unified.translation.TranslationFailure
import com.jerreader.unified.translation.TranslationInputPolicy
import com.jerreader.unified.translation.TranslationLanguageHeuristic
import com.jerreader.unified.translation.TranslationRequest
import com.jerreader.unified.translation.TranslationResult
import com.jerreader.unified.translation.TranslationService
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
        val source = request.sourceLanguage ?: TranslationLanguageHeuristic.detect(text)
        if (source == request.targetLanguage) throw TranslationFailure.UnsupportedLanguage
        // The model works a sentence at a time; a whole selection handed over in
        // one call comes back truncated or half-translated.
        val segments = OnDeviceTranslationPlan.segments(text, source)
        if (segments.isEmpty()) throw TranslationFailure.EmptyInput
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(source.mlKitTag())
            .setTargetLanguage(request.targetLanguage.mlKitTag())
            .build()
        val translator = Translation.getClient(options)
        try {
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            val parts = segments.map { segment -> translator.translate(segment).await().trim() }
            val translated = OnDeviceTranslationPlan.joinedTranslation(
                parts = parts,
                target = request.targetLanguage
            )
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
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { result -> continuation.resume(result) }
    addOnFailureListener { error -> continuation.resumeWithException(error) }
    addOnCanceledListener { continuation.cancel() }
}
