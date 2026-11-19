import CryptoKit
import Foundation

protocol BackendTranslationHTTPClient: Sendable {
    func data(for request: URLRequest) async throws -> (Data, HTTPURLResponse)
}

struct URLSessionBackendTranslationHTTPClient: BackendTranslationHTTPClient, ChatGPTPlanHTTPClient {
    private let session: URLSession

    init(session: URLSession = .shared) {
        self.session = session
    }

    func data(for request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let (data, response) = try await session.data(for: request)
        guard let response = response as? HTTPURLResponse else {
            throw ServiceError.temporarilyUnavailable
        }
        return (data, response)
    }
}

struct BackendTranslationService: TranslationService, ContextExplanationService {
    static let providerIdentifier = "ai-backend-proxy"
    static let explanationRequestTimeout: TimeInterval = 60

    let configuration: BackendTranslationConfiguration
    private let client: any BackendTranslationHTTPClient

    init(
        configuration: BackendTranslationConfiguration,
        client: any BackendTranslationHTTPClient = URLSessionBackendTranslationHTTPClient()
    ) {
        self.configuration = configuration
        self.client = client
    }

    var providerVersion: String {
        Self.providerVersion(for: configuration)
    }

    static func providerVersion(for configuration: BackendTranslationConfiguration) -> String {
        let value = [
            configuration.endpoint.absoluteString,
            configuration.model ?? "default",
            configuration.translationPromptTemplate,
            configuration.grammarAnalysisPromptTemplate,
            "jerreader-proxy-v1",
        ].joined(separator: "\u{001F}")
        let digest = SHA256.hash(data: Data(value.utf8))
        return digest.prefix(12).map { String(format: "%02x", $0) }.joined()
    }

    func translate(
        text: String,
        sourceLanguage: LanguageCode?,
        targetLanguage: LanguageCode
    ) async throws -> TranslationResult {
        try await translate(
            text: text,
            context: nil,
            sourceLanguage: sourceLanguage,
            targetLanguage: targetLanguage
        )
    }

    func translate(
        text: String,
        context: String?,
        sourceLanguage: LanguageCode?,
        targetLanguage: LanguageCode
    ) async throws -> TranslationResult {
        let sourceText = TranslationCacheStore.normalizedText(text)
        let normalizedContext = context.map(TranslationCacheStore.normalizedText)
            .flatMap { value in
                value.isEmpty || value == sourceText ? nil : value
            }
        guard !sourceText.isEmpty else { throw ServiceError.emptyText }
        guard sourceText.count <= 2_000 else { throw ServiceError.textTooLong }
        guard let sourceLanguage,
              LanguageCode.allCases.contains(sourceLanguage),
              LanguageCode.allCases.contains(targetLanguage),
              sourceLanguage != targetLanguage
        else {
            throw ServiceError.unsupportedLanguage
        }
        guard configuration.endpoint.scheme?.lowercased() == "https",
              configuration.endpoint.host?.isEmpty == false
        else {
            throw ServiceError.invalidConfiguration
        }

        let body = RequestBody(
            text: sourceText,
            context: normalizedContext,
            sourceLanguage: sourceLanguage.rawValue,
            targetLanguage: targetLanguage.rawValue,
            model: configuration.model,
            prompt: AIPromptTemplateDefaults.rendered(
                configuration.translationPromptTemplate,
                sourceLanguage: sourceLanguage,
                targetLanguage: targetLanguage
            )
        )
        var request = URLRequest(url: configuration.endpoint)
        request.httpMethod = "POST"
        request.timeoutInterval = 30
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("1", forHTTPHeaderField: "X-Jerreader-Translation-Protocol")
        request.setValue(
            TranslationCacheStore.cacheKey(
                text: sourceText,
                contextText: normalizedContext,
                sourceLanguage: sourceLanguage,
                targetLanguage: targetLanguage,
                providerIdentifier: Self.providerIdentifier,
                providerVersion: providerVersion
            ),
            forHTTPHeaderField: "X-Jerreader-Idempotency-Key"
        )
        if let token = configuration.accessToken, !token.isEmpty {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        request.httpBody = try JSONEncoder().encode(body)

        let data: Data
        let response: HTTPURLResponse
        do {
            (data, response) = try await client.data(for: request)
        } catch is CancellationError {
            throw CancellationError()
        } catch let error as ServiceError {
            throw error
        } catch {
            throw ServiceError.temporarilyUnavailable
        }

        switch response.statusCode {
        case 200 ..< 300:
            break
        case 401, 403:
            throw ServiceError.authenticationFailed
        case 400, 404, 405, 415, 422:
            throw ServiceError.invalidConfiguration
        default:
            throw ServiceError.temporarilyUnavailable
        }

        let payload: ResponseBody
        do {
            payload = try JSONDecoder().decode(ResponseBody.self, from: data)
        } catch {
            throw ServiceError.resultNotFound
        }
        guard let translatedText = TranslationOutputPolicy.displayText(
            payload.translatedText ?? payload.translation ?? ""
        ) else {
            throw ServiceError.resultNotFound
        }

        return TranslationResult(
            sourceText: sourceText,
            translatedText: translatedText,
            sourceLanguage: sourceLanguage,
            targetLanguage: targetLanguage,
            providerIdentifier: Self.providerIdentifier,
            providerVersion: providerVersion,
            isFromCache: false
        )
    }

    func explain(
        _ explanationRequest: ContextExplanationRequest
    ) async throws -> ContextExplanationResult {
        let explanationRequest = ContextExplanationInputPolicy.prepared(
            explanationRequest
        )
        let focusedText = explanationRequest.focusedText
        let context = [explanationRequest.contextText,
            explanationRequest.isWordLookup ? explanationRequest.dictionaryEntry.map {
                "词典候选资料（仅供参考，若不适合原句请指出）：\n" + $0
            } : nil].compactMap { $0 }.joined(separator: "\n\n").nilIfBlankForLookup
        guard !focusedText.isEmpty else { throw ServiceError.emptyText }
        guard focusedText.count <= 2_000 else { throw ServiceError.textTooLong }
        guard configuration.endpoint.scheme?.lowercased() == "https",
              configuration.endpoint.host?.isEmpty == false
        else {
            throw ServiceError.invalidConfiguration
        }

        var request = URLRequest(url: configuration.endpoint)
        request.httpMethod = "POST"
        // Grammar analysis is intentionally allowed more time than a normal
        // translation. Reasoning-capable models commonly need longer than the
        // old 30–35 second window, which made an otherwise healthy request look
        // like a permanent failure in the explanation sheet.
        request.timeoutInterval = Self.explanationRequestTimeout
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("1", forHTTPHeaderField: "X-Jerreader-Translation-Protocol")
        if let token = configuration.accessToken, !token.isEmpty {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        request.httpBody = try JSONEncoder().encode(
            ExplanationRequestBody(
                task: "explain",
                analysisMode: explanationRequest.lens?.mode.rawValue ?? "grammar",
                text: focusedText,
                context: context,
                sourceLanguage: explanationRequest.sourceLanguage?.rawValue,
                responseLanguage: explanationRequest.responseLanguage.rawValue,
                model: configuration.model,
                prompt: explanationRequest.systemInstruction(
                    grammarPrompt: AIPromptTemplateDefaults.rendered(
                        configuration.grammarAnalysisPromptTemplate,
                        sourceLanguage: explanationRequest.sourceLanguage,
                        responseLanguage: explanationRequest.responseLanguage,
                        fallback: AIPromptTemplateDefaults.grammarAnalysis
                    )
                )
            )
        )

        let data: Data
        let response: HTTPURLResponse
        do {
            (data, response) = try await client.data(for: request)
        } catch is CancellationError {
            throw CancellationError()
        } catch let error as ServiceError {
            throw error
        } catch {
            throw ServiceError.temporarilyUnavailable
        }

        switch response.statusCode {
        case 200 ..< 300:
            break
        case 401, 403:
            throw ServiceError.authenticationFailed
        case 400, 404, 405, 415, 422:
            throw ServiceError.invalidConfiguration
        default:
            throw ServiceError.temporarilyUnavailable
        }

        let payload: ExplanationResponseBody
        do {
            payload = try JSONDecoder().decode(ExplanationResponseBody.self, from: data)
        } catch {
            throw ServiceError.resultNotFound
        }
        guard let explanation = TranslationOutputPolicy.displayText(
            payload.explanation
                ?? payload.answer
                ?? payload.translatedText
                ?? payload.translation
                ?? ""
        ) else {
            throw ServiceError.resultNotFound
        }
        return ContextExplanationResult(
            explanation: explanation,
            providerIdentifier: Self.providerIdentifier,
            providerVersion: providerVersion
        )
    }

    private struct RequestBody: Encodable {
        let text: String
        let context: String?
        let sourceLanguage: String
        let targetLanguage: String
        let model: String?
        let prompt: String?
    }

    private struct ResponseBody: Decodable {
        let translatedText: String?
        let translation: String?
    }

    private struct ExplanationRequestBody: Encodable {
        let task: String
        let analysisMode: String
        let text: String
        let context: String?
        let sourceLanguage: String?
        let responseLanguage: String
        let model: String?
        let prompt: String?
    }

    private struct ExplanationResponseBody: Decodable {
        let explanation: String?
        let answer: String?
        let translatedText: String?
        let translation: String?
    }
}

struct DirectAITranslationService: TranslationService, ContextExplanationService {
    static let explanationRequestTimeout: TimeInterval = 60

    let configuration: DirectAITranslationConfiguration
    private let client: any BackendTranslationHTTPClient
    /// Hands out the access token of a ChatGPT plan; unused by other providers.
    private let planSession: ChatGPTPlanSession
    /// Called when OpenAI says the plan's use was never granted, so the next
    /// sign-in shows the consent page again.
    private let planConsentNeeded: @Sendable () -> Void

    init(
        configuration: DirectAITranslationConfiguration,
        client: any BackendTranslationHTTPClient = URLSessionBackendTranslationHTTPClient(),
        planSession: ChatGPTPlanSession = .shared,
        planConsentNeeded: @escaping @Sendable () -> Void = { ChatGPTPlanRegistration.setConsentNeeded(true) }
    ) {
        self.configuration = configuration
        self.client = client
        self.planSession = planSession
        self.planConsentNeeded = planConsentNeeded
    }

    var providerIdentifier: String {
        Self.providerIdentifier(for: configuration.provider)
    }

    var providerVersion: String {
        Self.providerVersion(for: configuration)
    }

    static func providerIdentifier(for provider: DirectAIProviderChoice) -> String {
        "direct-ai-\(provider.rawValue)"
    }

    static func providerVersion(for configuration: DirectAITranslationConfiguration) -> String {
        var parts = [
            configuration.provider.rawValue,
            configuration.endpoint.absoluteString,
            configuration.model,
            configuration.translationPromptTemplate,
            configuration.grammarAnalysisPromptTemplate,
            // v2: the context is tagged and the request says not to
            // translate it, so results cached from v1 prompts, which some
            // models answered with the whole paragraph, are not reused.
            "jerreader-direct-api-v2",
        ]
        // Added only when set, so a configuration in its provider's original
        // format keeps the cache identity it had before formats were chosen.
        if configuration.style != configuration.provider.legacyStyle {
            parts.append("style:" + configuration.style.rawValue)
        }
        if let reasoning = configuration.reasoning {
            parts.append("reasoning:" + reasoning.rawValue)
        }
        let value = parts.joined(separator: "\u{001F}")
        let digest = SHA256.hash(data: Data(value.utf8))
        return digest.prefix(12).map { String(format: "%02x", $0) }.joined()
    }

    func translate(
        text: String,
        sourceLanguage: LanguageCode?,
        targetLanguage: LanguageCode
    ) async throws -> TranslationResult {
        try await translate(
            text: text,
            context: nil,
            sourceLanguage: sourceLanguage,
            targetLanguage: targetLanguage
        )
    }

    func translate(
        text: String,
        context: String?,
        sourceLanguage: LanguageCode?,
        targetLanguage: LanguageCode
    ) async throws -> TranslationResult {
        let sourceText = TranslationCacheStore.normalizedText(text)
        let normalizedContext = context.map(TranslationCacheStore.normalizedText)
            .flatMap { value in
                value.isEmpty || value == sourceText ? nil : value
            }
        guard !sourceText.isEmpty else { throw ServiceError.emptyText }
        guard sourceText.count <= 2_000 else { throw ServiceError.textTooLong }
        guard let sourceLanguage,
              LanguageCode.allCases.contains(sourceLanguage),
              LanguageCode.allCases.contains(targetLanguage),
              sourceLanguage != targetLanguage
        else {
            throw ServiceError.unsupportedLanguage
        }
        guard configuration.endpoint.scheme?.lowercased() == "https",
              configuration.endpoint.host?.isEmpty == false,
              !configuration.model.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !configuration.apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        else {
            throw ServiceError.invalidConfiguration
        }

        let prompt = TranslationPrompt(
            sourceText: sourceText,
            context: normalizedContext,
            sourceLanguage: sourceLanguage,
            targetLanguage: targetLanguage,
            template: configuration.translationPromptTemplate
        )
        let text = try await completion(
            systemInstruction: prompt.systemInstruction,
            userContent: prompt.userContent
        )

        guard let translatedText = TranslationOutputPolicy.displayText(text) else {
            throw ServiceError.resultNotFound
        }

        return TranslationResult(
            sourceText: sourceText,
            translatedText: translatedText,
            sourceLanguage: sourceLanguage,
            targetLanguage: targetLanguage,
            providerIdentifier: providerIdentifier,
            providerVersion: providerVersion,
            isFromCache: false
        )
    }

    func explain(
        _ explanationRequest: ContextExplanationRequest
    ) async throws -> ContextExplanationResult {
        let explanationRequest = ContextExplanationInputPolicy.prepared(
            explanationRequest
        )
        let focusedText = explanationRequest.focusedText
        let context = [explanationRequest.contextText,
            explanationRequest.isWordLookup ? explanationRequest.dictionaryEntry.map {
                "词典候选资料（仅供参考，若不适合原句请指出）：\n" + $0
            } : nil].compactMap { $0 }.joined(separator: "\n\n").nilIfBlankForLookup
        guard !focusedText.isEmpty else { throw ServiceError.emptyText }
        guard focusedText.count <= 2_000 else { throw ServiceError.textTooLong }
        guard configuration.endpoint.scheme?.lowercased() == "https",
              configuration.endpoint.host?.isEmpty == false,
              !configuration.model.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !configuration.apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        else {
            throw ServiceError.invalidConfiguration
        }

        let systemInstruction = explanationRequest.systemInstruction(
            grammarPrompt: AIPromptTemplateDefaults.rendered(
                configuration.grammarAnalysisPromptTemplate,
                sourceLanguage: explanationRequest.sourceLanguage,
                responseLanguage: explanationRequest.responseLanguage,
                fallback: AIPromptTemplateDefaults.grammarAnalysis
            )
        )
        let userContent: String
        if let context {
            userContent = """
            SELECTED TEXT:
            \(focusedText)

            READING CONTEXT:
            \(context)
            """
        } else {
            userContent = "SELECTED TEXT:\n\(focusedText)"
        }
        let text = try await completion(
            systemInstruction: systemInstruction,
            userContent: userContent,
            timeoutInterval: Self.explanationRequestTimeout,
            quick: false
        )

        guard let explanation = TranslationOutputPolicy.displayText(text) else {
            throw ServiceError.resultNotFound
        }
        return ContextExplanationResult(
            explanation: explanation,
            providerIdentifier: providerIdentifier,
            providerVersion: providerVersion
        )
    }

    /// Sends one request and returns the model's text.
    /// - Parameter quick: a translation, as opposed to an explanation; 自动
    ///   reasoning is lighter for it.
    private func completion(
        systemInstruction: String,
        userContent: String,
        timeoutInterval: TimeInterval = 30,
        quick: Bool = true
    ) async throws -> String {
        if configuration.provider.usesAccountSignIn {
            return try await planCompletion(
                systemInstruction: systemInstruction, userContent: userContent,
                timeoutInterval: timeoutInterval, quick: quick
            )
        }
        let request = try makeRequest(
            systemInstruction: systemInstruction,
            userContent: userContent,
            timeoutInterval: timeoutInterval,
            quick: quick
        )
        let (data, response) = try await send(request)
        switch response.statusCode {
        case 200 ..< 300:
            break
        case 401, 403:
            throw ServiceError.authenticationFailed
        case 400, 404, 405, 415, 422:
            throw ServiceError.invalidConfiguration
        default:
            throw ServiceError.temporarilyUnavailable
        }
        return try decodedText(from: data)
    }

    /// A request paid by a ChatGPT plan: a current access token, one retry
    /// with a fresh token when it is refused, and success only on
    /// `response.completed`, so a cut-off answer is never shown or cached.
    private func planCompletion(
        systemInstruction: String,
        userContent: String,
        timeoutInterval: TimeInterval,
        quick: Bool
    ) async throws -> String {
        let extra = AIRequestTuning.extraFields(
            kind: configuration.provider,
            style: .responses,
            model: configuration.model,
            reasoning: configuration.reasoning,
            quick: quick
        )
        func request(_ token: String) throws -> URLRequest {
            try ChatGPTPlanRequests.responses(
                accessToken: token, model: configuration.model, instructions: systemInstruction,
                input: userContent, extra: extra, timeoutInterval: timeoutInterval
            )
        }
        var token = try await planAccessToken(rejected: nil)
        var (data, response) = try await send(try request(token))
        if response.statusCode == 401 {
            token = try await planAccessToken(rejected: token)
            (data, response) = try await send(try request(token))
        }
        // OpenAI's own reason is kept: the status, the code and the request id.
        guard (200 ..< 300).contains(response.statusCode) else {
            if ChatGPTPlanErrorBody(data).needsConsent { planConsentNeeded() }
            throw Self.serviceError(ChatGPTPlanRefusal.classify(
                status: response.statusCode, body: data,
                requestID: response.value(forHTTPHeaderField: "x-request-id")
            ))
        }
        switch ChatGPTPlanStream.parse(data) {
        case .completed(let text):
            let answer = text.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !answer.isEmpty else { throw ServiceError.resultNotFound }
            return answer
        case .failed(let code, let message):
            throw Self.serviceError(ChatGPTPlanRefusal.streamFailure(code: code, message: message))
        case .incomplete:
            throw ServiceError.resultNotFound
        case .interrupted:
            throw ServiceError.temporarilyUnavailable
        }
    }

    private static func serviceError(_ refusal: ChatGPTPlanRefusal) -> ServiceError {
        switch refusal {
        case .signInRequired: return .planSignInRequired
        case .usageLimit: return .planUsageLimitReached
        case .temporary: return .temporarilyUnavailable
        case .rejected(let message): return .planRequestRejected(message)
        }
    }

    private func planAccessToken(rejected: String?) async throws -> String {
        do {
            return try await planSession.accessToken(rejected: rejected)
        } catch is CancellationError {
            throw CancellationError()
        } catch ChatGPTPlanError.signInRequired {
            throw ServiceError.planSignInRequired
        } catch {
            throw ServiceError.temporarilyUnavailable
        }
    }

    private func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        do {
            return try await client.data(for: request)
        } catch is CancellationError {
            throw CancellationError()
        } catch let error as ServiceError {
            throw error
        } catch {
            throw ServiceError.temporarilyUnavailable
        }
    }

    /// - Parameter quick: a translation, as opposed to an explanation; 自动
    ///   reasoning is lighter for it.
    private func makeRequest(
        systemInstruction: String,
        userContent: String,
        timeoutInterval: TimeInterval = 30,
        quick: Bool = true
    ) throws -> URLRequest {
        var request = URLRequest(url: requestURL)
        request.httpMethod = "POST"
        request.timeoutInterval = timeoutInterval
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        switch configuration.style {
        case .responses:
            request.setValue(
                "Bearer \(configuration.apiKey)",
                forHTTPHeaderField: "Authorization"
            )
            request.httpBody = try JSONEncoder().encode(
                OpenAIResponsesRequest(
                    model: configuration.model,
                    instructions: systemInstruction,
                    input: userContent
                )
            )

        case .chatCompletions:
            request.setValue(
                "Bearer \(configuration.apiKey)",
                forHTTPHeaderField: "Authorization"
            )
            request.httpBody = try JSONEncoder().encode(
                ChatCompletionsRequest(
                    model: configuration.model,
                    messages: [
                        .init(role: "system", content: systemInstruction),
                        .init(role: "user", content: userContent),
                    ],
                    stream: false
                )
            )

        case .geminiNative:
            request.setValue(configuration.apiKey, forHTTPHeaderField: "x-goog-api-key")
            request.httpBody = try JSONEncoder().encode(
                GeminiRequest(
                    systemInstruction: .init(
                        parts: [.init(text: systemInstruction)]
                    ),
                    contents: [
                        .init(role: "user", parts: [.init(text: userContent)])
                    ]
                )
            )

        case .anthropicMessages:
            request.setValue(configuration.apiKey, forHTTPHeaderField: "x-api-key")
            request.setValue("2023-06-01", forHTTPHeaderField: "anthropic-version")
            request.httpBody = try JSONEncoder().encode(
                AnthropicRequest(
                    model: configuration.model,
                    maxTokens: AIRequestTuning.anthropicOutputTokens,
                    system: systemInstruction,
                    messages: [
                        .init(role: "user", content: userContent)
                    ]
                )
            )
        }

        // Reasoning and output fields of the chosen model; none for most
        // models on 自动, which leaves the body as it was.
        let extra = AIRequestTuning.extraFields(
            kind: configuration.provider,
            style: configuration.style,
            model: configuration.model,
            reasoning: configuration.reasoning,
            quick: quick
        )
        if !extra.isEmpty, let body = request.httpBody,
           var object = try JSONSerialization.jsonObject(with: body) as? [String: Any] {
            object.merge(extra) { _, added in added }
            request.httpBody = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
        }
        return request
    }

    private var requestURL: URL {
        guard configuration.style == .geminiNative else {
            return configuration.endpoint
        }
        return configuration.endpoint
            .appendingPathComponent("models")
            .appendingPathComponent("\(configuration.model):generateContent")
    }

    private func decodedText(from data: Data) throws -> String {
        do {
            switch configuration.style {
            case .responses:
                let response = try JSONDecoder().decode(OpenAIResponsesResponse.self, from: data)
                if let outputText = response.outputText?.nilIfBlank {
                    return outputText
                }
                return (response.output ?? [])
                    // Responses from reasoning-capable models can contain a
                    // reasoning item without `content` before the final
                    // message. Requiring content on every item caused the
                    // entire otherwise valid response to fail decoding.
                    .flatMap { $0.content ?? [] }
                    .compactMap(\.text)
                    .joined()

            case .chatCompletions:
                let response = try JSONDecoder().decode(ChatCompletionsResponse.self, from: data)
                return response.choices.first?.message.content ?? ""

            case .geminiNative:
                let response = try JSONDecoder().decode(GeminiResponse.self, from: data)
                return (response.candidates ?? [])
                    .flatMap { $0.content?.parts ?? [] }
                    .compactMap(\.text)
                    .joined()

            case .anthropicMessages:
                let response = try JSONDecoder().decode(AnthropicResponse.self, from: data)
                return response.content.compactMap(\.text).joined()
            }
        } catch {
            throw ServiceError.resultNotFound
        }
    }

    private struct TranslationPrompt {
        static let contextInstruction =
            "只翻译 <source> 里的原文。<context> 是原文所在的上下文，只用来理解原文，不要翻译它，也不要把它写进输出。"

        let systemInstruction: String
        let userContent: String

        init(
            sourceText: String,
            context: String?,
            sourceLanguage: LanguageCode,
            targetLanguage: LanguageCode,
            template: String
        ) {
            systemInstruction = AIPromptTemplateDefaults.rendered(
                template,
                sourceLanguage: sourceLanguage,
                targetLanguage: targetLanguage
            )
            if let context {
                // A custom prompt may not say what the context is for, and
                // some models then translate it as well, so one selected
                // sentence came back with its whole paragraph. The request
                // itself says which part to translate.
                userContent = """
                \(Self.contextInstruction)

                <source>
                \(sourceText)
                </source>

                <context>
                \(context)
                </context>
                """
            } else {
                userContent = "SOURCE:\n\(sourceText)"
            }
        }
    }

    private struct OpenAIResponsesRequest: Encodable {
        let model: String
        let instructions: String
        let input: String
    }

    private struct OpenAIResponsesResponse: Decodable {
        let outputText: String?
        let output: [OutputItem]?

        enum CodingKeys: String, CodingKey {
            case outputText = "output_text"
            case output
        }

        struct OutputItem: Decodable {
            let content: [Content]?
        }

        struct Content: Decodable {
            let text: String?
        }
    }

    private struct ChatCompletionsRequest: Encodable {
        let model: String
        let messages: [Message]
        let stream: Bool

        struct Message: Encodable {
            let role: String
            let content: String
        }
    }

    private struct ChatCompletionsResponse: Decodable {
        let choices: [Choice]

        struct Choice: Decodable {
            let message: Message
        }

        struct Message: Decodable {
            let content: String?
        }
    }

    private struct GeminiRequest: Encodable {
        let systemInstruction: Content
        let contents: [Content]

        struct Content: Encodable {
            let role: String?
            let parts: [Part]

            init(role: String? = nil, parts: [Part]) {
                self.role = role
                self.parts = parts
            }
        }

        struct Part: Encodable {
            let text: String
        }
    }

    private struct GeminiResponse: Decodable {
        let candidates: [Candidate]?

        struct Candidate: Decodable {
            let content: Content?
        }

        struct Content: Decodable {
            let parts: [Part]?
        }

        struct Part: Decodable {
            let text: String?
        }
    }

    private struct AnthropicRequest: Encodable {
        let model: String
        let maxTokens: Int
        let system: String
        let messages: [Message]

        enum CodingKeys: String, CodingKey {
            case model
            case maxTokens = "max_tokens"
            case system
            case messages
        }

        struct Message: Encodable {
            let role: String
            let content: String
        }
    }

    private struct AnthropicResponse: Decodable {
        let content: [Content]

        struct Content: Decodable {
            let text: String?
        }
    }
}

private extension String {
    var nilIfBlank: String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}

private extension String {
    var nilIfBlankForLookup: String? { isEmpty ? nil : self }
}
