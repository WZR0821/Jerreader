import class JerreaderCore.VocabularyLearningPolicy
import Foundation
import SwiftData

@MainActor
enum VocabularyDataMigration {
    private static let migrationKey = "migration.v1.4.vocabulary-state"
    private static let reviewMigrationKey = "migration.v1.5.review-state"

    /// Backfills fields that SwiftData added with safe defaults in 1.4.
    static func runIfNeeded(
        container: ModelContainer,
        defaults: UserDefaults = .standard
    ) throws {
        let needsVocabularyMigration = !defaults.bool(forKey: migrationKey)
        let needsReviewMigration = !defaults.bool(forKey: reviewMigrationKey)
        guard needsVocabularyMigration || needsReviewMigration else { return }
        let context = ModelContext(container)
        let records = try context.fetch(FetchDescriptor<WordLookupRecord>())
        if needsVocabularyMigration {
            for record in records {
                if record.isFavorite, record.vocabularyStatusRawValue == "new" {
                    record.vocabularyStatusRawValue = "learning"
                }
                if record.contextHistoryText.isEmpty, let sentence = record.sentenceContext {
                    record.contextHistoryText = VocabularyLearningPolicy.shared.encodeContexts(
                        contexts: [sentence]
                    )
                }
            }
        }
        if context.hasChanges {
            try context.save()
        }
        if needsVocabularyMigration {
            defaults.set(true, forKey: migrationKey)
        }
        if needsReviewMigration {
            // New review properties have SwiftData defaults. The marker keeps
            // future 1.5 migrations independent from the already-run 1.4 pass.
            defaults.set(true, forKey: reviewMigrationKey)
        }
    }
}
