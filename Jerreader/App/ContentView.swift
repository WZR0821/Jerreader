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
                .jerreaderTabSurface(themeColor)
                .tabItem {
                    Label { Text("书架") } icon: { Image(uiImage: JerreaderIconImage.image("books.vertical.fill")) }
                }
                .tag(AppTab.library)

            LearningHubView(translationSettings: translationSettings)
                .jerreaderTabSurface(themeColor)
                .tabItem {
                    Label { Text("学习") } icon: { Image(uiImage: JerreaderIconImage.image("character.book.closed.fill")) }
                }
                .tag(AppTab.learning)

            AppSettingsView(translationSettings: translationSettings)
                .jerreaderTabSurface(themeColor)
                .tabItem {
                    Label { Text("设置") } icon: { Image(uiImage: JerreaderIconImage.image("gearshape.fill")) }
                }
                .tag(AppTab.settings)
        }
        .tabViewStyle(.sidebarAdaptable)
        .tint(JerreaderTheme.accent(for: themeColor))
        .background(JerreaderCanvasBackground())
        .sensoryFeedback(.selection, trigger: selectedTab)
        .onOpenURL { url in
            selectedTab = .library
            incomingBookURL = url
        }
        .task {
#if DEBUG
            guard !ProcessInfo.processInfo.arguments.contains("--jerreader-learning-ui-fixture") else { return }
#endif
            await LibraryBackupAutomation.shared.performIfDue(
                context: modelContext
            )
        }
        .onChange(of: scenePhase) { _, phase in
#if DEBUG
            guard !ProcessInfo.processInfo.arguments.contains("--jerreader-learning-ui-fixture") else { return }
#endif
            guard phase == .active || phase == .background else { return }
            Task {
                await LibraryBackupAutomation.shared.performIfDue(
                    context: modelContext
                )
            }
        }
    }
}

private extension View {
    /// The same surface on every tab: the canvas behind the page and a
    /// canvas-coloured tab bar. A tab bar background only takes effect when it
    /// is set on a tab's content, not on the TabView itself.
    @MainActor
    func jerreaderTabSurface(_ themeColor: JerreaderThemeColorChoice) -> some View {
        background {
            JerreaderCanvasBackground()
        }
        .toolbarBackground(JerreaderTheme.canvas(for: themeColor), for: .tabBar)
        .toolbarBackground(.visible, for: .tabBar)
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
