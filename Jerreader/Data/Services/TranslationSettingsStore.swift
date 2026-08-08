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

    @Published var directAPIProvider: DirectAIProviderChoice {
        didSet {
            defaults.set(directAPIProvider.rawValue, forKey: Keys.directAPIProvider)
            loadDirectAPISettings()
        }
    }

    @Published var directAPIKey: String {
        didSet {
            guard !isLoadingDirectAPISettings else { return }
            persistDirectAPIKey()
        }
    }

    @Published var directAPIModel: String {
        didSet {
            guard !isLoadingDirectAPISettings else { return }
            defaults.set(
                directAPIModel,
                forKey: Keys.directAPIModel(provider: directAPIProvider)
            )
        }
    }

    @Published var directAPIEndpoint: String {
        didSet {
            guard !isLoadingDirectAPISettings else { return }
            defaults.set(
                directAPIEndpoint,
                forKey: Keys.directAPIEndpoint(provider: directAPIProvider)
            )
        }
    }

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
    private var isLoadingDirectAPISettings = false

    init(
        defaults: UserDefaults = .standard,
        credentialStore: (any TranslationCredentialStoring)? = nil
    ) {
        let resolvedCredentialStore = credentialStore
            ?? KeychainTranslationCredentialStore()
        let selectedDirectProvider = DirectAIProviderChoice(
            rawValue: defaults.string(forKey: Keys.directAPIProvider) ?? ""
        ) ?? .openAI
        var restoredBackendToken = ""
        var restoredDirectAPIKey = ""
        var restoredCredentialError: String?

        do {
            restoredBackendToken = try resolvedCredentialStore.readSecret(
                account: CredentialAccounts.backendProxy
            ) ?? ""
        } catch {
            restoredCredentialError = "无法从钥匙串读取代理凭据。"
        }
        do {
            restoredDirectAPIKey = try resolvedCredentialStore.readSecret(
                account: CredentialAccounts.directAPI(provider: selectedDirectProvider)
            ) ?? ""
        } catch {
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
        directAPIProvider = selectedDirectProvider
        directAPIKey = restoredDirectAPIKey
        directAPIModel = defaults.string(
            forKey: Keys.directAPIModel(provider: selectedDirectProvider)
        ) ?? selectedDirectProvider.defaultModel
        directAPIEndpoint = defaults.string(
            forKey: Keys.directAPIEndpoint(provider: selectedDirectProvider)
        ) ?? selectedDirectProvider.defaultEndpointText
        backendEndpoint = defaults.string(forKey: Keys.backendEndpoint) ?? ""
        backendModel = defaults.string(forKey: Keys.backendModel) ?? ""
        backendAccessToken = restoredBackendToken
        credentialErrorMessage = restoredCredentialError
    }

    var directAPIConfiguration: DirectAITranslationConfiguration? {
        let endpointText = (
            directAPIProvider.usesCustomEndpoint
                ? directAPIEndpoint
                : directAPIProvider.defaultEndpointText
        ).trimmingCharacters(in: .whitespacesAndNewlines)
        let model = directAPIModel.trimmingCharacters(in: .whitespacesAndNewlines)
        let key = directAPIKey.trimmingCharacters(in: .whitespacesAndNewlines)

        guard let endpoint = URL(string: endpointText),
              endpoint.scheme?.lowercased() == "https",
              endpoint.host?.isEmpty == false,
              !model.isEmpty,
              !key.isEmpty
        else {
            return nil
        }

        return DirectAITranslationConfiguration(
            provider: directAPIProvider,
            endpoint: endpoint,
            model: model,
            apiKey: key,
            translationPromptTemplate: translationPromptTemplate,
            grammarAnalysisPromptTemplate: grammarAnalysisPromptTemplate
        )
    }

    var directAPIConfigurationMessage: String {
        if directAPIKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return "粘贴 \(directAPIProvider.title) API Key 后即可使用。"
        }
        if directAPIModel.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return "请填写模型名称。"
        }
        if directAPIProvider.usesCustomEndpoint,
           directAPIEndpoint.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        {
            return "请填写兼容服务的 HTTPS 请求地址。"
        }
        guard directAPIConfiguration != nil else {
            return "配置无效，请检查 HTTPS 地址、模型和 API Key。"
        }
        if credentialErrorMessage != nil {
            return "配置可用，但 API Key 未能安全保存。"
        }
        return "配置已就绪；API Key 只保存在本机钥匙串中。"
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
        directAPIProvider = DirectAIProviderChoice(
            rawValue: defaults.string(forKey: Keys.directAPIProvider) ?? ""
        ) ?? .openAI
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

    private func loadDirectAPISettings() {
        isLoadingDirectAPISettings = true
        defer { isLoadingDirectAPISettings = false }

        directAPIModel = defaults.string(
            forKey: Keys.directAPIModel(provider: directAPIProvider)
        ) ?? directAPIProvider.defaultModel
        directAPIEndpoint = defaults.string(
            forKey: Keys.directAPIEndpoint(provider: directAPIProvider)
        ) ?? directAPIProvider.defaultEndpointText
        do {
            directAPIKey = try credentialStore.readSecret(
                account: CredentialAccounts.directAPI(provider: directAPIProvider)
            ) ?? ""
            credentialErrorMessage = nil
        } catch {
            directAPIKey = ""
            credentialErrorMessage = "无法从钥匙串读取 API Key。"
        }
    }

    private func persistDirectAPIKey() {
        do {
            let key = directAPIKey.trimmingCharacters(in: .whitespacesAndNewlines)
            try credentialStore.saveSecret(
                key.isEmpty ? nil : key,
                account: CredentialAccounts.directAPI(provider: directAPIProvider)
            )
            credentialErrorMessage = nil
        } catch {
            credentialErrorMessage = "无法将 API Key 安全保存到钥匙串。"
        }
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

        static func directAPI(provider: DirectAIProviderChoice) -> String {
            "direct-api-\(provider.rawValue)"
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
