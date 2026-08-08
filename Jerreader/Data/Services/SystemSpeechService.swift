import AVFoundation
import Foundation

@MainActor
final class SystemSpeechService:
    NSObject,
    SpeechService,
    AVSpeechSynthesizerDelegate
{
    static let shared = SystemSpeechService()

    private let synthesizer = AVSpeechSynthesizer()
    private var activeUtterance: AVSpeechUtterance?
    private var activeUtteranceID: UInt?
    private var onRangeChange: ((NSRange) -> Void)?
    private var onFinish: ((Bool) -> Void)?

    override init() {
        super.init()
        synthesizer.delegate = self
    }

    func speak(
        _ text: String,
        language: LanguageCode,
        rate: Double,
        onRangeChange: @escaping @MainActor (NSRange) -> Void,
        onFinish: @escaping @MainActor (Bool) -> Void
    ) async throws {
        let normalizedText = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalizedText.isEmpty else {
            throw ServiceError.emptyText
        }

        let voiceLanguage: String
        switch language {
        case .japanese:
            voiceLanguage = "ja-JP"
        case .english:
            voiceLanguage = "en-US"
        case .simplifiedChinese:
            voiceLanguage = "zh-CN"
        }

        guard let voice = AVSpeechSynthesisVoice(language: voiceLanguage) else {
            throw ServiceError.voiceUnavailable
        }

        stop()

        let utterance = AVSpeechUtterance(string: normalizedText)
        utterance.voice = voice
        utterance.rate = Self.utteranceRate(multiplier: rate)
        activeUtterance = utterance
        activeUtteranceID = Self.identifier(for: utterance)
        self.onRangeChange = onRangeChange
        self.onFinish = onFinish
        synthesizer.speak(utterance)
    }

    func stop() {
        guard activeUtterance != nil || synthesizer.isSpeaking else { return }
        activeUtterance = nil
        activeUtteranceID = nil
        onRangeChange = nil
        let completion = onFinish
        onFinish = nil
        synthesizer.stopSpeaking(at: .immediate)
        completion?(false)
    }

    nonisolated func speechSynthesizer(
        _ synthesizer: AVSpeechSynthesizer,
        willSpeakRangeOfSpeechString characterRange: NSRange,
        utterance: AVSpeechUtterance
    ) {
        let utteranceID = Self.identifier(for: utterance)
        let location = characterRange.location
        let length = characterRange.length
        Task { @MainActor [weak self] in
            guard let self, activeUtteranceID == utteranceID else { return }
            onRangeChange?(NSRange(location: location, length: length))
        }
    }

    nonisolated func speechSynthesizer(
        _ synthesizer: AVSpeechSynthesizer,
        didFinish utterance: AVSpeechUtterance
    ) {
        let utteranceID = Self.identifier(for: utterance)
        Task { @MainActor [weak self] in
            self?.complete(utteranceID: utteranceID, finished: true)
        }
    }

    nonisolated func speechSynthesizer(
        _ synthesizer: AVSpeechSynthesizer,
        didCancel utterance: AVSpeechUtterance
    ) {
        let utteranceID = Self.identifier(for: utterance)
        Task { @MainActor [weak self] in
            self?.complete(utteranceID: utteranceID, finished: false)
        }
    }

    private func complete(utteranceID: UInt, finished: Bool) {
        guard activeUtteranceID == utteranceID else { return }
        activeUtterance = nil
        activeUtteranceID = nil
        onRangeChange = nil
        let completion = onFinish
        onFinish = nil
        completion?(finished)
    }

    nonisolated private static func identifier(
        for utterance: AVSpeechUtterance
    ) -> UInt {
        UInt(bitPattern: Unmanaged.passUnretained(utterance).toOpaque())
    }

    static func utteranceRate(multiplier: Double) -> Float {
        let normalized = min(max(multiplier, 0.5), 2)
        return min(
            max(
                AVSpeechUtteranceDefaultSpeechRate * Float(normalized),
                AVSpeechUtteranceMinimumSpeechRate
            ),
            AVSpeechUtteranceMaximumSpeechRate
        )
    }
}
