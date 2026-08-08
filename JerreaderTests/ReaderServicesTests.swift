import AVFoundation
import CoreGraphics
import PDFKit
@preconcurrency import ReadiumShared
import SwiftData
@preconcurrency import WebKit
import XCTest
@testable import Jerreader

@MainActor
final class ReaderServicesTests: XCTestCase {
    func testNativeSelectionGestureGateBlocksTrailingTapWithoutBlockingLaterTap() {
        var gate = EPUBSelectionGestureGate()

        gate.noteNativeSelection(at: 100)

        XCTAssertTrue(gate.shouldSuppressTap(at: 100.9))
        XCTAssertTrue(gate.shouldSuppressTap(at: 101.5))
        XCTAssertFalse(gate.shouldSuppressTap(at: 101.501))
        XCTAssertNil(gate.suppressTapUntilUptime)
    }

    func testRepeatedNativeSelectionCallbacksExtendGestureGate() {
        var gate = EPUBSelectionGestureGate()

        gate.noteNativeSelection(at: 200)
        gate.noteNativeSelection(at: 201)

        XCTAssertTrue(gate.shouldSuppressTap(at: 202.4))
        XCTAssertFalse(gate.shouldSuppressTap(at: 202.501))
    }

    func testLongPressSelectionDoesNotRecognizeAlongsidePagePan() {
        XCTAssertFalse(
            EPUBLongPressPageTurnPolicy.allowsSimultaneousRecognition(
                otherGestureIsPagePan: true
            )
        )
        XCTAssertTrue(
            EPUBLongPressPageTurnPolicy.allowsSimultaneousRecognition(
                otherGestureIsPagePan: false
            )
        )
    }

    func testFollowReadingRangeNormalizesAndClipsToSelectedText() throws {
        let highlight = try XCTUnwrap(
            ReaderSpeechHighlight(
                utf16Range: NSRange(location: 5, length: 4),
                totalLength: 10
            )
        )

        XCTAssertEqual(highlight.lowerBound, 0.5, accuracy: 0.0001)
        XCTAssertEqual(highlight.upperBound, 0.9, accuracy: 0.0001)
        XCTAssertNil(
            ReaderSpeechHighlight(
                utf16Range: NSRange(location: 0, length: 0),
                totalLength: 10
            )
        )
    }

    func testFollowReadingRateIsMonotonicAndClamped() {
        let slow = SystemSpeechService.utteranceRate(multiplier: 0.1)
        let normal = SystemSpeechService.utteranceRate(multiplier: 1)
        let fast = SystemSpeechService.utteranceRate(multiplier: 9)

        XCTAssertGreaterThan(normal, slow)
        XCTAssertGreaterThan(fast, normal)
        XCTAssertGreaterThanOrEqual(slow, AVSpeechUtteranceMinimumSpeechRate)
        XCTAssertLessThanOrEqual(fast, AVSpeechUtteranceMaximumSpeechRate)
    }

    func testFollowReadingGeometryUsesSameSelectionOverlayAcrossFormats() throws {
        let highlight = try XCTUnwrap(
            ReaderSpeechHighlight(
                utf16Range: NSRange(location: 2, length: 6),
                totalLength: 10
            )
        )
        let rectangles = ReaderSpeechHighlightGeometry.rectangles(
            in: [
                CGRect(x: 10, y: 20, width: 100, height: 18),
                CGRect(x: 10, y: 44, width: 100, height: 18),
            ],
            highlight: highlight
        )

        XCTAssertEqual(rectangles.count, 2)
        XCTAssertEqual(rectangles[0].minX, 50, accuracy: 0.01)
        XCTAssertEqual(rectangles[0].maxX, 110, accuracy: 0.01)
        XCTAssertEqual(rectangles[1].minX, 10, accuracy: 0.01)
        XCTAssertEqual(rectangles[1].maxX, 70, accuracy: 0.01)
    }

    func testSelectionViewportConversionCompensatesNegativeVerticalContentOffset() {
        let destination = UIView(frame: CGRect(x: 0, y: 0, width: 402, height: 874))
        let webView = WKWebView(frame: destination.bounds)
        destination.addSubview(webView)
        webView.scrollView.contentInset.top = 62
        webView.scrollView.contentOffset = CGPoint(x: 14_683, y: -62)

        let converted = EPUBSelectionBridge.convertViewportRect(
            CGRect(x: 240, y: 152.9, width: 17, height: 16),
            from: webView,
            to: destination
        )

        XCTAssertEqual(converted.minX, 240, accuracy: 0.01)
        XCTAssertEqual(converted.minY, 214.9, accuracy: 0.01)
        XCTAssertEqual(converted.width, 17, accuracy: 0.01)
        XCTAssertEqual(converted.height, 16, accuracy: 0.01)

        let domPoint = EPUBSelectionBridge.domViewportPoint(
            CGPoint(x: converted.midX, y: converted.midY),
            in: webView
        )
        XCTAssertEqual(domPoint.x, 248.5, accuracy: 0.01)
        XCTAssertEqual(domPoint.y, 160.9, accuracy: 0.01)
    }

    func testTranslationTextNormalizationTrimsAndCollapsesWhitespace() {
        XCTAssertEqual(
            TranslationCacheStore.normalizedText("  The\r\n evening\tlight   faded. \n"),
            "The evening light faded."
        )
        XCTAssertEqual(TranslationCacheStore.normalizedText("\n\t  \r"), "")
    }

    func testTranslationOutputRejectsUnicodeWhichRendersAsABlankCard() {
        XCTAssertNil(
            TranslationOutputPolicy.displayText(
                " \n\u{200B}\u{200E}\u{2060}\u{FEFF}\t"
            )
        )
        XCTAssertEqual(
            TranslationOutputPolicy.displayText("\n  可以看见的译文。 \n"),
            "可以看见的译文。"
        )
        XCTAssertNil(
            ContextExplanationOutputPolicy.validated(
                ContextExplanationResult(
                    explanation: "\u{200B}\u{2060}",
                    providerIdentifier: "test",
                    providerVersion: "v1"
                )
            )
        )
    }

    func testInvisibleLegacyCacheRowIsEvictedInsteadOfDisplayed() throws {
        let container = try makeCacheContainer()
        let sourceText = "Do not show an empty cache result."
        let key = TranslationCacheStore.cacheKey(
            text: sourceText,
            sourceLanguage: .english,
            targetLanguage: .simplifiedChinese
        )
        container.mainContext.insert(
            TranslationCacheRecord(
                cacheKey: key,
                sourceText: sourceText,
                translatedText: "\u{200B}\u{2060}",
                sourceLanguage: .english,
                targetLanguage: .simplifiedChinese,
                providerIdentifier: TranslationCacheStore.appleProviderIdentifier,
                providerVersion: TranslationCacheStore.appleProviderVersion
            )
        )
        try container.mainContext.save()

        let store = TranslationCacheStore(modelContext: container.mainContext)
        XCTAssertNil(
            store.result(
                for: sourceText,
                sourceLanguage: .english,
                targetLanguage: .simplifiedChinese
            )
        )
        XCTAssertTrue(
            try container.mainContext.fetch(
                FetchDescriptor<TranslationCacheRecord>()
            ).isEmpty
        )
    }

    func testTranslationCacheRoundTripMarksResultAsCached() throws {
        let container = try makeCacheContainer()
        let store = TranslationCacheStore(modelContext: container.mainContext)
        let original = makeResult(
            sourceText: "The evening light faded behind the hills.",
            translatedText: "暮色渐渐隐没在群山之后。"
        )

        XCTAssertTrue(store.store(original))
        let cached = store.result(
            for: "  The evening\r\nlight faded behind the hills.\r\n",
            sourceLanguage: .english,
            targetLanguage: .simplifiedChinese
        )

        XCTAssertEqual(cached?.translatedText, original.translatedText)
        XCTAssertEqual(cached?.sourceText, original.sourceText)
        XCTAssertEqual(cached?.isFromCache, true)

        let records = try container.mainContext.fetch(
            FetchDescriptor<TranslationCacheRecord>()
        )
        XCTAssertEqual(records.count, 1)
        XCTAssertEqual(records.first?.accessCount, 2)
    }

    func testCacheKeyCanonicalizesUnicodeAndWhitespaceButKeepsLanguagePair() {
        let composed = TranslationCacheStore.cacheKey(
            text: " café\nau lait ",
            sourceLanguage: .english,
            targetLanguage: .simplifiedChinese
        )
        let decomposed = TranslationCacheStore.cacheKey(
            text: "cafe\u{301}   au\tlait",
            sourceLanguage: .english,
            targetLanguage: .simplifiedChinese
        )
        let japanese = TranslationCacheStore.cacheKey(
            text: "café au lait",
            sourceLanguage: .japanese,
            targetLanguage: .simplifiedChinese
        )

        XCTAssertEqual(composed, decomposed)
        XCTAssertNotEqual(composed, japanese)
    }

    func testTranslationCacheSeparatesTheSameSentenceByParagraphContext() throws {
        let container = try makeCacheContainer()
        let store = TranslationCacheStore(modelContext: container.mainContext)
        let result = makeResult(sourceText: "It was light.")

        XCTAssertTrue(store.store(result, contextText: "She lifted the box. It was light."))
        XCTAssertNotNil(
            store.result(
                for: result.sourceText,
                contextText: "She lifted the box. It was light.",
                sourceLanguage: .english,
                targetLanguage: .simplifiedChinese
            )
        )
        XCTAssertNil(
            store.result(
                for: result.sourceText,
                contextText: "The lamp came on. It was light.",
                sourceLanguage: .english,
                targetLanguage: .simplifiedChinese
            )
        )
    }

    func testReaderLanguageDetectorPrioritizesSelectedTextThenUsesMetadataFallback() {
        XCTAssertEqual(
            ReaderLanguageDetector.detect(text: "ambiguous", bookLanguage: "ja-JP"),
            .english
        )
        XCTAssertEqual(
            ReaderLanguageDetector.detect(text: "静かな夜でした。", bookLanguage: nil),
            .japanese
        )
        XCTAssertEqual(
            ReaderLanguageDetector.detect(text: "It was a quiet night.", bookLanguage: nil),
            .english
        )
        XCTAssertEqual(
            ReaderLanguageDetector.detect(text: "这是一个安静的夜晚。", bookLanguage: "zh-Hans"),
            .simplifiedChinese
        )
        XCTAssertNil(ReaderLanguageDetector.detect(text: "12345。", bookLanguage: nil))
    }

    func testLexicalLookupLanguageResolverSupportsAutomaticAndManualChoices() {
        XCTAssertEqual(
            LexicalLookupLanguageResolver.resolve(
                choice: .automatic,
                word: "食べました",
                sentenceContext: nil
            ),
            .japanese
        )
        XCTAssertEqual(
            LexicalLookupLanguageResolver.resolve(
                choice: .automatic,
                word: "went",
                sentenceContext: nil
            ),
            .english
        )
        XCTAssertEqual(
            LexicalLookupLanguageResolver.resolve(
                choice: .automatic,
                word: "勉強",
                sentenceContext: "昨日は家で勉強しました。"
            ),
            .japanese
        )
        XCTAssertEqual(
            LexicalLookupLanguageResolver.resolve(
                choice: .automatic,
                word: "勉強",
                sentenceContext: nil
            ),
            .japanese
        )
        XCTAssertEqual(
            LexicalLookupLanguageResolver.resolve(
                choice: .english,
                word: "食べる",
                sentenceContext: nil
            ),
            .english
        )
    }

    func testTranslationOverlayPlacementAvoidsSelectionAndViewportEdges() {
        let below = ReaderTranslationOverlayPlacement.centerY(
            selectionFrame: CGRect(x: 20, y: 80, width: 120, height: 24),
            viewportHeight: 800,
            cardHeight: 140,
            topInset: 60,
            bottomInset: 40
        )
        let above = ReaderTranslationOverlayPlacement.centerY(
            selectionFrame: CGRect(x: 20, y: 680, width: 120, height: 24),
            viewportHeight: 800,
            cardHeight: 140,
            topInset: 60,
            bottomInset: 40
        )
        let fallback = ReaderTranslationOverlayPlacement.centerY(
            selectionFrame: nil,
            viewportHeight: 800,
            cardHeight: 140,
            topInset: 60,
            bottomInset: 40
        )

        XCTAssertGreaterThan(below, 104)
        XCTAssertLessThan(above, 680)
        XCTAssertEqual(fallback, 130)
        XCTAssertLessThanOrEqual(above, 690)
    }

    func testOnlyShortTranslationAccentGetsACompactHeightCap() {
        XCTAssertEqual(ReaderTranslationAccentMetric.maximumHeight(for: "好。"), 22)
        XCTAssertNil(
            ReaderTranslationAccentMetric.maximumHeight(
                for: String(repeating: "译", count: 60)
            )
        )
    }

    func testTranslationContentAlwaysKeepsVisibleHeightAndCapsOverflow() {
        XCTAssertEqual(
            ReaderTranslationContentMetric.height(measured: 0, maximum: 180),
            54
        )
        XCTAssertEqual(
            ReaderTranslationContentMetric.height(measured: 92, maximum: 180),
            92
        )
        XCTAssertEqual(
            ReaderTranslationContentMetric.height(measured: 320, maximum: 180),
            180
        )
        // A paragraph may opt into a larger maximum, but a four-line result
        // must still hug its actual content instead of producing blank space.
        XCTAssertEqual(
            ReaderTranslationContentMetric.height(measured: 92, maximum: 260),
            92
        )
    }

    func testLongParagraphTranslationPrefersExpandedMaximumViewport() {
        XCTAssertTrue(
            ReaderTranslationViewportPolicy.prefersExpandedMaximumHeight(
                sourceCharacterCount: 210,
                translatedCharacterCount: 120,
                isParagraph: true
            )
        )
        XCTAssertTrue(
            ReaderTranslationViewportPolicy.prefersExpandedMaximumHeight(
                sourceCharacterCount: 90,
                translatedCharacterCount: 42,
                isParagraph: true
            )
        )
        XCTAssertFalse(
            ReaderTranslationViewportPolicy.prefersExpandedMaximumHeight(
                sourceCharacterCount: 42,
                translatedCharacterCount: 24,
                isParagraph: false
            )
        )
    }

    func testStandaloneTranslatorResolvesAutomaticChineseEnglishAndJapanese() throws {
        let english = try StandaloneTranslationRequestPolicy.makeRequest(
            text: "The night was quiet.",
            sourceChoice: .automatic,
            targetLanguage: .simplifiedChinese,
            mode: .sentence
        )
        let japanese = try StandaloneTranslationRequestPolicy.makeRequest(
            text: "夜は静かだった。",
            sourceChoice: .automatic,
            targetLanguage: .english,
            mode: .sentence
        )
        let chinese = try StandaloneTranslationRequestPolicy.makeRequest(
            text: "夜晚很安静。",
            sourceChoice: .automatic,
            targetLanguage: .japanese,
            mode: .sentence
        )

        XCTAssertEqual(english.sourceLanguage, .english)
        XCTAssertEqual(japanese.sourceLanguage, .japanese)
        XCTAssertEqual(chinese.sourceLanguage, .simplifiedChinese)
    }

    func testStandaloneTranslatorRejectsSameDirectionAndAppliesModeLimits() {
        XCTAssertThrowsError(
            try StandaloneTranslationRequestPolicy.makeRequest(
                text: "hello",
                sourceChoice: .english,
                targetLanguage: .english,
                mode: .word
            )
        ) { error in
            XCTAssertEqual(
                error as? StandaloneTranslationValidationError,
                .sameLanguage
            )
        }

        XCTAssertThrowsError(
            try StandaloneTranslationRequestPolicy.makeRequest(
                text: String(repeating: "a", count: 81),
                sourceChoice: .english,
                targetLanguage: .japanese,
                mode: .word
            )
        ) { error in
            XCTAssertEqual(
                error as? StandaloneTranslationValidationError,
                .textTooLong(maximum: 80)
            )
        }

        XCTAssertNoThrow(
            try StandaloneTranslationRequestPolicy.makeRequest(
                text: String(repeating: "a", count: 81),
                sourceChoice: .english,
                targetLanguage: .japanese,
                mode: .sentence
            )
        )
    }

    func testStandaloneWordModeUsesConciseAIPrompt() {
        let prompt = StandaloneTranslationMode.word.translationPrompt(
            fallback: "sentence fallback"
        )

        XCTAssertTrue(prompt.contains("只输出译词"))
        XCTAssertFalse(prompt.contains("sentence fallback"))
        XCTAssertEqual(
            StandaloneTranslationMode.sentence.translationPrompt(
                fallback: "custom sentence prompt"
            ),
            "custom sentence prompt"
        )
    }

    func testStandaloneWordModeOnlyAddsDictionaryDetailsForJapaneseAndEnglish() {
        let japaneseWord = StandaloneTranslationRequest(
            id: UUID(),
            text: "食べました",
            sourceLanguage: .japanese,
            targetLanguage: .simplifiedChinese,
            mode: .word
        )
        let englishWord = StandaloneTranslationRequest(
            id: UUID(),
            text: "went",
            sourceLanguage: .english,
            targetLanguage: .simplifiedChinese,
            mode: .word
        )
        let chineseWord = StandaloneTranslationRequest(
            id: UUID(),
            text: "阅读",
            sourceLanguage: .simplifiedChinese,
            targetLanguage: .japanese,
            mode: .word
        )
        let japaneseSentence = StandaloneTranslationRequest(
            id: UUID(),
            text: "私は学生です。",
            sourceLanguage: .japanese,
            targetLanguage: .simplifiedChinese,
            mode: .sentence
        )

        XCTAssertTrue(StandaloneLexicalLookupPolicy.supports(japaneseWord))
        XCTAssertTrue(StandaloneLexicalLookupPolicy.supports(englishWord))
        XCTAssertFalse(StandaloneLexicalLookupPolicy.supports(chineseWord))
        XCTAssertFalse(StandaloneLexicalLookupPolicy.supports(japaneseSentence))
    }

    func testJapaneseFuriganaPreservesTextAndAnnotatesKanjiRuns() {
        let source = "私は学生です。食べました。"
        let segments = JapaneseFuriganaFormatter.segments(for: source)

        XCTAssertEqual(segments.map(\.text).joined(), source)
        XCTAssertTrue(
            segments.contains {
                $0.text == "私" && $0.reading?.isEmpty == false
            }
        )
        XCTAssertTrue(
            segments.contains {
                $0.text == "学生" && $0.reading?.isEmpty == false
            }
        )
        XCTAssertTrue(
            segments.contains {
                $0.text == "食" && $0.reading?.isEmpty == false
            }
        )
        XCTAssertFalse(
            segments.contains {
                $0.text.contains("です") && $0.reading != nil
            }
        )
    }

    func testProductionTimingAllowsLanguageDownloadAndLongerGrammarAnalysis() {
        XCTAssertGreaterThan(
            ReaderTranslationTiming.standard.appleRequestTimeout,
            .seconds(30)
        )
        XCTAssertGreaterThan(
            ReaderTranslationTiming.standard.contextExplanationRequestTimeout,
            ReaderTranslationTiming.standard.backendRequestTimeout
        )
    }

    func testReaderVocabularyCandidateAcceptsWordsButRejectsSentences() {
        XCTAssertEqual(
            ReaderVocabularyCandidate.term(from: "reader's", language: .english),
            "reader's"
        )
        XCTAssertEqual(
            ReaderVocabularyCandidate.term(from: "食べました", language: .japanese),
            "食べました"
        )
        XCTAssertEqual(
            ReaderVocabularyCandidate.term(from: "结构", language: .simplifiedChinese),
            "结构"
        )
        XCTAssertNil(
            ReaderVocabularyCandidate.term(
                from: "This is a sentence.",
                language: .english
            )
        )
        XCTAssertNil(
            ReaderVocabularyCandidate.term(
                from: "これは文です。",
                language: .japanese
            )
        )
    }

    func testTranslationOverlayPlacementTracksSelectionAndClampsHorizontally() {
        let cardSize = CGSize(width: 220, height: 120)
        let upperSelection = CGRect(x: 4, y: 90, width: 70, height: 28)
        let lowerSelection = CGRect(x: 300, y: 590, width: 80, height: 28)

        let upperPosition = ReaderTranslationOverlayPlacement.position(
            selectionFrame: upperSelection,
            viewportSize: CGSize(width: 390, height: 780),
            cardSize: cardSize,
            topInset: 60,
            bottomInset: 36
        )
        let lowerPosition = ReaderTranslationOverlayPlacement.position(
            selectionFrame: lowerSelection,
            viewportSize: CGSize(width: 390, height: 780),
            cardSize: cardSize,
            topInset: 60,
            bottomInset: 36
        )

        XCTAssertGreaterThan(upperPosition.y, upperSelection.maxY)
        XCTAssertLessThan(lowerPosition.y, lowerSelection.minY)
        XCTAssertEqual(upperPosition.x, 124)
        XCTAssertEqual(lowerPosition.x, 266)
        XCTAssertNotEqual(upperPosition, lowerPosition)
    }

    func testManuallyMovedTranslationOverlayRemainsInsideViewport() {
        let viewport = CGSize(width: 390, height: 780)
        let card = CGSize(width: 362, height: 160)
        let upperLeft = ReaderTranslationOverlayPlacement.clampedPosition(
            CGPoint(x: -500, y: -500),
            viewportSize: viewport,
            cardSize: card,
            topInset: 54,
            bottomInset: 34
        )
        let lowerRight = ReaderTranslationOverlayPlacement.clampedPosition(
            CGPoint(x: 900, y: 1_200),
            viewportSize: viewport,
            cardSize: card,
            topInset: 54,
            bottomInset: 34
        )

        XCTAssertEqual(upperLeft, CGPoint(x: 195, y: 134))
        XCTAssertEqual(lowerRight, CGPoint(x: 195, y: 666))
    }

    func testTranslationOverlayConvertsWindowCoordinatesIntoItsSafeAreaSpace() {
        let local = ReaderTranslationOverlayPlacement.localSelectionFrame(
            CGRect(x: 42, y: 318, width: 126, height: 36),
            relativeTo: CGRect(x: 0, y: 59, width: 390, height: 687)
        )

        XCTAssertEqual(local, CGRect(x: 42, y: 259, width: 126, height: 36))
        XCTAssertNil(
            ReaderTranslationOverlayPlacement.localSelectionFrame(
                nil,
                relativeTo: CGRect(x: 0, y: 59, width: 390, height: 687)
            )
        )
    }

    func testTranslationOverlayKeepsAStableSideAsItsContentHeightChanges() {
        let selection = CGRect(x: 120, y: 330, width: 130, height: 30)
        let compact = ReaderTranslationOverlayPlacement.layout(
            selectionFrame: selection,
            viewportSize: CGSize(width: 390, height: 780),
            cardSize: CGSize(width: 280, height: 72),
            topInset: 60,
            bottomInset: 36
        )
        let expanded = ReaderTranslationOverlayPlacement.layout(
            selectionFrame: selection,
            viewportSize: CGSize(width: 390, height: 780),
            cardSize: CGSize(width: 350, height: 180),
            topInset: 60,
            bottomInset: 36
        )

        XCTAssertEqual(compact.edge, expanded.edge)
        XCTAssertEqual(compact.maximumCardHeight, expanded.maximumCardHeight)
        XCTAssertEqual(compact.availableHeight, expanded.availableHeight)
    }

    func testTranslationOverlayUsesTheFreeRegionAwayFromTopAndBottomSelections() {
        let viewport = CGSize(width: 390, height: 780)
        let card = CGSize(width: 300, height: 76)
        let topSelection = CGRect(x: 105, y: 82, width: 180, height: 28)
        let bottomSelection = CGRect(x: 105, y: 676, width: 180, height: 28)
        let topLayout = ReaderTranslationOverlayPlacement.layout(
            selectionFrame: topSelection,
            viewportSize: viewport,
            cardSize: card,
            topInset: 60,
            bottomInset: 36
        )
        let bottomLayout = ReaderTranslationOverlayPlacement.layout(
            selectionFrame: bottomSelection,
            viewportSize: viewport,
            cardSize: card,
            topInset: 60,
            bottomInset: 36
        )

        XCTAssertEqual(topLayout.edge, .below)
        XCTAssertGreaterThanOrEqual(
            topLayout.position.y - card.height / 2,
            topSelection.maxY + 18
        )
        XCTAssertEqual(bottomLayout.edge, .above)
        XCTAssertLessThanOrEqual(
            bottomLayout.position.y + card.height / 2,
            bottomSelection.minY - 18
        )
    }

    func testVerticalTranslationOverlayUsesSideSpaceAndStaysAboveBottomInset() {
        let viewport = CGSize(width: 430, height: 800)
        let card = CGSize(width: 300, height: 246)
        let verticalSelection = CGRect(x: 370, y: 350, width: 24, height: 390)
        let layout = ReaderTranslationOverlayPlacement.layout(
            selectionFrame: verticalSelection,
            viewportSize: viewport,
            cardSize: card,
            topInset: 58,
            bottomInset: 40,
            horizontalInset: 16,
            gap: 24,
            minimumCardHeight: 108,
            preferredMaximumCardHeight: 246,
            prefersHorizontalAvoidance: true
        )
        let cardFrame = CGRect(
            x: layout.position.x - card.width / 2,
            y: layout.position.y - card.height / 2,
            width: card.width,
            height: card.height
        )

        XCTAssertEqual(layout.edge, .left)
        XCTAssertLessThanOrEqual(cardFrame.maxX, verticalSelection.minX - 24)
        XCTAssertGreaterThanOrEqual(cardFrame.minY, 58)
        XCTAssertLessThanOrEqual(cardFrame.maxY, viewport.height - 40)
    }

    func testVerticalTranslationOverlayShrinksIntoRealIPhoneSideSpace() {
        let viewport = CGSize(width: 390, height: 844)
        let preferredCard = CGSize(width: 248, height: 280)
        let verticalSelection = CGRect(x: 252, y: 150, width: 26, height: 540)
        let layout = ReaderTranslationOverlayPlacement.layout(
            selectionFrame: verticalSelection,
            viewportSize: viewport,
            cardSize: preferredCard,
            topInset: 58,
            bottomInset: 42,
            horizontalInset: 12,
            gap: 16,
            minimumCardHeight: 108,
            preferredMaximumCardHeight: 280,
            prefersHorizontalAvoidance: true,
            minimumHorizontalCardWidth: 188
        )
        let cardFrame = CGRect(
            x: layout.position.x - layout.cardWidth / 2,
            y: layout.position.y - preferredCard.height / 2,
            width: layout.cardWidth,
            height: preferredCard.height
        )

        XCTAssertEqual(layout.edge, .left)
        XCTAssertEqual(layout.cardWidth, 224)
        XCTAssertEqual(layout.maximumCardHeight, 280)
        XCTAssertLessThanOrEqual(cardFrame.maxX, verticalSelection.minX - 16)
        XCTAssertGreaterThanOrEqual(cardFrame.minY, 58)
        XCTAssertLessThanOrEqual(cardFrame.maxY, viewport.height - 42)
        XCTAssertFalse(cardFrame.intersects(verticalSelection))
    }

    func testVerticalSideAvoidanceDoesNotDependOnlyOnCachedMetadata() {
        let tallJapaneseSelection = CGRect(x: 260, y: 140, width: 28, height: 410)

        XCTAssertTrue(
            ReaderTranslationLayoutPolicy.usesVerticalSideAvoidance(
                isReflowable: true,
                preservesPublicationOrientation: true,
                publicationIsVertical: false,
                isJapaneseBook: false,
                selectionFrame: tallJapaneseSelection
            )
        )
        XCTAssertFalse(
            ReaderTranslationLayoutPolicy.usesVerticalSideAvoidance(
                isReflowable: true,
                preservesPublicationOrientation: true,
                publicationIsVertical: false,
                isJapaneseBook: true,
                selectionFrame: CGRect(x: 30, y: 160, width: 330, height: 180)
            )
        )
        XCTAssertEqual(
            ReaderTranslationLayoutPolicy.selectionGap(
                isParagraph: true,
                usesVerticalSideAvoidance: true
            ),
            12
        )
    }

    func testVerticalParagraphFloatingWindowUsesTappedColumnWhenWholeSelectionIsWide() {
        let viewport = CGSize(width: 390, height: 844)
        let wholeParagraph = CGRect(x: 42, y: 120, width: 306, height: 610)
        let tappedColumn = CGRect(x: 252, y: 260, width: 24, height: 280)
        let layout = ReaderTranslationOverlayPlacement.layout(
            selectionFrame: wholeParagraph,
            horizontalAvoidanceFrame: tappedColumn,
            viewportSize: viewport,
            cardSize: CGSize(width: 220, height: 300),
            topInset: 58,
            bottomInset: 42,
            horizontalInset: 12,
            gap: 12,
            minimumCardHeight: 108,
            preferredMaximumCardHeight: 300,
            prefersHorizontalAvoidance: true,
            minimumHorizontalCardWidth: 144
        )
        let floatingWindow = CGRect(
            x: layout.position.x - layout.cardWidth / 2,
            y: layout.position.y - layout.maximumCardHeight / 2,
            width: layout.cardWidth,
            height: layout.maximumCardHeight
        )

        XCTAssertEqual(layout.edge, .left)
        XCTAssertEqual(layout.cardWidth, 220)
        XCTAssertLessThanOrEqual(floatingWindow.maxX, tappedColumn.minX - 12)
        XCTAssertFalse(floatingWindow.intersects(tappedColumn))
        XCTAssertGreaterThanOrEqual(floatingWindow.minY, 58)
        XCTAssertLessThanOrEqual(floatingWindow.maxY, viewport.height - 42)
    }

    func testParagraphTranslationKeepsExpandedGapFromOriginalText() {
        let paragraph = CGRect(x: 36, y: 170, width: 318, height: 280)
        let card = CGSize(width: 334, height: 126)
        let layout = ReaderTranslationOverlayPlacement.layout(
            selectionFrame: paragraph,
            viewportSize: CGSize(width: 390, height: 844),
            cardSize: card,
            topInset: 58,
            bottomInset: 42,
            gap: 26,
            minimumCardHeight: 108,
            preferredMaximumCardHeight: 246
        )
        let cardFrame = CGRect(
            x: layout.position.x - card.width / 2,
            y: layout.position.y - card.height / 2,
            width: card.width,
            height: card.height
        )

        XCTAssertGreaterThanOrEqual(cardFrame.minY, paragraph.maxY + 26)
        XCTAssertFalse(cardFrame.intersects(paragraph.insetBy(dx: -26, dy: -26)))
    }

    func testLongParagraphCardKeepsComfortableHeightWithoutCoveringOriginal() {
        let viewport = CGSize(width: 390, height: 844)
        let card = CGSize(width: 358, height: 280)
        let paragraph = CGRect(x: 32, y: 430, width: 326, height: 220)
        let layout = ReaderTranslationOverlayPlacement.layout(
            selectionFrame: paragraph,
            viewportSize: viewport,
            cardSize: card,
            topInset: 58,
            bottomInset: 42,
            horizontalInset: 16,
            gap: 26,
            minimumCardHeight: 108,
            preferredMaximumCardHeight: 280,
            prefersHorizontalAvoidance: true,
            minimumHorizontalCardWidth: 220
        )
        let resolvedHeight = min(card.height, layout.maximumCardHeight)
        let cardFrame = CGRect(
            x: layout.position.x - layout.cardWidth / 2,
            y: layout.position.y - resolvedHeight / 2,
            width: layout.cardWidth,
            height: resolvedHeight
        )

        XCTAssertEqual(layout.edge, .above)
        XCTAssertEqual(layout.maximumCardHeight, 280)
        XCTAssertEqual(resolvedHeight, 280)
        XCTAssertLessThanOrEqual(cardFrame.maxY, paragraph.minY - 26)
        XCTAssertFalse(cardFrame.intersects(paragraph))
    }

    func testTopTranslationModeNeverCoversATopSelection() {
        let viewport = CGSize(width: 390, height: 780)
        let card = CGSize(width: 320, height: 126)
        let selection = CGRect(x: 70, y: 78, width: 250, height: 52)
        let layout = ReaderTranslationOverlayPlacement.layout(
            selectionFrame: selection,
            viewportSize: viewport,
            cardSize: card,
            topInset: 59,
            bottomInset: 34,
            gap: 18,
            minimumCardHeight: 108,
            preferredMaximumCardHeight: 220,
            prefersTop: true
        )
        let cardFrame = CGRect(
            x: layout.position.x - card.width / 2,
            y: layout.position.y - card.height / 2,
            width: card.width,
            height: card.height
        )

        XCTAssertFalse(cardFrame.intersects(selection.insetBy(dx: -18, dy: -18)))
        XCTAssertEqual(layout.edge, .below)
    }

    func testTranslationOverlayAvoidsTheWholeParagraphSelection() {
        let viewport = CGSize(width: 390, height: 780)
        let card = CGSize(width: 334, height: 126)
        let paragraph = CGRect(x: 34, y: 178, width: 322, height: 326)
        let layout = ReaderTranslationOverlayPlacement.layout(
            selectionFrame: paragraph,
            viewportSize: viewport,
            cardSize: card,
            topInset: 58,
            bottomInset: 42,
            gap: 18,
            minimumCardHeight: 108,
            preferredMaximumCardHeight: 220
        )
        let cardFrame = CGRect(
            x: layout.position.x - card.width / 2,
            y: layout.position.y - card.height / 2,
            width: card.width,
            height: card.height
        )

        XCTAssertEqual(layout.edge, .below)
        XCTAssertFalse(
            cardFrame.intersects(paragraph.insetBy(dx: -18, dy: -18))
        )
    }

    func testQuickTranslationTapPolicySuppressesOnlyTapModePageTurns() {
        XCTAssertTrue(
            ReaderTapInteractionPolicy.suppressesPageTurn(
                quickSentenceTranslationEnabled: true,
                disablesTapPageTurnsDuringQuickTranslation: true
            )
        )
        XCTAssertFalse(
            ReaderTapInteractionPolicy.suppressesPageTurn(
                quickSentenceTranslationEnabled: false,
                disablesTapPageTurnsDuringQuickTranslation: true
            )
        )
        XCTAssertFalse(
            ReaderTapInteractionPolicy.suppressesPageTurn(
                quickSentenceTranslationEnabled: true,
                disablesTapPageTurnsDuringQuickTranslation: false
            )
        )
    }

    func testReaderLoadWatchdogLeavesLoadingAndAllowsAnotherAttempt() async throws {
        let opener = HangingReaderPublicationOpener()
        let (model, _, _) = try makeReaderModel(
            publicationService: opener,
            loadTiming: ReaderLoadTiming(openTimeout: .milliseconds(35))
        )

        let firstAttempt = Task { await model.load() }
        while opener.callCount == 0 {
            await Task.yield()
        }
        try await Task.sleep(for: .milliseconds(100))

        guard case let .failed(message) = model.loadState else {
            firstAttempt.cancel()
            model.close()
            return XCTFail("The loading watchdog should replace the spinner with a failure state")
        }
        XCTAssertEqual(message, ReaderError.openTimedOut.localizedDescription)
        let cancellationDeadline = ContinuousClock().now.advanced(by: .seconds(1))
        while opener.cancellationCount < 1,
              ContinuousClock().now < cancellationDeadline
        {
            await Task.yield()
        }
        XCTAssertEqual(opener.cancellationCount, 1)

        let secondAttempt = Task { await model.load() }
        while opener.callCount < 2 {
            await Task.yield()
        }
        XCTAssertEqual(opener.callCount, 2)
        guard case .loading = model.loadState else {
            firstAttempt.cancel()
            secondAttempt.cancel()
            model.close()
            return XCTFail("A timed-out reader should allow an immediate retry")
        }

        firstAttempt.cancel()
        secondAttempt.cancel()
        model.close()
        await firstAttempt.value
        await secondAttempt.value
    }

    func testTransientFirstOpenCancellationRetriesWithoutShowingGenericFailure() async throws {
        let opener = FirstCancellationReaderPublicationOpener()
        let (model, _, _) = try makeReaderModel(publicationService: opener)

        await model.load()
        let deadline = ContinuousClock().now.advanced(by: .seconds(1))
        while opener.callCount < 2, ContinuousClock().now < deadline {
            await Task.yield()
        }
        while case .loading = model.loadState, ContinuousClock().now < deadline {
            await Task.yield()
        }

        XCTAssertEqual(opener.callCount, 2)
        guard case let .failed(message) = model.loadState else {
            model.close()
            return XCTFail("The independent retry should finish with the fixture's real error")
        }
        XCTAssertEqual(message, ReaderError.invalidEPUB.localizedDescription)
        XCTAssertNotEqual(message, "操作没有完成，请稍后重试。")
        model.close()
    }

    func testClosedReaderModelCanStartAFreshAttemptWhenSwiftUIReusesIt() async throws {
        let opener = CountingFailingReaderPublicationOpener()
        let (model, _, _) = try makeReaderModel(publicationService: opener)

        await model.load()
        guard case .failed = model.loadState else {
            model.close()
            return XCTFail("The fixture should finish its first failed attempt")
        }
        model.close()

        await model.load()
        XCTAssertEqual(opener.callCount, 2)
        guard case .failed = model.loadState else {
            model.close()
            return XCTFail("A reused model must not remain in its old loading state")
        }
        model.close()
    }

    func testSmartSelectionCompletesPartialWord() {
        XCTAssertEqual(
            ReaderSmartSelection.resolvedText(
                highlight: "ead",
                before: "Please r",
                after: " this book. Then rest."
            ),
            "read"
        )
    }

    func testSmartSelectionCompletesMultiwordSelectionToSentence() {
        XCTAssertEqual(
            ReaderSmartSelection.resolvedText(
                highlight: "light faded",
                before: "Intro. The evening ",
                after: " behind the hills. Another sentence."
            ),
            "The evening light faded behind the hills."
        )
    }

    func testSmartSelectionDoesNotTreatEnglishTitleAsSentenceBoundary() {
        XCTAssertEqual(
            ReaderSmartSelection.resolvedText(
                highlight: "looked up",
                before: "Earlier context. Dr. Smith ",
                after: ". The room was quiet."
            ),
            "Dr. Smith looked up."
        )
    }

    func testSmartSelectionDoesNotSplitEnglishInitialismOrDecimal() {
        XCTAssertEqual(
            ReaderSmartSelection.resolvedText(
                highlight: "was still growing",
                before: "Earlier context. The U.S. market ",
                after: " at 3.14 percent. Investors waited."
            ),
            "The U.S. market was still growing at 3.14 percent."
        )
    }

    func testSmartSelectionKeepsEnglishPersonalInitialsInTheSentence() {
        XCTAssertEqual(
            ReaderSmartSelection.resolvedText(
                highlight: "Rowling yesterday",
                before: "Earlier context. She met J. K. ",
                after: ". They spoke for an hour."
            ),
            "She met J. K. Rowling yesterday."
        )
    }

    func testSmartSelectionIgnoresSelectionEdgeWhitespaceWhenCompletingSentence() {
        XCTAssertEqual(
            ReaderSmartSelection.resolvedText(
                highlight: " light faded \n",
                before: "Intro. The evening",
                after: "behind the hills. Another sentence."
            ),
            "The evening light faded behind the hills."
        )
    }

    func testRubyAnnotationHeuristicRecognizesKanaButNotBaseText() {
        XCTAssertTrue(ReaderJapaneseSelection.isLikelyRubyAnnotation("ふりがな"))
        XCTAssertTrue(ReaderJapaneseSelection.isLikelyRubyAnnotation("トウ キョウ"))
        XCTAssertTrue(ReaderJapaneseSelection.isLikelyRubyAnnotation("ｶﾞｸｾｲ"))
        XCTAssertTrue(ReaderJapaneseSelection.isLikelyRubyAnnotation("とうきょう・だいがく"))
        XCTAssertTrue(ReaderJapaneseSelection.isLikelyRubyAnnotation("とうきょう。"))

        XCTAssertFalse(ReaderJapaneseSelection.isLikelyRubyAnnotation("漢字"))
        XCTAssertFalse(ReaderJapaneseSelection.isLikelyRubyAnnotation("東京とうきょう"))
        XCTAssertFalse(ReaderJapaneseSelection.isLikelyRubyAnnotation("ruby"))
        XCTAssertFalse(ReaderJapaneseSelection.isLikelyRubyAnnotation(""))
    }

    func testSelectionPolicyLetsPhysicalTouchesReachRubyBaseWithoutRewritingRanges() {
        let japanese = EPUBReaderViewController.selectionPolicyScript(
            detachRubyAnnotations: true
        )
        let other = EPUBReaderViewController.selectionPolicyScript(
            detachRubyAnnotations: false
        )

        for script in [japanese, other] {
            XCTAssertTrue(script.contains("-webkit-user-select:none"))
            XCTAssertTrue(script.contains("pointer-events:none"))
            XCTAssertTrue(script.contains("data-jerreader-rt"))
            XCTAssertTrue(script.contains("content: attr(data-jerreader-rt)"))
            XCTAssertTrue(script.contains("__jerreaderReaderSelectionBridgeVersion = 3"))
            XCTAssertFalse(script.contains("touchstart"))
            XCTAssertFalse(script.contains("selectionchange"))
            XCTAssertFalse(script.contains("selection.addRange"))
            XCTAssertFalse(script.contains("setTimeout"))
        }
        XCTAssertTrue(japanese.contains("const bookPrefersDetachment = true"))
        XCTAssertTrue(other.contains("const bookPrefersDetachment = false"))
    }

    func testSelectionPolicyLeavesNonJapaneseRubyDOMUntouched() async throws {
        let webView = WKWebView(frame: CGRect(x: 0, y: 0, width: 390, height: 400))
        webView.loadHTMLString(
            """
            <!doctype html><html lang="zh"><head>
            <meta name="viewport" content="width=device-width">
            </head><body>
            <p style="font-size:28px;margin:40px">
              我去<ruby>北京<rt id="pinyin">Beijing</rt></ruby>上学。
            </p>
            </body></html>
            """,
            baseURL: nil
        )
        for _ in 0 ..< 100 {
            if try await webView.evaluateJavaScript(
                "document.getElementById('pinyin') !== null"
            ) as? Bool == true {
                break
            }
            try await Task.sleep(for: .milliseconds(10))
        }

        _ = try await webView.evaluateJavaScript(
            EPUBReaderViewController.selectionPolicyScript(
                detachRubyAnnotations: false
            )
        )
        try await Task.sleep(for: .milliseconds(60))

        let stateRaw = try await webView.evaluateJavaScript(
            """
            (() => {
              const rt = document.getElementById('pinyin');
              const style = getComputedStyle(rt);
              return {
                version: globalThis.__jerreaderReaderSelectionBridgeVersion,
                text: rt.textContent,
                hasTextNodes: rt.firstChild !== null,
                detached: rt.dataset.jerreaderRt !== undefined,
                userSelect: style.userSelect || style.webkitUserSelect
              };
            })()
            """
        )
        let state = try XCTUnwrap(stateRaw as? [String: Any])
        XCTAssertEqual((state["version"] as? NSNumber)?.intValue, 3)
        XCTAssertEqual(state["text"] as? String, "Beijing")
        XCTAssertEqual(state["hasTextNodes"] as? Bool, true)
        XCTAssertEqual(state["detached"] as? Bool, false)
        XCTAssertEqual(state["userSelect"] as? String, "none")
    }

    func testEPUBCrossPageContextReadsNeighbouringBaseTextAndExcludesRuby() async throws {
        let webView = WKWebView(
            frame: CGRect(x: 0, y: 0, width: 390, height: 600)
        )
        let host = UIViewController()
        host.view.frame = webView.bounds
        host.view.backgroundColor = .white
        host.view.addSubview(webView)
        let windowScene = try XCTUnwrap(
            UIApplication.shared.connectedScenes
                .compactMap { $0 as? UIWindowScene }
                .first
        )
        let window = UIWindow(windowScene: windowScene)
        window.frame = webView.bounds
        window.windowLevel = .alert + 1
        window.rootViewController = host
        window.makeKeyAndVisible()
        defer {
            window.isHidden = true
            window.rootViewController = nil
        }

        webView.loadHTMLString(
            """
            <!doctype html><html lang="ja"><head>
            <meta name="viewport" content="width=device-width">
            </head><body>
              <p id="context-start">廊下には誰もいなかった。</p>
              <p>彼は<ruby>扉<rt>とびら</rt></ruby>に手をかけたが、声を聞いて立ち止まった。</p>
              <p>部屋は静かだった。</p>
            </body></html>
            """,
            baseURL: nil
        )
        var didLoad = false
        for _ in 0 ..< 300 {
            if try await webView.evaluateJavaScript(
                "document.readyState === 'complete' && document.getElementById('context-start') !== null"
            ) as? Bool == true {
                didLoad = true
                break
            }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertTrue(didLoad, "The WebKit document must finish loading before context extraction")
        guard didLoad else { return }
        window.layoutIfNeeded()
        host.view.layoutIfNeeded()
        webView.layoutIfNeeded()

        let rawContext = try await webView.evaluateJavaScript(
            EPUBReaderViewController.crossPageDocumentContextScript(
                sourceText: "手をかけたが、声を聞いて立ち止まった。"
            )
        )
        let context = try XCTUnwrap(rawContext as? String)

        XCTAssertTrue(context.contains("彼は扉に手をかけた"))
        XCTAssertTrue(context.contains("部屋は静かだった"))
        XCTAssertFalse(context.contains("とびら"))
        let expansion = try XCTUnwrap(
            ReaderCrossPageTranslationResolver.expansion(
                sourceText: "手をかけたが、声を聞いて立ち止まった。",
                contextText: context,
                language: .japanese
            )
        )
        XCTAssertEqual(
            expansion.text,
            "彼は扉に手をかけたが、声を聞いて立ち止まった。"
        )
    }

    func testQuickSentenceSpeechHighlightTracksDOMRangeAndExcludesRuby() async throws {
        let webView = WKWebView(
            frame: CGRect(x: 0, y: 0, width: 390, height: 600)
        )
        let host = UIViewController()
        host.view.frame = webView.bounds
        host.view.backgroundColor = .white
        host.view.addSubview(webView)
        let windowScene = try XCTUnwrap(
            UIApplication.shared.connectedScenes
                .compactMap { $0 as? UIWindowScene }
                .first
        )
        let window = UIWindow(windowScene: windowScene)
        window.frame = webView.bounds
        window.windowLevel = .alert + 1
        window.rootViewController = host
        window.makeKeyAndVisible()
        defer {
            window.isHidden = true
            window.rootViewController = nil
        }

        let sentence = "彼は扉を開けた。"
        webView.loadHTMLString(
            """
            <!doctype html><html lang="ja"><head>
            <meta name="viewport" content="width=device-width">
            </head><body>
              <p id="line" style="font-size:40px;margin:80px 24px">彼は<ruby>扉<rt id="reading">とびら</rt></ruby>を開けた。</p>
            </body></html>
            """,
            baseURL: nil
        )
        for _ in 0 ..< 300 {
            if try await webView.evaluateJavaScript(
                "document.readyState === 'complete' && document.getElementById('reading') !== null"
            ) as? Bool == true {
                break
            }
            try await Task.sleep(for: .milliseconds(10))
        }
        window.layoutIfNeeded()
        host.view.layoutIfNeeded()
        webView.layoutIfNeeded()
        _ = try await webView.evaluateJavaScript(
            EPUBReaderViewController.selectionPolicyScript(
                detachRubyAnnotations: true
            )
        )

        for _ in 0 ..< 100 {
            if try await webView.evaluateJavaScript(
                "document.getElementById('reading').dataset.jerreaderRt !== undefined"
            ) as? Bool == true {
                break
            }
            try await Task.sleep(for: .milliseconds(10))
        }

        let rawPoint = try await webView.evaluateJavaScript(
            """
            (() => {
              const node = document.getElementById('line').firstChild;
              const range = document.createRange();
              range.setStart(node, 0);
              range.setEnd(node, 1);
              const rect = range.getBoundingClientRect();
              return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };
            })()
            """
        )
        let pointJSON = try XCTUnwrap(rawPoint as? [String: Any])
        let point = CGPoint(
            x: CGFloat(truncating: try XCTUnwrap(pointJSON["x"] as? NSNumber)),
            y: CGFloat(truncating: try XCTUnwrap(pointJSON["y"] as? NSNumber))
        )
        let requestToken = "speech-highlight-test"
        let baseHighlight = try await webView.evaluateJavaScript(
            EPUBReaderViewController.quickSentenceHighlightScript(
                at: point,
                utf16Range: 0 ..< (sentence as NSString).length,
                requestToken: requestToken
            )
        )
        XCTAssertNotNil(baseHighlight as? [String: Any])

        let spokenRange = try XCTUnwrap(
            ReaderSpeechHighlight(
                utf16Range: NSRange(location: 2, length: 1),
                totalLength: (sentence as NSString).length
            )
        )
        let didPaint = try await webView.evaluateJavaScript(
            EPUBReaderViewController.quickSentenceSpeechHighlightScript(
                spokenRange,
                matching: requestToken,
                sequence: 1
            )
        ) as? Bool
        XCTAssertEqual(didPaint, true)

        let rawState = try await webView.evaluateJavaScript(
            """
            (() => {
              const state = globalThis.__jerreaderReaderQuickSentenceSelection;
              return {
                baseMarkers: document.getElementById(
                  'jerreader-reader-quick-sentence-highlight'
                )?.children.length || 0,
                baseBackground: document.getElementById(
                  'jerreader-reader-quick-sentence-highlight'
                )?.firstElementChild?.style.background || '',
                baseBorder: document.getElementById(
                  'jerreader-reader-quick-sentence-highlight'
                )?.firstElementChild?.style.border || '',
                spokenMarkers: document.getElementById(
                  'jerreader-reader-quick-sentence-speech-highlight'
                )?.children.length || 0,
                hasRubyNode: state.nodes.some(
                  (node) => Boolean(node.parentElement?.closest('rt,rp'))
                )
              };
            })()
            """
        )
        let state = try XCTUnwrap(rawState as? [String: Any])
        XCTAssertGreaterThan((state["baseMarkers"] as? NSNumber)?.intValue ?? 0, 0)
        XCTAssertTrue(
            (state["baseBackground"] as? String)?.contains("rgba") == true
        )
        XCTAssertTrue(
            (state["baseBorder"] as? String)?.contains("solid") == true
        )
        XCTAssertGreaterThan((state["spokenMarkers"] as? NSNumber)?.intValue ?? 0, 0)
        XCTAssertEqual(state["hasRubyNode"] as? Bool, false)

        let didClear = try await webView.evaluateJavaScript(
            EPUBReaderViewController.quickSentenceSpeechHighlightScript(
                nil,
                matching: requestToken,
                sequence: 2
            )
        ) as? Bool
        XCTAssertEqual(didClear, true)
        let didRemoveSpokenHighlight = try await webView.evaluateJavaScript(
            "document.getElementById('jerreader-reader-quick-sentence-speech-highlight') === null"
        ) as? Bool
        XCTAssertEqual(didRemoveSpokenHighlight, true)
    }

    func testRubySelectionBridgeRecoversKanjiWithoutMutatingNativeSelection() async throws {
        let webView = WKWebView(frame: CGRect(x: 0, y: 0, width: 390, height: 600))
        let host = UIViewController()
        host.view.frame = webView.bounds
        host.view.backgroundColor = .white
        host.view.addSubview(webView)
        let windowScene = try XCTUnwrap(
            UIApplication.shared.connectedScenes
                .compactMap { $0 as? UIWindowScene }
                .first
        )
        let window = UIWindow(windowScene: windowScene)
        window.frame = webView.bounds
        window.windowLevel = .alert + 1
        window.rootViewController = host
        window.makeKeyAndVisible()
        defer {
            window.isHidden = true
            window.rootViewController = nil
        }

        webView.loadHTMLString(
            """
            <!doctype html><html><head>
            <meta name="viewport" content="width=device-width">
            </head><body>
            <p id="text" style="font-size:42px;margin:70px 30px">
              私は<ruby>東京<rt id="reading">とうきょう</rt></ruby>へ行く。
            </p>
            <p id="multi" style="font-size:28px;margin:20px 30px">私は<ruby>東京<rt id="reading2">とうきょう</rt></ruby>と<ruby>大学<rt id="reading3">だいがく</rt></ruby>へ行く。</p>
            </body></html>
            """,
            baseURL: nil
        )

        for _ in 0 ..< 100 {
            if try await webView.evaluateJavaScript(
                "document.getElementById('reading') !== null"
            ) as? Bool == true {
                break
            }
            try await Task.sleep(for: .milliseconds(10))
        }
        window.layoutIfNeeded()
        host.view.layoutIfNeeded()
        webView.layoutIfNeeded()
        _ = try await webView.evaluateJavaScript(
            EPUBReaderViewController.selectionPolicyScript(
                detachRubyAnnotations: true
            )
        )

        // The v3 policy detaches annotation text at DOMContentLoaded; give the
        // sweep a moment when the document was still parsing at injection.
        for _ in 0 ..< 100 {
            if try await webView.evaluateJavaScript(
                "document.getElementById('reading').dataset.jerreaderRt !== undefined"
            ) as? Bool == true {
                break
            }
            try await Task.sleep(for: .milliseconds(10))
        }

        let rawPolicy = try await webView.evaluateJavaScript(
            """
                (() => {
                  const rt = document.getElementById('reading');
                  const style = getComputedStyle(rt);
                  const rect = rt.getBoundingClientRect();
                  const hit = document.elementFromPoint(
                    rect.left + rect.width / 2,
                    rect.top + rect.height / 2
                  );
                  return {
                    version: globalThis.__jerreaderReaderSelectionBridgeVersion,
                    userSelect: style.userSelect || style.webkitUserSelect,
                    pointerEvents: style.pointerEvents,
                    hitIsAnnotation: Boolean(hit?.closest?.('rt,rp')),
                    detachedReading: rt.dataset.jerreaderRt,
                    residualText: rt.textContent,
                    hasTextNodes: rt.firstChild !== null,
                    annotationStillRendered: rect.width > 1 && rect.height > 1
                  };
                })()
            """
        )
        let policy = try XCTUnwrap(rawPolicy as? [String: Any])
        XCTAssertEqual((policy["version"] as? NSNumber)?.intValue, 3)
        XCTAssertEqual(policy["userSelect"] as? String, "none")
        XCTAssertEqual(policy["pointerEvents"] as? String, "none")
        XCTAssertEqual(policy["hitIsAnnotation"] as? Bool, false)
        XCTAssertEqual(policy["detachedReading"] as? String, "とうきょう")
        XCTAssertEqual(policy["residualText"] as? String, "")
        XCTAssertEqual(policy["hasTextNodes"] as? Bool, false)
        XCTAssertEqual(
            policy["annotationStillRendered"] as? Bool,
            true,
            "The pseudo-element reading must keep the annotation visible"
        )

        let baseRectRaw = try await webView.evaluateJavaScript(
            """
            (() => {
              getSelection()?.removeAllRanges();
              const base = document.querySelector('ruby').firstChild;
              const range = document.createRange();
              range.selectNodeContents(base);
              const rect = range.getBoundingClientRect();
              return { x: rect.left, y: rect.top, width: rect.width, height: rect.height };
            })()
            """
        )
        let baseRectJSON = try XCTUnwrap(baseRectRaw as? [String: Any])
        let baseRect = CGRect(
            x: CGFloat(truncating: try XCTUnwrap(baseRectJSON["x"] as? NSNumber)),
            y: CGFloat(truncating: try XCTUnwrap(baseRectJSON["y"] as? NSNumber)),
            width: CGFloat(truncating: try XCTUnwrap(baseRectJSON["width"] as? NSNumber)),
            height: CGFloat(truncating: try XCTUnwrap(baseRectJSON["height"] as? NSNumber))
        )
        let pointFallbackRaw = try await webView.evaluateJavaScript(
            EPUBReaderViewController.selectionPointSnapshotScript(
                at: CGPoint(x: baseRect.midX, y: baseRect.midY),
                selectedText: "とうきょう"
            )
        )
        let pointFallback = try XCTUnwrap(pointFallbackRaw as? [String: Any])
        XCTAssertEqual(pointFallback["normalizedText"] as? String, "東京")
        XCTAssertEqual(pointFallback["recoveredFromRuby"] as? Bool, true)
        XCTAssertEqual(pointFallback["anchorRole"] as? String, "pointFallback")

        // Under the v3 policy a Range simply cannot contain annotation text:
        // the reading no longer exists as DOM text. The historical worst case
        // — a selection spanning the whole <ruby> element — must therefore
        // resolve to base text only, with no recovery step required.
        let annotationRangeBefore = try await webView.evaluateJavaScript(
            """
            (() => {
              const reading = document.getElementById('reading');
              const probe = document.createRange();
              probe.selectNodeContents(reading);
              const range = document.createRange();
              range.selectNodeContents(reading.closest('ruby'));
              const selection = getSelection();
              selection.removeAllRanges();
              selection.addRange(range);
              return {
                readingRangeText: probe.toString(),
                rangeText: range.toString(),
                selectionText: selection.toString(),
                endpointIsAnnotation:
                  selection.anchorNode?.parentElement?.closest('rt,rp') != null
              };
            })()
            """
        ) as? [String: Any]
        XCTAssertEqual(annotationRangeBefore?["readingRangeText"] as? String, "")
        XCTAssertEqual(annotationRangeBefore?["rangeText"] as? String, "東京")
        XCTAssertEqual(annotationRangeBefore?["selectionText"] as? String, "東京")
        XCTAssertEqual(annotationRangeBefore?["endpointIsAnnotation"] as? Bool, false)

        let annotationRaw = try await webView.evaluateJavaScript(
            EPUBReaderViewController.nativeSelectionSnapshotScript
        )
        let annotationSnapshot = try XCTUnwrap(annotationRaw as? [String: Any])
        XCTAssertEqual(annotationSnapshot["rawText"] as? String, "東京")
        XCTAssertEqual(annotationSnapshot["normalizedText"] as? String, "東京")
        XCTAssertEqual(annotationSnapshot["recoveredFromRuby"] as? Bool, false)
        XCTAssertGreaterThan(
            (annotationSnapshot["rectCount"] as? NSNumber)?.intValue ?? 0,
            0
        )
        XCTAssertFalse((annotationSnapshot["rects"] as? [Any] ?? []).isEmpty)

        // The bridge paints UIKit rectangles over the base text. It does not
        // alter the programmatic annotation Range used to simulate the
        // defensive fallback path.
        let decoded = try XCTUnwrap(
            EPUBSelectionSnapshot(javaScriptValue: annotationRaw)
        )
        let bridge = EPUBSelectionBridge()
        bridge.attach(to: host.view)
        host.view.layoutIfNeeded()
        let windowFrame = bridge.display(
            snapshot: decoded,
            from: webView
        )
        XCTAssertNotNil(windowFrame)
        XCTAssertFalse(bridge.displayedRects.isEmpty)
        let annotationRangeAfter = try await webView.evaluateJavaScript(
            """
            (() => {
              const selection = getSelection();
              const range = selection.getRangeAt(0);
              return {
                rangeText: range.toString(),
                endpointIsAnnotation:
                  selection.anchorNode?.parentElement?.closest('rt,rp') != null
              };
            })()
            """
        ) as? [String: Any]
        XCTAssertEqual(annotationRangeAfter?["rangeText"] as? String, "東京")
        XCTAssertEqual(annotationRangeAfter?["endpointIsAnnotation"] as? Bool, false)

        _ = try await webView.evaluateJavaScript(
            """
            (() => {
              const base = document.querySelector('ruby').firstChild;
              const range = document.createRange();
              range.selectNodeContents(base);
              const selection = getSelection();
              selection.removeAllRanges();
              selection.addRange(range);
              return true;
            })()
            """
        )
        let baseRaw = try await webView.evaluateJavaScript(
            EPUBReaderViewController.nativeSelectionSnapshotScript
        )
        let baseSnapshot = try XCTUnwrap(baseRaw as? [String: Any])
        XCTAssertEqual(baseSnapshot["normalizedText"] as? String, "東京")
        XCTAssertEqual(baseSnapshot["recoveredFromRuby"] as? Bool, false)
        XCTAssertEqual(baseSnapshot["anchorRole"] as? String, "rubyBase")

        // The visible highlight must cover the whole ruby unit: the furigana
        // box above the selected kanji has to be inside the snapshot geometry,
        // otherwise the selection looks "skipped" under its annotation.
        let readingRectRaw = try await webView.evaluateJavaScript(
            """
            (() => {
              const rect = document.getElementById('reading').getBoundingClientRect();
              return { x: rect.left, y: rect.top, width: rect.width, height: rect.height };
            })()
            """
        )
        let readingRectJSON = try XCTUnwrap(readingRectRaw as? [String: Any])
        let readingRect = CGRect(
            x: CGFloat(truncating: try XCTUnwrap(readingRectJSON["x"] as? NSNumber)),
            y: CGFloat(truncating: try XCTUnwrap(readingRectJSON["y"] as? NSNumber)),
            width: CGFloat(truncating: try XCTUnwrap(readingRectJSON["width"] as? NSNumber)),
            height: CGFloat(truncating: try XCTUnwrap(readingRectJSON["height"] as? NSNumber))
        )
        let baseDecoded = try XCTUnwrap(EPUBSelectionSnapshot(javaScriptValue: baseRaw))
        let baseUnion = baseDecoded.localRects.dropFirst().reduce(
            baseDecoded.localRects[0]
        ) { $0.union($1) }
        XCTAssertTrue(
            baseUnion.insetBy(dx: -1, dy: -1).contains(
                CGPoint(x: readingRect.midX, y: readingRect.midY)
            ),
            "Selection rects \(baseDecoded.localRects) must cover furigana \(readingRect)"
        )

        _ = try await webView.evaluateJavaScript(
            """
            (() => {
              const range = document.createRange();
              range.selectNodeContents(document.getElementById('multi'));
              const selection = getSelection();
              selection.removeAllRanges();
              selection.addRange(range);
              return true;
            })()
            """
        )
        let multiRaw = try await webView.evaluateJavaScript(
            EPUBReaderViewController.nativeSelectionSnapshotScript
        )
        let multi = try XCTUnwrap(multiRaw as? [String: Any])
        XCTAssertEqual(
            TranslationCacheStore.normalizedText(
                multi["normalizedText"] as? String ?? ""
            ),
            "私は東京と大学へ行く。"
        )
        XCTAssertEqual(multi["recoveredFromRuby"] as? Bool, false)

        let verticalStateRaw = try await webView.evaluateJavaScript(
            """
            (() => {
              document.documentElement.style.writingMode = 'vertical-rl';
              document.documentElement.style.textOrientation = 'mixed';
              const reading = document.getElementById('reading3');
              const ruby = reading.closest('ruby');
              const walker = document.createTreeWalker(ruby, NodeFilter.SHOW_TEXT, {
                acceptNode(node) {
                  return node.parentElement?.closest('rt,rp')
                    ? NodeFilter.FILTER_REJECT
                    : NodeFilter.FILTER_ACCEPT;
                }
              });
              const base = walker.nextNode();
              const range = document.createRange();
              range.setStart(base, 0);
              range.setEnd(base, base.data.length);
              const selection = getSelection();
              selection.removeAllRanges();
              selection.addRange(range);
              const rect = reading.getBoundingClientRect();
              return {
                annotationStillRendered: rect.width > 1 && rect.height > 1,
                annotationHasTextNodes: reading.firstChild !== null
              };
            })()
            """
        )
        let verticalState = try XCTUnwrap(verticalStateRaw as? [String: Any])
        XCTAssertEqual(verticalState["annotationStillRendered"] as? Bool, true)
        XCTAssertEqual(verticalState["annotationHasTextNodes"] as? Bool, false)
        try await Task.sleep(for: .milliseconds(40))
        let verticalRaw = try await webView.evaluateJavaScript(
            EPUBReaderViewController.nativeSelectionSnapshotScript
        )
        let vertical = try XCTUnwrap(verticalRaw as? [String: Any])
        XCTAssertEqual(vertical["normalizedText"] as? String, "大学")
        XCTAssertEqual(vertical["recoveredFromRuby"] as? Bool, false)
        XCTAssertGreaterThan(
            (vertical["rectCount"] as? NSNumber)?.intValue ?? 0,
            0
        )

        // Having rectangles in the snapshot is not the same as painting them:
        // the bridge still has to convert them into the highlight view's space
        // and keep the ones that intersect it. Vertical books are where that
        // conversion is most likely to drop everything, so assert the drawn
        // result, not just the geometry that feeds it.
        let verticalDecoded = try XCTUnwrap(
            EPUBSelectionSnapshot(javaScriptValue: verticalRaw)
        )
        let verticalFrame = bridge.display(
            snapshot: verticalDecoded,
            from: webView
        )
        XCTAssertNotNil(
            verticalFrame,
            "Vertical selection produced no drawable frame. DOM rects: "
                + "\(verticalDecoded.localRects) webView.bounds=\(webView.bounds)"
        )
        XCTAssertFalse(
            bridge.displayedRects.isEmpty,
            "Vertical selection highlight was dropped before painting"
        )

        let snapshotConfiguration = WKSnapshotConfiguration()
        snapshotConfiguration.rect = webView.bounds
        let image = try await webView.takeSnapshot(configuration: snapshotConfiguration)
        let attachment = XCTAttachment(image: image)
        attachment.name = "ruby-selection-bridge-base-highlight"
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    func testExternalRubyPublicationFixtureWhenProvided() async throws {
        guard let encodedHTML = ProcessInfo.processInfo.environment[
            "JERREADER_RUBY_FIXTURE_BASE64"
        ] else {
            throw XCTSkip("Set JERREADER_RUBY_FIXTURE_BASE64 for a real-publication check")
        }
        let fixtureData = try XCTUnwrap(Data(base64Encoded: encodedHTML))
        let fixtureHTML = try XCTUnwrap(String(data: fixtureData, encoding: .utf8))
        let webView = WKWebView(frame: CGRect(x: 0, y: 0, width: 430, height: 700))
        webView.loadHTMLString(fixtureHTML, baseURL: nil)

        var rubyCount = 0
        for _ in 0 ..< 200 {
            rubyCount = (try? await webView.evaluateJavaScript(
                "document.querySelectorAll('ruby > rt').length"
            ) as? NSNumber)?.intValue ?? 0
            if rubyCount > 0 { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertGreaterThan(rubyCount, 0)
        _ = try await webView.evaluateJavaScript(
            EPUBReaderViewController.selectionPolicyScript(
                detachRubyAnnotations: true
            )
        )

        for _ in 0 ..< 100 {
            if try await webView.evaluateJavaScript(
                "document.querySelector('rt')?.dataset.jerreaderRt !== undefined"
            ) as? Bool == true {
                break
            }
            try await Task.sleep(for: .milliseconds(10))
        }

        // This fixture path deliberately prefers grouped Japanese ruby such as
        // <ruby>base<rt>reading</rt>base<rt>reading</rt></ruby>, which is more
        // demanding than the single-pair synthetic regression above. Under the
        // v3 policy the readings live in data attributes, so even a selection
        // over the whole ruby element can only ever contain base text.
        let structureRaw = try await webView.evaluateJavaScript(
            """
            (() => {
              const rubies = Array.from(document.querySelectorAll('ruby'));
              const ruby = rubies.find((candidate) =>
                Array.from(candidate.children).filter((child) => child.matches('rt')).length > 1
              ) || rubies[0];
              const readings = Array.from(ruby.querySelectorAll('rt'));
              const clone = ruby.cloneNode(true);
              clone.querySelectorAll('rt,rp').forEach((node) => node.remove());
              const range = document.createRange();
              range.selectNodeContents(ruby);
              const selection = getSelection();
              selection.removeAllRanges();
              selection.addRange(range);
              ruby.scrollIntoView({ block: 'center', inline: 'center' });
              globalThis.__jerreaderRealFixtureRuby = ruby;
              globalThis.__jerreaderRealFixtureExpectedBase = clone.textContent;
              return {
                rubyCount: rubies.length,
                directReadingCount: Array.from(ruby.children)
                  .filter((child) => child.matches('rt')).length,
                readingsDetached: readings.every((candidate) =>
                  candidate.firstChild === null &&
                  (candidate.dataset.jerreaderRt || '').length > 0
                ),
                annotationsStillRendered: readings.every((candidate) => {
                  const rect = candidate.getBoundingClientRect();
                  return rect.width > 1 && rect.height > 1;
                }),
                visibleText: ruby.textContent,
                expectedBase: clone.textContent
              };
            })()
            """
        )
        let structure = try XCTUnwrap(structureRaw as? [String: Any])
        XCTAssertGreaterThanOrEqual(
            (structure["directReadingCount"] as? NSNumber)?.intValue ?? 0,
            2,
            "The supplied real fixture should exercise grouped ruby"
        )
        XCTAssertEqual(structure["readingsDetached"] as? Bool, true)
        XCTAssertEqual(structure["annotationsStillRendered"] as? Bool, true)
        let expectedBase = try XCTUnwrap(structure["expectedBase"] as? String)
        XCTAssertEqual(
            TranslationCacheStore.normalizedText(
                structure["visibleText"] as? String ?? ""
            ),
            TranslationCacheStore.normalizedText(expectedBase),
            "Detached readings must no longer appear in the DOM text flow"
        )

        var correctedVisibleText: String?
        for _ in 0 ..< 80 {
            correctedVisibleText = try await webView.evaluateJavaScript(
                "getSelection().toString()"
            ) as? String
            if TranslationCacheStore.normalizedText(correctedVisibleText ?? "")
                == TranslationCacheStore.normalizedText(expectedBase)
            {
                break
            }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertTrue(
            TranslationCacheStore.normalizedText(correctedVisibleText ?? "")
                == TranslationCacheStore.normalizedText(expectedBase)
        )

        let horizontalRaw = try await webView.evaluateJavaScript(
            EPUBReaderViewController.nativeSelectionSnapshotScript
        )
        let horizontal = try XCTUnwrap(horizontalRaw as? [String: Any])
        XCTAssertTrue(
            TranslationCacheStore.normalizedText(
                horizontal["normalizedText"] as? String ?? ""
            ) == TranslationCacheStore.normalizedText(expectedBase)
        )
        XCTAssertGreaterThan(
            (horizontal["rectCount"] as? NSNumber)?.intValue ?? 0,
            0
        )

        let multiBlockRaw = try await webView.evaluateJavaScript(
            """
            (() => {
              const block = Array.from(document.querySelectorAll(
                'p,li,blockquote,dd,dt,figcaption,td,th'
              )).find((candidate) => candidate.querySelectorAll('ruby').length >= 2);
              if (!block) return null;
              const clone = block.cloneNode(true);
              clone.querySelectorAll('rt,rp').forEach((node) => node.remove());
              const range = document.createRange();
              range.selectNodeContents(block);
              const selection = getSelection();
              selection.removeAllRanges();
              selection.addRange(range);
              globalThis.__jerreaderRealFixtureMultiBase = clone.textContent;
              return {
                rubyCount: block.querySelectorAll('ruby').length,
                expectedBase: clone.textContent
              };
            })()
            """
        )
        let multiBlock = try XCTUnwrap(
            multiBlockRaw as? [String: Any],
            "The supplied real fixture should contain a block with multiple ruby groups"
        )
        XCTAssertGreaterThanOrEqual(
            (multiBlock["rubyCount"] as? NSNumber)?.intValue ?? 0,
            2
        )
        let multiSnapshotRaw = try await webView.evaluateJavaScript(
            EPUBReaderViewController.nativeSelectionSnapshotScript
        )
        let multiSnapshot = try XCTUnwrap(multiSnapshotRaw as? [String: Any])
        XCTAssertTrue(
            TranslationCacheStore.normalizedText(
                multiSnapshot["normalizedText"] as? String ?? ""
            ) == TranslationCacheStore.normalizedText(
                multiBlock["expectedBase"] as? String ?? ""
            )
        )

        let verticalSetup = try await webView.evaluateJavaScript(
            """
            (() => {
              document.documentElement.style.writingMode = 'vertical-rl';
              document.documentElement.style.textOrientation = 'mixed';
              const ruby = globalThis.__jerreaderRealFixtureRuby;
              const range = document.createRange();
              range.selectNodeContents(ruby);
              const selection = getSelection();
              selection.removeAllRanges();
              selection.addRange(range);
              ruby.scrollIntoView({ block: 'center', inline: 'center' });
              return getComputedStyle(document.documentElement).writingMode;
            })()
            """
        ) as? String
        XCTAssertEqual(verticalSetup, "vertical-rl")
        try await Task.sleep(for: .milliseconds(50))

        var correctedVerticalText: String?
        for _ in 0 ..< 80 {
            correctedVerticalText = try await webView.evaluateJavaScript(
                "getSelection().toString()"
            ) as? String
            if TranslationCacheStore.normalizedText(correctedVerticalText ?? "")
                == TranslationCacheStore.normalizedText(expectedBase)
            {
                break
            }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertTrue(
            TranslationCacheStore.normalizedText(correctedVerticalText ?? "")
                == TranslationCacheStore.normalizedText(expectedBase)
        )
        let verticalRaw = try await webView.evaluateJavaScript(
            EPUBReaderViewController.nativeSelectionSnapshotScript
        )
        let vertical = try XCTUnwrap(verticalRaw as? [String: Any])
        XCTAssertTrue(
            TranslationCacheStore.normalizedText(
                vertical["normalizedText"] as? String ?? ""
            ) == TranslationCacheStore.normalizedText(expectedBase)
        )
        XCTAssertGreaterThan(
            (vertical["rectCount"] as? NSNumber)?.intValue ?? 0,
            0
        )
    }

    func testHorizontalJapanesePreferencesForceLeftToRightPagination() {
        let horizontal = EPUBReaderViewController.preferences(
            fontSize: 1,
            theme: .coolGray,
            fontChoice: .serif,
            lineHeight: 1.4,
            paragraphSpacing: 1.25,
            pageMargins: 1,
            textOrientation: .horizontal
        )
        let publication = EPUBReaderViewController.preferences(
            fontSize: 1,
            theme: .coolGray,
            fontChoice: .serif,
            lineHeight: 1.4,
            paragraphSpacing: 0.5,
            pageMargins: 1,
            textOrientation: .publication,
            publicationLayout: .verticalRTL
        )

        XCTAssertEqual(horizontal.readingProgression, .ltr)
        XCTAssertEqual(horizontal.verticalText, false)
        XCTAssertEqual(horizontal.scroll, false)
        XCTAssertEqual(horizontal.columnCount, .auto)
        // Readium removes the explicit `.auto` value from its sparse
        // preferences object; nil is the effective automatic spread default.
        XCTAssertNil(horizontal.spread)
        XCTAssertEqual(horizontal.paragraphSpacing, 1.25)

        XCTAssertEqual(publication.readingProgression, .rtl)
        XCTAssertEqual(publication.verticalText, true)
        XCTAssertEqual(publication.scroll, false)
        XCTAssertEqual(publication.columnCount, .auto)
        XCTAssertNil(publication.spread)
        XCTAssertEqual(publication.paragraphSpacing, 0.5)
    }

    /// The regression this guards is the whole reason the horizontal switch
    /// used to fail. Readium's `cjk-horizontal` variant declares no
    /// `writing-mode` at all, so selecting it only stops enforcing vertical
    /// text. A publication that pins its own writing mode — the EBPAJ
    /// `.vrtl` pattern reproduced below, including the `!important` and
    /// vendor-prefixed forms found in the wild — stayed vertical forever.
    /// Readium's own runtime (`readium-reflowable`: native selection
    /// callbacks, decorations, gestures) is registered on the very
    /// `WKUserContentController` handed to `setupUserScripts`. Clearing that
    /// controller to re-register our own scripts silently removed Readium's
    /// too, which killed selection highlighting the moment the reader switched
    /// orientation. Nothing in the reader may remove user scripts.
    func testReaderNeverRemovesScriptsFromReadiumsContentController() throws {
        let source = try String(
            contentsOf: URL(
                fileURLWithPath: #filePath
            )
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent(
                "Jerreader/Features/Reader/EPUBReaderHost.swift"
            ),
            encoding: .utf8
        )

        XCTAssertFalse(
            source.contains("removeAllUserScripts"),
            "Removing user scripts also deletes Readium's own runtime script"
        )
        XCTAssertFalse(source.contains("removeScriptMessageHandler"))
    }

    /// The document-start injection bakes in whatever mode was active when its
    /// WebView was created, so it must defer to the mode recorded by the host;
    /// the host's own application must instead record what it wants. The
    /// round trip is asserted on the generated source rather than at runtime
    /// because `localStorage` is unavailable to the origins a unit-test
    /// WKWebView can load.
    /// Tap-to-translate paints its highlight with an in-page overlay. It used
    /// to position the markers with `window.scrollX/scrollY`, which describes
    /// the containing block only in an ordinary horizontal-tb document. In
    /// `vertical-rl` every marker landed off-screen, so tapping a sentence in a
    /// Japanese book showed the translation with no highlight at all — while
    /// long-press, which paints through the UIKit bridge, kept working. The
    /// markers must land on the text in both writing modes.
    func testQuickSentenceHighlightLandsOnTheTextInVerticalWriting() async throws {
        for writingMode in ["horizontal-tb", "vertical-rl"] {
            let webView = WKWebView(
                frame: CGRect(x: 0, y: 0, width: 390, height: 640)
            )
            webView.loadHTMLString(
                """
                <!doctype html><html lang="ja"><head>
                <meta name="viewport" content="width=device-width">
                <style>
                  html { writing-mode: \(writingMode); }
                  body { margin: 24px; font-size: 22px; }
                </style>
                </head><body>
                <p id="p">吾輩は猫である。名前はまだ無い。どこで生れたか頓と見当がつかぬ。</p>
                </body></html>
                """,
                baseURL: nil
            )
            for _ in 0 ..< 200 {
                if try await webView.evaluateJavaScript(
                    "document.getElementById('p') !== null"
                ) as? Bool == true {
                    break
                }
                try await Task.sleep(for: .milliseconds(10))
            }
            try await Task.sleep(for: .milliseconds(80))

            // Tap an actual glyph. In vertical writing the center of the
            // paragraph's bounding box can be whitespace between columns.
            let probeRaw = try await webView.evaluateJavaScript(
                """
                (() => {
                  const paragraph = document.getElementById('p');
                  const text = paragraph.firstChild;
                  if (!text || text.nodeType !== Node.TEXT_NODE) return null;
                  const range = document.createRange();
                  range.setStart(text, 0);
                  range.setEnd(text, 1);
                  const rect = Array.from(range.getClientRects()).find(
                    candidate => (
                      candidate.width > 0 && candidate.height > 0 &&
                      candidate.right > 0 && candidate.bottom > 0 &&
                      candidate.left < innerWidth && candidate.top < innerHeight
                    )
                  );
                  if (!rect) return null;
                  return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };
                })()
                """
            )
            let probe = try XCTUnwrap(probeRaw as? [String: Any])
            let point = CGPoint(
                x: CGFloat(truncating: try XCTUnwrap(probe["x"] as? NSNumber)),
                y: CGFloat(truncating: try XCTUnwrap(probe["y"] as? NSNumber))
            )

            _ = try await webView.evaluateJavaScript(
                EPUBReaderViewController.quickSentenceHighlightScript(
                    at: point,
                    utf16Range: 0 ..< 8,
                    requestToken: "test-\(writingMode)"
                )
            )

            // The markers must overlap the paragraph, and be on screen.
            let placementRaw = try await webView.evaluateJavaScript(
                """
                (() => {
                  const overlay = document.getElementById(
                    'jerreader-reader-quick-sentence-highlight'
                  );
                  if (!overlay) return null;
                  const markers = Array.from(overlay.children).map(
                    (node) => node.getBoundingClientRect()
                  );
                  if (!markers.length) return null;
                  const text = document.getElementById('p').getBoundingClientRect();
                  const onScreen = markers.filter((rect) =>
                    rect.width > 0.5 && rect.height > 0.5 &&
                    rect.right > 0 && rect.bottom > 0 &&
                    rect.left < window.innerWidth && rect.top < window.innerHeight
                  );
                  const overlapping = onScreen.filter((rect) =>
                    rect.right > text.left && rect.left < text.right &&
                    rect.bottom > text.top && rect.top < text.bottom
                  );
                  return {
                    markers: markers.length,
                    onScreen: onScreen.length,
                    overlapping: overlapping.length
                  };
                })()
                """
            )
            let placement = try XCTUnwrap(
                placementRaw as? [String: Any],
                "No overlay was produced in \(writingMode)"
            )
            XCTAssertGreaterThan(
                (placement["onScreen"] as? NSNumber)?.intValue ?? 0,
                0,
                "Every highlight marker was positioned off-screen in \(writingMode)"
            )
            XCTAssertGreaterThan(
                (placement["overlapping"] as? NSNumber)?.intValue ?? 0,
                0,
                "The highlight did not land on the text in \(writingMode)"
            )
        }
    }

    func testWritingModePolicyReadsStoredModeOnlyWhenNotAuthoritative() {
        let injected = EPUBReaderViewController.writingModePolicyScript(
            forcesHorizontal: false,
            authoritative: false
        )
        let applied = EPUBReaderViewController.writingModePolicyScript(
            forcesHorizontal: true,
            authoritative: true
        )

        XCTAssertTrue(injected.contains("const authoritative = false"))
        XCTAssertTrue(applied.contains("const authoritative = true"))
        // The key name is asserted rather than a full call expression: the
        // generated source wraps those calls across lines.
        for script in [injected, applied] {
            XCTAssertTrue(script.contains("getItem("))
            XCTAssertTrue(script.contains("setItem('jerreaderReaderWritingMode', mode)"))
            XCTAssertTrue(script.contains("'jerreaderReaderWritingMode'"))
        }
    }

    func testWritingModePolicyForcesHorizontalOverPublisherVerticalCSS() async throws {
        let webView = WKWebView(frame: CGRect(x: 0, y: 0, width: 390, height: 600))
        webView.loadHTMLString(
            """
            <!doctype html><html lang="ja" class="vrtl"><head>
            <meta name="viewport" content="width=device-width">
            <style>
              .vrtl {
                -webkit-writing-mode: vertical-rl;
                -epub-writing-mode: vertical-rl;
                writing-mode: vertical-rl;
              }
              #pinned { writing-mode: vertical-rl !important; }
            </style>
            </head><body class="vrtl">
            <p id="plain" style="font-size:22px">吾輩は猫である。</p>
            <div id="pinned"><p>名前はまだ無い。</p></div>
            </body></html>
            """,
            baseURL: nil
        )
        for _ in 0 ..< 200 {
            if try await webView.evaluateJavaScript(
                "document.getElementById('pinned') !== null"
            ) as? Bool == true {
                break
            }
            try await Task.sleep(for: .milliseconds(10))
        }

        // Baseline: the publication really is vertical before the policy runs.
        let before = try await webView.evaluateJavaScript(
            """
            ({
              root: getComputedStyle(document.documentElement).writingMode,
              body: getComputedStyle(document.body).writingMode,
              pinned: getComputedStyle(document.getElementById('pinned')).writingMode
            })
            """
        ) as? [String: Any]
        XCTAssertEqual(before?["root"] as? String, "vertical-rl")
        XCTAssertEqual(before?["pinned"] as? String, "vertical-rl")

        _ = try await webView.evaluateJavaScript(
            EPUBReaderViewController.writingModePolicyScript(
                forcesHorizontal: true
            )
        )
        try await Task.sleep(for: .milliseconds(60))

        let horizontalRaw = try await webView.evaluateJavaScript(
            """
            ({
              root: getComputedStyle(document.documentElement).writingMode,
              body: getComputedStyle(document.body).writingMode,
              plain: getComputedStyle(document.getElementById('plain')).writingMode,
              pinned: getComputedStyle(document.getElementById('pinned')).writingMode,
              policy: globalThis.__jerreaderReaderWritingModePolicy
            })
            """
        )
        let horizontal = try XCTUnwrap(horizontalRaw as? [String: Any])
        XCTAssertEqual(horizontal["policy"] as? String, "horizontal")
        XCTAssertEqual(horizontal["root"] as? String, "horizontal-tb")
        XCTAssertEqual(horizontal["body"] as? String, "horizontal-tb")
        XCTAssertEqual(horizontal["plain"] as? String, "horizontal-tb")
        XCTAssertEqual(
            horizontal["pinned"] as? String,
            "horizontal-tb",
            "A publisher !important rule must not survive the switch"
        )

        // Switching back must hand the layout to the publication again, with
        // no residue from the horizontal pass.
        _ = try await webView.evaluateJavaScript(
            EPUBReaderViewController.writingModePolicyScript(
                forcesHorizontal: false
            )
        )
        try await Task.sleep(for: .milliseconds(60))

        let restoredRaw = try await webView.evaluateJavaScript(
            """
            ({
              root: getComputedStyle(document.documentElement).writingMode,
              pinned: getComputedStyle(document.getElementById('pinned')).writingMode,
              policy: globalThis.__jerreaderReaderWritingModePolicy,
              residue: document.querySelectorAll('[data-jerreader-writing-mode]').length,
              styleText: document.getElementById(
                'jerreader-reader-writing-mode-policy'
              ).textContent
            })
            """
        )
        let restored = try XCTUnwrap(restoredRaw as? [String: Any])
        XCTAssertEqual(restored["policy"] as? String, "publication")
        XCTAssertEqual(restored["root"] as? String, "vertical-rl")
        XCTAssertEqual(restored["pinned"] as? String, "vertical-rl")
        XCTAssertEqual((restored["residue"] as? NSNumber)?.intValue, 0)
        XCTAssertEqual(restored["styleText"] as? String, "")
    }

    func testWritingModePolicyIsIdempotentAndLeavesHorizontalBooksAlone() async throws {
        let webView = WKWebView(frame: CGRect(x: 0, y: 0, width: 390, height: 400))
        webView.loadHTMLString(
            """
            <!doctype html><html lang="en"><head>
            <meta name="viewport" content="width=device-width">
            </head><body><p id="text">A plain horizontal book.</p></body></html>
            """,
            baseURL: nil
        )
        for _ in 0 ..< 200 {
            if try await webView.evaluateJavaScript(
                "document.getElementById('text') !== null"
            ) as? Bool == true {
                break
            }
            try await Task.sleep(for: .milliseconds(10))
        }

        // `publication` mode must be inert: no marker attributes, no rules.
        _ = try await webView.evaluateJavaScript(
            EPUBReaderViewController.writingModePolicyScript(
                forcesHorizontal: false
            )
        )
        let inertRaw = try await webView.evaluateJavaScript(
            """
            ({
              writingMode: getComputedStyle(document.body).writingMode,
              marked: document.querySelectorAll('[data-jerreader-writing-mode]').length,
              inlineRoot: document.documentElement.getAttribute('style') || ''
            })
            """
        )
        let inert = try XCTUnwrap(inertRaw as? [String: Any])
        XCTAssertEqual(inert["writingMode"] as? String, "horizontal-tb")
        XCTAssertEqual((inert["marked"] as? NSNumber)?.intValue, 0)
        XCTAssertFalse((inert["inlineRoot"] as? String ?? "").contains("writing-mode"))

        // Repeated evaluation is what the layout pass does; it must settle
        // instead of appending another style element each time.
        for _ in 0 ..< 3 {
            _ = try await webView.evaluateJavaScript(
                EPUBReaderViewController.writingModePolicyScript(
                    forcesHorizontal: true
                )
            )
        }
        let repeatedRaw = try await webView.evaluateJavaScript(
            """
            ({
              styles: document.querySelectorAll(
                '#jerreader-reader-writing-mode-policy'
              ).length,
              writingMode: getComputedStyle(document.body).writingMode,
              policy: globalThis.__jerreaderReaderWritingModePolicy
            })
            """
        )
        let repeated = try XCTUnwrap(repeatedRaw as? [String: Any])
        XCTAssertEqual((repeated["styles"] as? NSNumber)?.intValue, 1)
        XCTAssertEqual(repeated["writingMode"] as? String, "horizontal-tb")
        XCTAssertEqual(repeated["policy"] as? String, "horizontal")
    }

    func testWritingModePolicyScriptCarriesTheRequestedModeOnly() {
        let horizontal = EPUBReaderViewController.writingModePolicyScript(
            forcesHorizontal: true
        )
        let publication = EPUBReaderViewController.writingModePolicyScript(
            forcesHorizontal: false
        )

        XCTAssertTrue(horizontal.contains("let mode = 'horizontal'"))
        XCTAssertTrue(publication.contains("let mode = 'publication'"))
        // The vendor-prefixed aliases are required: Japanese publications still
        // ship `-epub-`/`-webkit-` only declarations.
        XCTAssertTrue(horizontal.contains("-epub-writing-mode:horizontal-tb !important"))
        XCTAssertTrue(horizontal.contains("-webkit-writing-mode:horizontal-tb !important"))
        XCTAssertTrue(horizontal.contains("writing-mode:horizontal-tb !important"))
        for script in [horizontal, publication] {
            XCTAssertFalse(script.contains("vertical-rl"))
            XCTAssertFalse(script.contains("setTimeout"))
        }
    }

    func testOutlineBuilderKeepsARealTableOfContents() {
        let outline = ReaderOutlineBuilder.build(
            tableOfContents: [
                Link(href: "text/ch1.xhtml", title: "第一章"),
                Link(
                    href: "text/ch2.xhtml",
                    title: "第二章",
                    children: [Link(href: "text/ch2.xhtml#s2", title: "第二節")]
                )
            ],
            readingOrder: [
                Link(href: "text/cover.xhtml", mediaType: .xhtml),
                Link(href: "text/ch1.xhtml", mediaType: .xhtml),
                Link(href: "text/ch2.xhtml", mediaType: .xhtml)
            ]
        )

        XCTAssertEqual(outline.map(\.title), ["第一章", "第二章", "第二節"])
        XCTAssertEqual(outline.map(\.depth), [0, 0, 1])
        XCTAssertFalse(outline.contains { $0.isGenerated })
        // A nested entry pointing at a fragment must resolve to the resource,
        // otherwise the current chapter can never be matched.
        XCTAssertEqual(outline[2].resourceKey, "text/ch2.xhtml")
    }

    func testOutlineBuilderGeneratesContentsWhenThePublicationShipsNone() {
        let outline = ReaderOutlineBuilder.build(
            tableOfContents: [],
            readingOrder: [
                Link(href: "text/part0001.html", mediaType: .html),
                Link(href: "text/part0002.html", mediaType: .html),
                Link(href: "text/afterword.html", mediaType: .html, title: "あとがき"),
                Link(href: "images/cover.jpg", mediaType: .jpeg)
            ]
        )

        // Opaque file names become positional labels; a real title is kept.
        XCTAssertEqual(outline.map(\.title), ["第 1 节", "第 2 节", "あとがき"])
        XCTAssertEqual(outline.map(\.isGenerated), [true, true, false])
        XCTAssertEqual(outline.map(\.depth), [0, 0, 0])
    }

    func testOutlineBuilderReplacesACoverOnlyTableOfContents() {
        // A one-entry NCX cannot navigate anywhere, so the reading order is the
        // more useful source even though a table of contents technically exists.
        let outline = ReaderOutlineBuilder.build(
            tableOfContents: [Link(href: "text/cover.xhtml", title: "封面")],
            readingOrder: [
                Link(href: "text/cover.xhtml", mediaType: .xhtml),
                Link(href: "text/body01.xhtml", mediaType: .xhtml),
                Link(href: "text/body02.xhtml", mediaType: .xhtml)
            ]
        )

        XCTAssertEqual(outline.count, 3)
        XCTAssertTrue(outline.allSatisfy(\.isGenerated))
    }

    func testOutlineBuilderLeavesASingleDocumentBookAlone() {
        let outline = ReaderOutlineBuilder.build(
            tableOfContents: [],
            readingOrder: [Link(href: "text/only.xhtml", mediaType: .xhtml)]
        )

        XCTAssertTrue(
            outline.isEmpty,
            "A single-document book has nothing to navigate between"
        )
    }

    func testOutlineResourceKeyIgnoresFragmentsQueriesAndLeadingSlashes() {
        XCTAssertEqual(
            ReaderOutlineItem.resourceKey(for: "/text/ch1.xhtml#anchor"),
            "text/ch1.xhtml"
        )
        XCTAssertEqual(
            ReaderOutlineItem.resourceKey(for: "text/ch1.xhtml?v=2"),
            "text/ch1.xhtml"
        )
        XCTAssertEqual(
            ReaderOutlineItem.resourceKey(for: "text/%E7%AC%AC%E4%B8%80.xhtml"),
            "text/第一.xhtml"
        )
    }

    func testPublicationLayoutDetectorFindsVerticalCSSWithoutLanguageMetadata() {
        XCTAssertEqual(
            JapanesePublicationLayoutDetector.detect(
                markupSamples: [
                    "html { -epub-writing-mode: vertical-rl; }"
                ],
                languages: [],
                metadataReadingProgression: .auto
            ),
            .verticalRTL
        )
        XCTAssertEqual(
            JapanesePublicationLayoutDetector.detect(
                markupSamples: [
                    "body { writing-mode : vertical-lr !important; }"
                ],
                languages: ["en"],
                metadataReadingProgression: .ltr
            ),
            .verticalLTR
        )
        XCTAssertEqual(
            JapanesePublicationLayoutDetector.detect(
                markupSamples: [],
                languages: ["ja"],
                metadataReadingProgression: .rtl
            ),
            .verticalRTL
        )
    }

    func testReaderPreferencesSupportScrollingAndCustomBackground() {
        let preferences = EPUBReaderViewController.preferences(
            fontSize: 1,
            theme: .light,
            fontChoice: .serif,
            lineHeight: 1.4,
            paragraphSpacing: 0,
            pageMargins: 1,
            textOrientation: .horizontal,
            publicationLayout: .verticalRTL,
            readingMode: .scrolling,
            customBackgroundHex: "#102030"
        )

        XCTAssertEqual(preferences.scroll, true)
        XCTAssertEqual(preferences.readingProgression, .ltr)
        XCTAssertEqual(preferences.verticalText, false)
        XCTAssertEqual(preferences.backgroundColor?.rawValue, 0x102030)
        XCTAssertEqual(preferences.textColor?.rawValue, 0xF4F7FA)
    }

    func testReaderFontSizingUsesHalfPointStepsInsteadOfFivePercentSteps() {
        XCTAssertEqual(ReaderFontSizing.pointSize(fromScale: 1), 17)
        XCTAssertEqual(ReaderFontSizing.scale(fromPointSize: 17), 1, accuracy: 0.0001)
        XCTAssertEqual(ReaderFontSizing.pointSize(fromScale: 0.1), 12)
        XCTAssertEqual(ReaderFontSizing.pointSize(fromScale: 5), 32)
        XCTAssertEqual(ReaderFontSizing.pointSize(fromScale: 1.05), 18)

        let halfPointChange = ReaderFontSizing.scale(fromPointSize: 17.5) - 1
        XCTAssertGreaterThan(halfPointChange, 0)
        XCTAssertLessThan(halfPointChange, 0.05)
    }

    func testGlobalReaderDefaultsApplyOnlyWhenExplicitlyRequested() throws {
        let suiteName = "JerreaderTests.ReaderDefaults.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }
        defaults.removePersistentDomain(forName: suiteName)
        defaults.set(19.5, forKey: ReaderAppearanceDefaults.fontPointSizeKey)
        defaults.set(
            ReaderThemeChoice.dark.rawValue,
            forKey: ReaderAppearanceDefaults.themeKey
        )
        defaults.set(
            ReaderReadingMode.scrolling.rawValue,
            forKey: ReaderAppearanceDefaults.readingModeKey
        )
        defaults.set(
            ReaderFontChoice.sansSerif.rawValue,
            forKey: ReaderAppearanceDefaults.fontFamilyKey
        )
        defaults.set(1.8, forKey: ReaderAppearanceDefaults.lineHeightKey)
        defaults.set(0.7, forKey: ReaderAppearanceDefaults.paragraphSpacingKey)
        defaults.set(1.3, forKey: ReaderAppearanceDefaults.pageMarginsKey)
        defaults.set(
            "#243447",
            forKey: ReaderAppearanceDefaults.customBackgroundHexKey
        )
        defaults.set(
            "#FF55AA",
            forKey: ReaderAppearanceDefaults.customSelectionColorHexKey
        )
        defaults.set(
            ReaderTextOrientationChoice.horizontal.rawValue,
            forKey: ReaderTextOrientationDefaults.storageKey
        )

        let preferences = ReaderAppearanceDefaults.current(defaults: defaults)
        let book = BookRecord(
            title: "测试书",
            author: "作者",
            language: "ja",
            localFileName: "book.epub",
            fileFingerprint: UUID().uuidString
        )
        XCTAssertEqual(book.readerFontSize, 1)

        ReaderAppearanceDefaults.apply(preferences, to: book)

        XCTAssertEqual(book.readerFontSize, 19.5 / 17, accuracy: 0.0001)
        XCTAssertEqual(book.readerTheme, ReaderThemeChoice.dark.rawValue)
        XCTAssertTrue(book.readerScrollEnabled)
        XCTAssertEqual(book.readerCustomBackgroundHex, "#243447")
        XCTAssertEqual(book.readerCustomSelectionColorHex, "#FF55AA")
        XCTAssertEqual(
            book.readerTextOrientation,
            ReaderTextOrientationChoice.horizontal.rawValue
        )
    }

    func testAppThemeColorChoicePersistsAndFallsBackSafely() throws {
        let suiteName = "JerreaderTests.AppTheme.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }
        defaults.removePersistentDomain(forName: suiteName)

        XCTAssertEqual(JerreaderThemePreferences.current(defaults: defaults), .ocean)
        defaults.set(
            JerreaderThemeColorChoice.berry.rawValue,
            forKey: JerreaderThemePreferences.storageKey
        )
        XCTAssertEqual(JerreaderThemePreferences.current(defaults: defaults), .berry)
        defaults.set("not-a-theme", forKey: JerreaderThemePreferences.storageKey)
        XCTAssertEqual(JerreaderThemePreferences.current(defaults: defaults), .ocean)
    }

    func testSelectionPalettesAdaptToThemeAndCustomColors() {
        let light = ReaderSelectionVisualStyle.palette(
            theme: .light,
            customBackgroundHex: "",
            customSelectionColorHex: ""
        )
        let sepia = ReaderSelectionVisualStyle.palette(
            theme: .sepia,
            customBackgroundHex: "",
            customSelectionColorHex: ""
        )
        let dark = ReaderSelectionVisualStyle.palette(
            theme: .dark,
            customBackgroundHex: "",
            customSelectionColorHex: ""
        )
        let custom = ReaderSelectionVisualStyle.palette(
            theme: .light,
            customBackgroundHex: "#18202A",
            customSelectionColorHex: "#FF55AA"
        )

        XCTAssertNotEqual(light.fill.cgColor.components, sepia.fill.cgColor.components)
        XCTAssertNotEqual(light.fill.cgColor.components, dark.fill.cgColor.components)
        XCTAssertGreaterThan(dark.fill.cgColor.alpha, light.fill.cgColor.alpha)
        var red: CGFloat = 0
        var green: CGFloat = 0
        var blue: CGFloat = 0
        var alpha: CGFloat = 0
        XCTAssertTrue(
            custom.fill.getRed(
                &red,
                green: &green,
                blue: &blue,
                alpha: &alpha
            )
        )
        XCTAssertGreaterThan(red, blue)
        XCTAssertGreaterThan(red, green)
        XCTAssertGreaterThan(alpha, 0.3)
    }

    func testReaderModelPersistsASelectedHalfPointFontSize() throws {
        let (model, container, book) = try makeReaderModel()
        defer { withExtendedLifetime(container) {} }

        model.updateFontPointSize(17.5)

        XCTAssertEqual(model.fontPointSize, 17.5, accuracy: 0.0001)
        XCTAssertEqual(
            book.readerFontSize,
            ReaderFontSizing.scale(fromPointSize: 17.5),
            accuracy: 0.0001
        )
    }

    func testQuickTranslationIndicatorAutoHidesWithoutDisablingPointTranslation() async throws {
        let timing = ReaderQuickTranslationIndicatorTiming(
            autoExitDelay: .milliseconds(20)
        )
        let (model, container, _) = try makeReaderModel(
            quickTranslationIndicatorTiming: timing
        )
        defer { withExtendedLifetime(container) {} }

        XCTAssertTrue(model.isQuickSentenceTranslationEnabled)
        XCTAssertTrue(model.translationSettings.quickSentenceTranslationEnabled)

        model.quickTranslationIndicatorDidAppear()
        try await Task.sleep(for: .milliseconds(80))

        XCTAssertTrue(model.isQuickSentenceTranslationEnabled)
        XCTAssertTrue(model.translationSettings.quickSentenceTranslationEnabled)
        XCTAssertFalse(model.isQuickTranslationIndicatorVisible)
    }

    func testQuickTranslationIndicatorCancellationKeepsModeActive() async throws {
        let timing = ReaderQuickTranslationIndicatorTiming(
            autoExitDelay: .milliseconds(20)
        )
        let (model, container, _) = try makeReaderModel(
            quickTranslationIndicatorTiming: timing
        )
        defer { withExtendedLifetime(container) {} }

        model.quickTranslationIndicatorDidAppear()
        model.quickTranslationIndicatorDidDisappear()
        try await Task.sleep(for: .milliseconds(80))

        XCTAssertTrue(model.isQuickSentenceTranslationEnabled)
        XCTAssertTrue(model.isQuickTranslationIndicatorVisible)
    }

    func testBookWithoutLanguageMetadataCanPersistHorizontalOverride() throws {
        let (model, container, book) = try makeReaderModel(bookLanguage: nil)
        defer { withExtendedLifetime(container) {} }

        XCTAssertTrue(model.isReflowableBook)
        model.updateTextOrientation(.horizontal)

        XCTAssertEqual(model.textOrientation, .horizontal)
        XCTAssertEqual(book.readerTextOrientation, ReaderTextOrientationChoice.horizontal.rawValue)
    }

    func testThreeLayerSelectionKeepsInitialLongPressSmart() throws {
        var resolver = ReaderSelectionIntentResolver()

        let resolution = try XCTUnwrap(
            resolver.resolve(
                highlight: "ead",
                before: "Please r",
                after: " this book. Then rest."
            )
        )

        XCTAssertEqual(resolution.text, "read")
        XCTAssertEqual(resolution.trigger, .smartSelection)
    }

    func testThreeLayerSelectionUsesExactTextAfterHandleAdjustment() throws {
        var resolver = ReaderSelectionIntentResolver()
        _ = resolver.resolve(
            highlight: "light",
            before: "Intro. The evening ",
            after: " faded behind the hills. Another sentence."
        )

        let adjusted = try XCTUnwrap(
            resolver.resolve(
                highlight: "light faded",
                before: "Intro. The evening ",
                after: " behind the hills. Another sentence."
            )
        )

        XCTAssertEqual(adjusted.text, "light faded")
        XCTAssertEqual(adjusted.trigger, .preciseSelection)
    }

    func testThreeLayerSelectionResetRestoresSmartSelection() throws {
        var resolver = ReaderSelectionIntentResolver()
        _ = resolver.resolve(
            highlight: "light",
            before: "Intro. The evening ",
            after: " faded behind the hills. Another sentence."
        )
        _ = resolver.resolve(
            highlight: "light faded",
            before: "Intro. The evening ",
            after: " behind the hills. Another sentence."
        )

        resolver.reset()
        let restarted = try XCTUnwrap(
            resolver.resolve(
                highlight: "light faded",
                before: "Intro. The evening ",
                after: " behind the hills. Another sentence."
            )
        )

        XCTAssertEqual(restarted.text, "The evening light faded behind the hills.")
        XCTAssertEqual(restarted.trigger, .smartSelection)
    }

    func testQuickSentenceSegmentationHandlesEnglishAbbreviations() throws {
        let paragraph = "Dr. Smith looked up. The evening sky was clear! He smiled."
        let offset = (paragraph as NSString).range(of: "sky").location
        let segment = try XCTUnwrap(
            ReaderSentenceSegmenter.sentence(
                in: paragraph,
                utf16Offset: offset,
                language: .english
            )
        )

        XCTAssertEqual(segment.text, "The evening sky was clear!")
        XCTAssertEqual(segment.contextText, paragraph)
    }

    func testQuickSentenceSegmentationKeepsEnglishTitleWithFirstSentence() throws {
        let paragraph = "Dr. Smith looked up. The evening sky was clear."
        let segment = try XCTUnwrap(
            ReaderSentenceSegmenter.sentence(
                in: paragraph,
                utf16Offset: (paragraph as NSString).range(of: "Smith").location,
                language: .english
            )
        )

        XCTAssertEqual(segment.text, "Dr. Smith looked up.")
    }

    func testQuickSentenceSegmentationKeepsInitialismDecimalAndClosingQuote() throws {
        let paragraph = #"She said, “The U.S. market rose 3.14 percent.” Investors agreed."#
        let segment = try XCTUnwrap(
            ReaderSentenceSegmenter.sentence(
                in: paragraph,
                utf16Offset: (paragraph as NSString).range(of: "market").location,
                language: .english
            )
        )

        XCTAssertEqual(segment.text, #"She said, “The U.S. market rose 3.14 percent.”"#)
    }

    func testQuickSentenceSegmentationKeepsEnglishPersonalInitialsTogether() throws {
        let paragraph = "She met J. K. Rowling yesterday. They spoke for an hour."
        let segment = try XCTUnwrap(
            ReaderSentenceSegmenter.sentence(
                in: paragraph,
                utf16Offset: (paragraph as NSString).range(of: "Rowling").location,
                language: .english
            )
        )

        XCTAssertEqual(segment.text, "She met J. K. Rowling yesterday.")
    }

    func testQuickSentenceSegmentationCanEndAfterEnglishInitialism() throws {
        let paragraph = "He lives in the U.S. He moved there last year."
        let secondHe = (paragraph as NSString).range(
            of: "He",
            options: [],
            range: NSRange(location: 1, length: (paragraph as NSString).length - 1)
        ).location
        let segment = try XCTUnwrap(
            ReaderSentenceSegmenter.sentence(
                in: paragraph,
                utf16Offset: secondHe,
                language: .english
            )
        )

        XCTAssertEqual(segment.text, "He moved there last year.")
    }

    func testQuickSentenceSegmentationKeepsInitialismBeforeProperNoun() throws {
        let paragraph = "He joined the U.S. Army last year. Then he left."
        let segment = try XCTUnwrap(
            ReaderSentenceSegmenter.sentence(
                in: paragraph,
                utf16Offset: (paragraph as NSString).range(of: "Army").location,
                language: .english
            )
        )

        XCTAssertEqual(segment.text, "He joined the U.S. Army last year.")
    }

    func testQuickSentenceSegmentationDoesNotSplitPauseEllipsis() throws {
        let paragraph = "Wait... what happened? Nobody knew."
        let segment = try XCTUnwrap(
            ReaderSentenceSegmenter.sentence(
                in: paragraph,
                utf16Offset: (paragraph as NSString).range(of: "what").location,
                language: .english
            )
        )

        XCTAssertEqual(segment.text, "Wait... what happened?")
    }

    func testQuickSentenceSegmentationDoesNotMergeSectionLetterWithNextSentence() throws {
        let paragraph = "We chose plan B. Then we left."
        let segment = try XCTUnwrap(
            ReaderSentenceSegmenter.sentence(
                in: paragraph,
                utf16Offset: (paragraph as NSString).range(of: "Then").location,
                language: .english
            )
        )

        XCTAssertEqual(segment.text, "Then we left.")
    }

    func testQuickSentenceSegmentationKeepsNumberAbbreviationWithValue() throws {
        let paragraph = "No. 5 was missing. We searched every room."
        let segment = try XCTUnwrap(
            ReaderSentenceSegmenter.sentence(
                in: paragraph,
                utf16Offset: (paragraph as NSString).range(of: "missing").location,
                language: .english
            )
        )

        XCTAssertEqual(segment.text, "No. 5 was missing.")
    }

    func testQuickSentenceSegmentationTreatsSourceLineWrapAsWhitespace() throws {
        let paragraph = "The evening light\nfaded behind the hills. Another sentence."
        let segment = try XCTUnwrap(
            ReaderSentenceSegmenter.sentence(
                in: paragraph,
                utf16Offset: (paragraph as NSString).range(of: "faded").location,
                language: .english
            )
        )

        XCTAssertEqual(segment.text, "The evening light faded behind the hills.")
        XCTAssertEqual(
            segment.utf16Range,
            0 ..< ("The evening light\nfaded behind the hills." as NSString).length
        )
    }

    func testQuickSentenceSegmentationSupportsJapaneseAndChinese() throws {
        let japanese = "彼は本を閉じた。窓の外を見ると、雨が降っていた。やがて立ち上がった。"
        let japaneseSegment = try XCTUnwrap(
            ReaderSentenceSegmenter.sentence(
                in: japanese,
                utf16Offset: (japanese as NSString).range(of: "雨").location,
                language: .japanese
            )
        )
        XCTAssertEqual(japaneseSegment.text, "窓の外を見ると、雨が降っていた。")

        let chinese = "他合上了书。窗外正在下雨！房间里很安静。"
        let chineseSegment = try XCTUnwrap(
            ReaderSentenceSegmenter.sentence(
                in: chinese,
                utf16Offset: (chinese as NSString).range(of: "下雨").location,
                language: .simplifiedChinese
            )
        )
        XCTAssertEqual(chineseSegment.text, "窗外正在下雨！")
    }

    func testJapaneseQuotedQuestionKeepsFollowingQuotativeClause() throws {
        let paragraph = "「本当？」と彼は聞いた。私は答えた。"
        for target in ["本当", "彼は"] {
            let segment = try XCTUnwrap(
                ReaderSentenceSegmenter.sentence(
                    in: paragraph,
                    utf16Offset: (paragraph as NSString).range(of: target).location,
                    language: .japanese
                )
            )
            XCTAssertEqual(segment.text, "「本当？」と彼は聞いた。")
        }
    }

    func testJapaneseQuotedExclamationKeepsFollowingQuotativeClause() throws {
        let paragraph = "『危ない！』って彼女は叫んだ。皆が振り返った。"
        let segment = try XCTUnwrap(
            ReaderSentenceSegmenter.sentence(
                in: paragraph,
                utf16Offset: (paragraph as NSString).range(of: "危ない").location,
                language: .japanese
            )
        )

        XCTAssertEqual(segment.text, "『危ない！』って彼女は叫んだ。")
    }

    func testJapaneseClosedQuoteDoesNotConsumeIndependentNextSentence() throws {
        let paragraph = "「本当？」私は答えた。"
        let quoted = try XCTUnwrap(
            ReaderSentenceSegmenter.sentence(
                in: paragraph,
                utf16Offset: (paragraph as NSString).range(of: "本当").location,
                language: .japanese
            )
        )
        let following = try XCTUnwrap(
            ReaderSentenceSegmenter.sentence(
                in: paragraph,
                utf16Offset: (paragraph as NSString).range(of: "私").location,
                language: .japanese
            )
        )

        XCTAssertEqual(quoted.text, "「本当？」")
        XCTAssertEqual(following.text, "私は答えた。")
    }

    func testJapaneseNestedQuoteKeepsOuterQuoteBoundary() throws {
        let paragraph = "『彼は「行く」と言った。』私は驚いた。"
        let segment = try XCTUnwrap(
            ReaderSentenceSegmenter.sentence(
                in: paragraph,
                utf16Offset: (paragraph as NSString).range(of: "行く").location,
                language: .japanese
            )
        )

        XCTAssertEqual(segment.text, "『彼は「行く」と言った。』")
    }

    func testQuickSentenceContextIsBoundedAroundTheTarget() throws {
        let sentence = "The target sentence needs context."
        let paragraph = String(repeating: "Earlier context. ", count: 100)
            + sentence
            + String(repeating: " Later context.", count: 100)
        let segment = try XCTUnwrap(
            ReaderSentenceSegmenter.sentence(
                in: paragraph,
                utf16Offset: (paragraph as NSString).range(of: "target").location,
                language: .english
            )
        )

        XCTAssertLessThanOrEqual(
            segment.contextText.count,
            ReaderSentenceSegmenter.maximumContextCharacterCount
        )
        XCTAssertTrue(segment.contextText.contains(sentence))
    }

    func testPDFOCRSentenceJoinsWrappedLinesAndExcludesNextSentence() throws {
        let observations = [
            PDFOCRTextObservation(
                text: "The evening light",
                boundingBox: CGRect(x: 0.10, y: 0.82, width: 0.42, height: 0.055)
            ),
            PDFOCRTextObservation(
                text: "faded behind the hills. Another sentence.",
                boundingBox: CGRect(x: 0.10, y: 0.75, width: 0.58, height: 0.055)
            ),
        ]

        let selection = try XCTUnwrap(
            PDFOCRTextSelector.sentence(
                at: CGPoint(x: 0.25, y: 0.84),
                observations: observations,
                preferredLanguage: .english
            )
        )

        XCTAssertEqual(selection.text, "The evening light faded behind the hills.")
        XCTAssertEqual(selection.boundingBoxes.count, 2)
        XCTAssertTrue(selection.contextText?.contains("Another sentence.") == true)
    }

    func testParagraphSegmenterReturnsExactlyOneTrimmedBlock() throws {
        let source = " \n  第一段🙂包含两句话。第二句话也在本段。 \n"
        let offset = (source as NSString).range(of: "第二").location
        let segment = try XCTUnwrap(
            ReaderParagraphSegmenter.paragraph(
                in: source,
                utf16Offset: offset
            )
        )

        XCTAssertEqual(segment.text, "第一段🙂包含两句话。第二句话也在本段。")
        let selected = (source as NSString).substring(
            with: NSRange(
                location: segment.utf16Range.lowerBound,
                length: segment.utf16Range.count
            )
        )
        XCTAssertEqual(selected, segment.text)
    }

    func testPDFOCRParagraphJoinsOnlyTheTappedGeometryGroup() throws {
        let observations = [
            PDFOCRTextObservation(
                text: "First paragraph starts",
                boundingBox: CGRect(x: 0.10, y: 0.82, width: 0.52, height: 0.05)
            ),
            PDFOCRTextObservation(
                text: "and continues here.",
                boundingBox: CGRect(x: 0.10, y: 0.76, width: 0.44, height: 0.05)
            ),
            PDFOCRTextObservation(
                text: "Second paragraph.",
                boundingBox: CGRect(x: 0.18, y: 0.63, width: 0.42, height: 0.05)
            ),
        ]

        let selection = try XCTUnwrap(
            PDFOCRTextSelector.paragraph(
                at: CGPoint(x: 0.24, y: 0.83),
                observations: observations,
                preferredLanguage: .english
            )
        )

        XCTAssertEqual(
            selection.text,
            "First paragraph starts and continues here."
        )
        XCTAssertEqual(selection.boundingBoxes.count, 2)
    }

    func testPDFTextLayerSentenceJoinsWrapsButStopsAtParagraphBoundary() throws {
        let pageText = """
        Unpunctuated heading

        The evening light
        faded behind the hills. Another sentence.
        """
        let offset = (pageText as NSString).range(of: "faded").location
        let selection = try XCTUnwrap(
            PDFTextLayerSentenceSelector.sentence(
                in: pageText,
                utf16Offset: offset,
                language: .english
            )
        )

        XCTAssertEqual(selection.text, "The evening light faded behind the hills.")
        XCTAssertFalse(selection.text.contains("heading"))
        XCTAssertFalse(selection.text.contains("Another sentence"))
    }

    func testPDFPaperModeKeepsTextLayerSentenceInsideTappedColumn() throws {
        var cursor = 0
        func line(_ text: String, bounds: CGRect) -> PDFPaperTextLine {
            defer { cursor += text.utf16.count + 1 }
            return PDFPaperTextLine(
                text: text,
                pageUTF16Range: NSRange(
                    location: cursor,
                    length: text.utf16.count
                ),
                bounds: bounds
            )
        }

        let leftFirst = line(
            "Left sentence begins",
            bounds: CGRect(x: 42, y: 700, width: 225, height: 18)
        )
        let rightFirst = line(
            "Right column must stay separate.",
            bounds: CGRect(x: 330, y: 700, width: 225, height: 18)
        )
        let leftSecond = line(
            "and ends here. A second sentence follows.",
            bounds: CGRect(x: 42, y: 676, width: 225, height: 18)
        )
        let rightSecond = line(
            "It has an unrelated continuation.",
            bounds: CGRect(x: 330, y: 676, width: 225, height: 18)
        )
        let selection = try XCTUnwrap(
            PDFPaperTextSelector.selection(
                unit: .sentence,
                pageBounds: CGRect(x: 0, y: 0, width: 600, height: 800),
                pagePoint: CGPoint(x: 110, y: 708),
                utf16Offset: leftFirst.pageUTF16Range.location + 6,
                lines: [leftFirst, rightFirst, leftSecond, rightSecond],
                language: .english
            )
        )

        XCTAssertEqual(selection.text, "Left sentence begins and ends here.")
        XCTAssertEqual(selection.pageUTF16Ranges.count, 2)
        for range in selection.pageUTF16Ranges {
            XCTAssertFalse(NSIntersectionRange(range, rightFirst.pageUTF16Range).length > 0)
            XCTAssertFalse(NSIntersectionRange(range, rightSecond.pageUTF16Range).length > 0)
        }
    }

    func testPDFPaperModeFallsBackForFullWidthTextLayerHeading() {
        let heading = PDFPaperTextLine(
            text: "A Full Width Paper Heading",
            pageUTF16Range: NSRange(location: 0, length: 26),
            bounds: CGRect(x: 45, y: 740, width: 510, height: 24)
        )
        let left = PDFPaperTextLine(
            text: "Left body.",
            pageUTF16Range: NSRange(location: 27, length: 10),
            bounds: CGRect(x: 45, y: 700, width: 220, height: 18)
        )
        let right = PDFPaperTextLine(
            text: "Right body.",
            pageUTF16Range: NSRange(location: 38, length: 11),
            bounds: CGRect(x: 335, y: 700, width: 220, height: 18)
        )

        XCTAssertNil(
            PDFPaperTextSelector.selection(
                unit: .sentence,
                pageBounds: CGRect(x: 0, y: 0, width: 600, height: 800),
                pagePoint: CGPoint(x: 300, y: 750),
                utf16Offset: 5,
                lines: [heading, left, right],
                language: .english
            )
        )
    }

    func testPDFTextLayerParagraphJoinsWrapsAndStopsAtBlankLine() throws {
        let pageText = """
        Heading

        This paragraph wraps
        onto another physical line. It has two sentences.

        Next paragraph.
        """
        let offset = (pageText as NSString).range(of: "another").location
        let selection = try XCTUnwrap(
            PDFTextLayerSentenceSelector.paragraph(
                in: pageText,
                utf16Offset: offset
            )
        )

        XCTAssertEqual(
            selection.text,
            "This paragraph wraps onto another physical line. It has two sentences."
        )
        XCTAssertFalse(selection.text.contains("Heading"))
        XCTAssertFalse(selection.text.contains("Next paragraph"))
    }

    func testPDFTextLayerSentenceStopsBeforeIndentedNewParagraphWithoutPunctuation() throws {
        let pageText = "First paragraph has no punctuation\n  A new paragraph starts here."
        let selection = try XCTUnwrap(
            PDFTextLayerSentenceSelector.sentence(
                in: pageText,
                utf16Offset: 8,
                language: .english
            )
        )

        XCTAssertEqual(selection.text, "First paragraph has no punctuation")
    }

    /// Hidden OCR layers and CJK typesetting often produce page text with no
    /// sentence terminator anywhere. The tokenizer then reports the entire
    /// window as one sentence, and tapping a single line translated the whole
    /// page. The tap must stay bounded even when the text gives no help.
    func testPDFTextLayerSentenceNeverTranslatesAWholeUnpunctuatedPage() throws {
        let line = String(repeating: "あ", count: 90)
        let pageText = (0 ..< 8).map { _ in line }.joined(separator: "\n")
        let offset = (pageText as NSString).length / 2

        let selection = try XCTUnwrap(
            PDFTextLayerSentenceSelector.sentence(
                in: pageText,
                utf16Offset: offset,
                language: .japanese
            )
        )

        XCTAssertLessThanOrEqual(
            selection.text.count,
            PDFTextLayerSentenceSelector.maximumTappedSentenceCharacterCount
        )
        XCTAssertLessThan(
            selection.text.count,
            pageText.count / 2,
            "A tap must not select most of the page"
        )
        XCTAssertFalse(selection.text.isEmpty)
    }

    func testPDFTextLayerSentenceFallsBackToTheClauseAroundAVeryLongLine() throws {
        // One physical line, no full stop, only clause marks.
        let clause = String(repeating: "彼は歩いた", count: 40)
        let pageText = clause + "、" + String(repeating: "空は青い", count: 40)
        let offset = (pageText as NSString).length - 20

        let selection = try XCTUnwrap(
            PDFTextLayerSentenceSelector.sentence(
                in: pageText,
                utf16Offset: offset,
                language: .japanese
            )
        )

        XCTAssertLessThanOrEqual(
            selection.text.count,
            PDFTextLayerSentenceSelector.maximumTappedSentenceCharacterCount
        )
        XCTAssertFalse(selection.text.contains("彼は歩いた彼は歩いた彼は歩いた彼は歩いた彼は歩いた彼は歩いた"))
    }

    func testPDFTextLayerSentenceKeepsNormalSentencesIntact() throws {
        // The cap must not shorten ordinary prose.
        let pageText = "月が昇った。部屋は静かになった。彼は本を閉じた。"
        let offset = (pageText as NSString).range(of: "部屋").location

        let selection = try XCTUnwrap(
            PDFTextLayerSentenceSelector.sentence(
                in: pageText,
                utf16Offset: offset,
                language: .japanese
            )
        )

        XCTAssertEqual(selection.text, "部屋は静かになった。")
    }

    func testPDFTextLayerSentenceDoesNotMergeShortHeadingWithoutBlankLine() throws {
        let pageText = """
        Selectable text PDF
        The moon rose slowly. The room became quiet.
        """
        let offset = (pageText as NSString).range(of: "moon").location

        let selection = try XCTUnwrap(
            PDFTextLayerSentenceSelector.sentence(
                in: pageText,
                utf16Offset: offset,
                language: .english
            )
        )

        XCTAssertEqual(selection.text, "The moon rose slowly.")
        XCTAssertFalse(selection.text.contains("Selectable text PDF"))
    }

    func testPDFTextLayerHitResolverAcceptsNearGlyphTapButRejectsBlankArea() throws {
        let renderer = UIGraphicsPDFRenderer(
            bounds: CGRect(x: 0, y: 0, width: 320, height: 480)
        )
        let data = renderer.pdfData { context in
            context.beginPage()
            NSString(string: "Tap this sentence.").draw(
                at: CGPoint(x: 36, y: 70),
                withAttributes: [.font: UIFont.systemFont(ofSize: 22)]
            )
        }
        let document = try XCTUnwrap(PDFDocument(data: data))
        let page = try XCTUnwrap(document.page(at: 0))
        let wordRange = NSRange(location: 4, length: 4)
        let word = try XCTUnwrap(page.selection(for: wordRange))
        let bounds = word.bounds(for: page).standardized
        let nearStroke = CGPoint(x: bounds.midX, y: bounds.maxY + 5)

        let hit = PDFTextLayerHitResolver.hit(
            on: page,
            at: nearStroke,
            tolerance: CGSize(width: 16, height: 10)
        )
        let resolved = try XCTUnwrap(hit)
        XCTAssertTrue(wordRange.contains(resolved.utf16Index))

        XCTAssertNil(
            PDFTextLayerHitResolver.hit(
                on: page,
                at: CGPoint(x: 280, y: 420),
                tolerance: CGSize(width: 16, height: 10)
            )
        )
    }

    func testPDFCustomOCRLongPressNeverCompetesWithSelectableTextLayer() {
        XCTAssertGreaterThanOrEqual(PDFSelectionGesturePolicy.ocrLongPressDuration, 0.25)
        XCTAssertLessThanOrEqual(PDFSelectionGesturePolicy.ocrLongPressDuration, 0.35)
        XCTAssertFalse(
            PDFSelectionGesturePolicy.usesCustomOCRLongPress(
                hasUsableTextLayer: true
            )
        )
        XCTAssertTrue(
            PDFSelectionGesturePolicy.usesCustomOCRLongPress(
                hasUsableTextLayer: false
            )
        )
        XCTAssertFalse(
            PDFSelectionGesturePolicy.allowsSimultaneousRecognition(
                otherGestureIsPagePan: true
            )
        )
        XCTAssertTrue(
            PDFSelectionGesturePolicy.allowsSimultaneousRecognition(
                otherGestureIsPagePan: false
            )
        )
    }

    func testPDFOCRHighlightClipsContextFromTheSameRecognizedLine() throws {
        let fullBox = CGRect(x: 0.10, y: 0.80, width: 0.78, height: 0.05)
        let selection = try XCTUnwrap(
            PDFOCRTextSelector.sentence(
                at: CGPoint(x: 0.20, y: 0.82),
                observations: [
                    PDFOCRTextObservation(
                        text: "Translate this. Ignore this context.",
                        boundingBox: fullBox
                    ),
                ],
                preferredLanguage: .english
            )
        )
        let highlight = try XCTUnwrap(selection.boundingBoxes.first)

        XCTAssertEqual(selection.text, "Translate this.")
        XCTAssertEqual(selection.boundingBoxes.count, 1)
        XCTAssertEqual(highlight.minX, fullBox.minX, accuracy: 0.0001)
        XCTAssertLessThan(highlight.width, fullBox.width * 0.6)
    }

    func testPDFTranslationMenuRejectsOnlyEmptySelections() {
        XCTAssertFalse(PDFTranslationMenuPolicy.canTranslate(nil))
        XCTAssertFalse(PDFTranslationMenuPolicy.canTranslate(" \n "))
        XCTAssertTrue(PDFTranslationMenuPolicy.canTranslate("選択した文"))
    }

    func testPDFTranslationHighlightRendersEverySentenceLineAndClears() {
        let highlightView = PDFTranslationHighlightView(
            frame: CGRect(x: 0, y: 0, width: 390, height: 780)
        )
        let rectangles = [
            CGRect(x: 32, y: 120, width: 250, height: 22),
            CGRect(x: 32, y: 146, width: 180, height: 22),
        ]

        highlightView.show(rectangles: rectangles)

        XCTAssertFalse(highlightView.isHidden)
        XCTAssertEqual(highlightView.highlightedRectangleCount, 2)

        highlightView.clear()
        XCTAssertTrue(highlightView.isHidden)
        XCTAssertEqual(highlightView.highlightedRectangleCount, 0)
    }

    func testPDFTranslationHighlightFiltersAnUnrelatedTextBlock() throws {
        let renderer = UIGraphicsPDFRenderer(
            bounds: CGRect(x: 0, y: 0, width: 320, height: 480)
        )
        let data = renderer.pdfData { context in
            context.beginPage()
            NSString(string: "Unrelated heading").draw(
                at: CGPoint(x: 36, y: 48),
                withAttributes: [.font: UIFont.boldSystemFont(ofSize: 22)]
            )
            NSString(string: "Translate this sentence.").draw(
                at: CGPoint(x: 36, y: 96),
                withAttributes: [.font: UIFont.systemFont(ofSize: 18)]
            )
        }
        let document = try XCTUnwrap(PDFDocument(data: data))
        let page = try XCTUnwrap(document.page(at: 0))
        let pageText = try XCTUnwrap(page.string as NSString?)
        let sentenceRange = pageText.range(of: "Translate this sentence.")
        let combinedSelection = try XCTUnwrap(
            page.selection(
                for: NSRange(
                    location: 0,
                    length: NSMaxRange(sentenceRange)
                )
            )
        )
        let sentenceSelection = try XCTUnwrap(page.selection(for: sentenceRange))
        let sentenceBounds = sentenceSelection.bounds(for: page).standardized

        let rectangles = PDFTranslationHighlightGeometry.pageRectangles(
            for: combinedSelection,
            on: page,
            expectedText: "Translate this sentence."
        )

        XCTAssertEqual(rectangles.count, 1)
        XCTAssertTrue(rectangles[0].intersects(sentenceBounds))
    }

    func testPDFHitToleranceAndSemanticHighlightRecomputeAcrossZoom() throws {
        let renderer = UIGraphicsPDFRenderer(
            bounds: CGRect(x: 0, y: 0, width: 320, height: 480)
        )
        let data = renderer.pdfData { context in
            context.beginPage()
            NSString(string: "Zoom stays highlighted.").draw(
                at: CGPoint(x: 32, y: 88),
                withAttributes: [.font: UIFont.systemFont(ofSize: 20)]
            )
        }
        let document = try XCTUnwrap(PDFDocument(data: data))
        let page = try XCTUnwrap(document.page(at: 0))
        let pageText = try XCTUnwrap(page.string as NSString?)
        let sentenceRange = pageText.range(of: "Zoom stays highlighted.")
        let selection = try XCTUnwrap(page.selection(for: sentenceRange))
        let anchor = PDFTranslationHighlightAnchor.text(
            pageIndex: 0,
            selection: selection,
            expectedText: "Zoom stays highlighted."
        )
        guard case let .text(pageIndex, retainedSelection, expectedText) = anchor else {
            return XCTFail("Text PDF highlights must retain a semantic PDFSelection")
        }
        XCTAssertEqual(pageIndex, 0)
        XCTAssertTrue(retainedSelection === selection)
        XCTAssertEqual(expectedText, "Zoom stays highlighted.")

        let pdfView = PDFView(frame: CGRect(x: 0, y: 0, width: 390, height: 700))
        pdfView.document = document
        pdfView.displayMode = .singlePage
        pdfView.displayDirection = .vertical
        pdfView.autoScales = false
        pdfView.minScaleFactor = 0.25
        pdfView.maxScaleFactor = 4
        pdfView.scaleFactor = 1
        pdfView.layoutIfNeeded()

        let pageBounds = selection.bounds(for: page).standardized
        let viewPointAtOne = pdfView.convert(
            CGPoint(x: pageBounds.midX, y: pageBounds.midY),
            from: page
        )
        let toleranceAtOne = PDFTextLayerHitTolerance.pageSpaceTolerance(
            around: viewPointAtOne,
            in: pdfView,
            on: page
        )
        let rectanglesAtOne = PDFTranslationHighlightGeometry.viewRectangles(
            for: retainedSelection,
            on: page,
            in: pdfView,
            expectedText: expectedText
        )
        let firstRectangle = try XCTUnwrap(rectanglesAtOne.first)
        pdfView.highlightedSelections = [retainedSelection]

        pdfView.scaleFactor = 2
        pdfView.layoutIfNeeded()
        let viewPointAtTwo = pdfView.convert(
            CGPoint(x: pageBounds.midX, y: pageBounds.midY),
            from: page
        )
        let toleranceAtTwo = PDFTextLayerHitTolerance.pageSpaceTolerance(
            around: viewPointAtTwo,
            in: pdfView,
            on: page
        )
        let rectanglesAtTwo = PDFTranslationHighlightGeometry.viewRectangles(
            for: retainedSelection,
            on: page,
            in: pdfView,
            expectedText: expectedText
        )
        let secondRectangle = try XCTUnwrap(rectanglesAtTwo.first)

        // Twelve screen points become fewer PDF page units when zoomed in,
        // while the same semantic PDFSelection renders to a larger view rect.
        XCTAssertLessThan(toleranceAtTwo.width, toleranceAtOne.width)
        XCTAssertLessThan(toleranceAtTwo.height, toleranceAtOne.height)
        XCTAssertGreaterThan(secondRectangle.width, firstRectangle.width)
        XCTAssertGreaterThan(secondRectangle.height, firstRectangle.height)
        XCTAssertEqual(
            pdfView.highlightedSelections?.first?.string,
            retainedSelection.string
        )

        page.rotation = 90
        pdfView.layoutIfNeeded()
        let rotatedPoint = pdfView.convert(
            CGPoint(x: pageBounds.midX, y: pageBounds.midY),
            from: page
        )
        let rotatedTolerance = PDFTextLayerHitTolerance.pageSpaceTolerance(
            around: rotatedPoint,
            in: pdfView,
            on: page
        )
        XCTAssertTrue(rotatedTolerance.width.isFinite)
        XCTAssertTrue(rotatedTolerance.height.isFinite)
        XCTAssertGreaterThan(rotatedTolerance.width, 0.5)
        XCTAssertGreaterThan(rotatedTolerance.height, 0.5)
    }

    func testPDFOCRSentenceDoesNotMixAdjacentColumns() throws {
        let observations = [
            PDFOCRTextObservation(
                text: "Left column begins here",
                boundingBox: CGRect(x: 0.08, y: 0.82, width: 0.36, height: 0.05)
            ),
            PDFOCRTextObservation(
                text: "and ends here.",
                boundingBox: CGRect(x: 0.08, y: 0.75, width: 0.30, height: 0.05)
            ),
            PDFOCRTextObservation(
                text: "Right column must stay separate.",
                boundingBox: CGRect(x: 0.56, y: 0.82, width: 0.36, height: 0.05)
            ),
        ]

        let selection = try XCTUnwrap(
            PDFOCRTextSelector.sentence(
                at: CGPoint(x: 0.18, y: 0.84),
                observations: observations,
                preferredLanguage: .english
            )
        )

        XCTAssertEqual(selection.text, "Left column begins here and ends here.")
        XCTAssertFalse(selection.text.contains("Right column"))
        XCTAssertEqual(selection.boundingBoxes.count, 2)
    }

    func testPDFOCRPaperModeKeepsParagraphInsideTappedColumn() throws {
        let observations = [
            PDFOCRTextObservation(
                text: "Left paragraph first line",
                boundingBox: CGRect(x: 0.08, y: 0.82, width: 0.36, height: 0.04)
            ),
            PDFOCRTextObservation(
                text: "left paragraph continuation.",
                boundingBox: CGRect(x: 0.08, y: 0.76, width: 0.34, height: 0.04)
            ),
            PDFOCRTextObservation(
                text: "Right column first line",
                boundingBox: CGRect(x: 0.56, y: 0.82, width: 0.36, height: 0.04)
            ),
            PDFOCRTextObservation(
                text: "right column continuation.",
                boundingBox: CGRect(x: 0.56, y: 0.76, width: 0.34, height: 0.04)
            ),
        ]

        let selection = try XCTUnwrap(
            PDFOCRTextSelector.paragraph(
                at: CGPoint(x: 0.18, y: 0.84),
                observations: observations,
                preferredLanguage: .english,
                paperMode: true
            )
        )

        XCTAssertEqual(
            selection.text,
            "Left paragraph first line left paragraph continuation."
        )
        XCTAssertFalse(selection.text.contains("Right column"))
        XCTAssertEqual(selection.boundingBoxes.count, 2)
    }

    func testPDFOCRSentenceStopsAtIndentedParagraphWhenPunctuationIsMissing() throws {
        let observations = [
            PDFOCRTextObservation(
                text: "The first paragraph continues",
                boundingBox: CGRect(x: 0.10, y: 0.82, width: 0.52, height: 0.04)
            ),
            PDFOCRTextObservation(
                text: "without terminal punctuation",
                boundingBox: CGRect(x: 0.10, y: 0.76, width: 0.50, height: 0.04)
            ),
            PDFOCRTextObservation(
                text: "A new indented paragraph starts here.",
                boundingBox: CGRect(x: 0.14, y: 0.70, width: 0.60, height: 0.04)
            ),
        ]

        let selection = try XCTUnwrap(
            PDFOCRTextSelector.sentence(
                at: CGPoint(x: 0.25, y: 0.84),
                observations: observations,
                preferredLanguage: .english
            )
        )

        XCTAssertEqual(
            selection.text,
            "The first paragraph continues without terminal punctuation"
        )
        XCTAssertFalse(selection.text.contains("new indented paragraph"))
        XCTAssertEqual(selection.boundingBoxes.count, 2)
    }

    func testPDFOCRSentenceStopsAtLargeParagraphGap() throws {
        let observations = [
            PDFOCRTextObservation(
                text: "A paragraph without punctuation",
                boundingBox: CGRect(x: 0.10, y: 0.82, width: 0.50, height: 0.04)
            ),
            PDFOCRTextObservation(
                text: "A distant paragraph begins here.",
                boundingBox: CGRect(x: 0.10, y: 0.67, width: 0.52, height: 0.04)
            ),
        ]

        let selection = try XCTUnwrap(
            PDFOCRTextSelector.sentence(
                at: CGPoint(x: 0.24, y: 0.84),
                observations: observations,
                preferredLanguage: .english
            )
        )

        XCTAssertEqual(selection.text, "A paragraph without punctuation")
        XCTAssertEqual(selection.boundingBoxes.count, 1)
    }

    func testPDFOCRJapaneseRubyIsExcludedInFavorOfKanjiBase() throws {
        let observations = [
            PDFOCRTextObservation(
                text: "彼は東京へ行った。",
                boundingBox: CGRect(x: 0.10, y: 0.70, width: 0.62, height: 0.055)
            ),
            PDFOCRTextObservation(
                text: "とうきょう",
                boundingBox: CGRect(x: 0.29, y: 0.76, width: 0.16, height: 0.022)
            ),
        ]

        let selection = try XCTUnwrap(
            PDFOCRTextSelector.sentence(
                at: CGPoint(x: 0.36, y: 0.77),
                observations: observations,
                preferredLanguage: .japanese
            )
        )

        XCTAssertEqual(selection.text, "彼は東京へ行った。")
        XCTAssertFalse(selection.text.contains("とうきょう"))
        XCTAssertEqual(selection.boundingBoxes.count, 1)
    }

    func testPDFOCRVerticalKanjiUsesGeometryWithoutLanguageMetadata() throws {
        let observations = [
            PDFOCRTextObservation(
                text: "東京大学",
                boundingBox: CGRect(x: 0.78, y: 0.20, width: 0.04, height: 0.62)
            ),
            PDFOCRTextObservation(
                text: "図書館。次章。",
                boundingBox: CGRect(x: 0.71, y: 0.20, width: 0.04, height: 0.62)
            ),
        ]

        let selection = try XCTUnwrap(
            PDFOCRTextSelector.sentence(
                at: CGPoint(x: 0.80, y: 0.70),
                observations: observations,
                preferredLanguage: nil
            )
        )

        XCTAssertEqual(selection.text, "東京大学図書館。")
        XCTAssertEqual(selection.boundingBoxes.count, 2)
    }

    func testPDFOCRSelectionIgnoresDistantBlankArea() {
        let observations = [
            PDFOCRTextObservation(
                text: "A visible sentence.",
                boundingBox: CGRect(x: 0.10, y: 0.80, width: 0.40, height: 0.05)
            ),
        ]

        XCTAssertNil(
            PDFOCRTextSelector.sentence(
                at: CGPoint(x: 0.92, y: 0.10),
                observations: observations,
                preferredLanguage: .english
            )
        )
    }

    func testPDFOCRPageCoordinateMappingRoundTripsVisibleTap() throws {
        let page = CGRect(x: 40, y: 100, width: 300, height: 500)
        let normalized = try XCTUnwrap(
            PDFOCRPageCoordinateMapper.normalizedPoint(
                CGPoint(x: 100, y: 200),
                in: page
            )
        )

        XCTAssertEqual(normalized.x, 0.2, accuracy: 0.0001)
        XCTAssertEqual(normalized.y, 0.8, accuracy: 0.0001)

        let highlight = try XCTUnwrap(
            PDFOCRPageCoordinateMapper.viewRectangle(
                for: CGRect(x: 0.18, y: 0.78, width: 0.12, height: 0.05),
                in: page
            )
        )
        XCTAssertTrue(highlight.contains(CGPoint(x: 100, y: 200)))
    }

    func testPDFOCRPageCoordinateMappingRejectsOutsideTap() {
        XCTAssertNil(
            PDFOCRPageCoordinateMapper.normalizedPoint(
                CGPoint(x: 8, y: 8),
                in: CGRect(x: 40, y: 100, width: 300, height: 500)
            )
        )
    }

    func testPDFOCRVisibleFirstLineTapSelectsFirstRecognizedSentence() throws {
        let page = CGRect(x: 4, y: 182, width: 394, height: 510)
        let observations = [
            PDFOCRTextObservation(
                text: "The moon rose slowly.",
                boundingBox: CGRect(x: 0.086, y: 0.625, width: 0.324, height: 0.029)
            ),
            PDFOCRTextObservation(
                text: "The room became quiet.",
                boundingBox: CGRect(x: 0.086, y: 0.575, width: 0.348, height: 0.026)
            ),
            PDFOCRTextObservation(
                text: "Tap a sentence to run local OCR.",
                boundingBox: CGRect(x: 0.086, y: 0.399, width: 0.476, height: 0.024)
            ),
        ]
        let firstLinePoint = CGPoint(
            x: page.minX + 0.20 * page.width,
            y: page.minY + (1 - 0.639) * page.height
        )
        let normalized = try XCTUnwrap(
            PDFOCRPageCoordinateMapper.normalizedPoint(firstLinePoint, in: page)
        )
        let selection = try XCTUnwrap(
            PDFOCRTextSelector.sentence(
                at: normalized,
                observations: observations,
                preferredLanguage: .english
            )
        )

        XCTAssertEqual(normalized.y, 0.639, accuracy: 0.0001)
        XCTAssertEqual(selection.text, "The moon rose slowly.")
    }

    func testQuickSentenceModeDefaultsOnAndPersistsExplicitOptOut() throws {
        let suiteName = "JerreaderTests.QuickSentence.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }
        defaults.removePersistentDomain(forName: suiteName)

        var store: TranslationSettingsStore? = TranslationSettingsStore(
            defaults: defaults,
            credentialStore: TestTranslationCredentialStore()
        )
        XCTAssertEqual(store?.quickSentenceTranslationEnabled, true)
        XCTAssertEqual(store?.translationHapticsEnabled, true)
        XCTAssertEqual(
            store?.disablesTapPageTurnsDuringQuickTranslation,
            true
        )
        XCTAssertEqual(store?.quickTranslationUnit, .sentence)
        store?.quickSentenceTranslationEnabled = false
        store?.quickTranslationUnit = .paragraph
        store?.translationHapticsEnabled = false
        store?.disablesTapPageTurnsDuringQuickTranslation = false
        store = nil

        let restored = TranslationSettingsStore(
            defaults: defaults,
            credentialStore: TestTranslationCredentialStore()
        )
        XCTAssertEqual(restored.quickSentenceTranslationEnabled, false)
        XCTAssertEqual(restored.quickTranslationUnit, .paragraph)
        XCTAssertEqual(restored.translationHapticsEnabled, false)
        XCTAssertEqual(
            restored.disablesTapPageTurnsDuringQuickTranslation,
            false
        )
    }

    func testLanguageDirectionAndCustomAIPromptsPersistAndReachConfiguration()
        throws
    {
        let suiteName = "JerreaderTests.CustomPrompts.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }
        defaults.removePersistentDomain(forName: suiteName)
        let credentials = TestTranslationCredentialStore()
        var store: TranslationSettingsStore? = TranslationSettingsStore(
            defaults: defaults,
            credentialStore: credentials
        )

        store?.sourceLanguageChoice = .english
        store?.targetLanguage = .japanese
        store?.translationPromptTemplate =
            "Translate {source_language} into {target_language}."
        store?.grammarAnalysisPromptTemplate =
            "Explain {source_language} in {response_language}."
        store?.directAPIKey = "local-test-key"

        XCTAssertEqual(
            store?.directAPIConfiguration?.translationPromptTemplate,
            "Translate {source_language} into {target_language}."
        )
        store = nil

        let restored = TranslationSettingsStore(
            defaults: defaults,
            credentialStore: credentials
        )
        XCTAssertEqual(restored.sourceLanguageChoice, .english)
        XCTAssertEqual(restored.targetLanguage, .japanese)
        XCTAssertEqual(
            AIPromptTemplateDefaults.rendered(
                restored.translationPromptTemplate,
                sourceLanguage: .english,
                targetLanguage: .japanese
            ),
            "Translate 英语 into 日语."
        )
        XCTAssertEqual(
            restored.grammarAnalysisPromptTemplate,
            "Explain {source_language} in {response_language}."
        )
    }

    func testLegacyDefaultGrammarPromptMigratesWithoutOverwritingCustomPrompt() throws {
        let suiteName = "JerreaderTests.GrammarPromptMigration.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }
        defaults.removePersistentDomain(forName: suiteName)
        defaults.set(
            AIPromptTemplateDefaults.legacyGrammarAnalysis,
            forKey: "translation.prompt.grammarAnalysis"
        )

        var store: TranslationSettingsStore? = TranslationSettingsStore(
            defaults: defaults,
            credentialStore: TestTranslationCredentialStore()
        )
        XCTAssertEqual(
            store?.grammarAnalysisPromptTemplate,
            AIPromptTemplateDefaults.grammarAnalysis
        )
        XCTAssertTrue(
            store?.grammarAnalysisPromptTemplate.contains("关键语法") == true
        )
        XCTAssertTrue(
            store?.grammarAnalysisPromptTemplate.contains("1–3 点") == true
        )

        store?.grammarAnalysisPromptTemplate = "CUSTOM GRAMMAR PROMPT"
        store = nil
        let restored = TranslationSettingsStore(
            defaults: defaults,
            credentialStore: TestTranslationCredentialStore()
        )
        XCTAssertEqual(
            restored.grammarAnalysisPromptTemplate,
            "CUSTOM GRAMMAR PROMPT"
        )
    }

    func testDirectAPIKeysAreKeptSeparateForEachProvider() throws {
        let suiteName = "JerreaderTests.DirectAPI.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }
        defaults.removePersistentDomain(forName: suiteName)
        let credentials = TestTranslationCredentialStore()
        let store = TranslationSettingsStore(
            defaults: defaults,
            credentialStore: credentials
        )

        store.directAPIProvider = .openAI
        store.directAPIKey = "openai-local-key"
        store.directAPIModel = "openai-model"
        store.directAPIProvider = .deepSeek
        XCTAssertEqual(store.directAPIKey, "")
        store.directAPIKey = "deepseek-local-key"
        store.directAPIProvider = .openAI

        XCTAssertEqual(store.directAPIKey, "openai-local-key")
        XCTAssertEqual(store.directAPIModel, "openai-model")
        XCTAssertEqual(store.directAPIConfiguration?.provider, .openAI)
    }

    func testDirectAPIDefaultsExposeGPTClaudeAndKimiReadyToPasteKey() throws {
        let suiteName = "JerreaderTests.DirectDefaults.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }
        defaults.removePersistentDomain(forName: suiteName)
        let store = TranslationSettingsStore(
            defaults: defaults,
            credentialStore: TestTranslationCredentialStore()
        )

        XCTAssertEqual(store.directAPIProvider, .openAI)
        XCTAssertEqual(store.directAPIModel, "gpt-5.6-luna")
        XCTAssertEqual(DirectAIProviderChoice.anthropic.defaultModel, "claude-sonnet-5")
        XCTAssertEqual(DirectAIProviderChoice.kimi.defaultModel, "kimi-k2.6")
        XCTAssertEqual(
            DirectAIProviderChoice.kimi.defaultEndpointText,
            "https://api.moonshot.cn/v1/chat/completions"
        )

        store.directAPIProvider = .kimi
        store.directAPIKey = "local-kimi-key"
        XCTAssertEqual(store.directAPIConfiguration?.provider, .kimi)
        XCTAssertEqual(store.directAPIConfiguration?.model, "kimi-k2.6")
    }

    func testEveryDirectAIProviderHasLocalAssetSlotAndReadableFallbackMark() {
        let providers = DirectAIProviderChoice.allCases
        XCTAssertTrue(
            providers.allSatisfy { $0.localLogoAssetName.hasPrefix("ProviderLogo") }
        )
        XCTAssertTrue(providers.allSatisfy { !$0.fallbackMark.isEmpty })
        XCTAssertEqual(Set(providers.map(\.fallbackMark)).count, providers.count)
    }

    func testTransientFailureRetriesOnceAndThenCachesTheResult() async throws {
        let (model, container, _) = try makeReaderModel()
        defer { withExtendedLifetime(container) {} }
        let service = FlakyTranslationService()

        model.requestTranslation(for: selection("Retry this sentence."))
        await model.performPendingTranslation(using: service)

        let callCount = await service.numberOfCalls()
        XCTAssertEqual(callCount, 2)
        guard case let .success(_, result, _) = model.translationState else {
            return XCTFail("The second attempt should complete the translation")
        }
        XCTAssertEqual(result.translatedText, "重试成功。")
    }

    func testInvisibleProviderResultRetriesAndNeverBecomesBlankSuccess() async throws {
        let (model, container, _) = try makeReaderModel()
        defer { withExtendedLifetime(container) {} }
        let service = InvisibleThenValidTranslationService()

        model.requestTranslation(for: selection("Recover the visible translation."))
        await model.performPendingTranslation(using: service)

        let callCount = await service.numberOfCalls()
        XCTAssertEqual(callCount, 2)
        guard case let .success(_, result, _) = model.translationState else {
            return XCTFail("A visible retry result should replace the empty response")
        }
        XCTAssertEqual(result.translatedText, "恢复后的可见译文。")
    }

    func testTransientFailureSwitchesToConfiguredFallbackOnlyOnce() async throws {
        let (model, container, _) = try makeReaderModel(
            backendTranslationServiceFactory: { _ in
                SuspendedTranslationService()
            }
        )
        defer { withExtendedLifetime(container) {} }
        model.translationSettings.provider = .apple
        model.translationSettings.backendEndpoint = "https://translation.invalid/v1/translate"
        model.translationSettings.fallbackProvider = .backendProxy
        model.translationSettings.automaticRetryEnabled = false

        model.requestTranslation(for: selection("Use the fallback."))
        await model.performPendingTranslation(
            using: FailingTranslationService(error: .temporarilyUnavailable)
        )

        guard case let .loading(request) = model.translationState else {
            return XCTFail("A configured fallback should begin a fresh request")
        }
        XCTAssertEqual(request.provider, .backendProxy)
        XCTAssertTrue(request.didUseFallback)
        model.close()
    }

    func testReadingBookmarkCanBeAddedAndRemovedAtAStableLocation() throws {
        let container = try ModelContainer(
            for: BookRecord.self,
            ReadingBookmarkRecord.self,
            configurations: ModelConfiguration(isStoredInMemoryOnly: true)
        )
        let book = BookRecord(
            title: "Bookmark Test",
            author: "Reader",
            localFileName: "bookmark.epub",
            fileFingerprint: "bookmark-test"
        )
        container.mainContext.insert(book)
        let store = ReadingBookmarkStore(modelContext: container.mainContext)
        let locator = Locator(
            href: try XCTUnwrap(AnyURL(string: "chapter-1.xhtml")),
            mediaType: .xhtml,
            title: "第一章",
            locations: .init(progression: 0.25, totalProgression: 0.4, position: 18),
            text: .init(after: " after", before: "Before ", highlight: "selected")
        )

        XCTAssertTrue(try store.toggle(book: book, locator: locator, chapterTitle: "第一章"))
        XCTAssertTrue(store.isBookmarked(bookID: book.id, locator: locator))
        let bookmarks = store.bookmarks(for: book.id)
        XCTAssertEqual(bookmarks.count, 1)
        XCTAssertEqual(bookmarks.first?.excerpt, "Before selected after")
        XCTAssertEqual(bookmarks.first?.progress, 0.4)

        XCTAssertFalse(try store.toggle(book: book, locator: locator, chapterTitle: "第一章"))
        XCTAssertTrue(store.bookmarks(for: book.id).isEmpty)
    }

    func testReadingAnnotationRoundTripUpdatesInsteadOfDuplicating() throws {
        let container = try ModelContainer(
            for: BookRecord.self,
            ReadingAnnotationRecord.self,
            configurations: ModelConfiguration(isStoredInMemoryOnly: true)
        )
        let book = BookRecord(
            title: "Annotation Test",
            author: "Reader",
            localFileName: "annotation.epub",
            fileFingerprint: "annotation-test"
        )
        container.mainContext.insert(book)
        let store = ReadingAnnotationStore(modelContext: container.mainContext)
        let locator = Locator(
            href: try XCTUnwrap(AnyURL(string: "chapter-2.xhtml")),
            mediaType: .xhtml,
            title: "第二章",
            locations: .init(progression: 0.4, totalProgression: 0.55),
            text: .init(
                after: " The room became quiet.",
                before: "Before this, ",
                highlight: "the lamp came on."
            )
        )
        let locatorJSON = try XCTUnwrap(locator.readerJSONString)

        let first = try store.save(
            book: book,
            locatorJSON: locatorJSON,
            anchorJSON: nil,
            selectedText: "the lamp came on.",
            noteText: "",
            color: .yellow,
            chapterTitle: "第二章",
            progress: 0.55
        )
        let updated = try store.save(
            book: book,
            locatorJSON: locatorJSON,
            anchorJSON: #"{"pageIndex":1}"#,
            selectedText: "  the lamp came on. ",
            noteText: "这里照应上一段。",
            color: .blue,
            chapterTitle: "第二章",
            progress: 0.56
        )

        XCTAssertEqual(updated.id, first.id)
        XCTAssertEqual(store.annotations(for: book.id).count, 1)
        XCTAssertEqual(updated.noteText, "这里照应上一段。")
        XCTAssertEqual(updated.color, .blue)
        XCTAssertEqual(updated.progress, 0.56, accuracy: 0.0001)
        XCTAssertEqual(updated.anchorJSON, #"{"pageIndex":1}"#)
        XCTAssertEqual(
            store.annotation(
                bookID: book.id,
                locatorJSON: locatorJSON,
                selectedText: "the lamp came on."
            )?.id,
            first.id
        )

        try store.delete(updated)
        XCTAssertTrue(store.annotations(for: book.id).isEmpty)
    }

    func testReadingAnnotationUsesSemanticLocatorIdentityAndPreservesParagraphs() throws {
        let container = try ModelContainer(
            for: BookRecord.self,
            ReadingAnnotationRecord.self,
            configurations: ModelConfiguration(isStoredInMemoryOnly: true)
        )
        let book = BookRecord(
            title: "Long Reading",
            author: "Reader",
            localFileName: "long.epub",
            fileFingerprint: "long-reading-test"
        )
        container.mainContext.insert(book)
        let store = ReadingAnnotationStore(modelContext: container.mainContext)
        let compactLocator = #"{"href":"chapter.xhtml","locations":{"progression":0.42}}"#
        let reorderedLocator = """
        {
          "locations" : { "progression" : 0.42 },
          "href" : "chapter.xhtml"
        }
        """

        let first = try store.save(
            book: book,
            locatorJSON: compactLocator,
            anchorJSON: nil,
            selectedText: "第一段。\r\n第二段。",
            noteText: "  保留\n笔记换行。  ",
            color: .mint,
            chapterTitle: " ",
            progress: .nan
        )
        let updated = try store.save(
            book: book,
            locatorJSON: reorderedLocator,
            anchorJSON: nil,
            selectedText: "第一段。 第二段。",
            noteText: "更新",
            color: .purple,
            chapterTitle: "第三章",
            progress: 1.4
        )

        XCTAssertEqual(updated.id, first.id)
        XCTAssertEqual(store.annotations(for: book.id).count, 1)
        XCTAssertEqual(first.progress, 1)
        XCTAssertEqual(first.noteText, "更新")
        XCTAssertEqual(first.chapterTitle, "第三章")
        XCTAssertEqual(first.color, .purple)
    }

    func testReadingAnnotationPreservesDisplayLineBreaksAndClampsInvalidProgress() throws {
        let container = try ModelContainer(
            for: BookRecord.self,
            ReadingAnnotationRecord.self,
            configurations: ModelConfiguration(isStoredInMemoryOnly: true)
        )
        let book = BookRecord(
            title: "Paragraphs",
            author: "Reader",
            localFileName: "paragraphs.epub",
            fileFingerprint: "paragraph-test"
        )
        container.mainContext.insert(book)
        let store = ReadingAnnotationStore(modelContext: container.mainContext)

        let record = try store.save(
            book: book,
            locatorJSON: #"{"href":"p.xhtml"}"#,
            anchorJSON: nil,
            selectedText: "  First paragraph.\r\nSecond paragraph.  ",
            noteText: "  line one\rline two  ",
            color: .yellow,
            chapterTitle: "",
            progress: .nan
        )

        XCTAssertEqual(record.selectedText, "First paragraph.\nSecond paragraph.")
        XCTAssertEqual(record.noteText, "line one\nline two")
        XCTAssertEqual(record.chapterTitle, book.title)
        XCTAssertEqual(record.progress, 0)
    }

    func testReadingAnnotationDeleteAllOnlyRemovesRequestedBook() throws {
        let container = try ModelContainer(
            for: BookRecord.self,
            ReadingAnnotationRecord.self,
            configurations: ModelConfiguration(isStoredInMemoryOnly: true)
        )
        let firstBook = BookRecord(
            title: "First",
            author: "Reader",
            localFileName: "first.epub",
            fileFingerprint: "annotation-first"
        )
        let secondBook = BookRecord(
            title: "Second",
            author: "Reader",
            localFileName: "second.epub",
            fileFingerprint: "annotation-second"
        )
        container.mainContext.insert(firstBook)
        container.mainContext.insert(secondBook)
        let store = ReadingAnnotationStore(modelContext: container.mainContext)
        for book in [firstBook, secondBook] {
            try store.save(
                book: book,
                locatorJSON: #"{"href":"chapter.xhtml"}"#,
                anchorJSON: nil,
                selectedText: "Marked sentence.",
                noteText: "",
                color: .blue,
                chapterTitle: "正文",
                progress: 0.2
            )
        }

        try store.deleteAll(for: firstBook.id)

        XCTAssertTrue(store.annotations(for: firstBook.id).isEmpty)
        XCTAssertEqual(store.annotations(for: secondBook.id).count, 1)
    }

    func testPDFAnnotationAnchorRoundTripsNormalizedGeometry() throws {
        let anchor = ReaderPDFAnnotationAnchor(
            pageIndex: 7,
            coordinateSpace: .vision,
            rectangles: [
                CGRect(x: 0.12, y: 0.68, width: 0.44, height: 0.05),
                CGRect(x: 0.12, y: 0.61, width: 0.31, height: 0.05),
            ]
        )

        let decoded = try XCTUnwrap(
            ReaderPDFAnnotationAnchor(jsonString: anchor.jsonString)
        )

        XCTAssertEqual(decoded, anchor)
        XCTAssertEqual(decoded.rectangles.map(\.cgRect), anchor.rectangles.map(\.cgRect))
    }

    func testRepeatedEquivalentSelectionKeepsCurrentTranslationRequest() throws {
        let (model, container, _) = try makeReaderModel()
        defer { withExtendedLifetime(container) {} }

        model.requestTranslation(for: selection("Translate this sentence."))
        let firstRequestID = model.translationState.request?.id
        model.requestTranslation(for: selection("  Translate this   sentence.  "))

        XCTAssertEqual(model.translationState.request?.id, firstRequestID)
    }

    func testReaderWordCanBeAddedDirectlyToVocabularyWithCurrentTranslation() async throws {
        let (model, container, book) = try makeReaderModel()
        defer { withExtendedLifetime(container) {} }

        model.requestTranslation(for: selection("reader"))
        await model.performPendingTranslation(using: MockTranslationService())
        XCTAssertTrue(model.canAddCurrentSelectionToVocabulary)

        model.addCurrentSelectionToVocabulary()
        for _ in 0 ..< 20 where !model.isCurrentSelectionInVocabulary {
            await Task.yield()
        }

        let records = try container.mainContext.fetch(
            FetchDescriptor<WordLookupRecord>()
        )
        let record = try XCTUnwrap(records.first)
        XCTAssertEqual(records.count, 1)
        XCTAssertEqual(record.surfaceForm, "reader")
        XCTAssertTrue(record.isFavorite)
        XCTAssertEqual(record.sourceBookID, book.id)
        XCTAssertEqual(record.sourceBookTitle, book.title)
        XCTAssertTrue(model.isCurrentSelectionInVocabulary)
    }

    func testRepeatedEquivalentSelectionMovesOverlayWithoutNewTranslationRequest() throws {
        let (model, container, _) = try makeReaderModel()
        defer { withExtendedLifetime(container) {} }
        let firstFrame = CGRect(x: 30, y: 110, width: 90, height: 24)
        let movedFrame = CGRect(x: 210, y: 560, width: 120, height: 48)

        model.requestTranslation(
            for: ReaderSelectionPayload(
                text: "Translate this sentence.",
                locatorJSON: "selection-location",
                frame: firstFrame
            )
        )
        let firstRequestID = model.translationState.request?.id

        model.requestTranslation(
            for: ReaderSelectionPayload(
                text: "  Translate this sentence.  ",
                locatorJSON: "selection-location",
                frame: movedFrame
            )
        )

        XCTAssertEqual(model.translationState.request?.id, firstRequestID)
        XCTAssertEqual(model.translationAnchorFrame, movedFrame)
        XCTAssertEqual(model.translationState.request?.selectionFrame, firstFrame)
    }

    func testThreeInteractionLayersShareRequestWithinTheSameChapter() throws {
        let (model, container, _) = try makeReaderModel()
        defer { withExtendedLifetime(container) {} }
        let firstFrame = CGRect(x: 40, y: 180, width: 190, height: 24)
        let movedFrame = CGRect(x: 55, y: 190, width: 210, height: 44)
        let currentLocation = #"{"href":"chapter-1.xhtml","locations":{"progression":0.2}}"#
        let selectionLocation = #"{"href":"chapter-1.xhtml","locations":{"progression":0.24},"text":{"highlight":"The sky was clear."}}"#

        model.requestTranslation(
            for: ReaderSelectionPayload(
                text: "The sky was clear.",
                contextText: "Earlier. The sky was clear. Later.",
                locatorJSON: currentLocation,
                frame: firstFrame,
                trigger: .quickSentence
            )
        )
        let firstRequestID = model.translationState.request?.id

        model.requestTranslation(
            for: ReaderSelectionPayload(
                text: "The sky was clear.",
                contextText: "Different Readium context around the same sentence.",
                locatorJSON: selectionLocation,
                frame: movedFrame,
                trigger: .preciseSelection
            )
        )

        XCTAssertEqual(model.translationState.request?.id, firstRequestID)
        XCTAssertEqual(model.translationAnchorFrame, movedFrame)
    }

    func testSameTextAtDifferentBookLocationCreatesFreshContextualRequest() throws {
        let (model, container, _) = try makeReaderModel()
        defer { withExtendedLifetime(container) {} }

        model.requestTranslation(
            for: ReaderSelectionPayload(
                text: "Repeated sentence.",
                locatorJSON: "chapter-1-location",
                frame: CGRect(x: 30, y: 110, width: 120, height: 24)
            )
        )
        let firstRequestID = model.translationState.request?.id

        model.requestTranslation(
            for: ReaderSelectionPayload(
                text: "Repeated sentence.",
                locatorJSON: "chapter-2-location",
                frame: CGRect(x: 30, y: 510, width: 120, height: 24)
            )
        )

        XCTAssertNotEqual(model.translationState.request?.id, firstRequestID)
        XCTAssertEqual(
            model.translationState.request?.locatorJSON,
            "chapter-2-location"
        )
    }

    func testSelectionValidationAcceptsTwoThousandAndRejectsTwoThousandOneCharacters() throws {
        let (model, container, _) = try makeReaderModel()
        defer { withExtendedLifetime(container) {} }

        model.requestTranslation(for: selection(String(repeating: "a", count: 2_000)))
        guard case let .loading(request) = model.translationState else {
            return XCTFail("A 2,000-character selection should be accepted")
        }
        XCTAssertEqual(request.sourceText.count, 2_000)

        model.dismissTranslation()
        model.requestTranslation(for: selection(String(repeating: "a", count: 2_001)))
        guard case let .failure(_, error) = model.translationState else {
            return XCTFail("A 2,001-character selection should be rejected")
        }
        XCTAssertEqual(error, .textTooLong(limit: 2_000))
    }

    func testEmptyAndUnsupportedSelectionsProduceTypedErrors() throws {
        let (englishModel, englishContainer, _) = try makeReaderModel()
        defer { withExtendedLifetime(englishContainer) {} }
        englishModel.requestTranslation(for: selection(" \n\t "))
        guard case let .failure(_, emptyError) = englishModel.translationState else {
            return XCTFail("An empty selection should fail")
        }
        XCTAssertEqual(emptyError, .emptySelection)

        let (unknownModel, unknownContainer, _) = try makeReaderModel(bookLanguage: nil)
        defer { withExtendedLifetime(unknownContainer) {} }
        unknownModel.requestTranslation(for: selection("12345。"))
        guard case let .failure(_, languageError) = unknownModel.translationState else {
            return XCTFail("An unsupported language should fail")
        }
        XCTAssertEqual(languageError, .unsupportedLanguage)
    }

    func testSelectionContextIsRetainedInTranslationRequest() throws {
        let (model, container, book) = try makeReaderModel()
        defer { withExtendedLifetime(container) {} }
        let frame = CGRect(x: 18, y: 42, width: 160, height: 28)
        let payload = ReaderSelectionPayload(
            text: "  A selected sentence.  ",
            contextText: "Before it. A selected sentence. After it.",
            locatorJSON: "{\"href\":\"chapter-1.xhtml\"}",
            frame: frame,
            trigger: .quickSentence
        )

        model.requestTranslation(for: payload)
        guard case let .loading(request) = model.translationState else {
            return XCTFail("A valid selection should create a loading request")
        }

        XCTAssertEqual(request.bookID, book.id)
        XCTAssertEqual(request.sourceText, "A selected sentence.")
        XCTAssertEqual(request.contextText, "Before it. A selected sentence. After it.")
        XCTAssertEqual(request.targetLanguage, .simplifiedChinese)
        XCTAssertEqual(request.provider, .apple)
        XCTAssertEqual(request.providerIdentifier, TranslationCacheStore.appleProviderIdentifier)
        XCTAssertEqual(request.locatorJSON, payload.locatorJSON)
        XCTAssertEqual(request.selectionFrame, frame)
        XCTAssertEqual(request.trigger, .quickSentence)
    }

    func testContextIsForwardedToContextAwareTranslationService() async throws {
        let (model, container, _) = try makeReaderModel()
        defer { withExtendedLifetime(container) {} }
        let service = RecordingTranslationService()
        let context = "The room was dark. It was light enough to read by the window."

        model.requestTranslation(
            for: ReaderSelectionPayload(
                text: "It was light enough to read by the window.",
                contextText: context,
                locatorJSON: "context-location",
                frame: nil,
                trigger: .quickSentence
            )
        )
        await model.performPendingTranslation(using: service)

        let capturedContext = await service.lastContext()
        XCTAssertEqual(capturedContext, context)
    }

    func testSameSourceAndTargetLanguageFailsBeforeServiceRequest() throws {
        let (model, container, _) = try makeReaderModel(bookLanguage: "en")
        defer { withExtendedLifetime(container) {} }
        model.translationSettings.targetLanguage = .english

        model.requestTranslation(for: selection("This is already English."))

        guard case let .failure(_, error) = model.translationState else {
            return XCTFail("The same source and target should fail immediately")
        }
        XCTAssertEqual(error, .sameLanguage)
    }

    func testCacheHitDoesNotCallTranslationService() async throws {
        let (model, container, _) = try makeReaderModel()
        defer { withExtendedLifetime(container) {} }
        let store = TranslationCacheStore(modelContext: container.mainContext)
        XCTAssertTrue(store.store(makeResult(sourceText: "Already translated.")))
        let service = RecordingTranslationService()

        model.requestTranslation(for: selection("Already translated."))
        await model.performPendingTranslation(using: service)

        guard case let .success(_, result, source) = model.translationState else {
            return XCTFail("A cache hit should immediately succeed")
        }
        XCTAssertTrue(result.isFromCache)
        XCTAssertEqual(source, .cache)
        let callCount = await service.numberOfCalls()
        XCTAssertEqual(callCount, 0)
    }

    func testCacheMissCallsServiceOnceAndPersistsResult() async throws {
        let (model, container, _) = try makeReaderModel()
        defer { withExtendedLifetime(container) {} }
        let service = RecordingTranslationService()

        model.requestTranslation(for: selection("Translate this sentence."))
        await model.performPendingTranslation(using: service)

        guard case let .success(_, result, source) = model.translationState else {
            return XCTFail("A cache miss should succeed through the service")
        }
        XCTAssertEqual(result.translatedText, "译文：Translate this sentence.")
        XCTAssertEqual(source, .appleTranslation)
        let callCount = await service.numberOfCalls()
        XCTAssertEqual(callCount, 1)

        let records = try container.mainContext.fetch(
            FetchDescriptor<TranslationCacheRecord>()
        )
        XCTAssertEqual(records.count, 1)
        XCTAssertEqual(records.first?.sourceText, "Translate this sentence.")
    }

    func testAppleCacheIsSharedAcrossInteractionContexts() async throws {
        let (model, container, _) = try makeReaderModel()
        defer { withExtendedLifetime(container) {} }
        let service = RecordingTranslationService()

        model.requestTranslation(
            for: ReaderSelectionPayload(
                text: "A shared sentence.",
                contextText: "First paragraph context.",
                locatorJSON: #"{"href":"chapter-1.xhtml"}"#,
                frame: nil,
                trigger: .quickSentence
            )
        )
        await model.performPendingTranslation(using: service)
        model.dismissTranslation()

        model.requestTranslation(
            for: ReaderSelectionPayload(
                text: "A shared sentence.",
                contextText: "A different selection context.",
                locatorJSON: #"{"href":"chapter-2.xhtml"}"#,
                frame: nil,
                trigger: .preciseSelection
            )
        )
        await model.performPendingTranslation(using: service)

        guard case let .success(_, result, source) = model.translationState else {
            return XCTFail("The second interaction should reuse the Apple cache")
        }
        XCTAssertTrue(result.isFromCache)
        XCTAssertEqual(source, .cache)
        let callCount = await service.numberOfCalls()
        XCTAssertEqual(callCount, 1)

        let records = try container.mainContext.fetch(
            FetchDescriptor<TranslationCacheRecord>()
        )
        XCTAssertEqual(records.count, 1)
    }

    func testForcedRetryBypassesExistingCache() async throws {
        let (model, container, _) = try makeReaderModel()
        defer { withExtendedLifetime(container) {} }
        let store = TranslationCacheStore(modelContext: container.mainContext)
        XCTAssertTrue(store.store(makeResult(sourceText: "Use a fresh translation.")))
        let service = RecordingTranslationService()

        model.requestTranslation(for: selection("Use a fresh translation."))
        guard case .success = model.translationState else {
            return XCTFail("The first result should come from cache")
        }

        model.retryTranslation()
        await model.performPendingTranslation(using: service)

        guard case let .success(_, result, source) = model.translationState else {
            return XCTFail("Forced retry should complete")
        }
        XCTAssertFalse(result.isFromCache)
        XCTAssertEqual(source, .appleTranslation)
        let callCount = await service.numberOfCalls()
        XCTAssertEqual(callCount, 1)
    }

    func testManualRetranslationHasPerTextCooldownAndCannotStartConcurrentRequest() async throws {
        let timing = ReaderTranslationTiming(
            appleLaunchRetryDelay: .seconds(1),
            appleLaunchTimeout: .seconds(1),
            appleRequestTimeout: .seconds(1),
            backendRequestTimeout: .seconds(1),
            manualRetryCooldown: .milliseconds(120)
        )
        let (model, container, _) = try makeReaderModel(translationTiming: timing)
        defer { withExtendedLifetime(container) {} }
        let store = TranslationCacheStore(modelContext: container.mainContext)
        XCTAssertTrue(store.store(makeResult(sourceText: "Retry only once.")))
        let service = RecordingTranslationService()

        model.requestTranslation(for: selection("Retry only once."))
        XCTAssertTrue(model.canRetryTranslation)

        model.retryTranslation()
        guard case let .loading(firstRetry) = model.translationState else {
            return XCTFail("The first manual retranslation should start")
        }
        XCTAssertTrue(model.isTranslationRetryCoolingDown)
        XCTAssertFalse(model.canRetryTranslation)

        // A second tap while the first provider request is loading must be a
        // no-op; in particular it must not replace the request ID.
        model.retryTranslation()
        XCTAssertEqual(model.translationState.request?.id, firstRetry.id)

        await model.performPendingTranslation(using: service)
        guard case let .success(completedRetry, _, _) = model.translationState else {
            return XCTFail("The first manual retranslation should complete")
        }
        XCTAssertEqual(completedRetry.id, firstRetry.id)
        XCTAssertTrue(model.isTranslationRetryCoolingDown)

        // Success exposes the button again, so the cooldown itself must still
        // prevent duplicate paid requests for the same text.
        model.retryTranslation()
        XCTAssertEqual(model.translationState.request?.id, firstRetry.id)
        let callCountDuringCooldown = await service.numberOfCalls()
        XCTAssertEqual(callCountDuringCooldown, 1)

        try await Task.sleep(for: .milliseconds(180))
        XCTAssertFalse(model.isTranslationRetryCoolingDown)
        XCTAssertTrue(model.canRetryTranslation)

        model.retryTranslation()
        guard case let .loading(secondRetry) = model.translationState else {
            return XCTFail("Retranslation should be available after cooldown")
        }
        XCTAssertNotEqual(secondRetry.id, firstRetry.id)
        model.dismissTranslation()
    }

    func testManualRetranslationCooldownDoesNotBlockDifferentText() throws {
        let timing = ReaderTranslationTiming(
            appleLaunchRetryDelay: .seconds(1),
            appleLaunchTimeout: .seconds(1),
            appleRequestTimeout: .seconds(1),
            backendRequestTimeout: .seconds(1),
            manualRetryCooldown: .seconds(5)
        )
        let (model, container, _) = try makeReaderModel(translationTiming: timing)
        defer { withExtendedLifetime(container) {} }
        let store = TranslationCacheStore(modelContext: container.mainContext)
        XCTAssertTrue(store.store(makeResult(sourceText: "First cached sentence.")))
        XCTAssertTrue(store.store(makeResult(sourceText: "Second cached sentence.")))

        model.requestTranslation(for: selection("First cached sentence."))
        model.retryTranslation()
        XCTAssertTrue(model.isTranslationRetryCoolingDown)

        model.requestTranslation(for: selection("Second cached sentence."))
        guard case let .success(request, _, .cache) = model.translationState else {
            return XCTFail("A different cached sentence should open immediately")
        }
        XCTAssertEqual(request.sourceText, "Second cached sentence.")
        XCTAssertFalse(model.isTranslationRetryCoolingDown)
        XCTAssertTrue(model.canRetryTranslation)
    }

    func testSlowerOldRequestCannotOverwriteNewerResult() async throws {
        let (model, container, _) = try makeReaderModel()
        defer { withExtendedLifetime(container) {} }
        let service = ControlledTranslationService()

        model.requestTranslation(for: selection("Request A"))
        let taskA = Task { await model.performPendingTranslation(using: service) }
        await service.waitUntilRequested("Request A")

        model.requestTranslation(for: selection("Request B"))
        let taskB = Task { await model.performPendingTranslation(using: service) }
        await service.waitUntilRequested("Request B")

        await service.complete("Request B", translatedText: "B result")
        await taskB.value
        await service.complete("Request A", translatedText: "A result")
        await taskA.value

        guard case let .success(request, result, _) = model.translationState else {
            return XCTFail("The newer request should remain successful")
        }
        XCTAssertEqual(request.sourceText, "Request B")
        XCTAssertEqual(result.translatedText, "B result")
    }

    func testDismissedRequestCannotWriteBackLateResult() async throws {
        let (model, container, _) = try makeReaderModel()
        defer { withExtendedLifetime(container) {} }
        let service = ControlledTranslationService()

        model.requestTranslation(for: selection("Dismiss me"))
        let task = Task { await model.performPendingTranslation(using: service) }
        await service.waitUntilRequested("Dismiss me")

        model.dismissTranslation()
        await service.complete("Dismiss me", translatedText: "Late result")
        await task.value

        XCTAssertEqual(model.translationState, .idle)
        XCTAssertNil(model.presentedTranslationRequest)
    }

    func testTranslationWatchdogStopsAnUnstartedAppleRequest() async throws {
        let timing = ReaderTranslationTiming(
            appleLaunchRetryDelay: .milliseconds(10),
            appleLaunchTimeout: .milliseconds(15),
            appleRequestTimeout: .milliseconds(25),
            backendRequestTimeout: .milliseconds(25)
        )
        let (model, container, _) = try makeReaderModel(translationTiming: timing)
        defer { withExtendedLifetime(container) {} }

        model.requestTranslation(for: selection("Watchdog request"))
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: .seconds(2))
        while case .loading = model.translationState, clock.now < deadline {
            // A cold simulator can resume this test and the watchdog's first
            // sleep in the same main-actor turn. Polling gives the watchdog a
            // deterministic opportunity to schedule and finish its second
            // bounded wait without weakening the production timeout.
            try await Task.sleep(for: .milliseconds(20))
        }

        guard case let .failure(_, error) = model.translationState else {
            return XCTFail("A request whose TranslationSession never starts must time out")
        }
        XCTAssertEqual(error, .timedOut)
        XCTAssertTrue(error.canRetry)
        XCTAssertNil(model.translationConfiguration)
    }

    func testLateProviderResultCannotOverwriteTranslationTimeout() async throws {
        let timing = ReaderTranslationTiming(
            appleLaunchRetryDelay: .milliseconds(10),
            appleLaunchTimeout: .milliseconds(15),
            appleRequestTimeout: .milliseconds(25),
            backendRequestTimeout: .milliseconds(25)
        )
        let (model, container, _) = try makeReaderModel(translationTiming: timing)
        defer { withExtendedLifetime(container) {} }
        let service = ControlledTranslationService()

        model.requestTranslation(for: selection("Slow provider"))
        let providerTask = Task {
            await model.performPendingTranslation(using: service)
        }
        await service.waitUntilRequested("Slow provider")
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: .seconds(2))
        while case .loading = model.translationState, clock.now < deadline {
            // Simulator scheduling can pause the main actor for longer than
            // the deliberately tiny test timeout. Observe the state change
            // instead of assuming an 80 ms wall-clock sleep is sufficient.
            try await Task.sleep(for: .milliseconds(20))
        }

        guard case let .failure(_, timeoutError) = model.translationState else {
            return XCTFail("The watchdog should replace loading with a timeout")
        }
        XCTAssertEqual(timeoutError, .appleLanguagePreparationTimedOut)

        await service.complete("Slow provider", translatedText: "Too late")
        await providerTask.value

        guard case let .failure(_, finalError) = model.translationState else {
            return XCTFail("A late result must not replace the timeout")
        }
        XCTAssertEqual(finalError, .appleLanguagePreparationTimedOut)
    }

    func testCancellationAndServiceFailureMapToRetryableErrors() async throws {
        let (cancelModel, cancelContainer, _) = try makeReaderModel()
        defer { withExtendedLifetime(cancelContainer) {} }
        cancelModel.requestTranslation(for: selection("Cancel this"))
        await cancelModel.performPendingTranslation(using: CancellingTranslationService())
        guard case let .failure(_, cancellationError) = cancelModel.translationState else {
            return XCTFail("Cancellation should produce a failure state")
        }
        XCTAssertEqual(cancellationError, .userCancelled)
        XCTAssertTrue(cancellationError.canRetry)

        let (failureModel, failureContainer, _) = try makeReaderModel()
        defer { withExtendedLifetime(failureContainer) {} }
        failureModel.requestTranslation(for: selection("Fail this"))
        await failureModel.performPendingTranslation(
            using: FailingTranslationService(error: .translationUnavailable)
        )
        guard case let .failure(_, serviceError) = failureModel.translationState else {
            return XCTFail("Service failure should produce a failure state")
        }
        XCTAssertEqual(serviceError, .translationUnavailable)
        XCTAssertTrue(serviceError.canRetry)

        let (downloadModel, downloadContainer, _) = try makeReaderModel()
        defer { withExtendedLifetime(downloadContainer) {} }
        downloadModel.requestTranslation(for: selection("Download language"))
        await downloadModel.performPendingTranslation(
            using: FailingTranslationService(error: .languageDownloadDeclined)
        )
        guard case let .failure(_, downloadError) = downloadModel.translationState else {
            return XCTFail("Language download refusal should produce a failure state")
        }
        XCTAssertEqual(downloadError, .languageDownloadDeclined)
        XCTAssertTrue(downloadError.canRetry)
    }

    func testMockTranslationAcceptsBoundaryAndRejectsOversizedSelection() async throws {
        let boundaryResult = try await MockTranslationService().translate(
            text: String(repeating: "a", count: 2_000),
            sourceLanguage: .english,
            targetLanguage: .simplifiedChinese
        )
        XCTAssertEqual(boundaryResult.sourceText.count, 2_000)

        do {
            _ = try await MockTranslationService().translate(
                text: String(repeating: "a", count: 2_001),
                sourceLanguage: .english,
                targetLanguage: .simplifiedChinese
            )
            XCTFail("Expected an oversized selection error")
        } catch {
            XCTAssertEqual(error as? ServiceError, .textTooLong)
        }
    }

    func testCrossPageExpansionCompletesAnEnglishSentenceAcrossVisualPages() throws {
        let context = """
        The corridor was empty. He reached for the
        door, but stopped when he heard a voice. Nobody moved.
        """

        let expansion = try XCTUnwrap(
            ReaderCrossPageTranslationResolver.expansion(
                sourceText: "door, but stopped when he heard a voice.",
                contextText: context,
                language: .english
            )
        )

        XCTAssertEqual(
            expansion.text,
            "He reached for the door, but stopped when he heard a voice."
        )
        XCTAssertTrue(expansion.text.contains("He reached for the door"))
        XCTAssertFalse(expansion.text.contains("corridor"))
        XCTAssertFalse(expansion.text.contains("Nobody moved"))
    }

    func testCrossPageExpansionCompletesJapaneseTextAcrossVisualPages() throws {
        let context = "廊下には誰もいなかった。彼は扉に手を\nかけたが、声を聞いて立ち止まった。部屋は静かだった。"

        let expansion = try XCTUnwrap(
            ReaderCrossPageTranslationResolver.expansion(
                sourceText: "かけたが、声を聞いて立ち止まった。",
                contextText: context,
                language: .japanese
            )
        )

        XCTAssertEqual(
            expansion.text,
            "彼は扉に手を かけたが、声を聞いて立ち止まった。"
        )
        XCTAssertTrue(expansion.text.contains("彼は扉に手を"))
        XCTAssertFalse(expansion.text.contains("廊下には"))
        XCTAssertFalse(expansion.text.contains("部屋は静か"))
    }

    func testCrossPageExpansionRefusesToInventUnavailableContext() {
        XCTAssertNil(
            ReaderCrossPageTranslationResolver.expansion(
                sourceText: "Only this sentence is available.",
                contextText: "Only this sentence is available.",
                language: .english
            )
        )
    }

    func testCrossPageContextAlwaysRetainsASelectionNearBottomOfLongPDFPage() throws {
        let source = "The selected sentence is near the page bottom."
        let local = String(repeating: "Earlier local context. ", count: 90)
            + source
            + " Final local words."

        let context = try XCTUnwrap(
            ReaderCrossPageContextBuilder.context(
                sourceText: source,
                previousPageText: String(repeating: "Previous page. ", count: 80),
                localContext: local,
                nextPageText: "The next page continues this idea. Another sentence."
            )
        )

        XCTAssertLessThanOrEqual(
            context.count,
            ReaderSentenceSegmenter.maximumContextCharacterCount
        )
        XCTAssertTrue(context.contains(source))
        XCTAssertTrue(context.contains("next page continues"))
    }

    func testMockContextExplanationUsesNoNetworkAndKeepsFocusedText() async throws {
        let result = try await MockTranslationService().explain(
            ContextExplanationRequest(
                focusedText: "It was light.",
                contextText: "She lifted the box. It was light.",
                sourceLanguage: .english,
                responseLanguage: .simplifiedChinese
            )
        )

        XCTAssertTrue(result.explanation.contains("It was light."))
        XCTAssertEqual(result.providerIdentifier, "mock-explanation")
    }

    func testContextExplanationWatchdogStopsAHangingProvider() async throws {
        let timing = ReaderTranslationTiming(
            appleLaunchRetryDelay: .milliseconds(10),
            appleLaunchTimeout: .milliseconds(15),
            appleRequestTimeout: .milliseconds(25),
            backendRequestTimeout: .milliseconds(25)
        )
        let (model, container, _) = try makeReaderModel(
            translationTiming: timing,
            backendTranslationServiceFactory: { _ in
                SuspendedContextTranslationService()
            }
        )
        defer { withExtendedLifetime(container) {} }
        model.translationSettings.provider = .backendProxy
        model.translationSettings.backendEndpoint =
            "https://translation.example.test/v1/selection"

        model.requestTranslation(
            for: ReaderSelectionPayload(
                text: "It was light.",
                contextText: "She lifted the box. It was light.",
                locatorJSON: nil,
                frame: nil
            )
        )
        model.requestContextExplanation()
        try await Task.sleep(for: .milliseconds(90))

        guard case let .failure(_, error) = model.contextExplanationState else {
            return XCTFail("Expected the context explanation watchdog to fail the request")
        }
        XCTAssertEqual(error, .timedOut)
    }

    func testContextExplanationPolicyBoundsContextAroundFocusedText() throws {
        let focused = "The selected sentence is close to the end."
        let request = ContextExplanationRequest(
            focusedText: focused,
            contextText: String(repeating: "Earlier context. ", count: 120)
                + focused
                + String(repeating: " Later context.", count: 120),
            sourceLanguage: .english,
            responseLanguage: .simplifiedChinese
        )

        let prepared = ContextExplanationInputPolicy.prepared(request)

        XCTAssertEqual(prepared.focusedText, focused)
        XCTAssertLessThanOrEqual(
            try XCTUnwrap(prepared.contextText).count,
            ContextExplanationInputPolicy.maximumContextCharacterCount
        )
        XCTAssertTrue(try XCTUnwrap(prepared.contextText).contains(focused))
    }

    func testContextExplanationSemanticIdentityNormalizesEquivalentWhitespace() {
        let first = ContextExplanationRequest(
            focusedText: "It was light.",
            contextText: "She lifted the box.\nIt was light.",
            sourceLanguage: .english,
            responseLanguage: .simplifiedChinese
        )
        let second = ContextExplanationRequest(
            focusedText: " It was light. ",
            contextText: "She lifted the box.   It was light.",
            sourceLanguage: .english,
            responseLanguage: .simplifiedChinese
        )

        XCTAssertEqual(
            ContextExplanationInputPolicy.semanticIdentity(
                for: first,
                providerIdentifier: "provider",
                providerVersion: "v1"
            ),
            ContextExplanationInputPolicy.semanticIdentity(
                for: second,
                providerIdentifier: "provider",
                providerVersion: "v1"
            )
        )
    }

    private func makeCacheContainer() throws -> ModelContainer {
        try ModelContainer(
            for: TranslationCacheRecord.self,
            configurations: ModelConfiguration(isStoredInMemoryOnly: true)
        )
    }

    private func makeReaderModel(
        bookLanguage: String? = "en",
        publicationService: any ReaderPublicationOpening = EPUBPublicationService(),
        loadTiming: ReaderLoadTiming = .standard,
        translationTiming: ReaderTranslationTiming = .standard,
        quickTranslationIndicatorTiming: ReaderQuickTranslationIndicatorTiming = .standard,
        lexicalLookupService: any LexicalLookupService = MockLexicalLookupService(),
        backendTranslationServiceFactory: @escaping
            (BackendTranslationConfiguration) -> any TranslationService = {
                BackendTranslationService(configuration: $0)
            }
    ) throws -> (EPUBReaderViewModel, ModelContainer, BookRecord) {
        let container = try ModelContainer(
            for: BookRecord.self,
            TranslationCacheRecord.self,
            TranslationFavoriteRecord.self,
            ReadingBookmarkRecord.self,
            ReadingAnnotationRecord.self,
            WordLookupRecord.self,
            configurations: ModelConfiguration(isStoredInMemoryOnly: true)
        )
        let book = BookRecord(
            title: "Test Book",
            author: "Test Author",
            language: bookLanguage,
            localFileName: "unused.epub",
            fileFingerprint: UUID().uuidString
        )
        container.mainContext.insert(book)
        try container.mainContext.save()
        let suiteName = "JerreaderTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defaults.removePersistentDomain(forName: suiteName)
        let translationSettings = TranslationSettingsStore(
            defaults: defaults,
            credentialStore: TestTranslationCredentialStore()
        )
        translationSettings.provider = .apple
        translationSettings.targetLanguage = .simplifiedChinese
        return (
            EPUBReaderViewModel(
                book: book,
                modelContext: container.mainContext,
                lexicalLookupService: lexicalLookupService,
                translationSettings: translationSettings,
                publicationService: publicationService,
                loadTiming: loadTiming,
                translationTiming: translationTiming,
                quickTranslationIndicatorTiming: quickTranslationIndicatorTiming,
                backendTranslationServiceFactory: backendTranslationServiceFactory
            ),
            container,
            book
        )
    }

    private func selection(_ text: String) -> ReaderSelectionPayload {
        ReaderSelectionPayload(text: text, locatorJSON: nil, frame: nil)
    }

    private func makeResult(
        sourceText: String,
        translatedText: String? = nil
    ) -> TranslationResult {
        TranslationResult(
            sourceText: sourceText,
            translatedText: translatedText ?? "译文：\(sourceText)",
            sourceLanguage: .english,
            targetLanguage: .simplifiedChinese,
            providerIdentifier: TranslationCacheStore.appleProviderIdentifier,
            providerVersion: TranslationCacheStore.appleProviderVersion,
            isFromCache: false
        )
    }
}

@MainActor
private final class TestTranslationCredentialStore: TranslationCredentialStoring {
    private var secrets: [String: String] = [:]

    func readSecret(account: String) throws -> String? {
        secrets[account]
    }

    func saveSecret(_ secret: String?, account: String) throws {
        secrets[account] = secret
    }
}

@MainActor
private final class HangingReaderPublicationOpener: ReaderPublicationOpening {
    private(set) var callCount = 0
    private(set) var cancellationCount = 0

    func open(book: BookRecord) async throws -> Publication {
        callCount += 1
        do {
            try await Task.sleep(for: .seconds(60))
        } catch is CancellationError {
            cancellationCount += 1
            throw CancellationError()
        }
        throw ReaderError.invalidEPUB
    }
}

@MainActor
private final class CountingFailingReaderPublicationOpener: ReaderPublicationOpening {
    private(set) var callCount = 0

    func open(book: BookRecord) async throws -> Publication {
        callCount += 1
        throw ReaderError.invalidEPUB
    }
}

@MainActor
private final class FirstCancellationReaderPublicationOpener: ReaderPublicationOpening {
    private(set) var callCount = 0

    func open(book: BookRecord) async throws -> Publication {
        callCount += 1
        if callCount == 1 {
            throw CancellationError()
        }
        throw ReaderError.invalidEPUB
    }
}

private actor RecordingTranslationService: TranslationService {
    private var callCount = 0
    private var capturedContext: String?

    func translate(
        text: String,
        sourceLanguage: LanguageCode?,
        targetLanguage: LanguageCode
    ) async throws -> TranslationResult {
        try await translate(
            text: text,
            context: nil,
            sourceLanguage: sourceLanguage,
            targetLanguage: targetLanguage
        )
    }

    func translate(
        text: String,
        context: String?,
        sourceLanguage: LanguageCode?,
        targetLanguage: LanguageCode
    ) async throws -> TranslationResult {
        guard let sourceLanguage else { throw ServiceError.unsupportedLanguage }
        callCount += 1
        capturedContext = context
        return TranslationResult(
            sourceText: text,
            translatedText: "译文：\(text)",
            sourceLanguage: sourceLanguage,
            targetLanguage: targetLanguage,
            providerIdentifier: TranslationCacheStore.appleProviderIdentifier,
            providerVersion: TranslationCacheStore.appleProviderVersion,
            isFromCache: false
        )
    }

    func numberOfCalls() -> Int {
        callCount
    }

    func lastContext() -> String? {
        capturedContext
    }
}

private actor FlakyTranslationService: TranslationService {
    private var callCount = 0

    func translate(
        text: String,
        sourceLanguage: LanguageCode?,
        targetLanguage: LanguageCode
    ) async throws -> TranslationResult {
        guard let sourceLanguage else { throw ServiceError.unsupportedLanguage }
        callCount += 1
        if callCount == 1 {
            throw ServiceError.temporarilyUnavailable
        }
        return TranslationResult(
            sourceText: text,
            translatedText: "重试成功。",
            sourceLanguage: sourceLanguage,
            targetLanguage: targetLanguage,
            providerIdentifier: TranslationCacheStore.appleProviderIdentifier,
            providerVersion: TranslationCacheStore.appleProviderVersion,
            isFromCache: false
        )
    }

    func numberOfCalls() -> Int {
        callCount
    }
}

private actor InvisibleThenValidTranslationService: TranslationService {
    private var callCount = 0

    func translate(
        text: String,
        sourceLanguage: LanguageCode?,
        targetLanguage: LanguageCode
    ) async throws -> TranslationResult {
        guard let sourceLanguage else { throw ServiceError.unsupportedLanguage }
        callCount += 1
        return TranslationResult(
            sourceText: text,
            translatedText: callCount == 1
                ? "\u{200B}\u{2060}"
                : "恢复后的可见译文。",
            sourceLanguage: sourceLanguage,
            targetLanguage: targetLanguage,
            providerIdentifier: TranslationCacheStore.appleProviderIdentifier,
            providerVersion: TranslationCacheStore.appleProviderVersion,
            isFromCache: false
        )
    }

    func numberOfCalls() -> Int {
        callCount
    }
}

private actor ControlledTranslationService: TranslationService {
    private struct PendingRequest {
        let sourceLanguage: LanguageCode
        let targetLanguage: LanguageCode
        let continuation: CheckedContinuation<TranslationResult, Error>
    }

    private var pending: [String: PendingRequest] = [:]
    private var requestedTexts: Set<String> = []
    private var requestWaiters: [String: [CheckedContinuation<Void, Never>]] = [:]

    func translate(
        text: String,
        sourceLanguage: LanguageCode?,
        targetLanguage: LanguageCode
    ) async throws -> TranslationResult {
        guard let sourceLanguage else { throw ServiceError.unsupportedLanguage }
        requestedTexts.insert(text)
        requestWaiters.removeValue(forKey: text)?.forEach { $0.resume() }

        return try await withCheckedThrowingContinuation { continuation in
            pending[text] = PendingRequest(
                sourceLanguage: sourceLanguage,
                targetLanguage: targetLanguage,
                continuation: continuation
            )
        }
    }

    func waitUntilRequested(_ text: String) async {
        guard !requestedTexts.contains(text) else { return }
        await withCheckedContinuation { continuation in
            requestWaiters[text, default: []].append(continuation)
        }
    }

    func complete(_ text: String, translatedText: String) {
        guard let request = pending.removeValue(forKey: text) else { return }
        request.continuation.resume(
            returning: TranslationResult(
                sourceText: text,
                translatedText: translatedText,
                sourceLanguage: request.sourceLanguage,
                targetLanguage: request.targetLanguage,
                providerIdentifier: TranslationCacheStore.appleProviderIdentifier,
                providerVersion: TranslationCacheStore.appleProviderVersion,
                isFromCache: false
            )
        )
    }
}

private struct CancellingTranslationService: TranslationService {
    func translate(
        text: String,
        sourceLanguage: LanguageCode?,
        targetLanguage: LanguageCode
    ) async throws -> TranslationResult {
        throw CancellationError()
    }
}

private struct FailingTranslationService: TranslationService {
    let error: ServiceError

    func translate(
        text: String,
        sourceLanguage: LanguageCode?,
        targetLanguage: LanguageCode
    ) async throws -> TranslationResult {
        throw error
    }
}

private struct SuspendedTranslationService: TranslationService {
    func translate(
        text: String,
        sourceLanguage: LanguageCode?,
        targetLanguage: LanguageCode
    ) async throws -> TranslationResult {
        try await Task.sleep(for: .seconds(60))
        throw ServiceError.temporarilyUnavailable
    }
}

private struct SuspendedContextTranslationService:
    TranslationService,
    ContextExplanationService
{
    func translate(
        text: String,
        sourceLanguage: LanguageCode?,
        targetLanguage: LanguageCode
    ) async throws -> TranslationResult {
        try await Task.sleep(for: .seconds(60))
        throw ServiceError.temporarilyUnavailable
    }

    func explain(
        _ request: ContextExplanationRequest
    ) async throws -> ContextExplanationResult {
        try await Task.sleep(for: .seconds(60))
        throw ServiceError.temporarilyUnavailable
    }
}
