package com.jerreader.android.translation

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.jerreader.shared.domain.LanguageCode
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import com.google.mlkit.nl.translate.Translator
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MlKitTranslationBackend : LocalTranslationBackend {
    private val operationLock = Mutex()
    private val manager get() = RemoteModelManager.getInstance()
    override suspend fun installedLanguages(): Set<LanguageCode> =
        manager.getDownloadedModels(TranslateRemoteModel::class.java).awaitLocal().mapNotNull {
            when (it.language) {
                "zh" -> LanguageCode.CHINESE_SIMPLIFIED
                "ja" -> LanguageCode.JAPANESE
                else -> null
            }
        }.toSet()

    override suspend fun download(language: LanguageCode, wifiOnly: Boolean) {
        val conditions = DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()
        modelOperation { manager.download(model(language), conditions) }
    }

    override suspend fun delete(language: LanguageCode) {
        modelOperation { manager.deleteDownloadedModel(model(language)) }
    }

    override suspend fun translate(text: String, source: LanguageCode, target: LanguageCode): String {
        var client: Translator? = null
        return modelOperation(onComplete = { client?.close() }) {
            Translation.getClient(TranslatorOptions.Builder()
                .setSourceLanguage(source.tag()).setTargetLanguage(target.tag()).build()).also { client = it }.translate(text)
        }
    }

    // Cancellation stops the caller's wait, not an ML Kit Task. Its completion owns
    // the lock, so a timed-out download/inference cannot race a later delete/request.
    private suspend fun <T> modelOperation(onComplete: () -> Unit = {}, start: () -> Task<T>): T {
        operationLock.lock()
        val task = try { start() } catch (error: Exception) {
            try { onComplete() } finally { operationLock.unlock() }
            throw error
        }
        task.addOnCompleteListener {
            try { onComplete() } finally { operationLock.unlock() }
        }
        return task.awaitLocal()
    }

    private fun model(language: LanguageCode) = TranslateRemoteModel.Builder(language.tag()).build()
    private fun LanguageCode.tag() = when (this) {
        LanguageCode.CHINESE_SIMPLIFIED -> "zh"
        LanguageCode.ENGLISH -> "en"
        LanguageCode.JAPANESE -> "ja"
    }
}

private suspend fun <T> Task<T>.awaitLocal(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (continuation.isActive) {
            when {
                task.isCanceled -> continuation.cancel()
                task.isSuccessful -> continuation.resume(task.result)
                else -> continuation.resumeWithException(task.exception ?: IllegalStateException("Local model task failed"))
            }
        }
    }
}
