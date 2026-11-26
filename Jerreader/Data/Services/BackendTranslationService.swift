import CryptoKit
import Foundation

protocol BackendTranslationHTTPClient: Sendable {
    func data(for request: URLRequest) async throws -> (Data, HTTPURLResponse)

    /// Sends `request` and hands its body on a line at a time while it
    /// arrives (2.1.0), without the line breaks and with the blank lines
    /// that end Server-Sent Events. `response` sees the status first and may
    /// throw to stop; `line` returns false to stop reading. Stopping early,
    /// or being cancelled, closes the connection.
    func lines(
        for request: URLRequest,
        response: (HTTPURLResponse) async throws -> Void,
        line: (String) async throws -> Bool
    ) async throws
}

extension BackendTranslationHTTPClient {
    /// For a client that only has whole bodies: the lines once all have arrived.
    func lines(
        for request: URLRequest,
        response: (HTTPURLResponse) async throws -> Void,
        line: (String) async throws -> Bool
    ) async throws {
        let (data, http) = try await data(for: request)
        try await response(http)
        for text in AIStreamLineSplitter.lines(of: data) {
            try Task.checkCancellation()
            guard try await line(text) else { return }
        }
    }
}

struct URLSessionBackendTranslationHTTPClient: BackendTranslationHTTPClient, ChatGPTPlanHTTPClient {
    /// The session `lines` reads streams with (2.1.0). A shown stream asks
    /// to wait 80 s for its next bytes, and the shared session's own limit
    /// is 60 s; which of the two URLSession keeps when a request asks for
    /// more is not documented, so this session's limit is 80 s too and an
    /// 80 s request is not cut at 60 s either way. Requests read in one
    /// piece (`data`) keep the shared session.
    static let streamingSession: URLSession = {
        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = DirectAITranslationService.streamingRequestTimeout
        return URLSession(configuration: configuration)
    }()

    private let session: URLSession
    private let streamingSession: URLSession

    init(
        session: URLSession = .shared,
        streamingSession: URLSession = URLSessionBackendTranslationHTTPClient.streamingSession
    ) {
        self.session = session
        self.streamingSession = streamingSession
    }

    func data(for request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let (data, response) = try await session.data(for: request)
        guard let response = response as? HTTPURLResponse else {
            throw ServiceError.temporarilyUnavailable
        }
        return (data, response)
    }

    func lines(
        for request: URLRequest,
        response: (HTTPURLResponse) async throws -> Void,
        line: (String) async throws -> Bool
    ) async throws {
        let (bytes, urlResponse) = try await streamingSession.bytes(for: request)
        let task = bytes.task
        var finished = false
        // Leaving early, by a throw, a cancellation or `line`, closes the connection.
        defer { if !finished { task.cancel() } }
        guard let http = urlResponse as? HTTPURLResponse else {
            throw ServiceError.temporarilyUnavailable
        }
        try await response(http)
        // `bytes.lines` would drop the blank lines that end an event and
        // break a line at U+2028, so the bytes are split here.
        var splitter = AIStreamLineSplitter()
        for try await byte in bytes {
            if let text = splitter.append(byte) {
                guard try await line(text) else { return }
            }
        }
        if let text = splitter.finish() { _ = try await line(text) }
        finished = true
    }
}

struct BackendTranslationService: TranslationService, ContextExplanationService, AIActionService {
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

    /// 直译 and 追问 through the proxy (2.1.0): one turn, with the
    /// conversation flattened into `text` and the instruction as `prompt`.
    /// The proxy answers in one piece, so nothing is streamed.
    func respond(
        to actionRequest: AIActionRequest,
        onPartialText: AIPartialTextHandler?
    ) async throws -> AICompletion {
        let text = actionRequest.flattened.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { throw ServiceError.emptyText }
        guard configuration.endpoint.scheme?.lowercased() == "https",
              configuration.endpoint.host?.isEmpty == false
        else {
            throw ServiceError.invalidConfiguration
        }

        var request = URLRequest(url: configuration.endpoint)
        request.httpMethod = "POST"
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
                analysisMode: actionRequest.action.rawValue,
                text: text,
                context: nil,
                sourceLanguage: actionRequest.sourceLanguage?.rawValue,
                responseLanguage: actionRequest.responseLanguage.rawValue,
                model: configuration.model,
                prompt: actionRequest.instruction
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
        guard let answer = TranslationOutputPolicy.displayText(
            payload.explanation ?? payload.answer ?? payload.translatedText ?? payload.translation ?? ""
        ) else {
            throw ServiceError.resultNotFound
        }
        return AICompletion(text: answer)
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

struct DirectAITranslationService: StreamingTranslationService, StreamingContextExplanationService, AIActionService {
    static let explanationRequestTimeout: TimeInterval = 60
    /// How long a request whose text shows while it arrives waits for its
    /// next bytes (2.1.0), as Android's STREAM_READ_TIMEOUT_MILLIS: longer
    /// than the reader's own quiet limits (30 s for a translation, 70 s for
    /// an AI answer), so theirs decide and say so in their own words.
    static let streamingRequestTimeout: TimeInterval = 80

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
        try await translate(
            text: text,
            context: context,
            sourceLanguage: sourceLanguage,
            targetLanguage: targetLanguage,
            onPartialText: nil
        )
    }

    /// Streams the answer (2.1.0): `onPartialText` sees the text so far,
    /// the result is the whole validated answer.
    func translate(
        text: String,
        context: String?,
        sourceLanguage: LanguageCode?,
        targetLanguage: LanguageCode,
        onPartialText: AIPartialTextHandler?
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
        try validateConfiguration()

        let prompt = TranslationPrompt(
            sourceText: sourceText,
            context: normalizedContext,
            sourceLanguage: sourceLanguage,
            targetLanguage: targetLanguage,
            template: configuration.translationPromptTemplate
        )
        let answer = try await completion(
            systemInstruction: prompt.systemInstruction,
            conversation: [AIChatMessage(.user, prompt.userContent)],
            onPartialText: onPartialText
        )

        guard let translatedText = TranslationOutputPolicy.displayText(answer.text) else {
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
        try await explain(explanationRequest, onPartialText: nil)
    }

    /// Streams the explanation (2.1.0), as `translate` does the translation.
    func explain(
        _ explanationRequest: ContextExplanationRequest,
        onPartialText: AIPartialTextHandler?
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
        try validateConfiguration()

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
        let answer = try await completion(
            systemInstruction: systemInstruction,
            conversation: [AIChatMessage(.user, userContent)],
            timeoutInterval: Self.explanationRequestTimeout,
            quick: false,
            onPartialText: onPartialText
        )

        guard let explanation = TranslationOutputPolicy.displayText(answer.text) else {
            throw ServiceError.resultNotFound
        }
        return ContextExplanationResult(
            explanation: explanation,
            providerIdentifier: providerIdentifier,
            providerVersion: providerVersion
        )
    }

    /// 直译 and 追问 (2.1.0): the instruction, then every turn as a message
    /// of its own, for the four formats and the ChatGPT plan alike.
    func respond(
        to request: AIActionRequest,
        onPartialText: AIPartialTextHandler?
    ) async throws -> AICompletion {
        guard request.conversation.contains(where: { $0.role == .user }) else {
            throw ServiceError.emptyText
        }
        try validateConfiguration()
        let answer = try await completion(
            systemInstruction: request.instruction,
            conversation: request.conversation,
            timeoutInterval: Self.explanationRequestTimeout,
            quick: request.action == .literal,
            onPartialText: onPartialText
        )
        guard let text = TranslationOutputPolicy.displayText(answer.text) else {
            throw ServiceError.resultNotFound
        }
        return AICompletion(text: text, usage: answer.usage)
    }

    private func validateConfiguration() throws {
        guard configuration.endpoint.scheme?.lowercased() == "https",
              configuration.endpoint.host?.isEmpty == false,
              !configuration.model.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !configuration.apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        else {
            throw ServiceError.invalidConfiguration
        }
    }

    /// Sends one request and returns the model's answer. Every format streams
    /// (2.1.0); `onPartialText` sees the visible answer each time it grows.
    /// A service that answers with one JSON body instead is read as before.
    /// - Parameter quick: a translation, as opposed to an explanation; 自动
    ///   reasoning is lighter for it.
    /// - Parameter timeoutInterval: the limit for an answer shown only when
    ///   whole; one shown while it arrives waits `streamingRequestTimeout`.
    private func completion(
        systemInstruction: String,
        conversation: [AIChatMessage],
        timeoutInterval: TimeInterval = 30,
        quick: Bool = true,
        onPartialText: AIPartialTextHandler? = nil
    ) async throws -> AICompletion {
        let timeoutInterval = onPartialText == nil ? timeoutInterval : Self.streamingRequestTimeout
        if configuration.provider.usesAccountSignIn {
            return try await planCompletion(
                systemInstruction: systemInstruction, conversation: conversation,
                timeoutInterval: timeoutInterval, quick: quick, onPartialText: onPartialText
            )
        }
        let request = try makeRequest(
            systemInstruction: systemInstruction,
            conversation: conversation,
            timeoutInterval: timeoutInterval,
            quick: quick
        )
        var reader = AIStreamReader(format: AIStreamFormat(configuration.style))
        try await streamLines(for: request) { response in
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
        } line: { line in
            if reader.read(line), let onPartialText {
                await onPartialText(reader.decoder.text)
            }
            return !reader.isFinished
        }
        if let body = reader.plainBody {
            return AICompletion(text: try decodedText(from: body))
        }
        let decoder = reader.decoder
        switch decoder.finish() {
        case .completed:
            return AICompletion(text: decoder.answer, usage: decoder.usage)
        case .incomplete:
            // Cut short by a length or safety stop: not the whole answer, so
            // it is neither shown as a result nor cached (2.1.0), as on the
            // plan path and on Android. Its text so far was only shown.
            throw ServiceError.resultNotFound
        case .failed(let code, _):
            throw Self.streamError(code: code)
        case .interrupted:
            throw ServiceError.temporarilyUnavailable
        }
    }

    /// An error event in a stream that began with a 2xx status.
    private static func streamError(code: String?) -> ServiceError {
        switch code ?? "" {
        case "authentication_error", "invalid_api_key", "permission_error",
             "UNAUTHENTICATED", "PERMISSION_DENIED":
            return .authenticationFailed
        case "invalid_request_error", "not_found_error", "model_not_found",
             "INVALID_ARGUMENT", "NOT_FOUND":
            return .invalidConfiguration
        default:
            return .temporarilyUnavailable
        }
    }

    /// A request paid by a ChatGPT plan: a current access token, one retry
    /// with a fresh token when it is refused, and success only on
    /// `response.completed`, so a cut-off answer is never shown or cached.
    private func planCompletion(
        systemInstruction: String,
        conversation: [AIChatMessage],
        timeoutInterval: TimeInterval,
        quick: Bool,
        onPartialText: AIPartialTextHandler?
    ) async throws -> AICompletion {
        let extra = AIRequestTuning.extraFields(
            kind: configuration.provider,
            style: .responses,
            model: configuration.model,
            reasoning: configuration.reasoning,
            quick: quick
        )
        func request(_ token: String) throws -> URLRequest {
            if conversation.count == 1, let only = conversation.first, only.role == .user {
                return try ChatGPTPlanRequests.responses(
                    accessToken: token, model: configuration.model, instructions: systemInstruction,
                    input: only.text, extra: extra, timeoutInterval: timeoutInterval
                )
            }
            return try ChatGPTPlanRequests.responses(
                accessToken: token, model: configuration.model, instructions: systemInstruction,
                turns: conversation.map { (role: $0.role.rawValue, text: $0.text) },
                extra: extra, timeoutInterval: timeoutInterval
            )
        }
        var token = try await planAccessToken(rejected: nil)
        var attempt = try await planAttempt(try request(token), onPartialText: onPartialText)
        if case .refused(401, _, _) = attempt {
            token = try await planAccessToken(rejected: token)
            attempt = try await planAttempt(try request(token), onPartialText: onPartialText)
        }
        switch attempt {
        case .refused(let status, let body, let requestID):
            // OpenAI's own reason is kept: the status, the code and the request id.
            if ChatGPTPlanErrorBody(body).needsConsent { planConsentNeeded() }
            throw Self.serviceError(ChatGPTPlanRefusal.classify(
                status: status, body: body, requestID: requestID
            ))
        case .streamed(let outcome):
            switch outcome {
            case .completed(let text):
                let answer = text.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !answer.isEmpty else { throw ServiceError.resultNotFound }
                return AICompletion(text: answer)
            case .failed(let code, let message):
                throw Self.serviceError(ChatGPTPlanRefusal.streamFailure(code: code, message: message))
            case .incomplete:
                throw ServiceError.resultNotFound
            case .interrupted:
                throw ServiceError.temporarilyUnavailable
            }
        }
    }

    private enum PlanAttempt {
        case refused(status: Int, body: Data, requestID: String?)
        case streamed(ChatGPTPlanStreamOutcome)
    }

    /// One plan request: a refusal's body, or the stream read as it arrives.
    private func planAttempt(
        _ request: URLRequest,
        onPartialText: AIPartialTextHandler?
    ) async throws -> PlanAttempt {
        var status = 0
        var requestID: String?
        var refusal: [String] = []
        var refusalBytes = 0
        var decoder = ChatGPTPlanStreamDecoder()
        try await streamLines(for: request) { response in
            status = response.statusCode
            requestID = response.value(forHTTPHeaderField: "x-request-id")
        } line: { line in
            guard (200 ..< 300).contains(status) else {
                // An error body is small; more than this is not read.
                refusal.append(line)
                refusalBytes += line.utf8.count + 1
                return refusalBytes < 65_536
            }
            if decoder.feed(line), let onPartialText {
                await onPartialText(decoder.text)
            }
            return decoder.outcome == nil
        }
        guard (200 ..< 300).contains(status) else {
            return .refused(status: status, body: Data(refusal.joined(separator: "\n").utf8), requestID: requestID)
        }
        return .streamed(decoder.finish())
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

    /// Reads a response a line at a time; a dropped or cancelled connection
    /// becomes the same errors a whole-body request had.
    private func streamLines(
        for request: URLRequest,
        response: (HTTPURLResponse) async throws -> Void,
        line: (String) async throws -> Bool
    ) async throws {
        do {
            try await client.lines(for: request, response: response, line: line)
        } catch is CancellationError {
            throw CancellationError()
        } catch let error as ServiceError {
            throw error
        } catch {
            // URLSession reports a cancelled task as URLError.cancelled.
            if Task.isCancelled { throw CancellationError() }
            throw ServiceError.temporarilyUnavailable
        }
        try Task.checkCancellation()
    }

    /// - Parameter quick: a translation, as opposed to an explanation; 自动
    ///   reasoning is lighter for it.
    private func makeRequest(
        systemInstruction: String,
        conversation: [AIChatMessage],
        timeoutInterval: TimeInterval = 30,
        quick: Bool = true
    ) throws -> URLRequest {
        var request = URLRequest(url: requestURL)
        request.httpMethod = "POST"
        request.timeoutInterval = timeoutInterval
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        // A service that does not stream answers with JSON, which is read too.
        request.setValue("text/event-stream, application/json", forHTTPHeaderField: "Accept")
        let turns = conversation.filter { $0.role != .system }
        let messages: [[String: String]] = turns.map { turn in
            ["role": turn.role.rawValue, "content": turn.text]
        }

        var body: [String: Any]
        switch configuration.style {
        case .responses:
            request.setValue(
                "Bearer \(configuration.apiKey)",
                forHTTPHeaderField: "Authorization"
            )
            body = [
                "model": configuration.model,
                "instructions": systemInstruction,
                "stream": true,
            ]
            // One question goes out as a plain string, as before streaming.
            if turns.count == 1, let only = turns.first, only.role == .user {
                body["input"] = only.text
            } else {
                body["input"] = messages
            }

        case .chatCompletions:
            request.setValue(
                "Bearer \(configuration.apiKey)",
                forHTTPHeaderField: "Authorization"
            )
            let system: [String: String] = ["role": "system", "content": systemInstruction]
            body = [
                "model": configuration.model,
                "messages": [system] + messages,
                "stream": true,
            ]

        case .geminiNative:
            request.setValue(configuration.apiKey, forHTTPHeaderField: "x-goog-api-key")
            let contents: [[String: Any]] = turns.map { turn in
                let role = turn.role == .assistant ? "model" : "user"
                let parts: [[String: String]] = [["text": turn.text]]
                return ["role": role, "parts": parts]
            }
            let instruction: [String: Any] = ["parts": [["text": systemInstruction]]]
            body = [
                "systemInstruction": instruction,
                "contents": contents,
            ]

        case .anthropicMessages:
            request.setValue(configuration.apiKey, forHTTPHeaderField: "x-api-key")
            request.setValue("2023-06-01", forHTTPHeaderField: "anthropic-version")
            body = [
                "model": configuration.model,
                "max_tokens": AIRequestTuning.anthropicOutputTokens,
                "system": systemInstruction,
                "messages": messages,
                "stream": true,
            ]
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
        body.merge(extra) { _, added in added }
        request.httpBody = try JSONSerialization.data(withJSONObject: body, options: [.sortedKeys])
        return request
    }

    /// Gemini streams from `:streamGenerateContent` with `alt=sse`; the other
    /// formats stream from their own address.
    private var requestURL: URL {
        guard configuration.style == .geminiNative else {
            return configuration.endpoint
        }
        let url = configuration.endpoint
            .appendingPathComponent("models")
            .appendingPathComponent("\(configuration.model):streamGenerateContent")
        guard var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return url }
        var items = (components.queryItems ?? []).filter { $0.name != "alt" }
        items.append(URLQueryItem(name: "alt", value: "sse"))
        components.queryItems = items
        return components.url ?? url
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
                    // A thinking model's thought summary is not the answer.
                    .filter { $0.thought != true }
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

    private struct ChatCompletionsResponse: Decodable {
        let choices: [Choice]

        struct Choice: Decodable {
            let message: Message
        }

        struct Message: Decodable {
            let content: String?
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
            let thought: Bool?
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
