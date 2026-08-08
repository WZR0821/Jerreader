import SwiftData
import SwiftUI

struct ContentView: View {
    @Environment(\.modelContext) private var modelContext
    @Environment(\.scenePhase) private var scenePhase

    @State private var selectedTab: AppTab
    @State private var incomingBookURL: URL?
    @StateObject private var translationSettings = TranslationSettingsStore()
    @AppStorage(JerreaderThemePreferences.storageKey)
    private var themeColorRawValue = JerreaderThemeColorChoice.ocean.rawValue

    private var themeColor: JerreaderThemeColorChoice {
        JerreaderThemeColorChoice(rawValue: themeColorRawValue) ?? .ocean
    }

    init() {
#if DEBUG
        let initialTab: AppTab = ProcessInfo.processInfo.arguments.contains(
            "--jerreader-standalone-translator-ui-test"
        ) ? .learning : .library
#else
        let initialTab: AppTab = .library
#endif
        _selectedTab = State(initialValue: initialTab)
    }

    var body: some View {
        TabView(selection: $selectedTab) {
            LibraryView(
                incomingBookURL: $incomingBookURL,
                translationSettings: translationSettings
            )
                .tabItem {
                    Label("书架", systemImage: "books.vertical.fill")
                }
                .tag(AppTab.library)

            LearningHubView(translationSettings: translationSettings)
                .tabItem {
                    Label("学习", systemImage: "character.book.closed.fill")
                }
                .tag(AppTab.learning)

            AppSettingsView(translationSettings: translationSettings)
                .tabItem {
                    Label("设置", systemImage: "gearshape.fill")
                }
                .tag(AppTab.settings)
        }
        .tabViewStyle(.sidebarAdaptable)
        .tint(JerreaderTheme.accent(for: themeColor))
        .toolbarBackground(.ultraThinMaterial, for: .tabBar)
        .toolbarBackground(.visible, for: .tabBar)
        .background(JerreaderCanvasBackground())
        .sensoryFeedback(.selection, trigger: selectedTab)
        .onOpenURL { url in
            selectedTab = .library
            incomingBookURL = url
        }
        .task {
            await LibraryBackupAutomation.shared.performIfDue(
                context: modelContext
            )
        }
        .onChange(of: scenePhase) { _, phase in
            guard phase == .active || phase == .background else { return }
            Task {
                await LibraryBackupAutomation.shared.performIfDue(
                    context: modelContext
                )
            }
        }
    }
}

private enum AppTab: Hashable {
    case library
    case learning
    case settings
}

#Preview {
    ContentView()
        .modelContainer(
            for: [
                BookRecord.self,
                WordLookupRecord.self,
                TranslationCacheRecord.self,
                TranslationFavoriteRecord.self,
                ReadingBookmarkRecord.self,
                ReadingAnnotationRecord.self
            ],
            inMemory: true
        )
}
