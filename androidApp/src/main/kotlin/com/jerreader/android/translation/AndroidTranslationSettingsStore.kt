package com.jerreader.android.translation

import androidx.core.content.edit
import android.content.Context
import com.jerreader.shared.domain.LanguageCode
import com.jerreader.shared.translation.QuickTranslationUnit
import com.jerreader.shared.translation.TranslationPreferences
import com.jerreader.shared.translation.TranslationProviderMode
import com.jerreader.shared.translation.TranslationFallbackMode
import com.jerreader.shared.translation.TranslationDisplayMode
import com.jerreader.shared.translation.TranslationSourceChoice
import com.jerreader.android.translation.chatgptplan.ChatGPTPlan
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanAccount
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanEntries
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanHttpClient
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanModel
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanSession
import com.jerreader.android.translation.chatgptplan.KeystoreChatGPTPlanCredentialStore
import com.jerreader.android.translation.chatgptplan.UrlConnectionChatGPTPlanHttpClient
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** One model of one provider, ready to be called. */
data class DirectAIConfiguration(
    val kind: AIProviderKind,
    val displayName: String,
    /** The full request URL; for Gemini's own format, the address the model path is added to. */
    val endpoint: String,
    val model: String,
    val apiKey: String,
    val style: AIAPIStyle,
    /** `null` is 自动. */
    val reasoning: AIReasoningEffort?
)

data class TranslationServiceConfiguration(
    val preferences: TranslationPreferences,
    val endpoint: String,
    val model: String,
    val credential: String?,
    /** Set for the AI 大模型 service when the provider in use can be called. */
    val direct: DirectAIConfiguration? = null,
    /** Why the AI 大模型 service cannot be called, when it cannot. */
    val directIssue: AIProviderSettingsError? = null
)

class AndroidTranslationSettingsStore(
    context: Context,
    private val credentials: TranslationCredentialStore = AndroidKeystoreCredentialStore(context),
    /** Tests keep their settings apart from the app's. */
    preferencesName: String = PREFERENCES_NAME,
    /** Sends the ChatGPT plan's sign-in and model requests; tests pass a fake. */
    planClient: ChatGPTPlanHttpClient = UrlConnectionChatGPTPlanHttpClient()
) {
    private val storage = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val mutablePreferences = MutableStateFlow(load())
    val preferences: StateFlow<TranslationPreferences> = mutablePreferences.asStateFlow()

    /** Keys read from the keystore, by provider entry. */
    private val apiKeys = ConcurrentHashMap<String, String>()
    private val mutableProviders = MutableStateFlow(restoredProviders())

    /** Every AI provider that was set up, and the one in use. */
    val aiProviders: StateFlow<AIProviderSettings> = mutableProviders.asStateFlow()
    private val mutableKeyedProviderIDs = MutableStateFlow<Set<String>>(emptySet())

    /** The providers whose API key is in the keystore. */
    val keyedProviderIDs: StateFlow<Set<String>> = mutableKeyedProviderIDs.asStateFlow()
    private val mutableCredentialError = MutableStateFlow<String?>(null)

    /** Set when a key could not be saved to or read from the keystore. */
    val credentialError: StateFlow<String?> = mutableCredentialError.asStateFlow()

    /**
     * The signed-in ChatGPT plan. Its credential is kept in [credentials]
     * under its own account, beside the API keys.
     */
    val chatGPTPlanSession = ChatGPTPlanSession(
        store = KeystoreChatGPTPlanCredentialStore(credentials),
        client = planClient,
        onChange = { credential -> chatGPTPlanSessionChanged(credential?.account) }
    )
    private val mutableChatGPTPlanAccount = MutableStateFlow(chatGPTPlanSession.current()?.account)

    /** The ChatGPT account the plan is signed in with, or `null`. */
    val chatGPTPlanAccount: StateFlow<ChatGPTPlanAccount?> = mutableChatGPTPlanAccount.asStateFlow()
    private val mutableChatGPTPlanModelNames = MutableStateFlow(restoredPlanModelNames())

    /** The names the signed-in account gives its models, by slug. */
    val chatGPTPlanModelNames: StateFlow<Map<String, String>> = mutableChatGPTPlanModelNames.asStateFlow()

    init {
        readKeys(mutableProviders.value)
        persistProviders(mutableProviders.value)
    }

    fun backendAccessToken(): String =
        credentials.read(BACKEND_CREDENTIAL_ACCOUNT).orEmpty()

    fun updateProviderMode(value: TranslationProviderMode) = update(KEY_PROVIDER_MODE, value.name) {
        copy(providerMode = value)
    }

    fun updateBackendEndpoint(value: String) = update(KEY_BACKEND_ENDPOINT, value) {
        copy(backendEndpoint = value)
    }

    fun updateBackendModel(value: String) = update(KEY_BACKEND_MODEL, value) {
        copy(backendModel = value)
    }

    fun updateBackendAccessToken(value: String) {
        try {
            credentials.save(BACKEND_CREDENTIAL_ACCOUNT, value)
            mutableCredentialError.value = null
        } catch (error: Exception) {
            mutableCredentialError.value = "无法将代理凭据安全保存到本机安全存储。"
        }
        mutablePreferences.value = mutablePreferences.value.copy(
            backendAccessTokenPresent = value.trim().isNotEmpty()
        )
    }

    /** Whether the proxy address is an HTTPS URL with a host, as the proxy needs. */
    fun backendConfigurationReady(): Boolean {
        val uri = runCatching { java.net.URI(preferences.value.backendEndpoint.trim()) }.getOrNull()
            ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
    }

    /** One line on the state of the AI proxy. */
    fun backendConfigurationMessage(): String = when {
        preferences.value.backendEndpoint.isBlank() -> "请填写你自己控制的 HTTPS 翻译代理地址。"
        !backendConfigurationReady() -> "代理地址必须是带有完整主机名的 HTTPS URL。"
        credentialError.value != null -> "代理地址有效，但访问凭据未能安全保存。"
        else -> "配置已就绪；只会发送你主动选中的文字。"
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

    fun updateGrammarPrompt(value: String) = update(KEY_GRAMMAR_PROMPT, value) {
        copy(grammarAnalysisPromptTemplate = value)
    }

    // MARK: AI providers

    /** A provider's key; for the ChatGPT plan, a stand-in while it is signed in. */
    fun apiKey(id: String): String {
        if (id == CHATGPT_PLAN_ID) return if (mutableChatGPTPlanAccount.value == null) "" else PLAN_KEY_PLACEHOLDER
        return apiKeys[id].orEmpty()
    }

    /**
     * A request configuration for one model of a provider, which need not be
     * saved yet. Throws the reason it cannot be used.
     */
    fun configuration(entry: AIProviderEntry, model: AIModelEntry, key: String): DirectAIConfiguration {
        val modelID = model.id.trim()
        if (entry.kind.usesAccountSignIn) {
            // The signed-in session stands in for a key; the request asks it for a token.
            if (mutableChatGPTPlanAccount.value == null) {
                throw AIProviderSettingsError.SignInRequired(entry.displayName)
            }
            if (modelID.isEmpty()) throw AIProviderSettingsError.MissingModel()
            return DirectAIConfiguration(
                kind = entry.kind,
                displayName = entry.displayName,
                endpoint = ChatGPTPlan.RESPONSES_URL,
                model = modelID,
                apiKey = PLAN_KEY_PLACEHOLDER,
                style = AIAPIStyle.RESPONSES,
                reasoning = model.reasoning
            )
        }
        val apiKey = key.trim()
        if (apiKey.isEmpty()) throw AIProviderSettingsError.MissingKey(entry.displayName)
        if (modelID.isEmpty()) throw AIProviderSettingsError.MissingModel()
        val endpoint = AIEndpoint.url(entry.baseURL, model.style) ?: throw AIProviderSettingsError.InvalidAddress()
        return DirectAIConfiguration(
            kind = entry.kind,
            displayName = entry.displayName,
            endpoint = endpoint,
            model = modelID,
            apiKey = apiKey,
            style = model.style,
            reasoning = model.reasoning
        )
    }

    /** The provider in use, ready to be called, or `null` when it cannot be. */
    fun directConfiguration(): DirectAIConfiguration? = runCatching { activeConfiguration() }.getOrNull()

    private fun activeConfiguration(): DirectAIConfiguration {
        val entry = aiProviders.value.active
        return configuration(entry, entry.activeModel, apiKey(entry.id))
    }

    /** One line on the state of the provider in use. */
    fun directAPIConfigurationMessage(): String {
        val entry = aiProviders.value.active
        try {
            activeConfiguration()
        } catch (error: AIProviderSettingsError) {
            return error.message ?: "配置无效，请检查 API 地址、模型和密钥。"
        }
        if (credentialError.value != null) return "配置可用，但 API Key 未能安全保存。"
        return "${entry.displayName} · ${entry.model}"
    }

    /** Applies a change to the providers and saves it. */
    @Synchronized
    fun changeProviders(change: (AIProviderSettings) -> AIProviderSettings) {
        val current = mutableProviders.value
        val value = change(current)
        if (value == current) return
        mutableProviders.value = value
        persistProviders(value)
    }

    /** Saves a provider's key to the keystore; an empty key removes it. */
    @Synchronized
    fun saveKey(key: String, id: String) {
        val trimmed = key.trim()
        try {
            credentials.save(credentialAccount(id), trimmed.ifEmpty { null })
            // A key moves from the account it had before providers kept lists.
            legacyCredentialAccount(id)?.let { credentials.save(it, null) }
            mutableCredentialError.value = null
        } catch (error: Exception) {
            mutableCredentialError.value = "无法将 API Key 安全保存到本机安全存储。"
            throw AIProviderSettingsError.KeystoreUnavailable()
        }
        if (trimmed.isEmpty()) apiKeys.remove(id) else apiKeys[id] = trimmed
        mutableKeyedProviderIDs.value = if (trimmed.isEmpty()) {
            mutableKeyedProviderIDs.value - id
        } else {
            mutableKeyedProviderIDs.value + id
        }
    }

    /**
     * Adds a provider with its key.
     *
     * @return whether it is now in use, which it is only when the provider in
     *   use had no key.
     */
    fun addProvider(entry: AIProviderEntry, key: String): Boolean {
        if (!AIEndpoint.isValid(entry.baseURL)) throw AIProviderSettingsError.InvalidAddress()
        val activeHasKey = aiProviders.value.activeID in keyedProviderIDs.value
        saveKey(key, entry.id)
        var activated = false
        changeProviders { providers ->
            val (value, inUse) = providers.adding(entry, activeHasKey)
            activated = inUse
            value
        }
        return activated
    }

    /** Saves a provider's name, address and key together. */
    fun saveProvider(id: String, name: String?, baseURL: String, key: String) {
        if (aiProviders.value.entry(id) == null) throw AIProviderSettingsError.UnknownProvider()
        val address = baseURL.trim()
        if (!AIEndpoint.isValid(address)) throw AIProviderSettingsError.InvalidAddress()
        if (key.trim() != apiKey(id)) saveKey(key, id)
        changeProviders { providers ->
            providers.updating(id) { entry ->
                val label = name?.trim().orEmpty()
                entry.copy(
                    baseURL = address,
                    name = if (entry.kind.isCustom) label.ifEmpty { null } else entry.name
                )
            }
        }
    }

    /** Removes a provider and its key. The provider in use cannot be removed. */
    fun removeProvider(id: String) {
        if (id == aiProviders.value.activeID) throw AIProviderSettingsError.RemovingActiveProvider()
        if (id == CHATGPT_PLAN_ID) {
            // Forgets the account here; OpenAI is asked to end the sign-in in the background.
            chatGPTPlanSession.signOutInBackground()
            chatGPTPlanSignedOut()
        } else {
            saveKey("", id)
        }
        changeProviders { it.removing(id) }
    }

    // MARK: ChatGPT plan

    /**
     * Records a finished sign-in with the account's models. The plan is added
     * as a provider, or refreshed, and put in use: signing in is how the user
     * chooses it.
     */
    @Synchronized
    fun chatGPTPlanSignedIn(account: ChatGPTPlanAccount, models: List<ChatGPTPlanModel>) {
        mutableChatGPTPlanAccount.value = account
        mutableKeyedProviderIDs.value = mutableKeyedProviderIDs.value + CHATGPT_PLAN_ID
        val entry = mergedPlanEntry(models)
        // Without a model the plan cannot translate yet, so the provider in
        // use stays until the models are refreshed.
        val usable = entry.model.isNotEmpty()
        val keyed = mutableKeyedProviderIDs.value
        changeProviders { providers ->
            // The provider in use keeps its place in the list when it has a
            // key: the plan takes over, it does not replace what was set up.
            providers.adding(
                entry,
                activeHasKey = !usable || providers.active.id in keyed,
                takesOver = usable
            ).first
        }
    }

    /** Mirrors the session's account: `null` when the session ended by itself. */
    @Synchronized
    fun chatGPTPlanSessionChanged(account: ChatGPTPlanAccount?) {
        if (account == mutableChatGPTPlanAccount.value) return
        if (account != null) {
            mutableChatGPTPlanAccount.value = account
            mutableKeyedProviderIDs.value = mutableKeyedProviderIDs.value + CHATGPT_PLAN_ID
        } else {
            chatGPTPlanSignedOut()
        }
    }

    /** Takes the account's current models; the reasoning chosen for a model still offered is kept. */
    @Synchronized
    fun chatGPTPlanModelsRefreshed(models: List<ChatGPTPlanModel>) {
        if (aiProviders.value.entry(CHATGPT_PLAN_ID) == null) return
        val entry = mergedPlanEntry(models)
        changeProviders { providers -> providers.updating(CHATGPT_PLAN_ID) { _ -> entry } }
    }

    /** The account is gone from this device; the provider stays, signed out. */
    @Synchronized
    fun chatGPTPlanSignedOut() {
        mutableChatGPTPlanAccount.value = null
        mutableKeyedProviderIDs.value = mutableKeyedProviderIDs.value - CHATGPT_PLAN_ID
    }

    /** The name the account gives a model, or its slug. */
    fun chatGPTPlanModelName(slug: String): String = mutableChatGPTPlanModelNames.value[slug] ?: slug

    /** The name shown for a model: a plan model by the name its account gives it, any other by its id. */
    fun modelTitle(id: String, entry: AIProviderEntry?): String =
        if (entry?.kind?.usesAccountSignIn == true) chatGPTPlanModelName(id) else id

    /** This install's host id for Sign in with ChatGPT: made once, not a secret, never backed up. */
    fun chatGPTPlanHostID(): String {
        val saved = storage.getString(KEY_PLAN_HOST_ID, null)
        if (saved != null && saved.startsWith("urn:uuid:")) return saved
        val made = "urn:uuid:" + UUID.randomUUID().toString().lowercase(Locale.ROOT)
        storage.edit { putString(KEY_PLAN_HOST_ID, made) }
        return made
    }

    /** The client id OpenAI issued at this install's first sign-in, if there was one. */
    fun chatGPTPlanClientID(): String? = storage.getString(KEY_PLAN_CLIENT_ID, null)?.takeIf { it.isNotEmpty() }

    fun saveChatGPTPlanClientID(id: String) {
        storage.edit { putString(KEY_PLAN_CLIENT_ID, id) }
    }

    /**
     * Set when the plan's use was declined or OpenAI said the sign-in lacks
     * it: the next sign-in shows OpenAI's consent page again. Cleared by a
     * sign-in that grants it.
     */
    var chatGPTPlanConsentNeeded: Boolean
        get() = storage.getBoolean(KEY_PLAN_CONSENT_NEEDED, false)
        set(value) {
            storage.edit {
                if (value) putBoolean(KEY_PLAN_CONSENT_NEEDED, true) else remove(KEY_PLAN_CONSENT_NEEDED)
            }
        }

    /** Whether the page shown after the first sign-in was closed. */
    var chatGPTPlanWelcomeShown: Boolean
        get() = storage.getBoolean(KEY_PLAN_WELCOME_SHOWN, false)
        set(value) {
            storage.edit { putBoolean(KEY_PLAN_WELCOME_SHOWN, value) }
        }

    private fun mergedPlanEntry(models: List<ChatGPTPlanModel>): AIProviderEntry {
        if (models.isNotEmpty()) {
            val names = LinkedHashMap(mutableChatGPTPlanModelNames.value)
            for (model in models) names[model.slug] = model.displayName
            mutableChatGPTPlanModelNames.value = names
            val json = JSONObject()
            for ((slug, name) in names) json.put(slug, name)
            storage.edit { putString(KEY_PLAN_MODEL_NAMES, json.toString()) }
        }
        return ChatGPTPlanEntries.merged(aiProviders.value.entry(CHATGPT_PLAN_ID), models)
    }

    private fun restoredPlanModelNames(): Map<String, String> {
        val text = storage.getString(KEY_PLAN_MODEL_NAMES, null) ?: return emptyMap()
        val json = try {
            JSONObject(text)
        } catch (_: Exception) {
            return emptyMap()
        }
        val names = LinkedHashMap<String, String>()
        val slugs = json.keys()
        while (slugs.hasNext()) {
            val slug = slugs.next()
            val name = json.opt(slug) as? String
            if (name != null) names[slug] = name
        }
        return names
    }

    /**
     * The non-sensitive translation settings written to a backup archive. The
     * providers travel with their addresses and models; API keys and the proxy
     * credential stay in the keystore on this device.
     */
    fun backupJson(): JSONObject {
        val current = preferences.value
        val providers = aiProviders.value
        val active = providers.active
        return JSONObject()
            .put("providerMode", current.providerMode.name)
            .put("aiProviders", providers.toJson())
            // The provider in use, in the fields versions before 1.6.0 read.
            .put("directProvider", active.kind.legacyName ?: AIProviderKind.OPENAI_COMPATIBLE.legacyName)
            .put("directEndpoint", AIEndpoint.url(active.baseURL, active.activeModel.style) ?: active.baseURL)
            .put("directModel", active.model)
            .put("backendEndpoint", current.backendEndpoint)
            .put("backendModel", current.backendModel)
            .put("sourceChoice", current.sourceChoice.name)
            .put("targetLanguage", current.targetLanguage.name)
            .put("quickTranslationEnabled", current.quickTranslationEnabled)
            .put("quickTranslationUnit", current.quickTranslationUnit.name)
            .put("disablesTapPageTurnsDuringQuickTranslation", current.disablesTapPageTurnsDuringQuickTranslation)
            .put("displayMode", current.displayMode.name)
            .put("translationHapticsEnabled", current.translationHapticsEnabled)
            .put("automaticRetryEnabled", current.automaticRetryEnabled)
            .put("fallbackMode", current.fallbackMode.name)
            .put("translationPromptTemplate", current.translationPromptTemplate)
            .put("grammarAnalysisPromptTemplate", current.grammarAnalysisPromptTemplate)
    }

    /**
     * Restores the translation preferences held in a backup archive. The API
     * keys and the proxy credential are never in an archive, so they are left
     * untouched here and have to be entered again on a new device.
     */
    fun restoreFromBackup(payload: JSONObject) {
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
        restoreProviders(payload)
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

    /**
     * An archive from 1.6.0 on carries every provider; an older one only the
     * provider it used, which is put in use beside the ones already here.
     */
    private fun restoreProviders(payload: JSONObject) {
        val restored = AIProviderSettings.fromJson(payload.optJSONObject("aiProviders"))
        if (restored != null) {
            changeProviders { restored }
            readKeys(restored)
            return
        }
        val kind = AIProviderKind.fromLegacyName(payload.optString("directProvider")) ?: return
        val entry = AIProviderSettings.migrated(
            selected = kind,
            keyed = emptySet(),
            storedModels = mapOf(kind to payload.optString("directModel")),
            storedEndpoints = mapOf(kind to payload.optString("directEndpoint"))
        ).entry(kind.raw) ?: return
        changeProviders { providers ->
            if (providers.entry(entry.id) != null) {
                providers.activating(entry.id, entry.model.ifEmpty { null })
            } else {
                providers.adding(entry, activeHasKey = false).first
            }
        }
        readKeys(aiProviders.value)
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
            val entry = aiProviders.value.active
            try {
                val direct = activeConfiguration()
                TranslationServiceConfiguration(
                    preferences = servicePreferences,
                    endpoint = direct.endpoint,
                    model = direct.model,
                    credential = direct.apiKey,
                    direct = direct
                )
            } catch (error: AIProviderSettingsError) {
                TranslationServiceConfiguration(
                    preferences = servicePreferences,
                    endpoint = AIEndpoint.url(entry.baseURL, entry.activeModel.style).orEmpty(),
                    model = entry.model.trim(),
                    credential = apiKey(entry.id).ifBlank { null },
                    directIssue = error
                )
            }
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
        if (apiKey(aiProviders.value.activeID).isNotBlank()) return TranslationProviderMode.DIRECT_API
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
        val direct = directCacheIdentity()
        return (listOf(
            (mode ?: current.providerMode).name,
            current.fallbackMode.name
        ) + direct.take(3) + listOf(
            current.backendEndpoint.trim(),
            current.backendModel.trim(),
            promptTemplate ?: current.translationPromptTemplate,
            current.grammarAnalysisPromptTemplate
        ) + direct.drop(3)).joinToString("\u001F")
    }

    /**
     * Provider, request URL and model of the provider in use, in the form the
     * namespace had before providers kept lists, then its format and reasoning
     * when they differ from how the provider was called before. A provider
     * carried over from those settings therefore keeps its cached results.
     */
    private fun directCacheIdentity(): List<String> {
        val providers = aiProviders.value
        val entry = providers.active
        // An install that never set up a provider keeps the identity it had
        // before, whatever models the fresh default now starts with.
        if (providers.isUntouchedDefault(entry) && apiKey(entry.id).isEmpty()) {
            val kind = AIProviderKind.DEEPSEEK
            return listOf(kind.legacyName.orEmpty(), kind.legacyEndpoint, kind.legacyDefaultModel)
        }
        val model = entry.activeModel
        val parts = mutableListOf(
            entry.kind.legacyName ?: entry.kind.raw,
            AIEndpoint.url(entry.baseURL, model.style) ?: entry.baseURL.trim(),
            entry.model.trim()
        )
        if (model.style != entry.kind.legacyStyle) parts += "style:" + model.style.raw
        model.reasoning?.let { parts += "reasoning:" + it.raw }
        return parts
    }

    private fun load(): TranslationPreferences = TranslationPreferences(
        providerMode = enumValueOrDefault(
            storage.getString(KEY_PROVIDER_MODE, null),
            TranslationPreferences().providerMode
        ),
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
        backendAccessTokenPresent = backendAccessToken().isNotEmpty()
    )

    /**
     * The saved providers, or, when none were saved yet, the ones built from
     * the settings kept before providers had a list of models.
     */
    private fun restoredProviders(): AIProviderSettings {
        storage.getString(KEY_AI_PROVIDERS, null)
            ?.let { text -> runCatching { AIProviderSettings.fromJson(JSONObject(text)) }.getOrNull() }
            ?.let { return it }
        val keyed = mutableSetOf<AIProviderKind>()
        val models = mutableMapOf<AIProviderKind, String>()
        val endpoints = mutableMapOf<AIProviderKind, String>()
        for (kind in AIProviderKind.legacyCases) {
            val name = kind.legacyName ?: continue
            if (!credentials.read("$CREDENTIAL_PREFIX$name").isNullOrBlank()) keyed += kind
            storage.getString("$LEGACY_MODEL_PREFIX$name", null)?.let { models[kind] = it }
            storage.getString("$LEGACY_ENDPOINT_PREFIX$name", null)?.let { endpoints[kind] = it }
        }
        return AIProviderSettings.migrated(
            selected = AIProviderKind.fromLegacyName(storage.getString(KEY_LEGACY_DIRECT_PROVIDER, null)),
            keyed = keyed,
            storedModels = models,
            storedEndpoints = endpoints
        )
    }

    /** Reads every provider's key, falling back to the account a key had before providers kept lists. */
    private fun readKeys(providers: AIProviderSettings) {
        val keyed = mutableSetOf<String>()
        apiKeys.clear()
        for (entry in providers.entries) {
            val key = runCatching {
                credentials.read(credentialAccount(entry.id))
                    ?: legacyCredentialAccount(entry.id)?.let(credentials::read)
            }.getOrNull()?.trim().orEmpty()
            if (key.isNotEmpty()) {
                apiKeys[entry.id] = key
                keyed += entry.id
            }
        }
        // The plan has no key: it counts as set up while it is signed in.
        if (mutableChatGPTPlanAccount.value != null) keyed += CHATGPT_PLAN_ID
        mutableKeyedProviderIDs.value = keyed
    }

    private fun persistProviders(value: AIProviderSettings) {
        storage.edit { putString(KEY_AI_PROVIDERS, value.toJson().toString()) }
    }

    private fun update(
        key: String,
        value: Any,
        transform: TranslationPreferences.() -> TranslationPreferences
    ) {
        storage.edit {
            when (value) {
                is String -> putString(key, value)
                is Boolean -> putBoolean(key, value)
            }
        }
        mutablePreferences.value = mutablePreferences.value.transform()
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(raw: String?, default: T): T =
        raw?.let { value -> enumValues<T>().firstOrNull { it.name == value } } ?: default

    private fun credentialAccount(id: String) = "$CREDENTIAL_PREFIX$id"

    /** The account a preset's key had before providers kept lists, such as `direct-api-DEEPSEEK`. */
    private fun legacyCredentialAccount(id: String): String? {
        val kind = AIProviderKind.fromRaw(id) ?: return null
        return kind.legacyName?.let { "$CREDENTIAL_PREFIX$it" }
    }

    companion object {
        const val PREFERENCES_NAME = "jerreader_translation_settings"

        /** The provider entry of the ChatGPT plan. */
        const val CHATGPT_PLAN_ID = ChatGPTPlan.PROVIDER_ID

        /** Stands in for a key in the plan's configuration; requests ask the session for the real token. */
        const val PLAN_KEY_PLACEHOLDER = "chatgpt-plan-session"
        private const val KEY_PLAN_HOST_ID = "translation.chatgptPlan.hostID"
        private const val KEY_PLAN_CLIENT_ID = "translation.chatgptPlan.clientID"
        private const val KEY_PLAN_WELCOME_SHOWN = "translation.chatgptPlan.welcomeShown"
        private const val KEY_PLAN_MODEL_NAMES = "translation.chatgptPlan.modelNames"
        private const val KEY_PLAN_CONSENT_NEEDED = "translation.chatgptPlan.consentNeeded"
        private const val KEY_PROVIDER_MODE = "provider.mode"
        private const val KEY_AI_PROVIDERS = "translation.aiProviders.v1"
        private const val KEY_LEGACY_DIRECT_PROVIDER = "direct.provider"
        private const val LEGACY_ENDPOINT_PREFIX = "direct.endpoint."
        private const val LEGACY_MODEL_PREFIX = "direct.model."
        private const val CREDENTIAL_PREFIX = "direct-api-"
        private const val KEY_BACKEND_ENDPOINT = "backend.endpoint"
        private const val KEY_BACKEND_MODEL = "backend.model"
        private const val KEY_SOURCE = "source.language"
        private const val KEY_TARGET = "target.language"
        private const val KEY_QUICK_ENABLED = "quick.enabled"
        private const val KEY_QUICK_UNIT = "quick.unit"
        private const val KEY_DISABLES_TAP_PAGE_TURNS = "quick.disablesTapPageTurns"
        private const val KEY_DISPLAY_MODE = "display.mode"
        private const val KEY_HAPTICS = "translation.haptics"
        private const val KEY_AUTOMATIC_RETRY = "automatic.retry"
        private const val KEY_FALLBACK_MODE = "fallback.mode"
        private const val KEY_PROMPT = "translation.prompt"
        private const val KEY_GRAMMAR_PROMPT = "grammar.prompt"
        private const val BACKEND_CREDENTIAL_ACCOUNT = "backend-proxy-token"
    }
}
