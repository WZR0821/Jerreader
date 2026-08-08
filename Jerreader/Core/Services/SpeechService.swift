import Foundation

/// Speech is intentionally kept behind a single product switch while the
/// interaction is redesigned. Keeping the service boundary and implementation
/// intact makes this a reversible product change and avoids touching
/// translation, selection, vocabulary or persistence code.
enum SpeechFeatureAvailability {
    static let isEnabled = false
}

@MainActor
protocol SpeechService: AnyObject {
    func speak(
        _ text: String,
        language: LanguageCode,
        rate: Double,
        onRangeChange: @escaping @MainActor (NSRange) -> Void,
        onFinish: @escaping @MainActor (Bool) -> Void
    ) async throws
    func stop()
}

extension SpeechService {
    func speak(_ text: String, language: LanguageCode) async throws {
        try await speak(
            text,
            language: language,
            rate: 1,
            onRangeChange: { _ in },
            onFinish: { _ in }
        )
    }
}
