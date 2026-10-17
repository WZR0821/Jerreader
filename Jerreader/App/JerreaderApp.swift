@preconcurrency import SwiftData
import SwiftUI

@main
struct JerreaderApp: App {
    private let modelContainer: ModelContainer?
    private let storeErrorDescription: String?

    init() {
        do {
#if DEBUG
            if ProcessInfo.processInfo.arguments.contains("--jerreader-learning-ui-fixture") {
                let schema = Schema(versionedSchema: JerreaderSchemaV2.self)
                let container = try ModelContainer(for: schema, configurations: [ModelConfiguration(schema: schema, isStoredInMemoryOnly: true)])
                if !ProcessInfo.processInfo.arguments.contains("--jerreader-learning-ui-empty") {
                    try LearningUIFixture.seed(container.mainContext)
                }
                modelContainer = container
                storeErrorDescription = nil
                return
            }
#endif
            modelContainer = try ModelContainer(
                for: Schema(versionedSchema: JerreaderSchemaV2.self),
                migrationPlan: JerreaderMigrationPlan.self
            )
            storeErrorDescription = nil
        } catch {
            modelContainer = nil
            storeErrorDescription = error.localizedDescription
        }
    }

#if DEBUG
    private var learningFixtureColorScheme: ColorScheme? {
        let arguments = ProcessInfo.processInfo.arguments
        guard arguments.contains("--jerreader-learning-ui-fixture") else { return nil }
        return arguments.contains("--jerreader-learning-ui-dark") ? .dark : .light
    }
#endif

    var body: some Scene {
        WindowGroup {
            if let modelContainer {
                ContentView()
                    .modelContainer(modelContainer)
                    .dynamicTypeSize(...DynamicTypeSize.xLarge)
#if DEBUG
                    .preferredColorScheme(learningFixtureColorScheme)
#endif
            } else {
                DataStoreRecoveryView(
                    detail: storeErrorDescription ?? "未知错误"
                )
                .dynamicTypeSize(...DynamicTypeSize.xLarge)
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
