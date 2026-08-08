import SwiftData
import XCTest
@testable import Jerreader

@MainActor
final class WordLookupStoreTests: XCTestCase {
    func testRepeatedLookupUpdatesOneRecordAndKeepsFavorite() throws {
        let container = try makeContainer()
        let context = container.mainContext
        let firstDate = Date(timeIntervalSince1970: 100)
        let secondDate = Date(timeIntervalSince1970: 200)

        let first = try WordLookupStore.record(
            englishExplanation(surfaceForm: "went", lemma: "go"),
            at: firstDate,
            in: context
        )
        try WordLookupStore.setFavorite(true, for: first, in: context)

        let second = try WordLookupStore.record(
            englishExplanation(surfaceForm: "go", lemma: "go"),
            at: secondDate,
            in: context
        )

        let records = try context.fetch(FetchDescriptor<WordLookupRecord>())
        XCTAssertEqual(records.count, 1)
        XCTAssertEqual(second.id, first.id)
        XCTAssertEqual(second.surfaceForm, "go")
        XCTAssertEqual(second.lookupCount, 2)
        XCTAssertEqual(second.lastLookedUpAt, secondDate)
        XCTAssertTrue(second.isFavorite)
        XCTAssertTrue(second.isInHistory)
    }

    func testClearHistoryPreservesFavoritesAndDeletesOtherRecords() throws {
        let container = try makeContainer()
        let context = container.mainContext

        let favorite = try WordLookupStore.record(
            englishExplanation(surfaceForm: "went", lemma: "go"),
            in: context
        )
        try WordLookupStore.setFavorite(true, for: favorite, in: context)

        _ = try WordLookupStore.record(
            WordExplanation(
                surfaceForm: "食べました",
                lemma: "食べる",
                reading: "たべました",
                language: .japanese,
                partOfSpeech: "动词",
                definitions: ["吃", "食用"],
                usageNote: nil,
                sentenceContext: nil
            ),
            in: context
        )

        let beforeClear = try context.fetch(FetchDescriptor<WordLookupRecord>())
        try WordLookupStore.clearHistory(beforeClear, in: context)

        let remaining = try context.fetch(FetchDescriptor<WordLookupRecord>())
        XCTAssertEqual(remaining.count, 1)
        XCTAssertEqual(remaining.first?.id, favorite.id)
        XCTAssertEqual(remaining.first?.isFavorite, true)
        XCTAssertEqual(remaining.first?.isInHistory, false)
    }

    func testRemovingLastFavoriteDeletesRecordThatIsNotInHistory() throws {
        let container = try makeContainer()
        let context = container.mainContext
        let record = try WordLookupStore.record(
            englishExplanation(surfaceForm: "went", lemma: "go"),
            in: context
        )
        try WordLookupStore.setFavorite(true, for: record, in: context)
        try WordLookupStore.removeFromHistory(record, in: context)

        var records = try context.fetch(FetchDescriptor<WordLookupRecord>())
        XCTAssertEqual(records.count, 1)
        XCTAssertFalse(records[0].isInHistory)

        try WordLookupStore.setFavorite(false, for: records[0], in: context)
        records = try context.fetch(FetchDescriptor<WordLookupRecord>())
        XCTAssertTrue(records.isEmpty)
    }

    func testContextlessLookupClearsPreviousContextAndBookSource() throws {
        let container = try makeContainer()
        let context = container.mainContext
        let sourceBookID = UUID()

        let first = try WordLookupStore.record(
            englishExplanation(
                surfaceForm: "went",
                lemma: "go",
                sentenceContext: "She went home."
            ),
            sourceBookID: sourceBookID,
            sourceBookTitle: "Example Book",
            in: context
        )
        XCTAssertEqual(first.sentenceContext, "She went home.")
        XCTAssertEqual(first.sourceBookID, sourceBookID)

        let updated = try WordLookupStore.record(
            englishExplanation(
                surfaceForm: "go",
                lemma: "go",
                sentenceContext: nil
            ),
            in: context
        )

        XCTAssertNil(updated.sentenceContext)
        XCTAssertNil(updated.sourceBookID)
        XCTAssertNil(updated.sourceBookTitle)
    }

    func testFavoriteLookupMatchesInflectedSurfaceFormAndLemma() throws {
        let container = try makeContainer()
        let context = container.mainContext
        let record = try WordLookupStore.record(
            WordExplanation(
                surfaceForm: "読みました",
                lemma: "読む",
                reading: "よみました",
                language: .japanese,
                partOfSpeech: "动词",
                definitions: ["读"],
                inflectionNote: "礼貌体过去式",
                examples: [
                    WordExample(sourceText: "本を読みました。", translatedText: "读了书。"),
                ],
                usageNote: nil,
                sentenceContext: "本を読みました。"
            ),
            in: context
        )
        try WordLookupStore.setFavorite(true, for: record, in: context)

        XCTAssertTrue(
            WordLookupStore.isFavorite(
                word: "読みました",
                language: .japanese,
                in: context
            )
        )
        XCTAssertTrue(
            WordLookupStore.isFavorite(
                word: "読む",
                language: .japanese,
                in: context
            )
        )
        XCTAssertEqual(record.examples.first?.translatedText, "读了书。")
    }

    func testLookupKeyPreservesDiacriticsAndCanonicalizesUnicode() throws {
        let container = try makeContainer()
        let context = container.mainContext
        let resume = englishExplanation(
            surfaceForm: "resume",
            lemma: nil,
            definitions: ["继续"]
        )
        let accentedResume = englishExplanation(
            surfaceForm: "résumé",
            lemma: nil,
            definitions: ["简历"]
        )
        let decomposedResume = englishExplanation(
            surfaceForm: "re\u{301}sume\u{301}",
            lemma: nil,
            definitions: ["简历"]
        )

        XCTAssertNotEqual(
            WordLookupRecord.makeLookupKey(for: resume),
            WordLookupRecord.makeLookupKey(for: accentedResume)
        )
        XCTAssertEqual(
            WordLookupRecord.makeLookupKey(for: accentedResume),
            WordLookupRecord.makeLookupKey(for: decomposedResume)
        )

        _ = try WordLookupStore.record(resume, in: context)
        _ = try WordLookupStore.record(accentedResume, in: context)
        let records = try context.fetch(FetchDescriptor<WordLookupRecord>())
        XCTAssertEqual(records.count, 2)
    }

    func testLearningExportProducesCSVMarkdownAndAnkiFiles() throws {
        let entry = LearningExportEntry(
            kind: .word,
            front: "quiet, night",
            back: "安静的夜晚",
            reading: nil,
            context: "The night was quiet.",
            language: "英语",
            bookTitle: "Example",
            tags: ["文学", "chapter-1"],
            date: Date(timeIntervalSince1970: 1_000)
        )

        let csvData = LearningExportService.data(entries: [entry], format: .csv)
        XCTAssertEqual(Array(csvData.prefix(3)), [0xEF, 0xBB, 0xBF])
        let csv = try XCTUnwrap(String(data: csvData.dropFirst(3), encoding: .utf8))
        XCTAssertTrue(csv.contains("\"quiet, night\""))
        XCTAssertTrue(csv.contains("Example"))

        let markdown = LearningExportService.markdown([entry])
        XCTAssertTrue(markdown.contains("## 生词本"))
        XCTAssertTrue(markdown.contains("来源：《Example》"))

        let anki = LearningExportService.anki([entry])
        XCTAssertTrue(anki.contains("#separator:tab"))
        XCTAssertTrue(anki.contains("quiet, night\t安静的夜晚"))
        XCTAssertTrue(anki.contains("Jerreader 生词 文学 chapter-1"))
    }

    private func makeContainer() throws -> ModelContainer {
        let configuration = ModelConfiguration(isStoredInMemoryOnly: true)
        return try ModelContainer(
            for: WordLookupRecord.self,
            configurations: configuration
        )
    }

    private func englishExplanation(
        surfaceForm: String,
        lemma: String?,
        definitions: [String] = ["去", "前往"],
        sentenceContext: String? = "She went home."
    ) -> WordExplanation {
        WordExplanation(
            surfaceForm: surfaceForm,
            lemma: lemma,
            reading: nil,
            language: .english,
            partOfSpeech: "动词",
            definitions: definitions,
            usageNote: "表示移动。",
            sentenceContext: sentenceContext
        )
    }
}
