import SwiftData
import XCTest
@testable import Jerreader

@MainActor
final class TranslationFavoriteStoreTests: XCTestCase {
    func testFavoriteRoundTripPersistsTranslationContext() throws {
        let container = try makeContainer()
        let store = TranslationFavoriteStore(modelContext: container.mainContext)
        let bookID = UUID()
        let result = makeResult(translatedText: "夜色很安静。")

        XCTAssertFalse(
            store.isFavorite(
                bookID: bookID,
                sourceText: result.sourceText,
                sourceLanguage: .english,
                targetLanguage: .simplifiedChinese
            )
        )
        XCTAssertTrue(
            try store.setFavorite(
                true,
                bookID: bookID,
                bookTitle: "Quiet Night",
                locatorJSON: #"{"href":"chapter.xhtml"}"#,
                result: result
            )
        )

        let records = try container.mainContext.fetch(
            FetchDescriptor<TranslationFavoriteRecord>()
        )
        let record = try XCTUnwrap(records.first)
        XCTAssertEqual(records.count, 1)
        XCTAssertEqual(record.sourceText, result.sourceText)
        XCTAssertEqual(record.translatedText, result.translatedText)
        XCTAssertEqual(record.sourceLanguage, .english)
        XCTAssertEqual(record.targetLanguage, .simplifiedChinese)
        XCTAssertEqual(record.bookID, bookID)
        XCTAssertEqual(record.bookTitle, "Quiet Night")
    }

    func testEquivalentFavoriteUpdatesInsteadOfDuplicatingAndCanBeRemoved() throws {
        let container = try makeContainer()
        let store = TranslationFavoriteStore(modelContext: container.mainContext)
        let bookID = UUID()

        _ = try store.setFavorite(
            true,
            bookID: bookID,
            bookTitle: "Book",
            locatorJSON: nil,
            result: makeResult(sourceText: "  The night was quiet. ", translatedText: "旧译文")
        )
        _ = try store.setFavorite(
            true,
            bookID: bookID,
            bookTitle: "Book",
            locatorJSON: nil,
            result: makeResult(sourceText: "The   night was quiet.", translatedText: "新译文")
        )

        var records = try container.mainContext.fetch(
            FetchDescriptor<TranslationFavoriteRecord>()
        )
        XCTAssertEqual(records.count, 1)
        XCTAssertEqual(records.first?.translatedText, "新译文")

        _ = try store.setFavorite(
            false,
            bookID: bookID,
            bookTitle: "Book",
            locatorJSON: nil,
            result: makeResult(sourceText: "The night was quiet.", translatedText: "新译文")
        )
        records = try container.mainContext.fetch(
            FetchDescriptor<TranslationFavoriteRecord>()
        )
        XCTAssertTrue(records.isEmpty)
    }

    func testFavoriteKeySeparatesBooksAndLanguagePairs() {
        let bookID = UUID()
        let base = TranslationFavoriteStore.favoriteKey(
            bookID: bookID,
            sourceText: "Text",
            sourceLanguage: .english,
            targetLanguage: .simplifiedChinese
        )
        let anotherBook = TranslationFavoriteStore.favoriteKey(
            bookID: UUID(),
            sourceText: "Text",
            sourceLanguage: .english,
            targetLanguage: .simplifiedChinese
        )
        let anotherTarget = TranslationFavoriteStore.favoriteKey(
            bookID: bookID,
            sourceText: "Text",
            sourceLanguage: .english,
            targetLanguage: .japanese
        )

        XCTAssertNotEqual(base, anotherBook)
        XCTAssertNotEqual(base, anotherTarget)
    }

    private func makeContainer() throws -> ModelContainer {
        try ModelContainer(
            for: TranslationFavoriteRecord.self,
            configurations: ModelConfiguration(isStoredInMemoryOnly: true)
        )
    }

    private func makeResult(
        sourceText: String = "The night was quiet.",
        translatedText: String
    ) -> TranslationResult {
        TranslationResult(
            sourceText: TranslationCacheStore.normalizedText(sourceText),
            translatedText: translatedText,
            sourceLanguage: .english,
            targetLanguage: .simplifiedChinese,
            providerIdentifier: TranslationCacheStore.appleProviderIdentifier,
            providerVersion: TranslationCacheStore.appleProviderVersion,
            isFromCache: false
        )
    }
}
