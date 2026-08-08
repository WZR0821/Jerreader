import Foundation

struct ContextExplanationRequest: Equatable, Sendable {
    let focusedText: String
    let contextText: String?
    let sourceLanguage: LanguageCode?
    let responseLanguage: LanguageCode
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
            responseLanguage: request.responseLanguage
        )
    }

    static func semanticIdentity(
        for request: ContextExplanationRequest,
        providerIdentifier: String,
        providerVersion: String
    ) -> String {
        let request = prepared(request)
        return [
            request.focusedText,
            request.contextText ?? "",
            request.sourceLanguage?.rawValue ?? "auto",
            request.responseLanguage.rawValue,
            providerIdentifier,
            providerVersion,
        ].joined(separator: "\u{001F}")
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
