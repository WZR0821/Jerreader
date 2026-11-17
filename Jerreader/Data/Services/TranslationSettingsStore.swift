import Combine
import Foundation
import Security

@MainActor
protocol TranslationCredentialStoring: AnyObject {
    func readSecret(account: String) throws -> String?
    func saveSecret(_ secret: String?, account: String) throws
}

@MainActor
final class TranslationSettingsStore: ObservableObject {
    @Published var provider: TranslationProviderChoice {
        didSet { defaults.set(provider.rawValue, forKey: Keys.provider) }
    }

    @Published var targetLanguage: LanguageCode {
        didSet { defaults.set(targetLanguage.rawValue, forKey: Keys.targetLanguage) }
    }

    @Published var sourceLanguageChoice: TranslationSourceLanguageChoice {
        didSet {
            defaults.set(
                sourceLanguageChoice.rawValue,
                forKey: Keys.sourceLanguageChoice
            )
        }
    }

    @Published var translationPromptTemplate: String {
        didSet {
            defaults.set(
                translationPromptTemplate,
                forKey: Keys.translationPromptTemplate
            )
        }
    }

    @Published var grammarAnalysisPromptTemplate: String {
        didSet {
            defaults.set(
                grammarAnalysisPromptTemplate,
                forKey: Keys.grammarAnalysisPromptTemplate
            )
        }
    }

    @Published var displayMode: TranslationDisplayMode {
        didSet { defaults.set(displayMode.rawValue, forKey: Keys.displayMode) }
    }

    @Published var translationHapticsEnabled: Bool {
        didSet {
            defaults.set(
                translationHapticsEnabled,
                forKey: Keys.translationHapticsEnabled
            )
        }
    }

    @Published var quickSentenceTranslationEnabled: Bool {
        didSet {
            defaults.set(
                quickSentenceTranslationEnabled,
                forKey: Keys.quickSentenceTranslationEnabled
            )
        }
    }

    @Published var quickTranslationUnit: ReaderQuickTranslationUnit {
        didSet {
            defaults.set(
                quickTranslationUnit.rawValue,
                forKey: Keys.quickTranslationUnit
            )
        }
    }

    @Published var disablesTapPageTurnsDuringQuickTranslation: Bool {
        didSet {
            defaults.set(
                disablesTapPageTurnsDuringQuickTranslation,
                forKey: Keys.disablesTapPageTurnsDuringQuickTranslation
            )
        }
    }

    @Published var automaticRetryEnabled: Bool {
        didSet { defaults.set(automaticRetryEnabled, forKey: Keys.automaticRetryEnabled) }
    }

    @Published var fallbackProvider: TranslationFallbackChoice {
        didSet { defaults.set(fallbackProvider.rawValue, forKey: Keys.fallbackProvider) }
    }

    /// Every AI provider that was set up, and the one in use.
    @Published private(set) var aiProviders: AIProviderSettings

    /// The providers whose API key is in the keychain.
    @Published private(set) var keyedProviderIDs: Set<String>

    @Published var backendEndpoint: String {
        didSet { defaults.set(backendEndpoint, forKey: Keys.backendEndpoint) }
    }

    @Published var backendModel: String {
        didSet { defaults.set(backendModel, forKey: Keys.backendModel) }
    }

    @Published var backendAccessToken: String {
        didSet { persistAccessToken() }
    }

    @Published private(set) var credentialErrorMessage: String?

    private let defaults: UserDefaults
    private let credentialStore: any TranslationCredentialStoring
    /// Keys read from the keychain, by provider entry.
    private var apiKeys: [String: String]

    init(
        defaults: UserDefaults = .standard,
        credentialStore: (any TranslationCredentialStoring)? = nil
    ) {
        let resolvedCredentialStore = credentialStore
            ?? KeychainTranslationCredentialStore()
        let restoredProviders = Self.restoredProviders(
            defaults: defaults,
            credentialStore: resolvedCredentialStore
        )
        var restoredBackendToken = ""
        var restoredCredentialError: String?

        do {
            restoredBackendToken = try resolvedCredentialStore.readSecret(
                account: CredentialAccounts.backendProxy
            ) ?? ""
        } catch {
            restoredCredentialError = "无法从钥匙串读取代理凭据。"
        }
        let restoredKeys = Self.readKeys(
            for: restoredProviders,
            credentialStore: resolvedCredentialStore
        )
        if restoredKeys.failed {
            restoredCredentialError = "无法从钥匙串读取 API Key。"
        }

        self.defaults = defaults
        self.credentialStore = resolvedCredentialStore

        provider = TranslationProviderChoice(
            rawValue: defaults.string(forKey: Keys.provider) ?? ""
        ) ?? .apple
        targetLanguage = LanguageCode(
            rawValue: defaults.string(forKey: Keys.targetLanguage) ?? ""
        ) ?? .simplifiedChinese
        sourceLanguageChoice = TranslationSourceLanguageChoice(
            rawValue: defaults.string(forKey: Keys.sourceLanguageChoice) ?? ""
        ) ?? .automatic
        translationPromptTemplate = defaults.string(
            forKey: Keys.translationPromptTemplate
        ) ?? AIPromptTemplateDefaults.translation
        grammarAnalysisPromptTemplate = AIPromptTemplateDefaults
            .resolvedGrammarAnalysisTemplate(
                storedTemplate: defaults.string(
                    forKey: Keys.grammarAnalysisPromptTemplate
                )
            )
        displayMode = TranslationDisplayMode(
            rawValue: defaults.string(forKey: Keys.displayMode) ?? ""
        ) ?? .nearSelection
        if defaults.object(forKey: Keys.translationHapticsEnabled) == nil {
            translationHapticsEnabled = true
        } else {
            translationHapticsEnabled = defaults.bool(
                forKey: Keys.translationHapticsEnabled
            )
        }
        if defaults.object(forKey: Keys.quickSentenceTranslationEnabled) == nil {
            quickSentenceTranslationEnabled = true
        } else {
            quickSentenceTranslationEnabled = defaults.bool(
                forKey: Keys.quickSentenceTranslationEnabled
            )
        }
        quickTranslationUnit = ReaderQuickTranslationUnit(
            rawValue: defaults.string(forKey: Keys.quickTranslationUnit) ?? ""
        ) ?? .sentence
        if defaults.object(
            forKey: Keys.disablesTapPageTurnsDuringQuickTranslation
        ) == nil {
            disablesTapPageTurnsDuringQuickTranslation = true
        } else {
            disablesTapPageTurnsDuringQuickTranslation = defaults.bool(
                forKey: Keys.disablesTapPageTurnsDuringQuickTranslation
            )
        }
        if defaults.object(forKey: Keys.automaticRetryEnabled) == nil {
            automaticRetryEnabled = true
        } else {
            automaticRetryEnabled = defaults.bool(forKey: Keys.automaticRetryEnabled)
        }
        fallbackProvider = TranslationFallbackChoice(
            rawValue: defaults.string(forKey: Keys.fallbackProvider) ?? ""
        ) ?? .none
        aiProviders = restoredProviders
        apiKeys = restoredKeys.keys
        keyedProviderIDs = Set(restoredKeys.keys.filter { !$0.value.isEmpty }.keys)
        backendEndpoint = defaults.string(forKey: Keys.backendEndpoint) ?? ""
        backendModel = defaults.string(forKey: Keys.backendModel) ?? ""
        backendAccessToken = restoredBackendToken
        credentialErrorMessage = restoredCredentialError
        persistProviders()
    }

    // MARK: AI providers

    /// The provider in use. Setting it puts that preset in use, adding it
    /// first when it was never set up.
    var directAPIProvider: DirectAIProviderChoice {
        get { aiProviders.active.kind }
        set {
            changeProviders { providers in
                let id = newValue.rawValue
                if providers.entry(id) == nil {
                    providers.entries.append(.preset(newValue))
                }
                providers.activate(id)
            }
        }
    }

    /// The API key of the provider in use. Setting it saves it to the keychain.
    var directAPIKey: String {
        get { apiKey(for: aiProviders.activeID) }
        set { try? saveKey(newValue, for: aiProviders.activeID) }
    }

    /// The model in use. Setting it adds the model to its provider when needed.
    var directAPIModel: String {
        get { aiProviders.active.model }
        set {
            let model = newValue.trimmingCharacters(in: .whitespacesAndNewlines)
            changeProviders { providers in
                if model.isEmpty {
                    providers.update(providers.activeID) { $0.model = "" }
                } else {
                    providers.activate(providers.activeID, model: model)
                }
            }
        }
    }

    var directAPIConfiguration: DirectAITranslationConfiguration? {
        let entry = aiProviders.active
        return try? configuration(entry: entry, model: entry.activeModel, key: apiKey(for: entry.id))
    }

    /// One line on the state of the provider in use.
    var directAPIConfigurationMessage: String {
        let entry = aiProviders.active
        do {
            _ = try configuration(entry: entry, model: entry.activeModel, key: apiKey(for: entry.id))
        } catch {
            return (error as? LocalizedError)?.errorDescription ?? "配置无效，请检查 API 地址、模型和密钥。"
        }
        if credentialErrorMessage != nil {
            return "配置可用，但 API Key 未能安全保存。"
        }
        return "\(entry.displayName) · \(entry.model)"
    }

    func apiKey(for id: String) -> String {
        apiKeys[id] ?? ""
    }

    /// A request configuration for one model of a provider, which need not be
    /// saved yet. Throws the reason it cannot be used.
    func configuration(
        entry: AIProviderEntry,
        model: AIModelEntry,
        key: String
    ) throws -> DirectAITranslationConfiguration {
        let modelID = model.id.trimmingCharacters(in: .whitespacesAndNewlines)
        let apiKey = key.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !apiKey.isEmpty else { throw AIProviderSettingsError.missingKey(entry.displayName) }
        guard !modelID.isEmpty else { throw AIProviderSettingsError.missingModel }
        guard let endpoint = AIEndpoint.url(base: entry.baseURL, style: model.style) else {
            throw AIProviderSettingsError.invalidAddress
        }
        return DirectAITranslationConfiguration(
            provider: entry.kind,
            endpoint: endpoint,
            model: modelID,
            apiKey: apiKey,
            style: model.style,
            reasoning: model.reasoning,
            translationPromptTemplate: translationPromptTemplate,
            grammarAnalysisPromptTemplate: grammarAnalysisPromptTemplate
        )
    }

    /// Applies a change to the providers and saves it.
    func changeProviders(_ change: (inout AIProviderSettings) throws -> Void) rethrows {
        var value = aiProviders
        try change(&value)
        guard value != aiProviders else { return }
        aiProviders = value
        persistProviders()
    }

    /// Saves a provider's key to the keychain; an empty key removes it.
    func saveKey(_ key: String, for id: String) throws {
        let trimmed = key.trimmingCharacters(in: .whitespacesAndNewlines)
        do {
            try credentialStore.saveSecret(
                trimmed.isEmpty ? nil : trimmed,
                account: CredentialAccounts.directAPI(id: id)
            )
            credentialErrorMessage = nil
        } catch {
            credentialErrorMessage = "无法将 API Key 安全保存到钥匙串。"
            throw AIProviderSettingsError.keychainUnavailable
        }
        apiKeys[id] = trimmed
        if trimmed.isEmpty {
            keyedProviderIDs.remove(id)
        } else {
            keyedProviderIDs.insert(id)
        }
    }

    /// Adds a provider with its key.
    /// - Returns: whether it is now in use, which it is only when the provider
    ///   in use had no key.
    @discardableResult
    func addProvider(_ entry: AIProviderEntry, key: String) throws -> Bool {
        guard AIEndpoint.isValid(base: entry.baseURL) else { throw AIProviderSettingsError.invalidAddress }
        let activeHasKey = keyedProviderIDs.contains(aiProviders.activeID)
        try saveKey(key, for: entry.id)
        var activated = false
        changeProviders { activated = $0.add(entry, activeHasKey: activeHasKey) }
        return activated
    }

    /// Saves a provider's name, address and key together.
    func saveProvider(_ id: String, name: String?, baseURL: String, key: String) throws {
        guard aiProviders.entry(id) != nil else { throw AIProviderSettingsError.unknownProvider }
        let address = baseURL.trimmingCharacters(in: .whitespacesAndNewlines)
        guard AIEndpoint.isValid(base: address) else { throw AIProviderSettingsError.invalidAddress }
        if key.trimmingCharacters(in: .whitespacesAndNewlines) != apiKey(for: id) {
            try saveKey(key, for: id)
        }
        changeProviders { providers in
            providers.update(id) { entry in
                entry.baseURL = address
                if entry.kind.isCustom {
                    let label = name?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                    entry.name = label.isEmpty ? nil : label
                }
            }
        }
    }

    /// Removes a provider and its key. The provider in use cannot be removed.
    func removeProvider(_ id: String) throws {
        guard id != aiProviders.activeID else { throw AIProviderSettingsError.removingActiveProvider }
        try saveKey("", for: id)
        try changeProviders { try $0.remove(id) }
    }

    var backendConfiguration: BackendTranslationConfiguration? {
        let endpointText = backendEndpoint.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let endpoint = URL(string: endpointText),
              endpoint.scheme?.lowercased() == "https",
              endpoint.host?.isEmpty == false
        else {
            return nil
        }

        let model = backendModel.trimmingCharacters(in: .whitespacesAndNewlines)
        let token = backendAccessToken.trimmingCharacters(in: .whitespacesAndNewlines)
        return BackendTranslationConfiguration(
            endpoint: endpoint,
            model: model.isEmpty ? nil : model,
            accessToken: token.isEmpty ? nil : token,
            translationPromptTemplate: translationPromptTemplate,
            grammarAnalysisPromptTemplate: grammarAnalysisPromptTemplate
        )
    }

    func resetTranslationPrompt() {
        translationPromptTemplate = AIPromptTemplateDefaults.translation
    }

    func resetGrammarAnalysisPrompt() {
        grammarAnalysisPromptTemplate =
            AIPromptTemplateDefaults.grammarAnalysis
    }

    var backendConfigurationMessage: String {
        if backendEndpoint.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return "请填写你自己控制的 HTTPS 翻译代理地址。"
        }
        guard backendConfiguration != nil else {
            return "代理地址必须是带有完整主机名的 HTTPS URL。"
        }
        if credentialErrorMessage != nil {
            return "代理地址有效，但访问凭据未能安全保存。"
        }
        return "配置已就绪；只会发送你主动选中的文字。"
    }

    /// Non-sensitive preferences which can safely be written to a user-owned
    /// backup. API keys and the proxy access token are intentionally absent
    /// because those values only live in Keychain.
    nonisolated static var backupDefaultKeys: [String] {
        [
            Keys.provider,
            Keys.targetLanguage,
            Keys.sourceLanguageChoice,
            Keys.translationPromptTemplate,
            Keys.grammarAnalysisPromptTemplate,
            Keys.displayMode,
            Keys.translationHapticsEnabled,
            Keys.quickSentenceTranslationEnabled,
            Keys.quickTranslationUnit,
            Keys.disablesTapPageTurnsDuringQuickTranslation,
            Keys.automaticRetryEnabled,
            Keys.fallbackProvider,
            Keys.directAPIProvider,
            Keys.aiProviders,
            Keys.backendEndpoint,
            Keys.backendModel,
        ] + DirectAIProviderChoice.allCases.flatMap {
            [
                Keys.directAPIModel(provider: $0),
                Keys.directAPIEndpoint(provider: $0),
            ]
        }
    }

    /// Applies preferences restored after this observable store was created.
    /// Without this refresh, the backup would be present in UserDefaults but
    /// the running settings and reader screens would keep their stale values
    /// until the next process launch.
    func reloadNonSensitivePreferences() {
        provider = TranslationProviderChoice(
            rawValue: defaults.string(forKey: Keys.provider) ?? ""
        ) ?? .apple
        targetLanguage = LanguageCode(
            rawValue: defaults.string(forKey: Keys.targetLanguage) ?? ""
        ) ?? .simplifiedChinese
        sourceLanguageChoice = TranslationSourceLanguageChoice(
            rawValue: defaults.string(forKey: Keys.sourceLanguageChoice) ?? ""
        ) ?? .automatic
        translationPromptTemplate = defaults.string(
            forKey: Keys.translationPromptTemplate
        ) ?? AIPromptTemplateDefaults.translation
        grammarAnalysisPromptTemplate = AIPromptTemplateDefaults
            .resolvedGrammarAnalysisTemplate(
                storedTemplate: defaults.string(
                    forKey: Keys.grammarAnalysisPromptTemplate
                )
            )
        displayMode = TranslationDisplayMode(
            rawValue: defaults.string(forKey: Keys.displayMode) ?? ""
        ) ?? .nearSelection
        translationHapticsEnabled = defaults.object(
            forKey: Keys.translationHapticsEnabled
        ) == nil || defaults.bool(forKey: Keys.translationHapticsEnabled)
        quickSentenceTranslationEnabled = defaults.object(
            forKey: Keys.quickSentenceTranslationEnabled
        ) == nil || defaults.bool(forKey: Keys.quickSentenceTranslationEnabled)
        quickTranslationUnit = ReaderQuickTranslationUnit(
            rawValue: defaults.string(forKey: Keys.quickTranslationUnit) ?? ""
        ) ?? .sentence
        disablesTapPageTurnsDuringQuickTranslation = defaults.object(
            forKey: Keys.disablesTapPageTurnsDuringQuickTranslation
        ) == nil || defaults.bool(
            forKey: Keys.disablesTapPageTurnsDuringQuickTranslation
        )
        automaticRetryEnabled = defaults.object(
            forKey: Keys.automaticRetryEnabled
        ) == nil || defaults.bool(forKey: Keys.automaticRetryEnabled)
        fallbackProvider = TranslationFallbackChoice(
            rawValue: defaults.string(forKey: Keys.fallbackProvider) ?? ""
        ) ?? .none
        aiProviders = Self.restoredProviders(
            defaults: defaults,
            credentialStore: credentialStore
        )
        let keys = Self.readKeys(for: aiProviders, credentialStore: credentialStore)
        apiKeys = keys.keys
        keyedProviderIDs = Set(keys.keys.filter { !$0.value.isEmpty }.keys)
        if keys.failed {
            credentialErrorMessage = "无法从钥匙串读取 API Key。"
        }
        persistProviders()
        backendEndpoint = defaults.string(forKey: Keys.backendEndpoint) ?? ""
        backendModel = defaults.string(forKey: Keys.backendModel) ?? ""
    }

    private func persistAccessToken() {
        do {
            let token = backendAccessToken.trimmingCharacters(in: .whitespacesAndNewlines)
            try credentialStore.saveSecret(
                token.isEmpty ? nil : token,
                account: CredentialAccounts.backendProxy
            )
            credentialErrorMessage = nil
        } catch {
            credentialErrorMessage = "无法将代理凭据安全保存到钥匙串。"
        }
    }

    private func persistProviders() {
        guard let data = try? Self.providerEncoder.encode(aiProviders),
              let text = String(data: data, encoding: .utf8)
        else { return }
        defaults.set(text, forKey: Keys.aiProviders)
    }

    private static let providerEncoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        return encoder
    }()

    /// The saved providers, or, when none were saved yet, the ones built from
    /// the settings kept before providers had a list of models.
    private static func restoredProviders(
        defaults: UserDefaults,
        credentialStore: any TranslationCredentialStoring
    ) -> AIProviderSettings {
        if let text = defaults.string(forKey: Keys.aiProviders),
           let decoded = try? JSONDecoder().decode(AIProviderSettings.self, from: Data(text.utf8)),
           !decoded.entries.isEmpty {
            return decoded
        }
        var keyed: Set<DirectAIProviderChoice> = []
        var models: [DirectAIProviderChoice: String] = [:]
        var endpoints: [DirectAIProviderChoice: String] = [:]
        for kind in DirectAIProviderChoice.legacyCases {
            let key = try? credentialStore.readSecret(account: CredentialAccounts.directAPI(id: kind.rawValue))
            if let key, !key.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { keyed.insert(kind) }
            models[kind] = defaults.string(forKey: Keys.directAPIModel(provider: kind))
            endpoints[kind] = defaults.string(forKey: Keys.directAPIEndpoint(provider: kind))
        }
        return AIProviderSettings.migrated(
            selected: DirectAIProviderChoice(rawValue: defaults.string(forKey: Keys.directAPIProvider) ?? ""),
            keyed: keyed,
            storedModels: models,
            storedEndpoints: endpoints
        )
    }

    private static func readKeys(
        for providers: AIProviderSettings,
        credentialStore: any TranslationCredentialStoring
    ) -> (keys: [String: String], failed: Bool) {
        var keys: [String: String] = [:]
        var failed = false
        for entry in providers.entries {
            do {
                keys[entry.id] = try credentialStore.readSecret(
                    account: CredentialAccounts.directAPI(id: entry.id)
                )?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            } catch {
                failed = true
            }
        }
        return (keys, failed)
    }

    private enum Keys {
        static let provider = "translation.provider"
        static let targetLanguage = "translation.targetLanguage"
        static let sourceLanguageChoice = "translation.sourceLanguageChoice"
        static let translationPromptTemplate =
            "translation.prompt.translation"
        static let grammarAnalysisPromptTemplate =
            "translation.prompt.grammarAnalysis"
        static let displayMode = "translation.displayMode"
        static let translationHapticsEnabled = "translation.hapticsEnabled"
        static let quickSentenceTranslationEnabled = "translation.quickSentenceEnabled"
        static let quickTranslationUnit = "translation.quickTranslationUnit"
        static let disablesTapPageTurnsDuringQuickTranslation =
            "translation.quickSentenceDisablesTapPageTurns"
        static let automaticRetryEnabled = "translation.automaticRetryEnabled"
        static let fallbackProvider = "translation.fallbackProvider"
        static let directAPIProvider = "translation.directAPI.provider"
        static let aiProviders = "translation.aiProviders.v1"
        static let backendEndpoint = "translation.backendEndpoint"
        static let backendModel = "translation.backendModel"

        static func directAPIModel(provider: DirectAIProviderChoice) -> String {
            "translation.directAPI.model.\(provider.rawValue)"
        }

        static func directAPIEndpoint(provider: DirectAIProviderChoice) -> String {
            "translation.directAPI.endpoint.\(provider.rawValue)"
        }
    }

    private enum CredentialAccounts {
        static let backendProxy = "proxy-access-token"

        /// The keychain account of a provider entry. A preset's entry id is
        /// its raw value, so keys saved before providers had a list still match.
        static func directAPI(id: String) -> String {
            "direct-api-\(id)"
        }
    }
}

@MainActor
private final class KeychainTranslationCredentialStore: TranslationCredentialStoring {
    private let service = "Jerreader.translation-backend"

    func readSecret(account: String) throws -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound {
            return nil
        }
        guard status == errSecSuccess,
              let data = result as? Data,
              let token = String(data: data, encoding: .utf8)
        else {
            throw KeychainError(status: status)
        }
        return token
    }

    func saveSecret(_ secret: String?, account: String) throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        let deleteStatus = SecItemDelete(query as CFDictionary)
        guard deleteStatus == errSecSuccess || deleteStatus == errSecItemNotFound else {
            throw KeychainError(status: deleteStatus)
        }
        guard let secret else { return }

        var item = query
        item[kSecValueData as String] = Data(secret.utf8)
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(item as CFDictionary, nil)
        guard status == errSecSuccess else {
            throw KeychainError(status: status)
        }
    }
}

private struct KeychainError: Error {
    let status: OSStatus
}
