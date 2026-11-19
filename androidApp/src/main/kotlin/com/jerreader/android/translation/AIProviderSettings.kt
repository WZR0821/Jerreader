package com.jerreader.android.translation

import java.net.URI
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/*
 * The AI 大模型 settings, ported from the iOS `AIProviderSettings.swift`: the
 * providers that were set up, the models kept under each, and the request
 * fields each model needs. Raw values and the saved JSON match iOS, so the two
 * platforms describe a provider the same way.
 */

/**
 * The request format a model is called with. Its route is appended to the
 * provider's API address; Gemini's own format puts the model in the path.
 */
enum class AIAPIStyle(val raw: String, val displayName: String, val route: String?) {
    CHAT_COMPLETIONS("chatCompletions", "OpenAI 兼容", "chat/completions"),
    RESPONSES("responses", "OpenAI Responses", "responses"),
    ANTHROPIC_MESSAGES("anthropicMessages", "Claude 兼容", "messages"),

    /** Gemini's generateContent format: what Gemini setups used before formats could be chosen. */
    GEMINI_NATIVE("geminiNative", "Gemini 原生", null);

    val supportsReasoning: Boolean get() = this != GEMINI_NATIVE

    companion object {
        /** The formats offered for a model. Gemini's own format is only offered to a model that already uses it. */
        val selectable = listOf(CHAT_COMPLETIONS, RESPONSES, ANTHROPIC_MESSAGES)

        fun fromRaw(raw: String?): AIAPIStyle? = entries.firstOrNull { it.raw == raw }
    }
}

/** A chosen reasoning depth. `null` in a model entry means 自动. */
enum class AIReasoningEffort(val raw: String, val title: String) {
    OFF("off", "关闭"),
    LOW("low", "低"),
    MEDIUM("medium", "中"),
    HIGH("high", "高");

    companion object {
        fun fromRaw(raw: String?): AIReasoningEffort? = entries.firstOrNull { it.raw == raw }
    }
}

data class AIModelPreset(val id: String, val title: String, val detail: String)

/**
 * A kind of provider. [legacyName] is the name the provider had before
 * providers kept a list of models; the old settings, keys and translation
 * cache are found under it.
 */
enum class AIProviderKind(
    val raw: String,
    val title: String,
    val subtitle: String,
    val legacyName: String?
) {
    ANTHROPIC("anthropic", "Claude", "Anthropic", "ANTHROPIC"),
    OPENAI("openAI", "ChatGPT", "OpenAI", "OPENAI"),
    DEEPSEEK("deepSeek", "DeepSeek", "深度求索", "DEEPSEEK"),
    QWEN("qwen", "通义千问", "阿里云百炼", null),
    DOUBAO("doubao", "豆包", "火山方舟", null),
    KIMI("kimi", "Kimi", "月之暗面", "KIMI"),
    ZHIPU("zhipu", "智谱 GLM", "智谱 AI", null),
    MINIMAX("minimax", "MiniMax", "稀宇科技", null),
    GEMINI("gemini", "Gemini", "Google", "GEMINI"),
    OPENAI_COMPATIBLE("openAICompatible", "自定义 API", "OpenAI 或 Claude 兼容接口", "OPENAI_COMPATIBLE"),

    /**
     * A ChatGPT plan, signed in with Sign in with ChatGPT instead of an API
     * key. It did not exist before providers kept lists, so it has no legacy name.
     */
    CHATGPT_PLAN("chatGPTPlan", "ChatGPT 套餐", "用 ChatGPT 账号登录 · 实验", null);

    val isCustom: Boolean get() = this == OPENAI_COMPATIBLE

    /** Signed in with an account instead of an API key; its address and request format are fixed. */
    val usesAccountSignIn: Boolean get() = this == CHATGPT_PLAN

    val isRecommended: Boolean get() = this == defaultProvider

    val defaultBaseURL: String
        get() = when (this) {
            ANTHROPIC -> "https://api.anthropic.com/v1"
            OPENAI -> "https://api.openai.com/v1"
            DEEPSEEK -> "https://api.deepseek.com"
            QWEN -> "https://dashscope-us.aliyuncs.com/compatible-mode/v1"
            DOUBAO -> "https://ark.cn-beijing.volces.com/api/v3"
            KIMI -> "https://api.moonshot.cn/v1"
            ZHIPU -> "https://open.bigmodel.cn/api/paas/v4"
            MINIMAX -> "https://api.minimax.cn/v1"
            GEMINI -> "https://generativelanguage.googleapis.com/v1beta/openai"
            OPENAI_COMPATIBLE -> ""
            CHATGPT_PLAN -> "https://api.openai.com/v1"
        }

    /** The format a newly added model of this provider uses. */
    val defaultStyle: AIAPIStyle
        get() = when (this) {
            OPENAI, CHATGPT_PLAN -> AIAPIStyle.RESPONSES
            ANTHROPIC -> AIAPIStyle.ANTHROPIC_MESSAGES
            else -> AIAPIStyle.CHAT_COMPLETIONS
        }

    /**
     * The format each provider was called with before formats could be
     * chosen. A configuration in that format keeps its cache identity.
     */
    val legacyStyle: AIAPIStyle
        get() = if (this == GEMINI) AIAPIStyle.GEMINI_NATIVE else defaultStyle

    /** The models offered when the provider is added; the first one is used first. */
    val modelPresets: List<AIModelPreset>
        get() = when (this) {
            ANTHROPIC -> listOf(
                AIModelPreset("claude-haiku-5-5", "Claude Haiku 5.5", "日常翻译"),
                AIModelPreset("claude-sonnet-5-5", "Claude Sonnet 5.5", "复杂语境"),
                AIModelPreset("claude-opus-5-5", "Claude Opus 5.5", "疑难文本")
            )
            OPENAI -> listOf(
                AIModelPreset("gpt-6-luna", "GPT-6 Luna", "日常翻译"),
                AIModelPreset("gpt-6.1-sol", "GPT-6.1 Sol", "复杂语境"),
                AIModelPreset("gpt-4.1-mini", "GPT-4.1 Mini", "轻量模型")
            )
            DEEPSEEK -> listOf(
                AIModelPreset("deepseek-flash", "DeepSeek Flash", "日常翻译"),
                AIModelPreset("deepseek-v4-pro", "DeepSeek V4 Pro", "复杂语境")
            )
            QWEN -> listOf(
                AIModelPreset("qwen-plus", "Qwen Plus", "日常翻译"),
                AIModelPreset("qwen-turbo", "Qwen Turbo", "轻量翻译"),
                AIModelPreset("qwen-max", "Qwen Max", "复杂语境")
            )
            DOUBAO -> listOf(
                AIModelPreset("doubao-seed-2-1-lite-260915", "豆包 Seed 2.1 Lite", "日常翻译"),
                AIModelPreset("doubao-seed-2-1-pro-260915", "豆包 Seed 2.1 Pro", "复杂语境")
            )
            KIMI -> listOf(
                AIModelPreset("kimi-k3.1-pro", "Kimi K3.1 Pro", "日常翻译"),
                AIModelPreset("kimi-k3.1-pro-highspeed", "Kimi K3.1 Pro 高速版", "更快")
            )
            ZHIPU -> listOf(
                AIModelPreset("glm-5.3-flash", "GLM-5.3 Flash", "日常翻译"),
                AIModelPreset("glm-5.3", "GLM-5.3", "复杂语境")
            )
            MINIMAX -> listOf(
                AIModelPreset("MiniMax-M3.1-Flash", "MiniMax M3.1 Flash", "日常翻译"),
                AIModelPreset("MiniMax-M3", "MiniMax M3", "复杂语境")
            )
            GEMINI -> listOf(
                AIModelPreset("gemini-3.8-flash", "Gemini 3.8 Flash", "日常翻译"),
                AIModelPreset("gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite", "轻量翻译")
            )
            // A ChatGPT plan's models come from the signed-in account.
            OPENAI_COMPATIBLE, CHATGPT_PLAN -> emptyList()
        }

    val defaultModel: String get() = modelPresets.firstOrNull()?.id.orEmpty()

    /** What to know about the address: shown under the key and address fields. */
    val setupNote: String
        get() = when (this) {
            QWEN -> "默认是美国区域的地址，密钥要和区域一致。其他区域请把 API 地址改成控制台给出的地址。"
            DOUBAO -> "默认是火山方舟（中国站）的地址。国际版 BytePlus 的地址和模型 ID 都不同，要用它请改 API 地址并手动添加模型。"
            KIMI -> "默认是国内站的地址。国际站的密钥不通用，要用它请把 API 地址改成 https://api.moonshot.ai/v1。"
            ZHIPU -> "默认是国内站的地址。国际站 Z.AI 的密钥不通用，要用它请把 API 地址改成 https://api.z.ai/api/paas/v4。"
            MINIMAX -> "默认是国内站的地址。国际站请把 API 地址改成 https://api.minimax.io/v1。"
            ANTHROPIC, OPENAI, DEEPSEEK, GEMINI, OPENAI_COMPATIBLE -> "已填入官方地址，一般不用改。"
            CHATGPT_PLAN -> "不用 API Key，翻译用的是这个 ChatGPT 账号的套餐额度。Plus 的 5 小时额度和 ChatGPT、Codex 等用同一套餐的应用共用；额度用完会暂停，不会改走别的计费。"
        }

    val documentationURL: String
        get() = when (this) {
            ANTHROPIC -> "https://platform.claude.com/docs/en/models/overview"
            OPENAI -> "https://developers.openai.com/api/docs/models"
            DEEPSEEK -> "https://api-docs.deepseek.com/"
            QWEN -> "https://www.alibabacloud.com/help/en/model-studio/compatibility-of-openai-with-dashscope"
            DOUBAO -> "https://docs.volcengine.com/docs/82379"
            KIMI -> "https://platform.kimi.com/docs"
            ZHIPU -> "https://docs.bigmodel.cn"
            MINIMAX -> "https://platform.minimax.cn/docs"
            GEMINI -> "https://ai.google.dev/gemini-api/docs/openai"
            OPENAI_COMPATIBLE ->
                "https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create"
            CHATGPT_PLAN -> "https://developers.openai.com/siwc/token-sharing-open-source"
        }

    /** The console page where a key is made, when the provider has a stable one. */
    val keyURL: String?
        get() = when (this) {
            ANTHROPIC -> "https://platform.claude.com/settings/keys"
            OPENAI -> "https://platform.openai.com/api-keys"
            DEEPSEEK -> "https://platform.deepseek.com/api_keys"
            DOUBAO -> "https://ark.volcengine.com/region:cn-beijing/apiKey"
            KIMI -> "https://platform.kimi.com/console/api-keys"
            ZHIPU -> "https://bigmodel.cn/usercenter/proj-mgmt/apikeys"
            MINIMAX -> "https://platform.minimax.cn/user-center/basic-information/interface-key"
            GEMINI -> "https://aistudio.google.com/apikey"
            // 通义千问's console differs by region; the documentation link covers it.
            QWEN, OPENAI_COMPATIBLE, CHATGPT_PLAN -> null
        }

    /** 通义千问 only thinks when streaming, so it offers 关闭 alone. */
    val reasoningOptions: List<AIReasoningEffort>
        get() = if (this == QWEN) listOf(AIReasoningEffort.OFF) else AIReasoningEffort.entries

    val reasoningNote: String
        get() = when (this) {
            ANTHROPIC -> "自动：翻译时对 5 系列用低强度；语法解释不发送思考参数（默认自适应思考）。Opus 5.5 无法关闭思考，“关闭”会改用最低强度。"
            OPENAI -> "自动：翻译时对已知模型降低推理。不支持推理的模型（如 GPT-4.1 Mini）请保持自动。"
            DEEPSEEK, DOUBAO -> "自动：翻译时关闭思考，语法解释用低强度。"
            QWEN -> "通义千问的思考模式需要流式输出，这里只提供关闭。"
            KIMI -> "K3 系列始终思考，无法关闭：自动和“关闭”都用最低档。"
            ZHIPU -> "GLM-5.3 系列无法关闭思考，自动和“关闭”都用最低档；GLM-5.2 可以关闭。"
            MINIMAX -> "M3.1 Flash 无法关闭思考，自动和“关闭”都用最低档；M3 只能开或关，不分强度。"
            GEMINI -> "自动：翻译时对 Flash 系列用最低档（2.5 Flash 关闭思考）。Gemini 3 等无法完全关闭思考的模型，“关闭”会用最低档。"
            OPENAI_COMPATIBLE -> "自动不发送任何推理参数；其他档位按所选接口格式发送标准字段。"
            CHATGPT_PLAN -> "自动：翻译时对已知模型降低推理强度，语法解释用模型默认的强度。"
        }

    /**
     * The model the Android app used for this provider when none was chosen,
     * before providers kept a list of models. Only migration reads it.
     */
    val legacyDefaultModel: String
        get() = when (this) {
            OPENAI -> "gpt-5.2"
            ANTHROPIC -> "claude-sonnet-4-20250514"
            KIMI -> "kimi-k2.6"
            DEEPSEEK -> "deepseek-v4-flash"
            GEMINI -> "gemini-3.6-flash"
            else -> ""
        }

    /** The full request URL the Android app sent this provider's requests to before. */
    val legacyEndpoint: String
        get() = when (this) {
            OPENAI -> "https://api.openai.com/v1/responses"
            ANTHROPIC -> "https://api.anthropic.com/v1/messages"
            KIMI -> "https://api.moonshot.cn/v1/chat/completions"
            DEEPSEEK -> "https://api.deepseek.com/chat/completions"
            GEMINI -> "https://generativelanguage.googleapis.com/v1beta"
            else -> ""
        }

    /** The letter drawn on the provider's mark; Gemini and a custom service draw a symbol. */
    val monogram: String?
        get() = when (this) {
            ANTHROPIC -> "C"
            // The plan is ChatGPT too, so it draws the same letter.
            OPENAI, CHATGPT_PLAN -> "G"
            DEEPSEEK -> "D"
            QWEN -> "通"
            DOUBAO -> "豆"
            KIMI -> "K"
            ZHIPU -> "智"
            MINIMAX -> "M"
            GEMINI, OPENAI_COMPATIBLE -> null
        }

    /** The provider's colour, as 0xRRGGBB. */
    val tint: Long
        get() = when (this) {
            ANTHROPIC -> 0xC96442
            OPENAI -> 0x0E8F6F
            DEEPSEEK -> 0x4D6BFE
            QWEN -> 0x6A4DF4
            DOUBAO -> 0x1F7AE0
            KIMI -> 0x2B2B33
            ZHIPU -> 0x2F54EB
            MINIMAX -> 0xD9365E
            GEMINI -> 0x3B7DED
            OPENAI_COMPATIBLE -> 0x6E7380
            CHATGPT_PLAN -> 0x1A1A1A
        }

    companion object {
        /**
         * The order on the 添加供应商 page: Claude, ChatGPT and DeepSeek first,
         * then the rest, with no domestic/overseas split.
         */
        val presets = listOf(ANTHROPIC, OPENAI, DEEPSEEK, QWEN, DOUBAO, KIMI, ZHIPU, MINIMAX, GEMINI)

        /** The provider a fresh install starts with. */
        val defaultProvider = DEEPSEEK

        /** The providers that existed before providers kept a list of models. */
        val legacyCases: List<AIProviderKind> = entries.filter { it.legacyName != null }

        fun fromRaw(raw: String?): AIProviderKind? = entries.firstOrNull { it.raw == raw }

        fun fromLegacyName(name: String?): AIProviderKind? =
            name?.let { value -> entries.firstOrNull { it.legacyName == value } }
    }
}

/** One model kept under a provider, with the settings that belong to it. */
data class AIModelEntry(
    val id: String,
    val style: AIAPIStyle,
    val reasoning: AIReasoningEffort? = null
)

/**
 * One provider: its address, the models kept under it and the one it uses.
 * Its API key lives in the keystore under the entry's id.
 */
data class AIProviderEntry(
    /** A preset's raw value, or `custom-<uuid>` for a custom service. */
    val id: String,
    val kind: AIProviderKind,
    /** The user's own name for a custom service. */
    val name: String?,
    val baseURL: String,
    /** The model this provider uses. */
    val model: String,
    val models: List<AIModelEntry>
) {
    val displayName: String
        get() {
            if (!kind.isCustom) return kind.title
            val label = name?.trim().orEmpty()
            return label.ifEmpty { kind.title }
        }

    val host: String
        get() {
            val text = baseURL.trim()
            val parsed = runCatching { URI(text).host }.getOrNull()
            return parsed ?: text.ifEmpty { "未填写" }
        }

    /**
     * The settings of the model in use. A model missing from the list uses
     * the format of the provider's other models.
     */
    val activeModel: AIModelEntry
        get() = models.firstOrNull { it.id == model }
            ?: AIModelEntry(model, models.firstOrNull()?.style ?: kind.defaultStyle)

    /** The format a model added now starts with: the one the provider's address already works with. */
    val styleForNewModel: AIAPIStyle get() = activeModel.style

    fun upserting(entry: AIModelEntry): AIProviderEntry {
        val index = models.indexOfFirst { it.id == entry.id }
        val list = if (index >= 0) models.toMutableList().also { it[index] = entry } else models + entry
        return copy(models = list, model = model.ifEmpty { entry.id })
    }

    /** Removes a model. If it was the one in use, the next model takes over. */
    fun removingModel(id: String): AIProviderEntry {
        val list = models.filterNot { it.id == id }
        return copy(models = list, model = if (model == id) list.firstOrNull()?.id.orEmpty() else model)
    }

    companion object {
        fun preset(kind: AIProviderKind, models: List<String>? = null): AIProviderEntry {
            val ids = models ?: kind.modelPresets.map(AIModelPreset::id)
            return AIProviderEntry(
                id = kind.raw,
                kind = kind,
                name = null,
                baseURL = kind.defaultBaseURL,
                model = ids.firstOrNull().orEmpty(),
                models = ids.map { AIModelEntry(it, kind.defaultStyle) }
            )
        }

        fun newCustomID(): String = "custom-" + UUID.randomUUID().toString().lowercase(Locale.ROOT)
    }
}

sealed class AIProviderSettingsError(message: String) : Exception(message) {
    class MissingKey(provider: String) : AIProviderSettingsError("$provider 还没有填 API Key。")
    class KeystoreUnavailable : AIProviderSettingsError("无法将 API Key 安全保存到本机安全存储。")
    class RemovingActiveProvider : AIProviderSettingsError("正在用的供应商不能删除。先用“换模型”换到别的供应商。")
    class RemovingActiveModel : AIProviderSettingsError("正在用的模型不能删除，先换到别的模型。")
    class UnknownProvider : AIProviderSettingsError("找不到这个供应商，可能已经删除。")
    class InvalidAddress : AIProviderSettingsError("API 地址必须是带完整主机名的 HTTPS 地址。")
    class MissingModel : AIProviderSettingsError("请先选一个模型。")
    class SignInRequired(provider: String) : AIProviderSettingsError("$provider 还没有登录，或者登录已失效。")
}

/**
 * Every provider that was set up and the one in use. Saved as JSON in the
 * iOS format; keys stay in the keystore.
 */
data class AIProviderSettings(val activeID: String, val entries: List<AIProviderEntry>) {
    val active: AIProviderEntry
        get() = entry(activeID) ?: entries.firstOrNull() ?: AIProviderEntry.preset(AIProviderKind.defaultProvider)

    fun entry(id: String): AIProviderEntry? = entries.firstOrNull { it.id == id }

    fun displayName(id: String): String =
        entry(id)?.displayName ?: AIProviderKind.fromRaw(id)?.title ?: "自定义 API"

    fun isInUse(model: String, id: String): Boolean = activeID == id && entry(id)?.model == model

    /** The default provider as a fresh install has it: nothing of the user's in it yet. */
    fun isUntouchedDefault(entry: AIProviderEntry): Boolean =
        entry == AIProviderEntry.preset(AIProviderKind.defaultProvider)

    /**
     * A preset that is already set up is shown as added on the 添加供应商
     * page. The untouched default still counts as available until it has a key.
     */
    fun hasSetUp(kind: AIProviderKind, keyed: Set<String>): Boolean {
        val entry = entry(kind.raw) ?: return false
        return entry.id in keyed || !isUntouchedDefault(entry)
    }

    fun updating(id: String, change: (AIProviderEntry) -> AIProviderEntry): AIProviderSettings {
        val index = entries.indexOfFirst { it.id == id }
        if (index < 0) return this
        return copy(entries = entries.toMutableList().also { it[index] = change(it[index]) })
    }

    /** Puts a provider, and optionally one of its models, in use. */
    fun activating(id: String, model: String? = null): AIProviderSettings {
        if (entry(id) == null) return this
        val switched = copy(activeID = id)
        if (model == null) return switched
        return switched.updating(id) { entry ->
            val models = if (entry.models.any { it.id == model }) entry.models
            else entry.models + AIModelEntry(model, entry.styleForNewModel)
            entry.copy(models = models, model = model)
        }
    }

    /**
     * Adds a provider, or replaces a preset that was never set up. It is put
     * in use only when the provider in use has no key; the untouched default
     * then leaves the list.
     *
     * @return the new settings, and whether the new provider is now in use.
     */
    fun adding(entry: AIProviderEntry, activeHasKey: Boolean): Pair<AIProviderSettings, Boolean> {
        val previous = active
        val index = entries.indexOfFirst { it.id == entry.id }
        val list = if (index >= 0) entries.toMutableList().also { it[index] = entry } else entries + entry
        val added = copy(entries = list)
        if (activeHasKey && previous.id != entry.id) return added to false
        val remaining = if (previous.id != entry.id && isUntouchedDefault(previous)) {
            list.filterNot { it.id == previous.id }
        } else {
            list
        }
        return AIProviderSettings(entry.id, remaining) to true
    }

    fun removing(id: String): AIProviderSettings {
        if (id == activeID) throw AIProviderSettingsError.RemovingActiveProvider()
        return copy(entries = entries.filterNot { it.id == id })
    }

    fun removingModel(model: String, id: String): AIProviderSettings {
        if (isInUse(model, id)) throw AIProviderSettingsError.RemovingActiveModel()
        return updating(id) { it.removingModel(model) }
    }

    /** The JSON saved in preferences and backups: the same shape the iOS app encodes. */
    fun toJson(): JSONObject = JSONObject()
        .put("activeID", activeID)
        .put("entries", JSONArray(entries.map { entry ->
            JSONObject()
                .put("id", entry.id)
                .put("kind", entry.kind.raw)
                .apply { entry.name?.let { put("name", it) } }
                .put("baseURL", entry.baseURL)
                .put("model", entry.model)
                .put("models", JSONArray(entry.models.map { model ->
                    JSONObject()
                        .put("id", model.id)
                        .put("style", model.style.raw)
                        .apply { model.reasoning?.let { put("reasoning", it.raw) } }
                }))
        }))

    companion object {
        /** A fresh install: the default provider with its preset models, no key yet. */
        val fresh: AIProviderSettings
            get() {
                val entry = AIProviderEntry.preset(AIProviderKind.defaultProvider)
                return AIProviderSettings(entry.id, listOf(entry))
            }

        /**
         * Reads saved settings. An entry or model this version does not know is
         * left out rather than failing the rest; nothing usable gives `null`.
         */
        fun fromJson(json: JSONObject?): AIProviderSettings? {
            json ?: return null
            val rows = json.optJSONArray("entries") ?: return null
            val entries = (0 until rows.length()).mapNotNull { index ->
                val row = rows.optJSONObject(index) ?: return@mapNotNull null
                val id = row.optString("id").takeIf(String::isNotEmpty) ?: return@mapNotNull null
                val kind = AIProviderKind.fromRaw(row.optString("kind")) ?: return@mapNotNull null
                val modelRows = row.optJSONArray("models") ?: JSONArray()
                val models = (0 until modelRows.length()).mapNotNull { modelIndex ->
                    val item = modelRows.optJSONObject(modelIndex) ?: return@mapNotNull null
                    val modelID = item.optString("id").takeIf(String::isNotEmpty) ?: return@mapNotNull null
                    val style = AIAPIStyle.fromRaw(item.optString("style")) ?: return@mapNotNull null
                    val reasoning = if (item.isNull("reasoning")) null else AIReasoningEffort.fromRaw(item.optString("reasoning"))
                    AIModelEntry(modelID, style, reasoning)
                }
                AIProviderEntry(
                    id = id,
                    kind = kind,
                    name = if (row.isNull("name")) null else row.optString("name"),
                    baseURL = row.optString("baseURL"),
                    model = row.optString("model"),
                    models = models.distinctBy(AIModelEntry::id)
                )
            }.distinctBy(AIProviderEntry::id)
            if (entries.isEmpty()) return null
            return AIProviderSettings(json.optString("activeID"), entries)
        }

        /**
         * Builds the providers from the settings kept before providers had a
         * list of models: one entry for every provider that was selected, has a
         * key, or has a model or address of its own. Each keeps the model and
         * address it used, so its requests, and its translation cache, stay the same.
         */
        fun migrated(
            selected: AIProviderKind?,
            keyed: Set<AIProviderKind>,
            storedModels: Map<AIProviderKind, String>,
            storedEndpoints: Map<AIProviderKind, String>
        ): AIProviderSettings {
            val entries = mutableListOf<AIProviderEntry>()
            for (kind in AIProviderKind.legacyCases) {
                val storedModel = storedModels[kind]?.trim()
                val storedEndpoint = storedEndpoints[kind]?.trim()
                if (kind != selected && kind !in keyed && storedModel == null && storedEndpoint == null) continue
                val model = storedModel?.takeIf(String::isNotEmpty) ?: kind.legacyDefaultModel
                val (base, style) = when (kind) {
                    AIProviderKind.OPENAI_COMPATIBLE -> {
                        val split = AIEndpoint.split(storedEndpoint.orEmpty())
                        split.base to (split.style ?: AIAPIStyle.CHAT_COMPLETIONS)
                    }
                    AIProviderKind.GEMINI -> "https://generativelanguage.googleapis.com/v1beta" to AIAPIStyle.GEMINI_NATIVE
                    else -> kind.defaultBaseURL to kind.legacyStyle
                }
                entries += AIProviderEntry(
                    id = kind.raw,
                    kind = kind,
                    name = null,
                    baseURL = base,
                    model = model,
                    models = if (model.isEmpty()) emptyList() else listOf(AIModelEntry(model, style))
                )
            }
            if (entries.isEmpty()) return fresh
            // Before, a provider that was never chosen meant DeepSeek on Android.
            val wanted = (selected ?: AIProviderKind.DEEPSEEK).raw
            val activeID = if (entries.any { it.id == wanted }) wanted else entries[0].id
            return AIProviderSettings(activeID, entries)
        }
    }
}

/** Request URLs: a provider's API address plus the route of a format. */
object AIEndpoint {
    data class Split(val base: String, val style: AIAPIStyle?)

    /**
     * Splits a full request URL, as typed before formats were chosen apart,
     * into the API address and the format its route names.
     */
    fun split(text: String): Split {
        val trimmed = text.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull()
        if (uri?.host == null) return Split(trimmed, null)
        val (path, style) = stripRoute(uri.rawPath.orEmpty())
        return Split(rebuilt(uri, path), style)
    }

    /**
     * The URL a request goes to. An address that already ends in a route,
     * from a pasted full URL, is taken without it.
     */
    fun url(base: String, style: AIAPIStyle): String? {
        val uri = runCatching { URI(base.trim()) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase(Locale.ROOT) != "https" || uri.host.isNullOrEmpty() ||
            uri.rawUserInfo != null || uri.rawFragment != null
        ) {
            return null
        }
        var (path, _) = stripRoute(uri.rawPath.orEmpty())
        style.route?.let { path += "/$it" }
        return rebuilt(uri, path)
    }

    fun isValid(base: String): Boolean = url(base, AIAPIStyle.CHAT_COMPLETIONS) != null

    private fun rebuilt(uri: URI, rawPath: String): String = buildString {
        append(uri.scheme).append("://").append(uri.rawAuthority)
        append(rawPath)
        uri.rawQuery?.let { append('?').append(it) }
        uri.rawFragment?.let { append('#').append(it) }
    }

    private fun stripRoute(path: String): Pair<String, AIAPIStyle?> {
        val value = path.trimEnd('/')
        for (style in AIAPIStyle.selectable) {
            val route = style.route ?: continue
            if (value.lowercase(Locale.ROOT).endsWith("/$route")) {
                return value.dropLast(route.length + 1) to style
            }
        }
        return value to null
    }
}

/**
 * Fields added to a request body for a model, on top of the format's own.
 * 自动 sends only fields documented for known model families; a custom
 * service gets nothing it did not ask for.
 */
object AIRequestTuning {
    /** The translation's own room in a Messages request, as sent without thinking. */
    const val ANTHROPIC_OUTPUT_TOKENS = 1_200

    /** @param quick a translation, as opposed to a grammar explanation. */
    fun extraFields(
        kind: AIProviderKind,
        style: AIAPIStyle,
        model: String,
        reasoning: AIReasoningEffort?,
        quick: Boolean
    ): JSONObject {
        val body = JSONObject()
        applyOutputLimits(body, kind, style, model)
        if (!style.supportsReasoning) return body
        if (reasoning != null && reasoning in kind.reasoningOptions) {
            applyReasoning(reasoning, body, kind, style, model)
        } else {
            applyAutomaticReasoning(body, kind, style, model, quick)
        }
        return body
    }

    private fun thinkingType(value: String) = JSONObject().put("type", value)
    private fun effortLevel(value: String) = JSONObject().put("effort", value)

    /** Fields a known model needs to return a whole answer, whatever the reasoning setting. */
    private fun applyOutputLimits(body: JSONObject, kind: AIProviderKind, style: AIAPIStyle, model: String) {
        if (style != AIAPIStyle.CHAT_COMPLETIONS) return
        val id = model.lowercase(Locale.ROOT)
        when {
            // Without a limit the answer stops at 4K tokens.
            kind == AIProviderKind.DOUBAO && id.startsWith("doubao-seed-2-1") -> body.put("max_tokens", 16_000)
            // Reasoning counts toward the limit; Kimi's documentation asks for 16000 or more.
            kind == AIProviderKind.KIMI && id.startsWith("kimi-k3") -> body.put("max_tokens", 16_000)
            kind == AIProviderKind.MINIMAX && id.startsWith("minimax-m") -> {
                // Keeps the model's thinking out of the translated text.
                body.put("reasoning_split", true)
                if (id.startsWith("minimax-m3")) body.put("max_completion_tokens", 32_000)
            }
        }
    }

    private fun applyReasoning(
        effort: AIReasoningEffort,
        body: JSONObject,
        kind: AIProviderKind,
        style: AIAPIStyle,
        model: String
    ) {
        val off = effort == AIReasoningEffort.OFF
        val level = effort.raw
        if (kind == AIProviderKind.QWEN) {
            if (off && style != AIAPIStyle.ANTHROPIC_MESSAGES) body.put("enable_thinking", false)
            return
        }
        when (style) {
            AIAPIStyle.RESPONSES -> body.put("reasoning", effortLevel(if (off) "none" else level))
            AIAPIStyle.CHAT_COMPLETIONS -> when (kind) {
                AIProviderKind.DEEPSEEK, AIProviderKind.DOUBAO -> {
                    body.put("thinking", thinkingType(if (off) "disabled" else "enabled"))
                    if (!off) body.put("reasoning_effort", level)
                }
                // Gemini 2.x Flash can turn thinking off; later models only go down to minimal.
                AIProviderKind.GEMINI -> body.put(
                    "reasoning_effort",
                    if (off) (if (model.startsWith("gemini-2")) "none" else "minimal") else level
                )
                // K3 always thinks and takes low, high or max.
                AIProviderKind.KIMI -> body.put("reasoning_effort", threeStepEffort(effort))
                AIProviderKind.ZHIPU -> {
                    val id = model.lowercase(Locale.ROOT)
                    if (id.startsWith("glm-5.3")) {
                        // Thinking cannot be turned off; these models take low, high or max.
                        body.put("reasoning_effort", threeStepEffort(effort))
                    } else {
                        body.put("thinking", thinkingType(if (off) "disabled" else "enabled"))
                        // Earlier models switch thinking on or off and have no effort level.
                        if (!off && id.startsWith("glm-5.2")) body.put("reasoning_effort", level)
                    }
                }
                AIProviderKind.MINIMAX -> if (model.lowercase(Locale.ROOT).startsWith("minimax-m3.1")) {
                    // Thinking is always on for this model; only the effort goes down.
                    body.put("reasoning_effort", if (off) "low" else level)
                } else {
                    // Other models think or do not, and ignore an effort level.
                    body.put("thinking", thinkingType(if (off) "disabled" else "adaptive"))
                }
                else -> body.put("reasoning_effort", if (off) "none" else level)
            }
            AIAPIStyle.ANTHROPIC_MESSAGES -> if (kind == AIProviderKind.DEEPSEEK) {
                body.put("thinking", thinkingType(if (off) "disabled" else "enabled"))
                if (!off) body.put("output_config", effortLevel(level))
            } else {
                applyClaudeThinking(body, effort, model)
            }
            AIAPIStyle.GEMINI_NATIVE -> Unit
        }
    }

    /** 关闭 and 低 share the lowest step on models that take only low, high and max. */
    private fun threeStepEffort(effort: AIReasoningEffort): String = when (effort) {
        AIReasoningEffort.OFF, AIReasoningEffort.LOW -> "low"
        AIReasoningEffort.MEDIUM -> "high"
        AIReasoningEffort.HIGH -> "max"
    }

    private fun applyClaudeThinking(body: JSONObject, effort: AIReasoningEffort, model: String) {
        val id = model.lowercase(Locale.ROOT)
        // Older models only understand a fixed thinking budget and do not think unless asked.
        val budgetPrefixes = listOf(
            "claude-3", "claude-haiku-4", "claude-sonnet-4-5", "claude-sonnet-4-0",
            "claude-sonnet-4-2", "claude-opus-4-0", "claude-opus-4-1", "claude-opus-4-5",
            "claude-opus-4-2"
        )
        if (budgetPrefixes.any { id.startsWith(it) } || id == "claude-sonnet-4" || id == "claude-opus-4") {
            val budget = when (effort) {
                AIReasoningEffort.OFF -> return
                AIReasoningEffort.LOW -> 1_024
                AIReasoningEffort.MEDIUM -> 4_096
                AIReasoningEffort.HIGH -> 8_192
            }
            // Thinking counts toward max_tokens; keep the translation's own room.
            body.put("thinking", thinkingType("enabled").put("budget_tokens", budget))
            body.put("max_tokens", ANTHROPIC_OUTPUT_TOKENS + budget)
            return
        }
        if (effort == AIReasoningEffort.OFF) {
            when {
                id.startsWith("claude-sonnet-5-5") -> body.put("thinking", thinkingType("between_tools"))
                // Thinking cannot be turned off on these models; use the lowest effort.
                listOf("claude-opus-5-5", "claude-fable", "claude-mythos").any { id.startsWith(it) } ->
                    body.put("output_config", effortLevel("low"))
                else -> body.put("thinking", thinkingType("disabled"))
            }
            return
        }
        body.put("thinking", thinkingType("adaptive"))
        body.put("output_config", effortLevel(effort.raw))
        body.put("max_tokens", 16_000)
    }

    /** 自动: only documented fields for known model families. */
    private fun applyAutomaticReasoning(
        body: JSONObject,
        kind: AIProviderKind,
        style: AIAPIStyle,
        model: String,
        quick: Boolean
    ) {
        // DeepSeek reasons at high effort by default. A translation turns it
        // off; an explanation keeps it at low effort.
        if (kind == AIProviderKind.DEEPSEEK && model in listOf("deepseek-flash", "deepseek-v4-pro")) {
            if (style == AIAPIStyle.RESPONSES) {
                body.put("reasoning", effortLevel(if (quick) "none" else "low"))
            } else {
                body.put("thinking", thinkingType(if (quick) "disabled" else "enabled"))
                if (!quick) {
                    if (style == AIAPIStyle.ANTHROPIC_MESSAGES) body.put("output_config", effortLevel("low"))
                    else body.put("reasoning_effort", "low")
                }
            }
        }
        if (quick && (kind == AIProviderKind.OPENAI || kind == AIProviderKind.CHATGPT_PLAN)) {
            val efforts = mapOf("gpt-6-luna" to "none", "gpt-6-sol" to "none", "gpt-6.1-sol" to "low", "gpt-6-astra" to "low")
            efforts[model]?.let { value ->
                if (style == AIAPIStyle.RESPONSES) body.put("reasoning", effortLevel(value))
                else if (style == AIAPIStyle.CHAT_COMPLETIONS) body.put("reasoning_effort", value)
            }
        }
        val id = model.lowercase(Locale.ROOT)
        if (quick && kind == AIProviderKind.ANTHROPIC && style == AIAPIStyle.ANTHROPIC_MESSAGES &&
            listOf("claude-opus-5", "claude-sonnet-5", "claude-haiku-5", "claude-fable", "claude-mythos")
                .any { id.startsWith(it) }
        ) {
            // The 5 series thinks adaptively by default. Low effort keeps a
            // translation short; thinking stays available.
            body.put("output_config", effortLevel("low"))
        }
        // These models think at full depth unless told otherwise, which is
        // slow for a translation. Only model families whose fields are documented.
        if (style != AIAPIStyle.CHAT_COMPLETIONS) return
        when {
            // 2.5 Flash and Flash-Lite can turn thinking off.
            kind == AIProviderKind.GEMINI && quick && id.startsWith("gemini-2.5-flash") ->
                body.put("reasoning_effort", "none")
            // Thinking cannot be turned off on later models; minimal is their lowest level.
            kind == AIProviderKind.GEMINI && quick && id.contains("flash") &&
                (id.startsWith("gemini-3") || id.startsWith("gemini-4")) ->
                body.put("reasoning_effort", "minimal")
            (kind == AIProviderKind.DOUBAO && id.startsWith("doubao-seed-2-1")) ||
                (kind == AIProviderKind.ZHIPU && id.startsWith("glm-5.2")) -> {
                body.put("thinking", thinkingType(if (quick) "disabled" else "enabled"))
                if (!quick) body.put("reasoning_effort", "low")
            }
            // Thinking cannot be turned off here.
            (kind == AIProviderKind.KIMI && id.startsWith("kimi-k3")) ||
                (kind == AIProviderKind.ZHIPU && id.startsWith("glm-5.3")) ||
                (kind == AIProviderKind.MINIMAX && id.startsWith("minimax-m3.1")) ->
                body.put("reasoning_effort", "low")
            kind == AIProviderKind.MINIMAX && id == "minimax-m3" ->
                if (quick) body.put("thinking", thinkingType("disabled"))
        }
    }
}

/** Which model-list API a format's provider answers. */
enum class AIModelCatalogAPI {
    STANDARD,
    ANTHROPIC,
    GEMINI;

    companion object {
        fun forStyle(style: AIAPIStyle): AIModelCatalogAPI = when (style) {
            AIAPIStyle.ANTHROPIC_MESSAGES -> ANTHROPIC
            AIAPIStyle.GEMINI_NATIVE -> GEMINI
            AIAPIStyle.CHAT_COMPLETIONS, AIAPIStyle.RESPONSES -> STANDARD
        }
    }
}
