import Foundation

struct TranslationResult: Equatable, Sendable {
    let sourceText: String
    let translatedText: String
    let sourceLanguage: LanguageCode
    let targetLanguage: LanguageCode
    let providerIdentifier: String
    let providerVersion: String
    let isFromCache: Bool
}

/// Rejects provider responses which technically contain Unicode scalars but
/// render as an empty card (for example zero-width spaces, bidi controls or a
/// stale cache row containing only whitespace).
enum TranslationOutputPolicy {
    static func displayText(_ rawValue: String) -> String? {
        let value = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
        guard value.unicodeScalars.contains(where: isVisible) else {
            return nil
        }
        return value
    }

    static func validated(_ result: TranslationResult) -> TranslationResult? {
        guard let translatedText = displayText(result.translatedText) else {
            return nil
        }
        guard translatedText != result.translatedText else {
            return result
        }
        return TranslationResult(
            sourceText: result.sourceText,
            translatedText: translatedText,
            sourceLanguage: result.sourceLanguage,
            targetLanguage: result.targetLanguage,
            providerIdentifier: result.providerIdentifier,
            providerVersion: result.providerVersion,
            isFromCache: result.isFromCache
        )
    }

    private static func isVisible(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.properties.generalCategory {
        case .control, .format, .lineSeparator, .paragraphSeparator,
             .spaceSeparator, .nonspacingMark, .spacingMark, .enclosingMark:
            return false
        default:
            return true
        }
    }
}
