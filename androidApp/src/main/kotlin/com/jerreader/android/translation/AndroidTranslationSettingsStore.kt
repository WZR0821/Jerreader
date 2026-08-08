package com.jerreader.android.translation

import android.content.Context
import com.jerreader.shared.domain.LanguageCode
import com.jerreader.shared.translation.DirectAIProvider
import com.jerreader.shared.translation.QuickTranslationUnit
import com.jerreader.shared.translation.TranslationPreferences
import com.jerreader.shared.translation.TranslationProviderMode
import com.jerreader.shared.translation.TranslationFallbackMode
import com.jerreader.shared.translation.TranslationDisplayMode
import com.jerreader.shared.translation.TranslationSourceChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TranslationServiceConfiguration(
    val preferences: TranslationPreferences,
    val endpoint: String,
    val model: String,
    val credential: String?
)

class AndroidTranslationSettingsStore(
    context: Context,
    private val credentials: TranslationCredentialStore = AndroidKeystoreCredentialStore(context)
) {
    private val storage = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mutablePreferences = MutableStateFlow(load())
    val preferences: StateFlow<TranslationPreferences> = mutablePreferences.asStateFlow()

    fun directApiKey(provider: DirectAIProvider = preferences.value.directProvider): String =
        credentials.read(directCredentialAccount(provider)).orEmpty()

    fun backendAccessToken(): String =
        credentials.read(BACKEND_CREDENTIAL_ACCOUNT).orEmpty()

    fun updateProviderMode(value: TranslationProviderMode) = update(KEY_PROVIDER_MODE, value.name) {
        copy(providerMode = value)
    }

    fun updateDirectProvider(value: DirectAIProvider) {
        storage.edit().putString(KEY_DIRECT_PROVIDER, value.name).apply()
        mutablePreferences.value = mutablePreferences.value.copy(
            directProvider = value,
            directEndpoint = storedEndpoint(value),
            directModel = storedModel(value),
            directApiKeyPresent = directApiKey(value).isNotEmpty()
        )
    }

    fun updateDirectEndpoint(value: String) {
        storage.edit().putString(endpointKey(preferences.value.directProvider), value).apply()
        mutablePreferences.value = mutablePreferences.value.copy(directEndpoint = value)
    }

    fun updateDirectModel(value: String) {
        storage.edit().putString(modelKey(preferences.value.directProvider), value).apply()
        mutablePreferences.value = mutablePreferences.value.copy(directModel = value)
    }

    fun updateDirectApiKey(value: String) {
        credentials.save(directCredentialAccount(preferences.value.directProvider), value)
        mutablePreferences.value = mutablePreferences.value.copy(
            directApiKeyPresent = value.trim().isNotEmpty()
        )
    }

    fun updateBackendEndpoint(value: String) = update(KEY_BACKEND_ENDPOINT, value) {
        copy(backendEndpoint = value)
    }

    fun updateBackendModel(value: String) = update(KEY_BACKEND_MODEL, value) {
        copy(backendModel = value)
    }

    fun updateBackendAccessToken(value: String) {
        credentials.save(BACKEND_CREDENTIAL_ACCOUNT, value)
        mutablePreferences.value = mutablePreferences.value.copy(
            backendAccessTokenPresent = value.trim().isNotEmpty()
        )
    }

    fun updateSourceChoice(value: TranslationSourceChoice) = update(KEY_SOURCE, value.name) {
        copy(sourceChoice = value)
    }

    fun updateTargetLanguage(value: LanguageCode) = update(KEY_TARGET, value.name) {
        copy(targetLanguage = value)
    }

    fun updateQuickTranslationEnabled(value: Boolean) = update(KEY_QUICK_ENABLED, value) {
        copy(quickTranslationEnabled = value)
    }

    fun updateQuickTranslationUnit(value: QuickTranslationUnit) = update(KEY_QUICK_UNIT, value.name) {
        copy(quickTranslationUnit = value)
    }

    fun updateDisablesTapPageTurns(value: Boolean) = update(KEY_DISABLES_TAP_PAGE_TURNS, value) {
        copy(disablesTapPageTurnsDuringQuickTranslation = value)
    }

    fun updateDisplayMode(value: TranslationDisplayMode) = update(KEY_DISPLAY_MODE, value.name) {
        copy(displayMode = value)
    }

    fun updateTranslationHaptics(value: Boolean) = update(KEY_HAPTICS, value) {
        copy(translationHapticsEnabled = value)
    }

    fun updateAutomaticRetry(value: Boolean) = update(KEY_AUTOMATIC_RETRY, value) {
        copy(automaticRetryEnabled = value)
    }

    fun updateFallbackMode(value: TranslationFallbackMode) = update(KEY_FALLBACK_MODE, value.name) {
        copy(fallbackMode = value)
    }

    fun updateTranslationPrompt(value: String) = update(KEY_PROMPT, value) {
        copy(translationPromptTemplate = value)
    }

    /**
     * Restores the translation preferences held in a backup archive. The API
     * key and the proxy credential are never in an archive, so they are left
     * untouched here and have to be entered again on a new device.
     */
    fun restoreFromBackup(payload: org.json.JSONObject) {
        val current = preferences.value
        fun <T : Enum<T>> enumOrCurrent(raw: String?, values: Array<T>, fallback: T): T =
            values.firstOrNull { it.name == raw } ?: fallback

        updateProviderMode(
            enumOrCurrent(
                payload.optString("providerMode"),
                TranslationProviderMode.entries.toTypedArray(),
                current.providerMode
            )
        )
        updateDirectProvider(
            enumOrCurrent(
                payload.optString("directProvider"),
                DirectAIProvider.entries.toTypedArray(),
                current.directProvider
            )
        )
        updateDirectEndpoint(payload.optString("directEndpoint", current.directEndpoint))
        updateDirectModel(payload.optString("directModel", current.directModel))
        updateBackendEndpoint(payload.optString("backendEndpoint", current.backendEndpoint))
        updateBackendModel(payload.optString("backendModel", current.backendModel))
        updateSourceChoice(
            enumOrCurrent(
                payload.optString("sourceChoice"),
                TranslationSourceChoice.entries.toTypedArray(),
                current.sourceChoice
            )
        )
        updateTargetLanguage(
            enumOrCurrent(
                payload.optString("targetLanguage"),
                LanguageCode.entries.toTypedArray(),
                current.targetLanguage
            )
        )
        updateQuickTranslationEnabled(
            payload.optBoolean("quickTranslationEnabled", current.quickTranslationEnabled)
        )
        updateQuickTranslationUnit(
            enumOrCurrent(
                payload.optString("quickTranslationUnit"),
                QuickTranslationUnit.entries.toTypedArray(),
                current.quickTranslationUnit
            )
        )
        updateDisablesTapPageTurns(
            payload.optBoolean(
                "disablesTapPageTurnsDuringQuickTranslation",
                current.disablesTapPageTurnsDuringQuickTranslation
            )
        )
        updateDisplayMode(
            enumOrCurrent(
                payload.optString("displayMode"),
                TranslationDisplayMode.entries.toTypedArray(),
                current.displayMode
            )
        )
        updateTranslationHaptics(
            payload.optBoolean("translationHapticsEnabled", current.translationHapticsEnabled)
        )
        updateAutomaticRetry(
            payload.optBoolean("automaticRetryEnabled", current.automaticRetryEnabled)
        )
        updateFallbackMode(
            enumOrCurrent(
                payload.optString("fallbackMode"),
                TranslationFallbackMode.entries.toTypedArray(),
                current.fallbackMode
            )
        )
        updateTranslationPrompt(
            payload.optString("translationPromptTemplate", current.translationPromptTemplate)
        )
        updateGrammarPrompt(
            payload.optString("grammarAnalysisPromptTemplate", current.grammarAnalysisPromptTemplate)
        )
    }

    fun updateGrammarPrompt(value: String) = update(KEY_GRAMMAR_PROMPT, value) {
        copy(grammarAnalysisPromptTemplate = value)
    }

    /**
     * [promptTemplate] lets the standalone translate page run one request with
     * its own prompt — word mode needs a dictionary answer — without writing
     * that prompt back into the settings the reader uses.
     */
    fun currentServiceConfiguration(
        mode: TranslationProviderMode = preferences.value.providerMode,
        promptTemplate: String? = null
    ): TranslationServiceConfiguration {
        val current = preferences.value
        val servicePreferences = current.copy(
            providerMode = mode,
            translationPromptTemplate = promptTemplate ?: current.translationPromptTemplate
        )
        return if (mode == TranslationProviderMode.DIRECT_API) {
            TranslationServiceConfiguration(
                preferences = servicePreferences,
                endpoint = if (current.directProvider.usesCustomEndpoint) {
                    current.directEndpoint
                } else {
                    current.directProvider.defaultEndpoint
                },
                model = current.directModel,
                credential = directApiKey(current.directProvider).ifBlank { null }
            )
        } else if (mode == TranslationProviderMode.BACKEND_PROXY) {
            TranslationServiceConfiguration(
                preferences = servicePreferences,
                endpoint = current.backendEndpoint,
                model = current.backendModel,
                credential = backendAccessToken().ifBlank { null }
            )
        } else {
            TranslationServiceConfiguration(
                preferences = servicePreferences,
                endpoint = "on-device://ml-kit",
                model = "ml-kit-translate-17.0.3",
                credential = null
            )
        }
    }

    fun preferredAIProviderMode(): TranslationProviderMode? {
        val current = preferences.value
        if (current.providerMode != TranslationProviderMode.ON_DEVICE) return current.providerMode
        val fallback = current.fallbackMode.providerMode
        if (fallback != null && fallback != TranslationProviderMode.ON_DEVICE) return fallback
        if (directApiKey(current.directProvider).isNotBlank()) return TranslationProviderMode.DIRECT_API
        if (current.backendEndpoint.isNotBlank()) return TranslationProviderMode.BACKEND_PROXY
        return null
    }

    /**
     * A page that overrides the provider or the prompt must not read back
     * results the other configuration produced, so both overrides take part in
     * the namespace exactly as the stored values do.
     */
    fun cacheNamespace(
        mode: TranslationProviderMode? = null,
        promptTemplate: String? = null
    ): String {
        val current = preferences.value
        return listOf(
            (mode ?: current.providerMode).name,
            current.fallbackMode.name,
            current.directProvider.name,
            current.directEndpoint.trim(),
            current.directModel.trim(),
            current.backendEndpoint.trim(),
            current.backendModel.trim(),
            promptTemplate ?: current.translationPromptTemplate,
            current.grammarAnalysisPromptTemplate
        ).joinToString("\u001F")
    }

    private fun load(): TranslationPreferences {
        val provider = enumValueOrDefault(
            storage.getString(KEY_DIRECT_PROVIDER, null),
            DirectAIProvider.DEEPSEEK
        )
        return TranslationPreferences(
            providerMode = enumValueOrDefault(
                storage.getString(KEY_PROVIDER_MODE, null),
                TranslationPreferences().providerMode
            ),
            directProvider = provider,
            directEndpoint = storedEndpoint(provider),
            directModel = storedModel(provider),
            backendEndpoint = storage.getString(KEY_BACKEND_ENDPOINT, "").orEmpty(),
            backendModel = storage.getString(KEY_BACKEND_MODEL, "").orEmpty(),
            sourceChoice = enumValueOrDefault(
                storage.getString(KEY_SOURCE, null),
                TranslationSourceChoice.AUTOMATIC
            ),
            targetLanguage = enumValueOrDefault(
                storage.getString(KEY_TARGET, null),
                LanguageCode.CHINESE_SIMPLIFIED
            ),
            quickTranslationEnabled = storage.getBoolean(KEY_QUICK_ENABLED, true),
            quickTranslationUnit = enumValueOrDefault(
                storage.getString(KEY_QUICK_UNIT, null),
                QuickTranslationUnit.SENTENCE
            ),
            disablesTapPageTurnsDuringQuickTranslation = storage.getBoolean(
                KEY_DISABLES_TAP_PAGE_TURNS,
                true
            ),
            displayMode = enumValueOrDefault(
                storage.getString(KEY_DISPLAY_MODE, null),
                TranslationDisplayMode.NEAR_SELECTION
            ),
            translationHapticsEnabled = storage.getBoolean(KEY_HAPTICS, true),
            automaticRetryEnabled = storage.getBoolean(KEY_AUTOMATIC_RETRY, true),
            fallbackMode = enumValueOrDefault(
                storage.getString(KEY_FALLBACK_MODE, null),
                TranslationFallbackMode.NONE
            ),
            translationPromptTemplate = storage.getString(
                KEY_PROMPT,
                TranslationPreferences.DEFAULT_TRANSLATION_PROMPT
            ).orEmpty(),
            grammarAnalysisPromptTemplate = storage.getString(
                KEY_GRAMMAR_PROMPT,
                TranslationPreferences.DEFAULT_GRAMMAR_PROMPT
            ).orEmpty(),
            directApiKeyPresent = directApiKey(provider).isNotEmpty(),
            backendAccessTokenPresent = backendAccessToken().isNotEmpty()
        )
    }

    private fun storedEndpoint(provider: DirectAIProvider): String =
        storage.getString(endpointKey(provider), null) ?: provider.defaultEndpoint

    private fun storedModel(provider: DirectAIProvider): String =
        storage.getString(modelKey(provider), null) ?: provider.defaultModel

    private fun update(
        key: String,
        value: Any,
        transform: TranslationPreferences.() -> TranslationPreferences
    ) {
        val editor = storage.edit()
        when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
        }
        editor.apply()
        mutablePreferences.value = mutablePreferences.value.transform()
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(raw: String?, default: T): T =
        raw?.let { value -> enumValues<T>().firstOrNull { it.name == value } } ?: default

    private fun directCredentialAccount(provider: DirectAIProvider) = "direct-api-${provider.name}"
    private fun endpointKey(provider: DirectAIProvider) = "direct.endpoint.${provider.name}"
    private fun modelKey(provider: DirectAIProvider) = "direct.model.${provider.name}"

    private companion object {
        const val PREFERENCES_NAME = "jerreader_translation_settings"
        const val KEY_PROVIDER_MODE = "provider.mode"
        const val KEY_DIRECT_PROVIDER = "direct.provider"
        const val KEY_BACKEND_ENDPOINT = "backend.endpoint"
        const val KEY_BACKEND_MODEL = "backend.model"
        const val KEY_SOURCE = "source.language"
        const val KEY_TARGET = "target.language"
        const val KEY_QUICK_ENABLED = "quick.enabled"
        const val KEY_QUICK_UNIT = "quick.unit"
        const val KEY_DISABLES_TAP_PAGE_TURNS = "quick.disablesTapPageTurns"
        const val KEY_DISPLAY_MODE = "display.mode"
        const val KEY_HAPTICS = "translation.haptics"
        const val KEY_AUTOMATIC_RETRY = "automatic.retry"
        const val KEY_FALLBACK_MODE = "fallback.mode"
        const val KEY_PROMPT = "translation.prompt"
        const val KEY_GRAMMAR_PROMPT = "grammar.prompt"
        const val BACKEND_CREDENTIAL_ACCOUNT = "backend-proxy-token"
    }
}
