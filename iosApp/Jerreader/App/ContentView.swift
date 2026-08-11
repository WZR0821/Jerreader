import JerreaderCore
import SwiftData
import SwiftUI

/// Computed rather than a stored `let`: a Kotlin object arrives in Swift as a
/// plain class, so it is not `Sendable`, and Swift 6 rejects it as a global
/// constant. Nothing here is mutable, so re-reading `shared` costs nothing.
private var copy: JerreaderCopy { JerreaderCopy.shared }

struct ContentView: View {
    @Environment(\.modelContext) private var modelContext
    @Environment(\.scenePhase) private var scenePhase

    @State private var selectedTab: AppTab
    @State private var incomingBookURL: URL?
    @StateObject private var translationSettings = TranslationSettingsStore()
    @AppStorage(JerreaderThemePreferences.storageKey)
    private var themeColorRawValue = JerreaderThemeColorChoice.ocean.rawValue
    @AppStorage(LearningModulePreferences.visibilityKey)
    private var learningModuleVisible = true

    private var themeColor: JerreaderThemeColorChoice {
        JerreaderThemeColorChoice(rawValue: themeColorRawValue) ?? .ocean
    }

    init() {
#if DEBUG
        let arguments = ProcessInfo.processInfo.arguments
        // UI tests need a writable starting value. A `-key YES` launch
        // argument lives in UserDefaults' read-only argument domain, so a
        // Toggle can appear to accept a tap while the value immediately snaps
        // back to YES. This dedicated fixture writes the normal app domain
        // before AppStorage is first read.
        if arguments.contains("--jerreader-show-learning-module") {
            UserDefaults.standard.set(
                true,
                forKey: LearningModulePreferences.visibilityKey
            )
        }
        let launchesLearning = arguments.contains("--jerreader-standalone-translator-ui-test")
            || arguments.contains("--jerreader-vocabulary-ui-test")
            || arguments.contains("--jerreader-review-ui-test")
        let initialTab: AppTab = launchesLearning ? .learning : .library
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
                    Label(copy.libraryTab, systemImage: "books.vertical.fill")
                }
                .tag(AppTab.library)

            if learningModuleVisible {
                LearningHubView(translationSettings: translationSettings)
                    .tabItem {
                        Label(copy.learningTab, systemImage: "character.book.closed.fill")
                    }
                    .tag(AppTab.learning)
            }

            AppSettingsView(translationSettings: translationSettings)
                .tabItem {
                    Label(copy.settingsTab, systemImage: "gearshape.fill")
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
        .onChange(of: learningModuleVisible) { _, isVisible in
            if !isVisible, selectedTab == .learning {
                selectedTab = .library
            }
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
