import CryptoKit
import Foundation

protocol BackendTranslationHTTPClient: Sendable {
    func data(for request: URLRequest) async throws -> (Data, HTTPURLResponse)
}

struct URLSessionBackendTranslationHTTPClient: BackendTranslationHTTPClient {
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
        let context = explanationRequest.contextText
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
                analysisMode: "grammar",
                text: focusedText,
                context: context,
                sourceLanguage: explanationRequest.sourceLanguage?.rawValue,
                responseLanguage: explanationRequest.responseLanguage.rawValue,
                model: configuration.model,
                prompt: AIPromptTemplateDefaults.rendered(
                    configuration.grammarAnalysisPromptTemplate,
                    sourceLanguage: explanationRequest.sourceLanguage,
                    responseLanguage: explanationRequest.responseLanguage,
                    fallback: AIPromptTemplateDefaults.grammarAnalysis
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

    init(
        configuration: DirectAITranslationConfiguration,
        client: any BackendTranslationHTTPClient = URLSessionBackendTranslationHTTPClient()
    ) {
        self.configuration = configuration
        self.client = client
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
        let value = [
            configuration.provider.rawValue,
            configuration.endpoint.absoluteString,
            configuration.model,
            configuration.translationPromptTemplate,
            configuration.grammarAnalysisPromptTemplate,
            "jerreader-direct-api-v1",
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
        let request = try makeRequest(
            systemInstruction: prompt.systemInstruction,
            userContent: prompt.userContent
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

        guard let translatedText = TranslationOutputPolicy.displayText(
            try decodedText(from: data)
        ) else {
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
        let context = explanationRequest.contextText
        guard !focusedText.isEmpty else { throw ServiceError.emptyText }
        guard focusedText.count <= 2_000 else { throw ServiceError.textTooLong }
        guard configuration.endpoint.scheme?.lowercased() == "https",
              configuration.endpoint.host?.isEmpty == false,
              !configuration.model.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !configuration.apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        else {
            throw ServiceError.invalidConfiguration
        }

        let systemInstruction = AIPromptTemplateDefaults.rendered(
            configuration.grammarAnalysisPromptTemplate,
            sourceLanguage: explanationRequest.sourceLanguage,
            responseLanguage: explanationRequest.responseLanguage,
            fallback: AIPromptTemplateDefaults.grammarAnalysis
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
        let request = try makeRequest(
            systemInstruction: systemInstruction,
            userContent: userContent,
            timeoutInterval: Self.explanationRequestTimeout
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

        guard let explanation = TranslationOutputPolicy.displayText(
            try decodedText(from: data)
        ) else {
            throw ServiceError.resultNotFound
        }
        return ContextExplanationResult(
            explanation: explanation,
            providerIdentifier: providerIdentifier,
            providerVersion: providerVersion
        )
    }

    private func makeRequest(
        systemInstruction: String,
        userContent: String,
        timeoutInterval: TimeInterval = 30
    ) throws -> URLRequest {
        var request = URLRequest(url: requestURL)
        request.httpMethod = "POST"
        request.timeoutInterval = timeoutInterval
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        switch configuration.provider {
        case .openAI:
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

        case .kimi, .deepSeek, .openAICompatible:
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

        case .gemini:
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

        case .anthropic:
            request.setValue(configuration.apiKey, forHTTPHeaderField: "x-api-key")
            request.setValue("2023-06-01", forHTTPHeaderField: "anthropic-version")
            request.httpBody = try JSONEncoder().encode(
                AnthropicRequest(
                    model: configuration.model,
                    maxTokens: 1_200,
                    system: systemInstruction,
                    messages: [
                        .init(role: "user", content: userContent)
                    ]
                )
            )
        }
        return request
    }

    private var requestURL: URL {
        guard configuration.provider == .gemini else {
            return configuration.endpoint
        }
        return configuration.endpoint
            .appendingPathComponent("models")
            .appendingPathComponent("\(configuration.model):generateContent")
    }

    private func decodedText(from data: Data) throws -> String {
        do {
            switch configuration.provider {
            case .openAI:
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

            case .kimi, .deepSeek, .openAICompatible:
                let response = try JSONDecoder().decode(ChatCompletionsResponse.self, from: data)
                return response.choices.first?.message.content ?? ""

            case .gemini:
                let response = try JSONDecoder().decode(GeminiResponse.self, from: data)
                return (response.candidates ?? [])
                    .flatMap { $0.content?.parts ?? [] }
                    .compactMap(\.text)
                    .joined()

            case .anthropic:
                let response = try JSONDecoder().decode(AnthropicResponse.self, from: data)
                return response.content.compactMap(\.text).joined()
            }
        } catch {
            throw ServiceError.resultNotFound
        }
    }

    private struct TranslationPrompt {
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
                userContent = """
                SOURCE:
                \(sourceText)

                CONTEXT:
                \(context)
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
