package com.jerreader.android.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import com.jerreader.unified.domain.LanguageCode
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred

class AndroidSpeechService(context: Context) : TextToSpeech.OnInitListener {
    private val initialized = CompletableDeferred<Boolean>()
    private val engine = TextToSpeech(context.applicationContext, this)

    override fun onInit(status: Int) {
        if (!initialized.isCompleted) initialized.complete(status == TextToSpeech.SUCCESS)
    }

    suspend fun speak(text: String, language: LanguageCode, rate: Float = 1f) {
        require(text.isNotBlank())
        check(initialized.await()) { "系统语音初始化失败。" }
        val locale = when (language) {
            LanguageCode.JAPANESE -> Locale.JAPAN
            LanguageCode.ENGLISH -> Locale.ENGLISH
            LanguageCode.CHINESE_SIMPLIFIED -> Locale.SIMPLIFIED_CHINESE
        }
        check(engine.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE) {
            "设备尚未安装所需的系统语音。"
        }
        engine.language = locale
        engine.setSpeechRate(rate.coerceIn(0.5f, 2f))
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
    }

    fun stop() {
        engine.stop()
    }
}
