import Foundation

/// Turns saved word lookups into the plain signals the reading-companion rules
/// read. Mirrors androidApp/.../learning/ReadingErrorSignals.kt.
///
/// The surface form (not `displayTerm`, which prefers the lemma) is the term,
/// so a word met in an inflected form is still recognised as one.
enum ReadingCompanionSignals {
    static func from(_ records: [WordLookupRecord]) -> [ReadingErrorSignal] {
        records.compactMap(signal)
    }

    static func signal(_ record: WordLookupRecord) -> ReadingErrorSignal? {
        guard let bookID = record.sourceBookID?.uuidString else { return nil }
        let term = ReadingCompanionText.trimmed(record.surfaceForm)
        guard !term.isEmpty else { return nil }
        return ReadingErrorSignal(
            lookupKey: record.lookupKey,
            displayTerm: term,
            lemma: record.lemma,
            sentenceContext: record.sentenceContext ?? "",
            sourceBookID: bookID,
            sourceBookTitle: record.sourceBookTitle ?? "",
            lookupCount: record.lookupCount,
            review: ReadingReviewSummary.of(
                WordReviewState.decode(record.reviewData).answers.map(\.known)
            )
        )
    }
}
