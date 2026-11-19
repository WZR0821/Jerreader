import Foundation

/// Offline checks of the AI provider list: the order of the presets, request
/// URLs, migration from the settings kept before providers had a list of
/// models, editing the list, and the fields each model family is sent.
@main enum AIProviderTests {
    static func main() throws {
        presets()
        endpoints()
        migration()
        try editing()
        tuning()
        try storage()
        print("AI provider settings passed offline.")
    }

    static func presets() {
        let presets = DirectAIProviderChoice.presets
        precondition(Array(presets.prefix(3)) == [.anthropic, .openAI, .deepSeek])
        precondition(Set(presets).count == presets.count && !presets.contains(.openAICompatible))
        precondition(Set(presets + [.openAICompatible, .chatGPTPlan]) == Set(DirectAIProviderChoice.allCases))
        // The ChatGPT plan signs in instead of taking a key; it is listed on the 套餐 page only.
        let plan = DirectAIProviderChoice.chatGPTPlan
        precondition(!presets.contains(plan) && !DirectAIProviderChoice.legacyCases.contains(plan))
        precondition(plan.usesAccountSignIn && !plan.isCustom)
        precondition(DirectAIProviderChoice.allCases.filter(\.usesAccountSignIn) == [plan])
        precondition(plan.defaultStyle == .responses && plan.modelPresets.isEmpty && plan.keyURL == nil)
        precondition(plan.defaultBaseURL == "https://api.openai.com/v1" && plan.title == "ChatGPT 套餐")
        for kind in presets {
            precondition(!kind.modelPresets.isEmpty, "\(kind) has no models")
            precondition(kind.defaultModel == kind.modelPresets[0].id)
            precondition(AIEndpoint.isValid(base: kind.defaultBaseURL), "\(kind) address")
        }
        let marks = DirectAIProviderChoice.allCases.map(\.fallbackMark)
        precondition(Set(marks).count == marks.count)

        let fresh = AIProviderSettings.fresh
        precondition(fresh.activeID == "deepSeek" && fresh.active.model == "deepseek-flash")
        precondition(fresh.active.models.map(\.id) == ["deepseek-flash", "deepseek-v4-pro"])
        precondition(fresh.isUntouchedDefault(fresh.active))
    }

    static func endpoints() {
        func url(_ base: String, _ style: AIAPIStyle) -> String? {
            AIEndpoint.url(base: base, style: style)?.absoluteString
        }
        precondition(url("https://api.openai.com/v1", .responses) == "https://api.openai.com/v1/responses")
        precondition(url("https://api.deepseek.com/", .chatCompletions) == "https://api.deepseek.com/chat/completions")
        precondition(url("https://api.anthropic.com/v1", .anthropicMessages) == "https://api.anthropic.com/v1/messages")
        // A pasted full request URL is taken without its route.
        precondition(url("https://example.test/v1/chat/completions", .responses) == "https://example.test/v1/responses")
        precondition(url("https://generativelanguage.googleapis.com/v1beta", .geminiNative)
            == "https://generativelanguage.googleapis.com/v1beta")
        precondition(url("http://example.test/v1", .chatCompletions) == nil)
        precondition(url("example.test/v1", .chatCompletions) == nil)
        precondition(url("https://user:secret@example.test/v1", .chatCompletions) == nil)

        let split = AIEndpoint.split(" https://example.test/v1/messages ")
        precondition(split.base == "https://example.test/v1" && split.style == .anthropicMessages)
        precondition(AIEndpoint.split("https://example.test/v1").style == nil)
    }

    static func migration() {
        precondition(AIProviderSettings.migrated(selected: nil, keyed: [], storedModels: [:], storedEndpoints: [:]) == .fresh)

        // Before a provider was chosen, the provider was ChatGPT.
        let keyed = AIProviderSettings.migrated(
            selected: nil, keyed: [.anthropic, .openAI], storedModels: [.openAI: "gpt-legacy"], storedEndpoints: [:]
        )
        precondition(keyed.entries.map(\.id) == ["anthropic", "openAI"] && keyed.activeID == "openAI")
        precondition(keyed.active.activeModel == AIModelEntry(id: "gpt-legacy", style: .responses))
        precondition(keyed.entry("anthropic")?.activeModel == AIModelEntry(id: "claude-sonnet-5", style: .anthropicMessages))
        let onlyClaude = AIProviderSettings.migrated(selected: nil, keyed: [.anthropic], storedModels: [:], storedEndpoints: [:])
        precondition(onlyClaude.activeID == "anthropic")

        let gemini = AIProviderSettings.migrated(selected: .gemini, keyed: [], storedModels: [:], storedEndpoints: [:])
        precondition(gemini.activeID == "gemini")
        precondition(gemini.active.baseURL == "https://generativelanguage.googleapis.com/v1beta")
        precondition(gemini.active.activeModel == AIModelEntry(id: "gemini-3.5-flash", style: .geminiNative))

        let custom = AIProviderSettings.migrated(
            selected: .openAICompatible, keyed: [.openAICompatible],
            storedModels: [.openAICompatible: " my-model "],
            storedEndpoints: [.openAICompatible: "https://example.test/v1/responses"]
        )
        precondition(custom.active.baseURL == "https://example.test/v1")
        precondition(custom.active.activeModel == AIModelEntry(id: "my-model", style: .responses))

        // Every provider keeps the request URL, model and format it had.
        let before = [
            "openAI": "https://api.openai.com/v1/responses",
            "anthropic": "https://api.anthropic.com/v1/messages",
            "kimi": "https://api.moonshot.cn/v1/chat/completions",
            "deepSeek": "https://api.deepseek.com/chat/completions",
        ]
        let all = AIProviderSettings.migrated(
            selected: .kimi, keyed: [.openAI, .anthropic, .kimi, .deepSeek], storedModels: [:], storedEndpoints: [:]
        )
        precondition(all.activeID == "kimi" && all.entries.count == before.count)
        for entry in all.entries {
            let request = AIEndpoint.url(base: entry.baseURL, style: entry.activeModel.style)?.absoluteString
            precondition(request == before[entry.id], "\(entry.id) request URL")
            precondition(entry.activeModel.style == entry.kind.legacyStyle)
            precondition(entry.model == entry.kind.legacyDefaultModel)
        }
    }

    static func editing() throws {
        var settings = AIProviderSettings.fresh
        // A provider added while the default has no key takes the default's place.
        let replaced = settings.add(.preset(.anthropic), activeHasKey: false)
        precondition(replaced && settings.entries.map(\.id) == ["anthropic"] && settings.activeID == "anthropic")
        // With a key in use, a new provider is kept but not put in use.
        let switched = settings.add(.preset(.openAI), activeHasKey: true)
        precondition(!switched && settings.activeID == "anthropic")

        // A model not kept yet is added in the format of the provider's models.
        settings.activate("openAI", model: "gpt-new")
        precondition(settings.activeID == "openAI")
        precondition(settings.active.activeModel == AIModelEntry(id: "gpt-new", style: .responses))
        precondition(settings.isInUse(model: "gpt-new", of: "openAI"))
        do {
            try settings.removeModel("gpt-new", from: "openAI")
            preconditionFailure("removed the model in use")
        } catch {
            precondition(error as? AIProviderSettingsError == .removingActiveModel)
        }
        do {
            try settings.remove("openAI")
            preconditionFailure("removed the provider in use")
        } catch {
            precondition(error as? AIProviderSettingsError == .removingActiveProvider)
        }
        try settings.removeModel("gpt-6-luna", from: "openAI")
        precondition(!settings.active.models.contains { $0.id == "gpt-6-luna" })
        try settings.remove("anthropic")
        precondition(settings.entries.map(\.id) == ["openAI"])

        // Removing the model a provider uses hands over to its next one.
        var kimi = AIProviderEntry.preset(.kimi)
        kimi.removeModel("kimi-k3.1-pro")
        precondition(kimi.model == "kimi-k3.1-pro-highspeed")

        var custom = AIProviderEntry(
            id: AIProviderEntry.newCustomID(), kind: .openAICompatible, name: "  ",
            baseURL: "https://example.test/v1", model: "", models: []
        )
        precondition(custom.id.hasPrefix("custom-") && custom.host == "example.test")
        precondition(custom.displayName == DirectAIProviderChoice.openAICompatible.title)
        custom.name = "My Lab"
        precondition(custom.displayName == "My Lab")

        // A preset shows as added once it has a key or was changed.
        let fresh = AIProviderSettings.fresh
        precondition(!fresh.hasSetUp(.deepSeek, keyed: []) && fresh.hasSetUp(.deepSeek, keyed: ["deepSeek"]))
        precondition(!fresh.hasSetUp(.openAI, keyed: ["openAI"]))
    }

    static func tuning() {
        func fields(
            _ kind: DirectAIProviderChoice, _ style: AIAPIStyle, _ model: String,
            _ reasoning: AIReasoningEffort? = nil, quick: Bool = true
        ) -> [String: Any] {
            AIRequestTuning.extraFields(kind: kind, style: style, model: model, reasoning: reasoning, quick: quick)
        }
        func text(_ value: Any?, _ key: String) -> String? { (value as? [String: Any])?[key] as? String }

        // 自动 sends nothing to a model it does not know.
        precondition(fields(.openAICompatible, .chatCompletions, "anything").isEmpty)
        precondition(fields(.openAI, .responses, "gpt-test").isEmpty)
        precondition(fields(.gemini, .geminiNative, "gemini-3.5-flash", .high).isEmpty)

        let flash = fields(.deepSeek, .chatCompletions, "deepseek-flash")
        precondition(text(flash["thinking"], "type") == "disabled" && flash["reasoning_effort"] == nil)
        let explained = fields(.deepSeek, .chatCompletions, "deepseek-flash", quick: false)
        precondition(text(explained["thinking"], "type") == "enabled")
        precondition(explained["reasoning_effort"] as? String == "low")

        precondition(text(fields(.openAI, .responses, "gpt-6-luna")["reasoning"], "effort") == "none")
        // The plan's known models are tuned like the API's; unknown ones get nothing.
        precondition(text(fields(.chatGPTPlan, .responses, "gpt-6-luna")["reasoning"], "effort") == "none")
        precondition(text(fields(.chatGPTPlan, .responses, "gpt-6-astra", .high)["reasoning"], "effort") == "high")
        precondition(fields(.chatGPTPlan, .responses, "gpt-test").isEmpty)
        precondition(fields(.chatGPTPlan, .responses, "gpt-6-luna", quick: false).isEmpty)
        precondition(text(fields(.openAI, .responses, "gpt-6-luna", .high)["reasoning"], "effort") == "high")
        precondition(text(fields(.anthropic, .anthropicMessages, "claude-haiku-5-5")["output_config"], "effort") == "low")
        precondition(fields(.anthropic, .anthropicMessages, "claude-haiku-5-5", quick: false).isEmpty)

        let kimi = fields(.kimi, .chatCompletions, "kimi-k3.1-pro")
        precondition(kimi["max_tokens"] as? Int == 16_000 && kimi["reasoning_effort"] as? String == "low")
        precondition(fields(.kimi, .chatCompletions, "kimi-k3.1-pro", .high)["reasoning_effort"] as? String == "max")

        precondition(fields(.qwen, .chatCompletions, "qwen-plus", .off)["enable_thinking"] as? Bool == false)
        // 通义千问 offers 关闭 alone; any other level is treated as 自动.
        precondition(fields(.qwen, .chatCompletions, "qwen-plus", .high).isEmpty)

        let older = fields(.anthropic, .anthropicMessages, "claude-sonnet-4-5", .medium)
        precondition((older["thinking"] as? [String: Any])?["budget_tokens"] as? Int == 4_096)
        precondition(older["max_tokens"] as? Int == AIRequestTuning.anthropicOutputTokens + 4_096)
    }

    static func storage() throws {
        var settings = AIProviderSettings.fresh
        settings.update("deepSeek") { $0.models[1].reasoning = .high }
        let data = try JSONEncoder().encode(settings)
        let decoded = try JSONDecoder().decode(AIProviderSettings.self, from: data)
        precondition(decoded == settings)

        // Settings saved without the optional fields still load.
        let stored = #"{"activeID":"deepSeek","entries":[{"id":"deepSeek","kind":"deepSeek","baseURL":"https://api.deepseek.com","model":"deepseek-flash","models":[{"id":"deepseek-flash","style":"chatCompletions"}]}]}"#
        let older = try JSONDecoder().decode(AIProviderSettings.self, from: Data(stored.utf8))
        precondition(older.active.activeModel == AIModelEntry(id: "deepseek-flash", style: .chatCompletions))
        precondition(older.active.name == nil)

        // A provider kind this version does not know is skipped, not a reason to lose the rest.
        let later = #"{"activeID":"deepSeek","entries":[{"id":"future","kind":"futureKind","baseURL":"https://x.test","model":"m","models":[]},{"id":"deepSeek","kind":"deepSeek","baseURL":"https://api.deepseek.com","model":"deepseek-flash","models":[{"id":"deepseek-flash","style":"chatCompletions"}]}]}"#
        let skipped = try JSONDecoder().decode(AIProviderSettings.self, from: Data(later.utf8))
        precondition(skipped.entries.map(\.id) == ["deepSeek"] && skipped.active.id == "deepSeek")

        // The plan's entry round-trips like any other.
        var withPlan = AIProviderSettings.fresh
        withPlan.add(AIProviderEntry(id: "chatGPTPlan", kind: .chatGPTPlan, name: nil,
                                     baseURL: "https://api.openai.com/v1", model: "gpt-test",
                                     models: [AIModelEntry(id: "gpt-test", style: .responses, reasoning: .low)]),
                     activeHasKey: false)
        precondition(withPlan.activeID == "chatGPTPlan" && withPlan.entries.map(\.id) == ["chatGPTPlan"])
        let planData = try JSONEncoder().encode(withPlan)
        let planDecoded = try JSONDecoder().decode(AIProviderSettings.self, from: planData)
        precondition(planDecoded == withPlan)

        // A configuration made without a format uses the provider's original one.
        let endpoint = URL(string: "https://example.test/v1beta")!
        let gemini = DirectAITranslationConfiguration(provider: .gemini, endpoint: endpoint, model: "m", apiKey: "k")
        precondition(gemini.style == .geminiNative && gemini.reasoning == nil)
        let openAI = DirectAITranslationConfiguration(provider: .openAI, endpoint: endpoint, model: "m", apiKey: "k")
        precondition(openAI.style == .responses)
    }
}
