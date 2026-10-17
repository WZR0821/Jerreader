package com.jerreader.android.translation

import com.jerreader.shared.domain.LanguageCode
import com.jerreader.shared.translation.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** Only this boundary knows how the platform downloads or runs translation models. */
interface LocalTranslationBackend {
    suspend fun installedLanguages(): Set<LanguageCode>
    suspend fun download(language: LanguageCode, wifiOnly: Boolean)
    suspend fun delete(language: LanguageCode)
    suspend fun translate(text: String, source: LanguageCode, target: LanguageCode): String
}

data class LocalTranslationModelState(
    val installed: Set<LanguageCode> = emptySet(),
    val loading: Boolean = true,
    val workingLanguage: LanguageCode? = null,
    val message: String? = null
)

interface LocalTranslationModels {
    val modelState: StateFlow<LocalTranslationModelState>
    suspend fun refreshModels()
    suspend fun downloadModel(language: LanguageCode, wifiOnly: Boolean = true)
    suspend fun deleteModel(language: LanguageCode)
}

/** Translation never silently starts a download or sends text to a server. */
class OnDeviceTranslationService(
    private val backend: LocalTranslationBackend = MlKitTranslationBackend(),
    private val basicTranslation: suspend (TranslationRequest) -> TranslationResult? = { null },
    private val modelTimeoutMillis: Long = 180_000
) : TranslationService, LocalTranslationModels {
    override val identifier = "android-on-device-mlkit-v2"
    private val state = MutableStateFlow(LocalTranslationModelState())
    override val modelState = state.asStateFlow()
    // Model removal, download and inference must not race over the same model files.
    private val modelLock = Mutex()

    override suspend fun refreshModels() {
        try {
            modelLock.withLock { readInstalled() }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            state.update { it.copy(loading = false, message = "无法读取语言包状态，请重试。") }
        }
    }

    override suspend fun downloadModel(language: LanguageCode, wifiOnly: Boolean) {
        require(language != LanguageCode.ENGLISH)
        if (!modelLock.tryLock()) return
        state.update { it.copy(workingLanguage = language, message = null) }
        try {
            withTimeout(modelTimeoutMillis) { backend.download(language, wifiOnly) }
            val installed = readInstalled()
            check(language in installed)
            state.update { it.copy(message = "${language.modelTitle()}语言包已就绪，可离线翻译。") }
        } catch (_: TimeoutCancellationException) {
            state.update { it.copy(message = "语言包准备超时。请检查网络；下载可能仍在后台进行，可稍后刷新状态。") }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            state.update { it.copy(message = "语言包下载未完成，请检查网络和存储后重试。已下载的语言包仍可使用。") }
        } finally {
            state.update { it.copy(loading = false, workingLanguage = null) }
            modelLock.unlock()
        }
    }

    override suspend fun deleteModel(language: LanguageCode) {
        if (!modelLock.tryLock()) return
        state.update { it.copy(workingLanguage = language, message = null) }
        try {
            backend.delete(language)
            readInstalled()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            state.update { it.copy(message = "语言包未能删除，请重试。") }
        } finally {
            state.update { it.copy(loading = false, workingLanguage = null) }
            modelLock.unlock()
        }
    }

    override suspend fun translate(request: TranslationRequest): TranslationResult {
        val text = TranslationInputPolicy.validate(request.text)
        val source = request.sourceLanguage ?: when {
            text.any { it in '\u3040'..'\u30ff' } -> LanguageCode.JAPANESE
            text.any { it in '\u3400'..'\u9fff' } -> LanguageCode.CHINESE_SIMPLIFIED
            else -> LanguageCode.ENGLISH
        }
        if (source == request.targetLanguage) throw TranslationFailure.UnsupportedLanguage
        basicTranslation(request.copy(text = text, sourceLanguage = source))?.let {
            currentCoroutineContext().ensureActive()
            return it
        }
        // Fail promptly while a model is being prepared instead of holding a reader card open.
        if (state.value.workingLanguage != null) throw TranslationFailure.ProviderRejected("语言包正在准备，请完成后再翻译。内置查词仍可使用。")
        modelLock.lock()
        try {
            val installed = readInstalled()
            val missing = requiredModels(source, request.targetLanguage) - installed
            if (missing.isNotEmpty()) throw TranslationFailure.LocalModelsMissing(missing.joinToString("、") { it.modelTitle() })
            val translated = backend.translate(text, source, request.targetLanguage).trim()
            currentCoroutineContext().ensureActive()
            if (!TranslationInputPolicy.isVisiblyNonEmpty(translated)) throw TranslationFailure.ServiceUnavailable
            return TranslationResult(translated, source, request.targetLanguage, "本机翻译 · 离线")
        } catch (error: CancellationException) {
            throw error
        } catch (error: TranslationFailure) {
            throw error
        } catch (_: Exception) {
            throw TranslationFailure.ProviderRejected("本机翻译未完成，请在“设置 → 翻译与 AI”检查语言包后重试。")
        } finally {
            modelLock.unlock()
        }
    }

    private suspend fun readInstalled(): Set<LanguageCode> {
        val installed = backend.installedLanguages()
        currentCoroutineContext().ensureActive()
        state.update { it.copy(installed = installed, loading = false) }
        return installed
    }

    companion object {
        fun requiredModels(source: LanguageCode, target: LanguageCode) =
            setOf(source, target) - LanguageCode.ENGLISH
    }
}

fun LanguageCode.modelTitle(): String = when (this) {
    LanguageCode.CHINESE_SIMPLIFIED -> "中文"
    LanguageCode.JAPANESE -> "日语"
    LanguageCode.ENGLISH -> "英语"
}
