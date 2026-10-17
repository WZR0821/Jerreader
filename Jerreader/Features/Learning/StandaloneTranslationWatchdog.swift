import Foundation

/// Owns the deadline independently of a provider that may ignore cancellation.
@MainActor
final class StandaloneTranslationWatchdog {
    struct Timing: Sendable {
        var launchRetry: Duration = .milliseconds(1_500)
        var launchGrace: Duration = .seconds(5)
        var network: Duration = .seconds(30)
        var languagePreparation: Duration = .seconds(180)
    }

    enum Timeout: Equatable {
        case sessionUnavailable
        case network
        case languagePreparation

        var message: String {
            switch self {
            case .sessionUnavailable:
                return "系统翻译没有启动，请重试；若仍失败，请先在 Apple「翻译」App 下载对应语言。"
            case .network:
                return "翻译等待超时，请检查网络后重试。"
            case .languagePreparation:
                return "语言准备或翻译等待超时，请保持 App 在前台，或先下载对应语言后重试。"
            }
        }
    }

    private let timing: Timing
    private var task: Task<Void, Never>?
    private var requestID: UUID?
    private var sessionStarted = false

    init(timing: Timing = Timing()) {
        self.timing = timing
    }

    func start(
        id: UUID,
        systemTranslation: Bool,
        restart: @escaping @MainActor () -> Void,
        timedOut: @escaping @MainActor (Timeout) -> Void
    ) {
        cancel()
        requestID = id
        task = Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                if systemTranslation {
                    try await Task.sleep(for: timing.launchRetry)
                    guard requestID == id else { return }
                    if !sessionStarted {
                        restart()
                        try await Task.sleep(for: timing.launchGrace)
                        guard requestID == id else { return }
                        if !sessionStarted {
                            finish(id: id, reason: .sessionUnavailable, callback: timedOut)
                            return
                        }
                    }
                    try await Task.sleep(for: timing.languagePreparation)
                    finish(id: id, reason: .languagePreparation, callback: timedOut)
                } else {
                    try await Task.sleep(for: timing.network)
                    finish(id: id, reason: .network, callback: timedOut)
                }
            } catch {
                // Replacing input, leaving the page or completing a request cancels its timer.
            }
        }
    }

    func sessionDidStart(id: UUID) {
        guard requestID == id else { return }
        sessionStarted = true
    }

    func complete(id: UUID) {
        guard requestID == id else { return }
        cancel()
    }

    func cancel() {
        requestID = nil
        sessionStarted = false
        task?.cancel()
        task = nil
    }

    private func finish(id: UUID, reason: Timeout, callback: (Timeout) -> Void) {
        guard !Task.isCancelled, requestID == id else { return }
        requestID = nil
        task = nil
        callback(reason)
    }
}
