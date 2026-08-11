import XCTest
@testable import JerreaderUnified

final class MockServicesTests: XCTestCase {
    func testJapaneseTranslationReturnsKnownResult() async throws {
        let service = MockTranslationService()

        let result = try await service.translate(
            text: "彼は静かに本を閉じ、窓の外を見た。",
            sourceLanguage: .japanese,
            targetLanguage: .simplifiedChinese
        )

        XCTAssertEqual(result.translatedText, "他静静地合上书，望向窗外。")
        XCTAssertEqual(result.sourceLanguage, .japanese)
        XCTAssertEqual(result.providerIdentifier, "mock")
    }

    func testTranslationRejectsEmptyText() async {
        let service = MockTranslationService()

        do {
            _ = try await service.translate(
                text: "   ",
                sourceLanguage: .english,
                targetLanguage: .simplifiedChinese
            )
            XCTFail("Expected empty text to throw")
        } catch {
            XCTAssertEqual(error as? ServiceError, .emptyText)
        }
    }

    func testJapaneseLookupReturnsLemmaAndReading() async throws {
        let service = MockLexicalLookupService()

        let result = try await service.lookup(
            word: "食べました",
            sentenceContext: "昨日、家でご飯を食べました。",
            language: .japanese
        )

        XCTAssertEqual(result.lemma, "食べる")
        XCTAssertEqual(result.reading, "たべました")
        XCTAssertEqual(result.definitions, ["吃", "食用"])
    }

    func testEnglishLookupReturnsBaseForm() async throws {
        let service = MockLexicalLookupService()

        let result = try await service.lookup(
            word: "went",
            sentenceContext: "She went home before sunset.",
            language: .english
        )

        XCTAssertEqual(result.lemma, "go")
        XCTAssertNil(result.reading)
    }
}

