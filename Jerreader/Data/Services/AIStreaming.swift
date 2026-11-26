import Foundation

// MARK: - Answers

/// What a model said one answer cost, when the service said so.
struct AIUsage: Equatable, Sendable {
    var inputTokens: Int?
    var outputTokens: Int?

    init(inputTokens: Int? = nil, outputTokens: Int? = nil) {
        self.inputTokens = inputTokens
        self.outputTokens = outputTokens
    }

    var isEmpty: Bool { inputTokens == nil && outputTokens == nil }
}

/// A whole answer of a model (2.1.0).
struct AICompletion: Equatable, Sendable {
    let text: String
    /// Nil when the service did not say.
    let usage: AIUsage?

    init(text: String, usage: AIUsage? = nil) {
        self.text = text
        self.usage = usage?.isEmpty == true ? nil : usage
    }
}

/// Gets the whole answer so far each time it grows. Partial text is only
/// shown: it is never cached, and the caller still waits for the result,
/// which alone counts.
typealias AIPartialTextHandler = @Sendable (String) async -> Void

/// A translation service that can show its answer while it is written.
protocol StreamingTranslationService: TranslationService {
    func translate(
        text: String,
        context: String?,
        sourceLanguage: LanguageCode?,
        targetLanguage: LanguageCode,
        onPartialText: AIPartialTextHandler?
    ) async throws -> TranslationResult
}

/// An explanation service that can show its answer while it is written.
protocol StreamingContextExplanationService: ContextExplanationService {
    func explain(
        _ request: ContextExplanationRequest,
        onPartialText: AIPartialTextHandler?
    ) async throws -> ContextExplanationResult
}

/// One 直译 or 追问 request of the translation card's AI bar.
struct AIActionRequest: Equatable, Sendable {
    let action: AIReadingAction
    /// The instruction first, then the conversation: for a service that
    /// takes several turns (the four direct formats and the ChatGPT plan).
    let messages: [AIChatMessage]
    /// The same request as one text, for a service that takes one turn (AI 代理).
    let flattened: String
    let sourceLanguage: LanguageCode?
    let responseLanguage: LanguageCode

    var instruction: String {
        messages.first { $0.role == .system }?.text ?? ""
    }

    var conversation: [AIChatMessage] {
        messages.filter { $0.role != .system }
    }

    /// 直译: the sentence, its translation and its context in one turn.
    static func literal(
        source: String,
        translation: String?,
        context: String?,
        sourceLanguage: LanguageCode?,
        responseLanguage: LanguageCode
    ) -> AIActionRequest {
        let material = AIReadingActionPolicy.material(source: source, translation: translation, context: context)
        let instruction = AIReadingActionPolicy.instruction(
            for: .literal, sourceLanguage: sourceLanguage, responseLanguage: responseLanguage
        ) ?? ""
        return AIActionRequest(
            action: .literal,
            messages: [AIChatMessage(.system, instruction), AIChatMessage(.user, material)],
            flattened: material,
            sourceLanguage: sourceLanguage,
            responseLanguage: responseLanguage
        )
    }

    /// 追问: the newest turns, the last one being the question asked now.
    static func followUp(
        source: String,
        translation: String?,
        context: String?,
        sourceLanguage: LanguageCode?,
        responseLanguage: LanguageCode,
        turns: [AIFollowUpTurn]
    ) -> AIActionRequest {
        AIActionRequest(
            action: .followUp,
            messages: AIReadingActionPolicy.followUpMessages(
                source: source, translation: translation, context: context,
                sourceLanguage: sourceLanguage, responseLanguage: responseLanguage, turns: turns
            ),
            flattened: AIReadingActionPolicy.followUpFlattened(
                source: source, translation: translation, context: context, turns: turns
            ),
            sourceLanguage: sourceLanguage,
            responseLanguage: responseLanguage
        )
    }
}

/// A service that answers 直译 and 追问.
protocol AIActionService: Sendable {
    func respond(
        to request: AIActionRequest,
        onPartialText: AIPartialTextHandler?
    ) async throws -> AICompletion
}

// MARK: - Server-Sent Events

/// The four formats a model is called in directly.
enum AIStreamFormat: Equatable, Sendable {
    case responses
    case chatCompletions
    case anthropicMessages
    case gemini

    init(_ style: AIAPIStyle) {
        switch style {
        case .responses: self = .responses
        case .chatCompletions: self = .chatCompletions
        case .anthropicMessages: self = .anthropicMessages
        case .geminiNative: self = .gemini
        }
    }
}

enum AIStreamOutcome: Equatable, Sendable {
    /// The service said the answer is whole.
    case completed
    /// The answer stopped early: a length limit, a safety stop.
    case incomplete(reason: String?)
    case failed(code: String?, message: String?)
    /// The stream ended without saying how.
    case interrupted
}

/// Reads one streamed answer of a direct format, a line at a time.
/// URLSession's `lines` would leave out the blank lines between events, so
/// every `data:` line is read as an event of its own, named by the `event:`
/// line just before it. Thinking (`thinking_delta`, `response.reasoning_*`,
/// Gemini's `thought` parts, `reasoning_content`) is never part of the text.
struct AIStreamDecoder: Sendable {
    let format: AIStreamFormat
    /// The visible answer so far.
    private(set) var text = ""
    private(set) var usage = AIUsage()
    /// Set by the event that ends the stream; later lines are ignored.
    private(set) var outcome: AIStreamOutcome?
    private var eventName: String?
    /// Responses: the text of the final event, which wins over the deltas.
    private var finalText: String?
    private var stopReason: String?

    init(format: AIStreamFormat) {
        self.format = format
    }

    /// The answer: the final event's text when there is one, else the deltas.
    var answer: String { finalText ?? text }

    var isFinished: Bool { outcome != nil }

    /// Whether a body's first line belongs to an event stream rather than
    /// to a whole JSON answer of a service that does not stream.
    static func isEventLine(_ line: String) -> Bool {
        ["data:", "event:", ":", "id:", "retry:"].contains { line.hasPrefix($0) }
    }

    /// Reads one line. True when the visible answer grew.
    @discardableResult
    mutating func feed(_ line: String) -> Bool {
        guard outcome == nil else { return false }
        if line.isEmpty {
            eventName = nil
            return false
        }
        if line.hasPrefix("event:") {
            eventName = String(line.dropFirst(6)).trimmingCharacters(in: .whitespaces)
            return false
        }
        guard line.hasPrefix("data:") else { return false }
        var payload = line.dropFirst(5)
        if payload.first == " " { payload = payload.dropFirst() }
        let name = eventName
        eventName = nil
        return dispatch(String(payload), eventName: name)
    }

    /// How the stream ended, once the body has ended without an error.
    func finish() -> AIStreamOutcome {
        if let outcome { return outcome }
        switch format {
        case .chatCompletions:
            guard let stopReason else { return .interrupted }
            return Self.chatOutcome(stopReason)
        case .gemini:
            guard let stopReason else { return .interrupted }
            return stopReason == "STOP" ? .completed : .incomplete(reason: stopReason)
        case .responses, .anthropicMessages:
            return .interrupted
        }
    }

    private mutating func dispatch(_ payload: String, eventName: String?) -> Bool {
        if payload == "[DONE]" {
            if format == .chatCompletions {
                outcome = stopReason.map(Self.chatOutcome) ?? .completed
            }
            return false
        }
        guard let object = (try? JSONSerialization.jsonObject(with: Data(payload.utf8))) as? [String: Any]
        else { return false }
        switch format {
        case .responses: return responses(object, eventName: eventName)
        case .chatCompletions: return chatCompletions(object)
        case .anthropicMessages: return anthropic(object, eventName: eventName)
        case .gemini: return gemini(object)
        }
    }

    private mutating func responses(_ object: [String: Any], eventName: String?) -> Bool {
        let type = (object["type"] as? String) ?? eventName ?? ""
        switch type {
        case "response.output_text.delta":
            return append(object["delta"] as? String)
        case "response.completed", "response.incomplete":
            let response = object["response"] as? [String: Any]
            let final = Self.outputText(of: response)
            if !final.isEmpty { finalText = final }
            readUsage(response?["usage"], input: "input_tokens", output: "output_tokens")
            if type == "response.completed" {
                outcome = .completed
            } else {
                let details = response?["incomplete_details"] as? [String: Any]
                outcome = .incomplete(reason: details?["reason"] as? String)
            }
        case "response.failed":
            let error = (object["response"] as? [String: Any])?["error"] as? [String: Any]
            outcome = .failed(code: Self.text(error?["code"]), message: error?["message"] as? String)
        case "error":
            let nested = object["error"] as? [String: Any]
            outcome = .failed(
                code: Self.text(object["code"]) ?? Self.text(nested?["code"]) ?? (nested?["type"] as? String),
                message: (object["message"] as? String) ?? (nested?["message"] as? String)
            )
        default:
            break
        }
        return false
    }

    private mutating func chatCompletions(_ object: [String: Any]) -> Bool {
        if let error = object["error"] as? [String: Any] {
            outcome = .failed(
                code: Self.text(error["code"]) ?? (error["type"] as? String),
                message: error["message"] as? String
            )
            return false
        }
        let choice = (object["choices"] as? [[String: Any]])?.first
        // The last chunk may carry the usage, at the top or in the choice.
        readUsage(object["usage"], input: "prompt_tokens", output: "completion_tokens")
        readUsage(choice?["usage"], input: "prompt_tokens", output: "completion_tokens")
        if let reason = choice?["finish_reason"] as? String { stopReason = reason }
        // `reasoning_content` and `reasoning_details` are thinking and stay out.
        return append(Self.chatText((choice?["delta"] as? [String: Any])?["content"]))
    }

    private mutating func anthropic(_ object: [String: Any], eventName: String?) -> Bool {
        switch (object["type"] as? String) ?? eventName ?? "" {
        case "message_start":
            readUsage((object["message"] as? [String: Any])?["usage"], input: "input_tokens", output: "output_tokens")
        case "content_block_start":
            let block = object["content_block"] as? [String: Any]
            guard (block?["type"] as? String) == "text" else { return false }
            return append(block?["text"] as? String)
        case "content_block_delta":
            let delta = object["delta"] as? [String: Any]
            guard (delta?["type"] as? String) == "text_delta" else { return false }
            return append(delta?["text"] as? String)
        case "message_delta":
            readUsage(object["usage"], input: "input_tokens", output: "output_tokens")
            if let reason = (object["delta"] as? [String: Any])?["stop_reason"] as? String { stopReason = reason }
        case "message_stop":
            outcome = stopReason == "max_tokens" ? .incomplete(reason: stopReason) : .completed
        case "error":
            let error = object["error"] as? [String: Any]
            outcome = .failed(code: error?["type"] as? String, message: error?["message"] as? String)
        default:
            break
        }
        return false
    }

    private mutating func gemini(_ object: [String: Any]) -> Bool {
        if let error = object["error"] as? [String: Any] {
            outcome = .failed(
                code: (error["status"] as? String) ?? Self.text(error["code"]),
                message: error["message"] as? String
            )
            return false
        }
        readUsage(object["usageMetadata"], input: "promptTokenCount", output: "candidatesTokenCount")
        if let reason = (object["promptFeedback"] as? [String: Any])?["blockReason"] as? String {
            outcome = .incomplete(reason: reason)
            return false
        }
        var grew = false
        for candidate in object["candidates"] as? [[String: Any]] ?? [] {
            let parts = (candidate["content"] as? [String: Any])?["parts"] as? [[String: Any]] ?? []
            for part in parts where (part["thought"] as? Bool) != true {
                if append(part["text"] as? String) { grew = true }
            }
            if let reason = candidate["finishReason"] as? String { stopReason = reason }
        }
        return grew
    }

    private mutating func append(_ delta: String?) -> Bool {
        guard let delta, !delta.isEmpty else { return false }
        text += delta
        return true
    }

    private mutating func readUsage(_ value: Any?, input: String, output: String) {
        guard let fields = value as? [String: Any] else { return }
        if let tokens = fields[input] as? Int { usage.inputTokens = tokens }
        if let tokens = fields[output] as? Int { usage.outputTokens = tokens }
    }

    /// A length or safety stop leaves the answer cut short.
    private static func chatOutcome(_ reason: String) -> AIStreamOutcome {
        reason == "length" || reason == "content_filter" ? .incomplete(reason: reason) : .completed
    }

    /// A Chat Completions delta's content: a string, or a list of parts of
    /// which only the text parts are the answer.
    private static func chatText(_ value: Any?) -> String? {
        if let text = value as? String { return text }
        guard let parts = value as? [[String: Any]] else { return nil }
        let text = parts
            .filter { ($0["type"] as? String ?? "text") == "text" }
            .compactMap { $0["text"] as? String }
            .joined()
        return text.isEmpty ? nil : text
    }

    /// The output text of a Responses `response` object: message items only.
    private static func outputText(of response: [String: Any]?) -> String {
        let output = response?["output"] as? [[String: Any]] ?? []
        return output
            .filter { ($0["type"] as? String ?? "message") == "message" }
            .flatMap { $0["content"] as? [[String: Any]] ?? [] }
            .filter { ($0["type"] as? String) == "output_text" }
            .compactMap { $0["text"] as? String }
            .joined()
    }

    /// An error code, which some services send as a number.
    private static func text(_ value: Any?) -> String? {
        if let text = value as? String { return text }
        if let number = value as? Int { return String(number) }
        return nil
    }
}

/// Reads the body of a direct request: an event stream when the service
/// streams, one JSON answer when it does not (a proxy that ignores `stream`).
/// The first line that is not blank decides.
struct AIStreamReader: Sendable {
    /// A whole JSON answer longer than this is cut, and then fails to decode.
    static let maximumPlainBodyCharacters = 1_000_000

    private enum Mode: Sendable {
        case undecided
        case events
        case plain
    }

    private(set) var decoder: AIStreamDecoder
    private var mode = Mode.undecided
    private var plainLines: [String] = []
    private var plainCharacters = 0

    init(format: AIStreamFormat) {
        decoder = AIStreamDecoder(format: format)
    }

    /// The stream said how it ended; nothing after it is read.
    var isFinished: Bool { mode == .events && decoder.isFinished }

    /// The whole body, when the service answered without streaming.
    var plainBody: Data? {
        mode == .plain ? Data(plainLines.joined(separator: "\n").utf8) : nil
    }

    /// Reads one line. True when the visible answer grew.
    @discardableResult
    mutating func read(_ line: String) -> Bool {
        switch mode {
        case .undecided:
            guard !line.trimmingCharacters(in: .whitespaces).isEmpty else { return false }
            mode = AIStreamDecoder.isEventLine(line) ? .events : .plain
            return read(line)
        case .events:
            return decoder.feed(line)
        case .plain:
            guard plainCharacters <= Self.maximumPlainBodyCharacters else { return false }
            plainCharacters += line.utf8.count + 1
            plainLines.append(line)
            return false
        }
    }
}

/// Splits a body into lines at LF, CR or CRLF. Only those bytes end a line,
/// so a JSON string with U+2028 in it, which `AsyncBytes.lines` would cut in
/// two, stays whole. Blank lines are kept: they end an SSE event.
struct AIStreamLineSplitter: Sendable {
    private var buffer: [UInt8] = []
    private var afterCarriageReturn = false

    init() {}

    /// The line `byte` ends, if it ends one.
    mutating func append(_ byte: UInt8) -> String? {
        switch byte {
        case 0x0A:
            if afterCarriageReturn {
                afterCarriageReturn = false
                return nil
            }
            return takeLine()
        case 0x0D:
            afterCarriageReturn = true
            return takeLine()
        default:
            afterCarriageReturn = false
            buffer.append(byte)
            return nil
        }
    }

    /// The last line, when the body did not end with a line break.
    mutating func finish() -> String? {
        afterCarriageReturn = false
        return buffer.isEmpty ? nil : takeLine()
    }

    static func lines(of data: Data) -> [String] {
        var splitter = AIStreamLineSplitter()
        var lines: [String] = []
        for byte in data {
            if let line = splitter.append(byte) { lines.append(line) }
        }
        if let line = splitter.finish() { lines.append(line) }
        return lines
    }

    private mutating func takeLine() -> String {
        defer { buffer.removeAll(keepingCapacity: true) }
        return String(decoding: buffer, as: UTF8.self)
    }
}

// MARK: - Timeouts

/// When a streamed request last showed signs of life (2.1.0). Until the first
/// delta the request has its old limit; after it, the same limit counts from
/// the newest delta, and `total` caps the whole answer.
final class AIStreamActivity: @unchecked Sendable {
    private let lock = NSLock()
    private let started: ContinuousClock.Instant
    private var latest: ContinuousClock.Instant

    init(now: ContinuousClock.Instant = ContinuousClock.now) {
        started = now
        latest = now
    }

    /// Called for every delta.
    func touch(now: ContinuousClock.Instant = ContinuousClock.now) {
        lock.lock()
        defer { lock.unlock() }
        latest = max(latest, now)
    }

    /// When the request has timed out unless another delta arrives first.
    func deadline(idle: Duration, total: Duration) -> ContinuousClock.Instant {
        lock.lock()
        defer { lock.unlock() }
        return min(latest + idle, started + total)
    }

    /// Returns once the request has gone `idle` without a delta, or has run
    /// for `total`; throws when the waiting task is cancelled.
    func waitUntilTimedOut(idle: Duration, total: Duration) async throws {
        let clock = ContinuousClock()
        while true {
            let deadline = deadline(idle: idle, total: total)
            if clock.now >= deadline { return }
            try await clock.sleep(until: deadline, tolerance: nil)
        }
    }
}

// MARK: - Showing partial text

/// Hands partial text on at most once per `interval` (2.1.0: 50 ms), always
/// ending with the newest text, so a fast stream does not redraw the card
/// for every delta. One throttle serves one request.
@MainActor
final class AIPartialTextThrottle {
    nonisolated static let defaultInterval: Duration = .milliseconds(50)

    private let interval: Duration
    private let publish: @MainActor (String) -> Void
    private var lastPublished: ContinuousClock.Instant?
    private var pending: String?
    private var flushTask: Task<Void, Never>?
    private var isCancelled = false
    /// How many times text was handed on; for tests.
    private(set) var publishCount = 0

    init(interval: Duration = AIPartialTextThrottle.defaultInterval, publish: @escaping @MainActor (String) -> Void) {
        self.interval = interval
        self.publish = publish
    }

    func submit(_ text: String) {
        guard !isCancelled else { return }
        let now = ContinuousClock.now
        if let lastPublished, now - lastPublished < interval {
            pending = text
            scheduleFlush(at: lastPublished + interval)
            return
        }
        emit(text, now: now)
    }

    /// Drops held text and hands nothing on from now on.
    func cancel() {
        isCancelled = true
        flushTask?.cancel()
        flushTask = nil
        pending = nil
    }

    private func scheduleFlush(at deadline: ContinuousClock.Instant) {
        guard flushTask == nil else { return }
        flushTask = Task { @MainActor [weak self] in
            try? await ContinuousClock().sleep(until: deadline, tolerance: nil)
            guard !Task.isCancelled, let self else { return }
            self.flushTask = nil
            guard !self.isCancelled, let text = self.pending else { return }
            self.emit(text, now: ContinuousClock.now)
        }
    }

    private func emit(_ text: String, now: ContinuousClock.Instant) {
        pending = nil
        lastPublished = now
        publishCount += 1
        publish(text)
    }
}
