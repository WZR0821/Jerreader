import Foundation
import XCTest
@testable import Jerreader

final class WiktionaryServiceTests: XCTestCase {
    func testJapaneseParserExtractsReadingPartOfSpeechAndRealDefinitions() {
        let wikitext = """
        ==日語==
        ===發音===
        {{ja-pron|たべる|acc=2}}
        ===動詞===
        # [[吃]]
        #: {{ja-usex|ご飯を食べる|ごはんをたべる|吃飯}}
        # [[維持]][[生計]]，[[過日子]]
        # {{lb|ja|古舊|humble}} [[吃喝]]，[[飲食]]
        ==法语==
        # 不应该读到
        """

        let entry = WiktionaryEntryParser.parse(
            wikitext: wikitext,
            language: .japanese
        )

        XCTAssertEqual(entry?.reading, "たべる")
        XCTAssertEqual(entry?.partOfSpeech, "动词")
        XCTAssertEqual(entry?.definitions, ["吃", "維持生計，過日子", "吃喝，飲食"])
        XCTAssertEqual(
            entry?.examples,
            [
                WordExample(
                    sourceText: "ご飯を食べる",
                    translatedText: "吃飯",
                    sourceLabel: "词典例句"
                ),
            ]
        )
    }

    func testEnglishParserExtractsIPAAndStopsAtNextLanguage() {
        let wikitext = """
        ==英语==
        ===發音===
        * {{IPA|en|/ɡoʊ/}}
        ===动词===
        #[[去]]
        #[[经过]]、[[穿过]]
        ==中文==
        #[[跳转]]
        """

        let entry = WiktionaryEntryParser.parse(
            wikitext: wikitext,
            language: .english
        )

        XCTAssertEqual(entry?.reading, "/ɡoʊ/")
        XCTAssertEqual(entry?.partOfSpeech, "动词")
        XCTAssertEqual(entry?.definitions, ["去", "经过、穿过"])
    }

    func testLookupUsesEnglishLemmaWhenInflectedPageHasNoDefinition() async throws {
        let client = StubDictionaryHTTPClient(responses: [
            "went": response(title: "went", wikitext: "==英语==\n===发音===\n* {{IPA|en|/wɛnt/}}"),
            "go": response(title: "go", wikitext: "==英语==\n===动词===\n#[[去]]\n#[[前往]]"),
        ])
        let service = WiktionaryLexicalLookupService(client: client)

        let result = try await service.lookup(
            word: "went",
            sentenceContext: "She went home.",
            language: .english
        )

        XCTAssertEqual(result.surfaceForm, "went")
        XCTAssertEqual(result.lemma, "go")
        XCTAssertEqual(result.definitions, ["去", "前往"])
        let requestedTitles = await client.requestedTitles()
        XCTAssertEqual(requestedTitles, ["went", "go"])
    }

    func testJapaneseCandidatesCoverIchidanAndGodanPoliteForms() {
        XCTAssertEqual(
            WiktionaryLexicalLookupService.lookupCandidates(
                for: "食べました",
                language: .japanese
            ),
            ["食べました", "食べる"]
        )
        XCTAssertEqual(
            WiktionaryLexicalLookupService.lookupCandidates(
                for: "読みました",
                language: .japanese
            ),
            ["読みました", "読む", "読みる"]
        )
    }

    func testJapaneseInflectionDescriptionNamesFormAndResolvedLemma() {
        XCTAssertEqual(
            JapaneseMorphologyAnalyzer.inflectionNote(
                for: "読みました",
                resolvedLemma: "読む"
            ),
            "「〜ました」礼貌体的过去式；词典形为「読む」。"
        )
    }

    func testTransportFailureMapsToRetryableServiceError() async {
        let service = WiktionaryLexicalLookupService(client: FailingDictionaryHTTPClient())

        do {
            _ = try await service.lookup(
                word: "book",
                sentenceContext: nil,
                language: .english
            )
            XCTFail("Expected transport failure")
        } catch {
            XCTAssertEqual(error as? ServiceError, .temporarilyUnavailable)
        }
    }

    private func response(title: String, wikitext: String) -> Data {
        try! JSONSerialization.data(withJSONObject: [
            "parse": [
                "title": title,
                "wikitext": wikitext,
            ],
        ])
    }
}

private actor StubDictionaryHTTPClient: DictionaryHTTPClient {
    private let responses: [String: Data]
    private var requests: [String] = []

    init(responses: [String: Data]) {
        self.responses = responses
    }

    func data(for request: URLRequest) async throws -> Data {
        let title = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?
            .queryItems?
            .first { $0.name == "page" }?
            .value ?? ""
        requests.append(title)
        return responses[title] ?? Data(#"{"error":{"code":"missingtitle"}}"#.utf8)
    }

    func requestedTitles() -> [String] {
        requests
    }
}

private struct FailingDictionaryHTTPClient: DictionaryHTTPClient {
    func data(for request: URLRequest) async throws -> Data {
        throw URLError(.notConnectedToInternet)
    }
}
