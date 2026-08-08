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
            modelContainer = try ModelContainer(
                for: Schema(versionedSchema: JerreaderSchemaV1.self),
                migrationPlan: JerreaderMigrationPlan.self
            )
            storeErrorDescription = nil
        } catch {
            modelContainer = nil
            storeErrorDescription = error.localizedDescription
        }
    }

    var body: some Scene {
        WindowGroup {
            if let modelContainer {
                ContentView()
                    .modelContainer(modelContainer)
            } else {
                DataStoreRecoveryView(
                    detail: storeErrorDescription ?? "未知错误"
                )
            }
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
