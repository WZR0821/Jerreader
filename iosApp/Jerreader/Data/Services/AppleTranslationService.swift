import Foundation
@preconcurrency import Translation

@MainActor
final class AppleTranslationService: TranslationService {
    private let session: TranslationSession

    init(session: TranslationSession) {
        self.session = session
    }

    func translate(
        text: String,
        sourceLanguage: LanguageCode?,
        targetLanguage: LanguageCode
    ) async throws -> TranslationResult {
        let sourceText = TranslationCacheStore.normalizedText(text)
        guard !sourceText.isEmpty else {
            throw ServiceError.emptyText
        }
        guard sourceText.count <= 2_000 else {
            throw ServiceError.textTooLong
        }
        guard let sourceLanguage,
              LanguageCode.allCases.contains(sourceLanguage),
              LanguageCode.allCases.contains(targetLanguage),
              sourceLanguage != targetLanguage
        else {
            throw ServiceError.unsupportedLanguage
        }

        do {
            do {
                try await session.prepareTranslation()
            } catch is CancellationError {
                // While the request is still active, cancellation at this stage
                // represents dismissal of the system language download prompt.
                throw ServiceError.languageDownloadDeclined
            }
            let response = try await session.translate(sourceText)
            guard let translatedText = TranslationOutputPolicy.displayText(
                response.targetText
            ) else {
                throw ServiceError.resultNotFound
            }

            return TranslationResult(
                sourceText: sourceText,
                translatedText: translatedText,
                sourceLanguage: sourceLanguage,
                targetLanguage: targetLanguage,
                providerIdentifier: TranslationCacheStore.appleProviderIdentifier,
                providerVersion: TranslationCacheStore.appleProviderVersion,
                isFromCache: false
            )
        } catch is CancellationError {
            throw CancellationError()
        } catch let serviceError as ServiceError {
            throw serviceError
        } catch {
            if TranslationError.unsupportedSourceLanguage ~= error
                || TranslationError.unsupportedTargetLanguage ~= error
                || TranslationError.unsupportedLanguagePairing ~= error
            {
                throw ServiceError.unsupportedLanguage
            }
            if TranslationError.unableToIdentifyLanguage ~= error {
                throw ServiceError.unsupportedLanguage
            }
            if TranslationError.nothingToTranslate ~= error {
                throw ServiceError.emptyText
            }
            throw ServiceError.translationUnavailable
        }
    }
}

extension LanguageCode {
    var localeLanguage: Locale.Language {
        Locale.Language(identifier: rawValue)
    }
}
