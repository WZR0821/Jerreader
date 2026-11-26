import Foundation

struct ContextExplanationRequest: Equatable, Sendable {
    let focusedText: String
    let contextText: String?
    let sourceLanguage: LanguageCode?
    let responseLanguage: LanguageCode
    var isWordLookup = false
    var dictionaryEntry: String? = nil
    /// Set for an AI reading-lens question; nil for the sentence-structure analysis.
    var lens: ReadingLensOptions? = nil

    var lookupInstruction: String {
        isWordLookup ? "\n本次是语境查词。先给出该词在原句中的简短中文含义，再说明词典形与关键活用。区分推测和确定信息；缺少原句或无法消歧时明确说明，不编造读音、词源或词典依据。" : ""
    }

    /// The system prompt for this request. `grammarPrompt` is the user's
    /// rendered grammar-analysis template; a lens question replaces it with the
    /// lens contract and keeps it only as the 日语语法 task, as Android does.
    /// Both end with the guard line of `AIReadingActionPolicy`.
    func systemInstruction(grammarPrompt: String) -> String {
        guard let lens else { return AIReadingActionPolicy.guarded(grammarPrompt + lookupInstruction) }
        return AIReadingLensPromptPolicy.rendered(
            lens,
            sourceLanguage: sourceLanguage,
            responseLanguage: responseLanguage,
            grammarPrompt: grammarPrompt
        )
    }
}

struct ContextExplanationResult: Equatable, Sendable {
    let explanation: String
    let providerIdentifier: String
    let providerVersion: String
}

enum ContextExplanationOutputPolicy {
    static func validated(
        _ result: ContextExplanationResult
    ) -> ContextExplanationResult? {
        guard let explanation = TranslationOutputPolicy.displayText(
            result.explanation
        ) else {
            return nil
        }
        return ContextExplanationResult(
            explanation: explanation,
            providerIdentifier: result.providerIdentifier,
            providerVersion: result.providerVersion
        )
    }
}

/// Keeps explanation payloads bounded and deterministic before they reach a
/// provider. The focused text remains the only text to explain; nearby text is
/// clipped around the matching occurrence and is supplied only for
/// disambiguation.
enum ContextExplanationInputPolicy {
    static let maximumFocusedCharacterCount = 2_000
    static let maximumContextCharacterCount = 1_200

    static func prepared(
        _ request: ContextExplanationRequest
    ) -> ContextExplanationRequest {
        let focused = normalized(request.focusedText)
        let rawContext = normalized(request.contextText ?? "")
        let context = boundedContext(
            rawContext,
            focusedText: focused,
            maximumCharacterCount: maximumContextCharacterCount
        )
        return ContextExplanationRequest(
            focusedText: focused,
            contextText: context,
            sourceLanguage: request.sourceLanguage,
            responseLanguage: request.responseLanguage,
            isWordLookup: request.isWordLookup,
            dictionaryEntry: request.dictionaryEntry.map { String($0.prefix(1_000)) },
            lens: request.lens
        )
    }

    static func semanticIdentity(
        for request: ContextExplanationRequest,
        providerIdentifier: String,
        providerVersion: String
    ) -> String {
        let request = prepared(request)
        return ([
            request.focusedText,
            request.contextText ?? "",
            request.sourceLanguage?.rawValue ?? "auto",
            request.responseLanguage.rawValue,
            request.isWordLookup ? "word-lookup-v2" : "sentence",
            request.isWordLookup ? (request.dictionaryEntry ?? "") : "",
            providerIdentifier,
            providerVersion,
        ]
        // Appended only for lens questions, so existing sentence-analysis
        // cache entries keep their keys.
        + (request.lens.map { [$0.cacheIdentity] } ?? [])
        ).joined(separator: "\u{001F}")
    }

    private static func boundedContext(
        _ context: String,
        focusedText: String,
        maximumCharacterCount: Int
    ) -> String? {
        guard !context.isEmpty,
              context != focusedText,
              maximumCharacterCount > 0
        else { return nil }
        guard context.count > maximumCharacterCount else { return context }

        if !focusedText.isEmpty,
           let focusRange = occurrenceClosestToMiddle(
               focusedText,
               in: context
           )
        {
            let remaining = max(maximumCharacterCount - focusedText.count, 0)
            let beforeBudget = remaining / 2
            let afterBudget = remaining - beforeBudget
            let before = context[..<focusRange.lowerBound]
            let after = context[focusRange.upperBound...]
            let clipped = String(before.suffix(beforeBudget))
                + focusedText
                + String(after.prefix(afterBudget))
            let normalized = normalized(clipped)
            return normalized.isEmpty || normalized == focusedText
                ? nil
                : normalized
        }

        let clipped = normalized(String(context.prefix(maximumCharacterCount)))
        return clipped.isEmpty || clipped == focusedText ? nil : clipped
    }

    private static func occurrenceClosestToMiddle(
        _ focusedText: String,
        in context: String
    ) -> Range<String.Index>? {
        var result: Range<String.Index>?
        var resultDistance = Int.max
        var searchStart = context.startIndex
        let midpoint = context.count / 2

        while searchStart < context.endIndex,
              let range = context.range(
                  of: focusedText,
                  range: searchStart ..< context.endIndex
              )
        {
            let offset = context.distance(
                from: context.startIndex,
                to: range.lowerBound
            )
            let distance = abs(offset - midpoint)
            if distance < resultDistance {
                result = range
                resultDistance = distance
            }
            searchStart = range.upperBound
        }
        return result
    }

    private static func normalized(_ text: String) -> String {
        text
            .replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
            .replacingOccurrences(
                of: #"\s+"#,
                with: " ",
                options: .regularExpression
            )
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .precomposedStringWithCanonicalMapping
    }
}

protocol ContextExplanationService: Sendable {
    func explain(
        _ request: ContextExplanationRequest
    ) async throws -> ContextExplanationResult
}
