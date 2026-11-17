import Foundation
import Observation

/// In-book search state, split out of `EPUBReaderViewModel`. Only the search
/// panel reads it, so with Observation a new result list redraws that panel
/// and nothing else on the reader screen.
@MainActor
@Observable
final class ReaderSearchModel {
    private(set) var results: [ReaderSearchResult] = []
    private(set) var isSearching = false
    private(set) var errorMessage: String?
    private(set) var lastQuery = ""

    @ObservationIgnored private var task: Task<Void, Never>?

    /// Starts a search, replacing any search still running. A result that
    /// arrives after the query changed is dropped, so a slow earlier search
    /// cannot overwrite the results of the query the reader typed last.
    func search(_ query: String, in controller: (any PublicationReaderController)?) {
        let normalized = query.trimmingCharacters(in: .whitespacesAndNewlines)
        task?.cancel()
        task = nil
        errorMessage = nil
        lastQuery = normalized
        results = []

        guard !normalized.isEmpty else {
            isSearching = false
            return
        }
        guard let controller else {
            isSearching = false
            errorMessage = ReaderSearchError.unavailable.localizedDescription
            return
        }

        isSearching = true
        task = Task { @MainActor [weak self] in
            guard let self else { return }
            defer {
                if lastQuery == normalized {
                    isSearching = false
                    task = nil
                }
            }
            do {
                let locators = try await controller.search(normalized)
                try Task.checkCancellation()
                guard lastQuery == normalized else { return }
                results = locators.map(ReaderSearchResult.init(locator:))
            } catch is CancellationError {
                return
            } catch {
                guard lastQuery == normalized else { return }
                errorMessage = (error as? LocalizedError)?.errorDescription
                    ?? ReaderSearchError.failed.localizedDescription
            }
        }
    }

    func cancel() {
        task?.cancel()
        task = nil
        isSearching = false
    }
}
