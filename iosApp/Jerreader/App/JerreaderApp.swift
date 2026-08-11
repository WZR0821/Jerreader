@preconcurrency import SwiftData
import SwiftUI

enum JerreaderSchemaV1: VersionedSchema {
    static let versionIdentifier = Schema.Version(1, 0, 0)
    static let models: [any PersistentModel.Type] = [
        BookRecord.self,
        WordLookupRecord.self,
        TranslationCacheRecord.self,
        TranslationFavoriteRecord.self,
        ReadingBookmarkRecord.self,
        ReadingAnnotationRecord.self,
    ]
}

enum JerreaderMigrationPlan: SchemaMigrationPlan {
    static let schemas: [any VersionedSchema.Type] = [JerreaderSchemaV1.self]
    static let stages: [MigrationStage] = []
}

@main
struct JerreaderApp: App {
    private let modelContainer: ModelContainer?
    private let storeErrorDescription: String?

    init() {
        do {
            let container = try ModelContainer(
                for: Schema(versionedSchema: JerreaderSchemaV1.self),
                migrationPlan: JerreaderMigrationPlan.self
            )
            try VocabularyDataMigration.runIfNeeded(container: container)
            modelContainer = container
            storeErrorDescription = nil
        } catch {
            modelContainer = nil
            storeErrorDescription = error.localizedDescription
        }
    }

    var body: some Scene {
        WindowGroup {
            Group {
                if let modelContainer {
                    ContentView()
                        .modelContainer(modelContainer)
                } else {
                    DataStoreRecoveryView(
                        detail: storeErrorDescription ?? "未知错误"
                    )
                }
            }
            // 白天 / 黑夜 / 跟随系统 is applied once, here, so it reaches the
            // library, the settings pages and every sheet they present. The
            // reader sets its own scheme from the page theme and is presented
            // as a cover, so it keeps deciding for itself.
            .jerreaderAppearance()
        }
    }
}

private struct DataStoreRecoveryView: View {
    let detail: String

    var body: some View {
        ContentUnavailableView {
            Label("无法打开本机数据", systemImage: "externaldrive.badge.exclamationmark")
        } description: {
            Text("书架数据库没有成功打开。App 不会自动删除或重建数据，以免丢失书籍和进度。\n\n\(detail)")
        }
        .padding(30)
    }
}
