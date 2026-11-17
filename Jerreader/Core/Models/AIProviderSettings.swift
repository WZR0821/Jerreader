import Foundation

/// The request format a model is called with. Its route is appended to the
/// provider's API address; Gemini's own format puts the model in the path.
enum AIAPIStyle: String, Codable, CaseIterable, Identifiable, Sendable {
    case chatCompletions
    case responses
    case anthropicMessages
    /// Gemini's generateContent format: what Gemini setups used before
    /// formats could be chosen, kept for them.
    case geminiNative

    var id: Self { self }

    /// The name on the model page, in the words provider documents use.
    var displayName: String {
        switch self {
        case .chatCompletions: return "OpenAI 兼容"
        case .responses: return "OpenAI Responses"
        case .anthropicMessages: return "Claude 兼容"
        case .geminiNative: return "Gemini 原生"
        }
    }

    var route: String? {
        switch self {
        case .chatCompletions: return "chat/completions"
        case .responses: return "responses"
        case .anthropicMessages: return "messages"
        case .geminiNative: return nil
        }
    }

    /// The formats offered for a model. Gemini's own format is only offered
    /// to a model that already uses it.
    static let selectable: [AIAPIStyle] = [.chatCompletions, .responses, .anthropicMessages]

    var supportsReasoning: Bool { self != .geminiNative }
}

/// A chosen reasoning depth. `nil` in a model entry means 自动.
enum AIReasoningEffort: String, Codable, CaseIterable, Identifiable, Sendable {
    case off, low, medium, high

    var id: Self { self }

    var title: String {
        switch self {
        case .off: return "关闭"
        case .low: return "低"
        case .medium: return "中"
        case .high: return "高"
        }
    }
}

struct AIModelPreset: Identifiable, Equatable, Sendable {
    let id: String
    let title: String
    let detail: String

    init(_ id: String, _ title: String, _ detail: String) {
        self.id = id
        self.title = title
        self.detail = detail
    }
}

extension DirectAIProviderChoice {
    /// The order on the 添加供应商 page: Claude, ChatGPT and DeepSeek first,
    /// then the rest, with no domestic/overseas split.
    static let presets: [DirectAIProviderChoice] = [
        .anthropic, .openAI, .deepSeek, .qwen, .doubao, .kimi, .zhipu, .minimax, .gemini,
    ]

    /// The provider a fresh install starts with.
    static let defaultProvider = DirectAIProviderChoice.deepSeek

    var isCustom: Bool { self == .openAICompatible }

    /// Who runs the service: the second line of a provider row.
    var subtitle: String {
        switch self {
        case .anthropic: return "Anthropic"
        case .openAI: return "OpenAI"
        case .deepSeek: return "深度求索"
        case .qwen: return "阿里云百炼"
        case .doubao: return "火山方舟"
        case .kimi: return "月之暗面"
        case .zhipu: return "智谱 AI"
        case .minimax: return "稀宇科技"
        case .gemini: return "Google"
        case .openAICompatible: return "OpenAI 或 Claude 兼容接口"
        }
    }

    var isRecommended: Bool { self == .defaultProvider }

    var defaultBaseURL: String {
        switch self {
        case .anthropic: return "https://api.anthropic.com/v1"
        case .openAI: return "https://api.openai.com/v1"
        case .deepSeek: return "https://api.deepseek.com"
        case .qwen: return "https://dashscope-us.aliyuncs.com/compatible-mode/v1"
        case .doubao: return "https://ark.cn-beijing.volces.com/api/v3"
        case .kimi: return "https://api.moonshot.cn/v1"
        case .zhipu: return "https://open.bigmodel.cn/api/paas/v4"
        case .minimax: return "https://api.minimax.cn/v1"
        case .gemini: return "https://generativelanguage.googleapis.com/v1beta/openai"
        case .openAICompatible: return ""
        }
    }

    /// The format a newly added model of this provider uses.
    var defaultStyle: AIAPIStyle {
        switch self {
        case .openAI: return .responses
        case .anthropic: return .anthropicMessages
        default: return .chatCompletions
        }
    }

    /// The format each provider was called with before formats could be
    /// chosen. A configuration in that format keeps its cache identity.
    var legacyStyle: AIAPIStyle {
        self == .gemini ? .geminiNative : defaultStyle
    }

    /// The models offered when the provider is added; the first one is used first.
    var modelPresets: [AIModelPreset] {
        switch self {
        case .anthropic:
            return [.init("claude-haiku-5-5", "Claude Haiku 5.5", "日常翻译"),
                    .init("claude-sonnet-5-5", "Claude Sonnet 5.5", "复杂语境"),
                    .init("claude-opus-5-5", "Claude Opus 5.5", "疑难文本")]
        case .openAI:
            return [.init("gpt-6-luna", "GPT-6 Luna", "日常翻译"),
                    .init("gpt-6.1-sol", "GPT-6.1 Sol", "复杂语境"),
                    .init("gpt-4.1-mini", "GPT-4.1 Mini", "轻量模型")]
        case .deepSeek:
            return [.init("deepseek-flash", "DeepSeek Flash", "日常翻译"),
                    .init("deepseek-v4-pro", "DeepSeek V4 Pro", "复杂语境")]
        case .qwen:
            return [.init("qwen-plus", "Qwen Plus", "日常翻译"),
                    .init("qwen-turbo", "Qwen Turbo", "轻量翻译"),
                    .init("qwen-max", "Qwen Max", "复杂语境")]
        case .doubao:
            return [.init("doubao-seed-2-1-lite-260915", "豆包 Seed 2.1 Lite", "日常翻译"),
                    .init("doubao-seed-2-1-pro-260915", "豆包 Seed 2.1 Pro", "复杂语境")]
        case .kimi:
            return [.init("kimi-k3.1-pro", "Kimi K3.1 Pro", "日常翻译"),
                    .init("kimi-k3.1-pro-highspeed", "Kimi K3.1 Pro 高速版", "更快")]
        case .zhipu:
            return [.init("glm-5.3-flash", "GLM-5.3 Flash", "日常翻译"),
                    .init("glm-5.3", "GLM-5.3", "复杂语境")]
        case .minimax:
            return [.init("MiniMax-M3.1-Flash", "MiniMax M3.1 Flash", "日常翻译"),
                    .init("MiniMax-M3", "MiniMax M3", "复杂语境")]
        case .gemini:
            return [.init("gemini-3.8-flash", "Gemini 3.8 Flash", "日常翻译"),
                    .init("gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite", "轻量翻译")]
        case .openAICompatible:
            return []
        }
    }

    /// What to know about the address: shown under the key and address fields.
    var setupNote: String {
        switch self {
        case .qwen:
            return "默认是美国区域的地址，密钥要和区域一致。其他区域请把 API 地址改成控制台给出的地址。"
        case .doubao:
            return "默认是火山方舟（中国站）的地址。国际版 BytePlus 的地址和模型 ID 都不同，要用它请改 API 地址并手动添加模型。"
        case .kimi:
            return "默认是国内站的地址。国际站的密钥不通用，要用它请把 API 地址改成 https://api.moonshot.ai/v1。"
        case .zhipu:
            return "默认是国内站的地址。国际站 Z.AI 的密钥不通用，要用它请把 API 地址改成 https://api.z.ai/api/paas/v4。"
        case .minimax:
            return "默认是国内站的地址。国际站请把 API 地址改成 https://api.minimax.io/v1。"
        case .anthropic, .openAI, .deepSeek, .gemini, .openAICompatible:
            return "已填入官方地址，一般不用改。"
        }
    }

    var documentationURL: URL {
        let text: String
        switch self {
        case .anthropic: text = "https://platform.claude.com/docs/en/models/overview"
        case .openAI: text = "https://developers.openai.com/api/docs/models"
        case .deepSeek: text = "https://api-docs.deepseek.com/"
        case .qwen: text = "https://www.alibabacloud.com/help/en/model-studio/compatibility-of-openai-with-dashscope"
        case .doubao: text = "https://docs.volcengine.com/docs/82379"
        case .kimi: text = "https://platform.kimi.com/docs"
        case .zhipu: text = "https://docs.bigmodel.cn"
        case .minimax: text = "https://platform.minimax.cn/docs"
        case .gemini: text = "https://ai.google.dev/gemini-api/docs/openai"
        case .openAICompatible:
            text = "https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create"
        }
        return URL(string: text)!
    }

    /// The console page where a key is made, when the provider has a stable one.
    var keyURL: URL? {
        let text: String
        switch self {
        case .anthropic: text = "https://platform.claude.com/settings/keys"
        case .openAI: text = "https://platform.openai.com/api-keys"
        case .deepSeek: text = "https://platform.deepseek.com/api_keys"
        case .doubao: text = "https://ark.volcengine.com/region:cn-beijing/apiKey"
        case .kimi: text = "https://platform.kimi.com/console/api-keys"
        case .zhipu: text = "https://bigmodel.cn/usercenter/proj-mgmt/apikeys"
        case .minimax: text = "https://platform.minimax.cn/user-center/basic-information/interface-key"
        case .gemini: text = "https://aistudio.google.com/apikey"
        // 通义千问's console differs by region; the documentation link covers it.
        case .qwen, .openAICompatible: return nil
        }
        return URL(string: text)
    }

    /// 通义千问 only thinks when streaming, so it offers 关闭 alone.
    var reasoningOptions: [AIReasoningEffort] {
        self == .qwen ? [.off] : AIReasoningEffort.allCases
    }

    var reasoningNote: String {
        switch self {
        case .anthropic:
            return "自动：翻译时对 5 系列用低强度；语法解释不发送思考参数（默认自适应思考）。Opus 5.5 无法关闭思考，“关闭”会改用最低强度。"
        case .openAI:
            return "自动：翻译时对已知模型降低推理。不支持推理的模型（如 GPT-4.1 Mini）请保持自动。"
        case .deepSeek, .doubao:
            return "自动：翻译时关闭思考，语法解释用低强度。"
        case .qwen:
            return "通义千问的思考模式需要流式输出，这里只提供关闭。"
        case .kimi:
            return "K3 系列始终思考，无法关闭：自动和“关闭”都用最低档。"
        case .zhipu:
            return "GLM-5.3 系列无法关闭思考，自动和“关闭”都用最低档；GLM-5.2 可以关闭。"
        case .minimax:
            return "M3.1 Flash 无法关闭思考，自动和“关闭”都用最低档；M3 只能开或关，不分强度。"
        case .gemini:
            return "自动：翻译时对 Flash 系列用最低档（2.5 Flash 关闭思考）。Gemini 3 等无法完全关闭思考的模型，“关闭”会用最低档。"
        case .openAICompatible:
            return "自动不发送任何推理参数；其他档位按所选接口格式发送标准字段。"
        }
    }

    /// The model each provider used when none was chosen, before providers
    /// kept a list of models. Only migration reads it.
    var legacyDefaultModel: String {
        switch self {
        case .openAI: return "gpt-5.6-luna"
        case .anthropic: return "claude-sonnet-5"
        case .kimi: return "kimi-k2.6"
        case .deepSeek: return "deepseek-v4-flash"
        case .gemini: return "gemini-3.5-flash"
        default: return ""
        }
    }

    /// The providers that existed before providers kept a list of models.
    static let legacyCases: [DirectAIProviderChoice] = [
        .anthropic, .openAI, .deepSeek, .kimi, .gemini, .openAICompatible,
    ]
}

/// One model kept under a provider, with the settings that belong to it.
struct AIModelEntry: Codable, Equatable, Identifiable, Sendable {
    var id: String
    var style: AIAPIStyle
    var reasoning: AIReasoningEffort?

    init(id: String, style: AIAPIStyle, reasoning: AIReasoningEffort? = nil) {
        self.id = id
        self.style = style
        self.reasoning = reasoning
    }
}

/// One provider: its address, the models kept under it and the one it uses.
/// Its API key lives in the keychain under the entry's id.
struct AIProviderEntry: Codable, Equatable, Identifiable, Sendable {
    /// A preset's raw value, or `custom-<uuid>` for a custom service.
    var id: String
    var kind: DirectAIProviderChoice
    /// The user's own name for a custom service.
    var name: String?
    var baseURL: String
    /// The model this provider uses.
    var model: String
    var models: [AIModelEntry]

    var displayName: String {
        guard kind.isCustom else { return kind.title }
        let label = name?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return label.isEmpty ? kind.title : label
    }

    var host: String {
        let text = baseURL.trimmingCharacters(in: .whitespacesAndNewlines)
        return URLComponents(string: text)?.host ?? (text.isEmpty ? "未填写" : text)
    }

    /// The settings of the model in use. A model missing from the list uses
    /// the format of the provider's other models.
    var activeModel: AIModelEntry {
        models.first { $0.id == model }
            ?? AIModelEntry(id: model, style: models.first?.style ?? kind.defaultStyle)
    }

    /// The format a model added now starts with: the one the provider's
    /// address already works with.
    var styleForNewModel: AIAPIStyle { activeModel.style }

    static func preset(_ kind: DirectAIProviderChoice, models ids: [String]? = nil) -> AIProviderEntry {
        let ids = ids ?? kind.modelPresets.map(\.id)
        return AIProviderEntry(
            id: kind.rawValue, kind: kind, name: nil, baseURL: kind.defaultBaseURL,
            model: ids.first ?? "", models: ids.map { AIModelEntry(id: $0, style: kind.defaultStyle) }
        )
    }

    static func newCustomID() -> String { "custom-" + UUID().uuidString.lowercased() }

    mutating func upsert(_ entry: AIModelEntry) {
        if let index = models.firstIndex(where: { $0.id == entry.id }) {
            models[index] = entry
        } else {
            models.append(entry)
        }
        if model.isEmpty { model = entry.id }
    }

    /// Removes a model. If it was the one in use, the next model takes over.
    mutating func removeModel(_ id: String) {
        models.removeAll { $0.id == id }
        if model == id { model = models.first?.id ?? "" }
    }
}

enum AIProviderSettingsError: LocalizedError, Equatable {
    case removingActiveProvider
    case removingActiveModel
    case unknownProvider
    case invalidAddress
    case missingModel
    case missingKey(String)
    case keychainUnavailable

    var errorDescription: String? {
        switch self {
        case .missingKey(let provider): return "\(provider) 还没有填 API Key。"
        case .keychainUnavailable: return "无法将 API Key 安全保存到钥匙串。"
        case .removingActiveProvider: return "正在用的供应商不能删除。先用“换模型”换到别的供应商。"
        case .removingActiveModel: return "正在用的模型不能删除，先换到别的模型。"
        case .unknownProvider: return "找不到这个供应商，可能已经删除。"
        case .invalidAddress: return "API 地址必须是带完整主机名的 HTTPS 地址。"
        case .missingModel: return "请先选一个模型。"
        }
    }
}

/// Every provider that was set up and the one in use. Saved as JSON; keys
/// stay in the keychain.
struct AIProviderSettings: Codable, Equatable, Sendable {
    var activeID: String
    var entries: [AIProviderEntry]

    /// A fresh install: the default provider with its preset models, no key yet.
    static var fresh: AIProviderSettings {
        let entry = AIProviderEntry.preset(DirectAIProviderChoice.defaultProvider)
        return AIProviderSettings(activeID: entry.id, entries: [entry])
    }

    var active: AIProviderEntry {
        entry(activeID) ?? entries.first ?? .preset(DirectAIProviderChoice.defaultProvider)
    }

    func entry(_ id: String) -> AIProviderEntry? {
        entries.first { $0.id == id }
    }

    func displayName(for id: String) -> String {
        entry(id)?.displayName ?? DirectAIProviderChoice(rawValue: id)?.title ?? "自定义 API"
    }

    func isInUse(model: String, of id: String) -> Bool {
        activeID == id && entry(id)?.model == model
    }

    /// The default provider as a fresh install has it: nothing of the user's in it yet.
    func isUntouchedDefault(_ entry: AIProviderEntry) -> Bool {
        entry == .preset(DirectAIProviderChoice.defaultProvider)
    }

    /// A preset that is already set up is shown as added on the 添加供应商
    /// page. The untouched default still counts as available until it has a key.
    func hasSetUp(_ kind: DirectAIProviderChoice, keyed: Set<String>) -> Bool {
        guard let entry = entry(kind.rawValue) else { return false }
        return keyed.contains(entry.id) || !isUntouchedDefault(entry)
    }

    mutating func update(_ id: String, _ change: (inout AIProviderEntry) -> Void) {
        guard let index = entries.firstIndex(where: { $0.id == id }) else { return }
        change(&entries[index])
    }

    /// Puts a provider, and optionally one of its models, in use.
    mutating func activate(_ id: String, model: String? = nil) {
        guard entry(id) != nil else { return }
        activeID = id
        if let model {
            update(id) { entry in
                if !entry.models.contains(where: { $0.id == model }) {
                    entry.models.append(AIModelEntry(id: model, style: entry.styleForNewModel))
                }
                entry.model = model
            }
        }
    }

    /// Adds a provider, or replaces a preset that was never set up. It is put
    /// in use only when the provider in use has no key; the untouched default
    /// then leaves the list.
    /// - Returns: whether the new provider is now in use.
    @discardableResult
    mutating func add(_ entry: AIProviderEntry, activeHasKey: Bool) -> Bool {
        let previous = active
        if let index = entries.firstIndex(where: { $0.id == entry.id }) {
            entries[index] = entry
        } else {
            entries.append(entry)
        }
        guard !activeHasKey || previous.id == entry.id else { return false }
        activeID = entry.id
        if previous.id != entry.id, isUntouchedDefault(previous) {
            entries.removeAll { $0.id == previous.id }
        }
        return true
    }

    mutating func remove(_ id: String) throws {
        guard id != activeID else { throw AIProviderSettingsError.removingActiveProvider }
        entries.removeAll { $0.id == id }
    }

    mutating func removeModel(_ model: String, from id: String) throws {
        guard !isInUse(model: model, of: id) else { throw AIProviderSettingsError.removingActiveModel }
        update(id) { $0.removeModel(model) }
    }

    /// Builds the providers from the settings kept before providers had a
    /// list of models: one entry for every provider that was selected, has a
    /// key, or has a model or address of its own. Each keeps the model and
    /// address it used, so its requests, and its translation cache, stay the same.
    static func migrated(
        selected: DirectAIProviderChoice?,
        keyed: Set<DirectAIProviderChoice>,
        storedModels: [DirectAIProviderChoice: String],
        storedEndpoints: [DirectAIProviderChoice: String]
    ) -> AIProviderSettings {
        var entries: [AIProviderEntry] = []
        for kind in DirectAIProviderChoice.legacyCases {
            let storedModel = storedModels[kind]?.trimmingCharacters(in: .whitespacesAndNewlines)
            let storedEndpoint = storedEndpoints[kind]?.trimmingCharacters(in: .whitespacesAndNewlines)
            guard kind == selected || keyed.contains(kind) || storedModel != nil || storedEndpoint != nil
            else { continue }

            let model = storedModel.flatMap { $0.isEmpty ? nil : $0 } ?? kind.legacyDefaultModel
            let base: String
            let style: AIAPIStyle
            switch kind {
            case .openAICompatible:
                let split = AIEndpoint.split(storedEndpoint ?? "")
                base = split.base
                style = split.style ?? .chatCompletions
            case .gemini:
                base = "https://generativelanguage.googleapis.com/v1beta"
                style = .geminiNative
            default:
                base = kind.defaultBaseURL
                style = kind.legacyStyle
            }
            entries.append(AIProviderEntry(
                id: kind.rawValue, kind: kind, name: nil, baseURL: base, model: model,
                models: model.isEmpty ? [] : [AIModelEntry(id: model, style: style)]
            ))
        }
        guard !entries.isEmpty else { return .fresh }
        // Before, a provider that was never chosen meant ChatGPT.
        let wanted = (selected ?? .openAI).rawValue
        let activeID = entries.contains { $0.id == wanted } ? wanted : entries[0].id
        return AIProviderSettings(activeID: activeID, entries: entries)
    }
}

/// Request URLs: a provider's API address plus the route of a format.
enum AIEndpoint {
    /// Splits a full request URL, as typed before formats were chosen apart,
    /// into the API address and the format its route names.
    static func split(_ text: String) -> (base: String, style: AIAPIStyle?) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard var components = URLComponents(string: trimmed), components.host != nil else {
            return (trimmed, nil)
        }
        let (path, style) = stripRoute(components.path)
        components.path = path
        return (components.string ?? trimmed, style)
    }

    /// The URL a request goes to. An address that already ends in a route,
    /// from a pasted full URL, is taken without it.
    static func url(base: String, style: AIAPIStyle) -> URL? {
        let trimmed = base.trimmingCharacters(in: .whitespacesAndNewlines)
        guard var components = URLComponents(string: trimmed),
              components.scheme?.lowercased() == "https",
              components.host?.isEmpty == false,
              components.user == nil, components.password == nil, components.fragment == nil
        else { return nil }
        var (path, _) = stripRoute(components.path)
        if let route = style.route { path += "/" + route }
        components.path = path
        return components.url
    }

    static func isValid(base: String) -> Bool {
        url(base: base, style: .chatCompletions) != nil
    }

    private static func stripRoute(_ path: String) -> (String, AIAPIStyle?) {
        var path = path
        while path.hasSuffix("/") { path.removeLast() }
        for style in AIAPIStyle.selectable {
            guard let route = style.route else { continue }
            if path.lowercased().hasSuffix("/" + route) {
                return (String(path.dropLast(route.count + 1)), style)
            }
        }
        return (path, nil)
    }
}

/// Fields added to a request body for a model, on top of the format's own.
/// 自动 sends only fields documented for known model families; a custom
/// service gets nothing it did not ask for.
enum AIRequestTuning {
    /// - Parameter quick: a translation, as opposed to a grammar explanation.
    static func extraFields(
        kind: DirectAIProviderChoice,
        style: AIAPIStyle,
        model: String,
        reasoning: AIReasoningEffort?,
        quick: Bool
    ) -> [String: Any] {
        var body: [String: Any] = [:]
        applyOutputLimits(to: &body, kind: kind, style: style, model: model)
        guard style.supportsReasoning else { return body }
        if let effort = reasoning, kind.reasoningOptions.contains(effort) {
            applyReasoning(effort, to: &body, kind: kind, style: style, model: model)
        } else {
            applyAutomaticReasoning(to: &body, kind: kind, style: style, model: model, quick: quick)
        }
        return body
    }

    /// Fields a known model needs to return a whole answer, whatever the reasoning setting.
    private static func applyOutputLimits(
        to body: inout [String: Any], kind: DirectAIProviderChoice, style: AIAPIStyle, model: String
    ) {
        guard style == .chatCompletions else { return }
        let id = model.lowercased()
        switch kind {
        case .doubao where id.hasPrefix("doubao-seed-2-1"):
            // Without a limit the answer stops at 4K tokens.
            body["max_tokens"] = 16_000
        case .kimi where id.hasPrefix("kimi-k3"):
            // Reasoning counts toward the limit; Kimi's documentation asks for 16000 or more.
            body["max_tokens"] = 16_000
        case .minimax where id.hasPrefix("minimax-m"):
            // Keeps the model's thinking out of the translated text.
            body["reasoning_split"] = true
            if id.hasPrefix("minimax-m3") { body["max_completion_tokens"] = 32_000 }
        default:
            break
        }
    }

    private static func applyReasoning(
        _ effort: AIReasoningEffort, to body: inout [String: Any],
        kind: DirectAIProviderChoice, style: AIAPIStyle, model: String
    ) {
        let off = effort == .off
        let level = effort.rawValue
        if kind == .qwen {
            if off, style != .anthropicMessages { body["enable_thinking"] = false }
            return
        }
        switch style {
        case .responses:
            body["reasoning"] = ["effort": off ? "none" : level]
        case .chatCompletions:
            switch kind {
            case .deepSeek, .doubao:
                body["thinking"] = ["type": off ? "disabled" : "enabled"]
                if !off { body["reasoning_effort"] = level }
            case .gemini:
                // Gemini 2.x Flash can turn thinking off; later models only go down to minimal.
                body["reasoning_effort"] = off ? (model.hasPrefix("gemini-2") ? "none" : "minimal") : level
            case .kimi:
                // K3 always thinks and takes low, high or max.
                body["reasoning_effort"] = threeStepEffort(effort)
            case .zhipu:
                let id = model.lowercased()
                if id.hasPrefix("glm-5.3") {
                    // Thinking cannot be turned off; these models take low, high or max.
                    body["reasoning_effort"] = threeStepEffort(effort)
                } else {
                    body["thinking"] = ["type": off ? "disabled" : "enabled"]
                    // Earlier models switch thinking on or off and have no effort level.
                    if !off, id.hasPrefix("glm-5.2") { body["reasoning_effort"] = level }
                }
            case .minimax:
                if model.lowercased().hasPrefix("minimax-m3.1") {
                    // Thinking is always on for this model; only the effort goes down.
                    body["reasoning_effort"] = off ? "low" : level
                } else {
                    // Other models think or do not, and ignore an effort level.
                    body["thinking"] = ["type": off ? "disabled" : "adaptive"]
                }
            default:
                body["reasoning_effort"] = off ? "none" : level
            }
        case .anthropicMessages:
            if kind == .deepSeek {
                body["thinking"] = ["type": off ? "disabled" : "enabled"]
                if !off { body["output_config"] = ["effort": level] }
            } else {
                applyClaudeThinking(to: &body, effort: effort, model: model)
            }
        case .geminiNative:
            break
        }
    }

    /// 关闭 and 低 share the lowest step on models that take only low, high and max.
    private static func threeStepEffort(_ effort: AIReasoningEffort) -> String {
        switch effort {
        case .off, .low: return "low"
        case .medium: return "high"
        case .high: return "max"
        }
    }

    /// The translation's own room in a Messages request, as sent without thinking.
    static let anthropicOutputTokens = 1_200

    private static func applyClaudeThinking(
        to body: inout [String: Any], effort: AIReasoningEffort, model: String
    ) {
        let id = model.lowercased()
        // Older models only understand a fixed thinking budget and do not think unless asked.
        let budgetPrefixes = ["claude-3", "claude-haiku-4", "claude-sonnet-4-5", "claude-sonnet-4-0",
                              "claude-sonnet-4-2", "claude-opus-4-0", "claude-opus-4-1", "claude-opus-4-5",
                              "claude-opus-4-2"]
        if budgetPrefixes.contains(where: { id.hasPrefix($0) }) || id == "claude-sonnet-4" || id == "claude-opus-4" {
            let budget: Int
            switch effort {
            case .off: return
            case .low: budget = 1_024
            case .medium: budget = 4_096
            case .high: budget = 8_192
            }
            // Thinking counts toward max_tokens; keep the translation's own room.
            body["thinking"] = ["type": "enabled", "budget_tokens": budget]
            body["max_tokens"] = anthropicOutputTokens + budget
            return
        }
        if effort == .off {
            if id.hasPrefix("claude-sonnet-5-5") {
                body["thinking"] = ["type": "between_tools"]
            } else if ["claude-opus-5-5", "claude-fable", "claude-mythos"].contains(where: { id.hasPrefix($0) }) {
                // Thinking cannot be turned off on these models; use the lowest effort.
                body["output_config"] = ["effort": "low"]
            } else {
                body["thinking"] = ["type": "disabled"]
            }
            return
        }
        body["thinking"] = ["type": "adaptive"]
        body["output_config"] = ["effort": effort.rawValue]
        body["max_tokens"] = 16_000
    }

    /// 自动: only documented fields for known model families.
    private static func applyAutomaticReasoning(
        to body: inout [String: Any], kind: DirectAIProviderChoice, style: AIAPIStyle,
        model: String, quick: Bool
    ) {
        // DeepSeek reasons at high effort by default. A translation turns it
        // off; an explanation keeps it at low effort.
        if kind == .deepSeek, ["deepseek-flash", "deepseek-v4-pro"].contains(model) {
            if style == .responses {
                body["reasoning"] = ["effort": quick ? "none" : "low"]
            } else {
                body["thinking"] = ["type": quick ? "disabled" : "enabled"]
                if !quick {
                    if style == .anthropicMessages {
                        body["output_config"] = ["effort": "low"]
                    } else {
                        body["reasoning_effort"] = "low"
                    }
                }
            }
        }
        if quick, kind == .openAI {
            let efforts = ["gpt-6-luna": "none", "gpt-6-sol": "none", "gpt-6.1-sol": "low", "gpt-6-astra": "low"]
            if let effort = efforts[model] {
                if style == .responses {
                    body["reasoning"] = ["effort": effort]
                } else if style == .chatCompletions {
                    body["reasoning_effort"] = effort
                }
            }
        }
        let id = model.lowercased()
        if quick, kind == .anthropic, style == .anthropicMessages,
           ["claude-opus-5", "claude-sonnet-5", "claude-haiku-5", "claude-fable", "claude-mythos"]
            .contains(where: { id.hasPrefix($0) }) {
            // The 5 series thinks adaptively by default. Low effort keeps a
            // translation short; thinking stays available.
            body["output_config"] = ["effort": "low"]
        }
        // These models think at full depth unless told otherwise, which is
        // slow for a translation. Only model families whose fields are documented.
        guard style == .chatCompletions else { return }
        switch kind {
        case .gemini where quick && id.hasPrefix("gemini-2.5-flash"):
            // 2.5 Flash and Flash-Lite can turn thinking off.
            body["reasoning_effort"] = "none"
        case .gemini where quick && id.contains("flash") && (id.hasPrefix("gemini-3") || id.hasPrefix("gemini-4")):
            // Thinking cannot be turned off on later models; minimal is their lowest level.
            body["reasoning_effort"] = "minimal"
        case .doubao where id.hasPrefix("doubao-seed-2-1"), .zhipu where id.hasPrefix("glm-5.2"):
            body["thinking"] = ["type": quick ? "disabled" : "enabled"]
            if !quick { body["reasoning_effort"] = "low" }
        case .kimi where id.hasPrefix("kimi-k3"), .zhipu where id.hasPrefix("glm-5.3"),
             .minimax where id.hasPrefix("minimax-m3.1"):
            // Thinking cannot be turned off here.
            body["reasoning_effort"] = "low"
        case .minimax where id == "minimax-m3":
            if quick { body["thinking"] = ["type": "disabled"] }
        default:
            break
        }
    }
}
