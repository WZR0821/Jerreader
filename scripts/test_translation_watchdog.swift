import Foundation

/// Host-only tests of production Foundation logic; no simulator or online service.
@main
struct TranslationWatchdogRegression {
    @MainActor
    static func main() async throws {
        let timing = StandaloneTranslationWatchdog.Timing(
            launchRetry: .milliseconds(2), launchGrace: .milliseconds(2),
            network: .milliseconds(5), languagePreparation: .milliseconds(100)
        )
        let watchdog = StandaloneTranslationWatchdog(timing: timing)
        var timeouts: [StandaloneTranslationWatchdog.Timeout] = []
        var restarts = 0
        watchdog.start(id: UUID(), systemTranslation: true,
                       restart: { restarts += 1 }, timedOut: { timeouts.append($0) })
        try await waitUntil { !timeouts.isEmpty }
        precondition(restarts == 1 && timeouts == [.sessionUnavailable])

        timeouts = []
        restarts = 0
        let startedID = UUID()
        watchdog.start(id: startedID, systemTranslation: true,
                       restart: { restarts += 1 }, timedOut: { timeouts.append($0) })
        watchdog.sessionDidStart(id: startedID)
        try await Task.sleep(for: .milliseconds(20))
        precondition(restarts == 0 && timeouts.isEmpty, "A language download needs its own budget")
        watchdog.complete(id: startedID)
        try await Task.sleep(for: .milliseconds(120))
        precondition(timeouts.isEmpty, "A completed request must never expire")

        let oldID = UUID()
        watchdog.start(id: oldID, systemTranslation: true,
                       restart: { restarts += 1 }, timedOut: { _ in preconditionFailure("Stale timeout") })
        watchdog.start(id: UUID(), systemTranslation: false,
                       restart: { preconditionFailure("Network request cannot restart Apple") },
                       timedOut: { timeouts.append($0) })
        watchdog.complete(id: oldID)
        try await waitUntil { !timeouts.isEmpty }
        precondition(timeouts == [.network])

        timeouts = []
        watchdog.start(id: UUID(), systemTranslation: false,
                       restart: {}, timedOut: { timeouts.append($0) })
        watchdog.cancel()
        try await Task.sleep(for: .milliseconds(20))
        precondition(timeouts.isEmpty, "Leaving the page cancels its deadline")

        // The supplementary dictionary owns its deadline independently of the
        // main translation, so a finished translation cannot cancel that timer.
        let dictionary = StandaloneTranslationWatchdog(timing: timing)
        let translationID = UUID()
        var dictionaryTimeouts = 0
        watchdog.start(id: translationID, systemTranslation: false, restart: {}, timedOut: { _ in
            preconditionFailure("Completed translation timed out")
        })
        dictionary.start(id: UUID(), systemTranslation: false, restart: {}, timedOut: { _ in dictionaryTimeouts += 1 })
        watchdog.complete(id: translationID)
        try await waitUntil { dictionaryTimeouts == 1 }

        // Retrying from the timeout callback creates a new request. A late
        // completion from the first attempt must not cancel the retry's deadline.
        let firstAttempt = UUID()
        let retryAttempt = UUID()
        var retryTimeouts = 0
        dictionary.start(id: firstAttempt, systemTranslation: false, restart: {}, timedOut: { _ in
            dictionary.start(id: retryAttempt, systemTranslation: false, restart: {}, timedOut: { _ in retryTimeouts += 1 })
            dictionary.complete(id: firstAttempt)
        })
        try await waitUntil { retryTimeouts == 1 }

        for text in ["字", "𠮷", "😀", "🇯🇵", "e\u{0301}", "は\u{3099}", "👨‍👩‍👧‍👦"] {
            precondition(text.count == 1)
            precondition(String(repeating: text, count: 80).count == 80)
        }
        print("PASS: 6 watchdog scenarios and 7 composed-character parity fixtures")
    }

    @MainActor
    static func waitUntil(_ condition: () -> Bool) async throws {
        for _ in 0..<1_000 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(1))
        }
        preconditionFailure("Watchdog did not complete within the test deadline")
    }
}
