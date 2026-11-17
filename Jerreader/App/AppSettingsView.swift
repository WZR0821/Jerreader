import SwiftData
import SwiftUI
import UIKit

struct AppSettingsView: View {
    @ObservedObject var translationSettings: TranslationSettingsStore
    @AppStorage(JerreaderThemePreferences.storageKey)
    private var themeColorRawValue = JerreaderThemeColorChoice.ocean.rawValue
    @AppStorage(ReaderAppearanceDefaults.themeKey)
    private var readerThemeRawValue = ReaderThemeChoice.light.rawValue
    @AppStorage(ReaderAppearanceDefaults.readingModeKey)
    private var readingModeRawValue = ReaderReadingMode.paginated.rawValue
    @Environment(\.dynamicTypeSize) private var typeSize

    private var themeColor: JerreaderThemeColorChoice {
        JerreaderThemeColorChoice(rawValue: themeColorRawValue) ?? .ocean
    }

    /// The home page fills one screen exactly: its rows share whatever height
    /// a taller phone leaves over, so nothing scrolls and no gap is left above
    /// the tab bar. A system list cannot do that, so the groups are drawn here
    /// to look like one. Large text or a short screen still scrolls.
    var body: some View {
        NavigationStack {
            OneScreenPage(identifier: "settingsHome") { size in
                // The margin the system gives a grouped list at this width.
                let margin: CGFloat = size.width >= 400 ? 20 : 16
                FillStack {
                    brand
                    Group {
                        groupTitle("阅读")
                        row("界面主题", value: themeColor.title, icon: "paintpalette.fill",
                            color: JerreaderTheme.accent(for: themeColor), id: "themeSettingsLink",
                            position: .first, edge: margin) {
                            AppThemeColorSettingsView()
                        }
                        row("默认阅读排版", value: readerDefaultsSummary, icon: "textformat.size",
                            color: .indigo, id: "readerDefaultsLink", position: .last, edge: margin) {
                            GlobalReaderDefaultsView()
                        }
                    }
                    Group {
                        groupTitle("翻译")
                        row("翻译与 AI", value: translationSettings.provider.title,
                            icon: "character.bubble.fill", color: .blue, id: "translationSettingsLink",
                            position: .first, edge: margin) {
                            TranslationSettingsDetailView(
                                settings: translationSettings,
                                quickSentenceEnabled: quickSentenceBinding,
                                disablesTapPageTurnsDuringQuickTranslation:
                                    disablesTapPageTurnsDuringQuickTranslationBinding,
                                showsQuickSentenceToggle: true
                            )
                        }
                        row("AI 大模型", value: aiModelsSummary, warn: aiModelsNeedKey, icon: "sparkles",
                            color: .green, id: "aiModelsSettingsLink", position: .last, edge: margin) {
                            AIModelsView(store: translationSettings)
                        }
                    }
                    Group {
                        groupTitle("数据与帮助")
                        row("备份与恢复", icon: "externaldrive.fill", color: .orange, id: "backupSettingsLink",
                            position: .first, edge: margin) {
                            LibraryBackupSettingsView(translationSettings: translationSettings)
                        }
                        row("数据与隐私", icon: "hand.raised.fill", color: .teal, id: "privacySettingsLink",
                            position: .middle, edge: margin) {
                            DataPrivacySettingsView()
                        }
                        row("操作指南", icon: "questionmark", color: .gray, id: "guideSettingsLink",
                            position: .last, edge: margin) {
                            AppGuideView()
                        }
                    }
                    credit
                }
                .padding(.horizontal, margin)
                .frame(maxWidth: 720)
                .frame(maxWidth: .infinity)
            }
            .background(JerreaderCanvasBackground())
            .navigationTitle("设置")
            .navigationBarTitleDisplayMode(.large)
        }
        .tint(JerreaderTheme.accent(for: themeColor))
    }

    private var brand: some View {
        HStack(spacing: 14) {
            Image("BrandMark").resizable().scaledToFill().frame(width: 56, height: 56)
                .clipShape(RoundedRectangle(cornerRadius: 12.5, style: .continuous))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text("Jerreader").font(.title2.weight(.semibold))
                Text("原版阅读 · 查词 · 翻译").font(.subheadline).foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16).padding(.vertical, 12)
        .background(JerreaderTheme.groupedRow, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        .accessibilityElement(children: .combine)
    }

    private func groupTitle(_ title: String) -> some View {
        Text(title).font(.footnote).foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 20).padding(.top, 21).padding(.bottom, 5)
            .accessibilityAddTraits(.isHeader)
    }

    private var credit: some View {
        VStack(spacing: 5) {
            Text("Jerry Wong")
                .font(.system(.footnote, design: .serif).weight(.semibold))
                .foregroundStyle(JerreaderTheme.accent(for: themeColor))
                .accessibilityIdentifier("developerCredit")
            Text("Jerreader \(versionText)").font(.caption).foregroundStyle(.secondary)
                .accessibilityIdentifier("appVersionLabel")
        }
        .frame(maxWidth: .infinity).padding(.top, 12).padding(.bottom, 8)
    }

    /// A row in the style of the system Settings app: coloured icon, title, and
    /// the current value on the trailing side.
    /// - Parameter edge: the space after the chevron, which follows the page margin as in a system list.
    private func row<Destination: View>(
        _ title: String, value: String? = nil, warn: Bool = false, icon: String, color: Color,
        id: String, position: GroupPosition, edge: CGFloat,
        @ViewBuilder destination: () -> Destination
    ) -> some View {
        // Large text puts the value under the title instead of squeezing both onto one line.
        let text = typeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 2)) : AnyLayout(HStackLayout(spacing: 8))
        return NavigationLink(destination: destination()) {
            HStack(spacing: 14) {
                Image(systemName: icon).font(.system(size: 17, weight: .medium)).foregroundStyle(.white)
                    .frame(width: 32, height: 32)
                    .background(color.gradient, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                    .accessibilityHidden(true)
                text {
                    Text(title).foregroundStyle(Color.primary).layoutPriority(1)
                    if !typeSize.isAccessibilitySize { Spacer(minLength: 0) }
                    if let value {
                        Text(value).foregroundStyle(warn ? Color.orange : Color.secondary).lineLimit(1)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: "chevron.right").font(.footnote.weight(.semibold))
                    .foregroundStyle(Color(uiColor: .tertiaryLabel)).accessibilityHidden(true)
            }
            .padding(.leading, 16).padding(.trailing, edge).padding(.vertical, 6)
            // The row takes whatever height the page hands it.
            .frame(maxHeight: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(GroupedRowStyle(position: position, separatorInset: 62))
        .accessibilityIdentifier(id)
        .stretch(22)
    }

    private var readerDefaultsSummary: String {
        let theme = ReaderThemeChoice(rawValue: readerThemeRawValue) ?? .light
        let mode = ReaderReadingMode(rawValue: readingModeRawValue) ?? .paginated
        return "\(theme.title) · \(mode.title)"
    }

    private var aiModelsHasKey: Bool {
        translationSettings.keyedProviderIDs.contains(translationSettings.aiProviders.activeID)
    }

    /// The provider in use, or 未设置 while it has no key.
    private var aiModelsSummary: String {
        let providers = translationSettings.aiProviders
        return aiModelsHasKey ? providers.displayName(for: providers.activeID) : "未设置"
    }

    /// Orange only when translation already depends on the missing key.
    private var aiModelsNeedKey: Bool {
        !aiModelsHasKey && translationSettings.provider == .directAPI
    }

    private var quickSentenceBinding: Binding<Bool> {
        Binding(
            get: { translationSettings.quickSentenceTranslationEnabled },
            set: { translationSettings.quickSentenceTranslationEnabled = $0 }
        )
    }

    private var disablesTapPageTurnsDuringQuickTranslationBinding: Binding<Bool> {
        Binding(
            get: {
                translationSettings.disablesTapPageTurnsDuringQuickTranslation
            },
            set: {
                translationSettings.disablesTapPageTurnsDuringQuickTranslation = $0
            }
        )
    }

    private var versionText: String {
        Bundle.main.object(
            forInfoDictionaryKey: "CFBundleShortVersionString"
        ) as? String ?? "—"
    }
}

private struct AppThemeColorSettingsView: View {
    @AppStorage(JerreaderThemePreferences.storageKey)
    private var selectedRawValue = JerreaderThemeColorChoice.ocean.rawValue

    private var selected: JerreaderThemeColorChoice {
        JerreaderThemeColorChoice(rawValue: selectedRawValue) ?? .ocean
    }

    var body: some View {
        Form {
            Section {
                ForEach(JerreaderThemeColorChoice.allCases) { choice in
                    Button {
                        selectedRawValue = choice.rawValue
                    } label: {
                        HStack(spacing: 14) {
                            Circle()
                                .fill(JerreaderTheme.accent(for: choice))
                                .frame(width: 32, height: 32)
                                .overlay {
                                    Circle().stroke(.white.opacity(0.7), lineWidth: 1)
                                }
                                .shadow(color: .black.opacity(0.12), radius: 3, y: 1)
                            VStack(alignment: .leading, spacing: 3) {
                                Text(choice.title)
                                    .foregroundStyle(.primary)
                                Text(choice.detail)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            if selected == choice {
                                Image(systemName: "checkmark.circle.fill")
                                    .foregroundStyle(JerreaderTheme.accent(for: choice))
                            }
                        }
                        .frame(minHeight: 56)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(selected == choice ? .isSelected : [])
                }
            } header: {
                Text("主题色系")
            } footer: {
                Text("色系会同时调整强调色、按钮、卡片与背景，并自动适配系统深色/浅色外观。阅读页的纸张主题仍在“默认阅读排版”中单独设置。")
            }
        }
        .scrollContentBackground(.hidden)
        .background(JerreaderCanvasBackground())
        .navigationTitle("界面主题")
        .navigationBarTitleDisplayMode(.inline)
        .tint(JerreaderTheme.accent(for: selected))
    }
}

private struct GlobalReaderDefaultsView: View {
    @Environment(\.modelContext) private var modelContext
    @Query private var books: [BookRecord]
    @AppStorage(ReaderAppearanceDefaults.fontPointSizeKey)
    private var fontPointSize = ReaderFontSizing.basePointSize
    @AppStorage(ReaderAppearanceDefaults.themeKey)
    private var themeRawValue = ReaderThemeChoice.light.rawValue
    @AppStorage(ReaderAppearanceDefaults.readingModeKey)
    private var readingModeRawValue = ReaderReadingMode.paginated.rawValue
    @AppStorage(ReaderAppearanceDefaults.fontFamilyKey)
    private var fontRawValue = ReaderFontChoice.serif.rawValue
    @AppStorage(ReaderAppearanceDefaults.lineHeightKey)
    private var lineHeight = 1.4
    @AppStorage(ReaderAppearanceDefaults.paragraphSpacingKey)
    private var paragraphSpacing = 0.0
    @AppStorage(ReaderAppearanceDefaults.pageMarginsKey)
    private var pageMargins = 1.0
    @AppStorage(ReaderAppearanceDefaults.customBackgroundHexKey)
    private var customBackgroundHex = ""
    @AppStorage(ReaderAppearanceDefaults.customSelectionColorHexKey)
    private var customSelectionColorHex = ""
    @AppStorage(ReaderAppearanceDefaults.appliesToExistingBooksKey)
    private var appliesToExistingBooks = false
    @AppStorage(ReaderTextOrientationDefaults.storageKey)
    private var japaneseOrientationRawValue =
        ReaderTextOrientationChoice.publication.rawValue
    @AppStorage(ReaderAppearanceDefaults.showsProgressKey)
    private var showsReadingProgress = true
    @State private var isConfirmingApply = false
    @State private var didApply = false
    @State private var applyErrorMessage: String?
    @State private var synchronizationTask: Task<Void, Never>?

    var body: some View {
        Form {
            Section {
                Toggle(
                    "同步应用到现有全部书籍",
                    isOn: $appliesToExistingBooks
                )
                Text(
                    appliesToExistingBooks
                        ? "已开启：本页每次修改都会立即同步到书架中的现有书籍；单本书之后仍可单独调整。"
                        : "关闭时只作为新导入书籍的默认值；也可用下方按钮手动应用一次。"
                )
                .font(.caption)
                .foregroundStyle(.secondary)

                if appliesToExistingBooks {
                    Label(
                        "已自动同步到 \(books.count) 本现有书籍",
                        systemImage: "checkmark.circle.fill"
                    )
                    .foregroundStyle(.secondary)
                } else {
                    Button {
                        isConfirmingApply = true
                    } label: {
                        JerreaderLabel(title: "立即应用到书架中的现有书籍", icon: "books.vertical", size: 20)
                    }
                }

                Text("只覆盖阅读排版，不会修改阅读位置、书签、划线、批注或笔记。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } header: {
                Text("应用范围")
            }

            Section {
                Picker("翻页方式", selection: readingModeBinding) {
                    ForEach(ReaderReadingMode.allCases) { mode in
                        Text(mode.title).tag(mode)
                    }
                }
                .pickerStyle(.segmented)
                Text(readingModeBinding.wrappedValue.detail)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Toggle("显示可拖动阅读进度", isOn: $showsReadingProgress)
            } header: {
                Text("翻页与导航")
            } footer: {
                Text(
                    appliesToExistingBooks
                        ? "当前排版会同步到现有书籍；阅读位置、书签与批注不受影响。"
                        : "当前排版只会自动用于以后导入的新书。"
                )
            }

            Section("字体与字号") {
                Picker("字体", selection: fontBinding) {
                    ForEach(ReaderFontChoice.allCases) { font in
                        Text(font.title).tag(font)
                    }
                }
                LabeledContent("字号") {
                    Text("\(fontPointSize.formatted(.number.precision(.fractionLength(1)))) 号")
                        .foregroundStyle(.secondary)
                        .monospacedDigit()
                }
                Slider(
                    value: $fontPointSize,
                    in: ReaderFontSizing.pointSizeRange,
                    step: ReaderFontSizing.pointSizeStep
                )
            }

            Section("间距") {
                LabeledContent("行间距") {
                    Text(lineHeight.formatted(.number.precision(.fractionLength(1))))
                        .monospacedDigit()
                }
                Slider(value: $lineHeight, in: 1 ... 2.2, step: 0.1)
                LabeledContent("段间距") {
                    Text(paragraphSpacing.formatted(.number.precision(.fractionLength(1))))
                        .monospacedDigit()
                }
                Slider(value: $paragraphSpacing, in: 0 ... 2, step: 0.1)
                LabeledContent("页边距") {
                    Text(pageMargins.formatted(.number.precision(.fractionLength(1))))
                        .monospacedDigit()
                }
                Slider(value: $pageMargins, in: 0.5 ... 2, step: 0.1)
            }

            Section("背景") {
                Picker("预设背景", selection: themeBinding) {
                    ForEach(ReaderThemeChoice.allCases) { theme in
                        Text(theme.title).tag(theme)
                    }
                }
                Toggle("自定义背景颜色", isOn: customBackgroundEnabledBinding)
                if !customBackgroundHex.isEmpty {
                    ColorPicker(
                        "选择背景颜色",
                        selection: customBackgroundColorBinding,
                        supportsOpacity: false
                    )
                }
                Toggle(
                    "自定义选区颜色",
                    isOn: customSelectionColorEnabledBinding
                )
                if !customSelectionColorHex.isEmpty {
                    ColorPicker(
                        "选择选区颜色",
                        selection: customSelectionColorBinding,
                        supportsOpacity: false
                    )
                }
            }

            Section("日文原版排版") {
                Picker("默认版式", selection: japaneseOrientationBinding) {
                    ForEach(ReaderTextOrientationChoice.allCases) { orientation in
                        Text(orientation.title).tag(orientation)
                    }
                }
                .pickerStyle(.segmented)
                Text(japaneseOrientationBinding.wrappedValue.detail)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

        }
        .scrollContentBackground(.hidden)
        .background(JerreaderCanvasBackground())
        .navigationTitle("默认阅读排版")
        .navigationBarTitleDisplayMode(.inline)
        .confirmationDialog(
            "将当前默认排版应用到所有现有书籍？",
            isPresented: $isConfirmingApply,
            titleVisibility: .visible
        ) {
            Button("应用到 \(books.count) 本书") {
                applyToExistingBooks()
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text("会覆盖现有书籍各自保存的排版，但不会修改阅读位置、书签、划线、批注或笔记。")
        }
        .alert("排版已应用", isPresented: $didApply) {
            Button("好") {}
        } message: {
            Text("以后仍可在每本书的阅读设置中单独调整。")
        }
        .alert(
            "排版保存失败",
            isPresented: Binding(
                get: { applyErrorMessage != nil },
                set: { if !$0 { applyErrorMessage = nil } }
            )
        ) {
            Button("好") { applyErrorMessage = nil }
        } message: {
            Text(applyErrorMessage ?? "")
        }
        .onChange(of: appliesToExistingBooks) {
            if appliesToExistingBooks {
                applyToExistingBooks(showsConfirmation: false)
            } else {
                synchronizationTask?.cancel()
            }
        }
        .onChange(of: currentEditorPreferences) {
            if appliesToExistingBooks {
                scheduleSynchronization()
            }
        }
        .onDisappear {
            synchronizationTask?.cancel()
        }
    }

    private var themeBinding: Binding<ReaderThemeChoice> {
        Binding(
            get: { ReaderThemeChoice(rawValue: themeRawValue) ?? .light },
            set: {
                themeRawValue = $0.rawValue
                customBackgroundHex = ""
            }
        )
    }

    private var readingModeBinding: Binding<ReaderReadingMode> {
        Binding(
            get: {
                ReaderReadingMode(rawValue: readingModeRawValue) ?? .paginated
            },
            set: { readingModeRawValue = $0.rawValue }
        )
    }

    private var fontBinding: Binding<ReaderFontChoice> {
        Binding(
            get: { ReaderFontChoice(rawValue: fontRawValue) ?? .serif },
            set: { fontRawValue = $0.rawValue }
        )
    }

    private var japaneseOrientationBinding:
        Binding<ReaderTextOrientationChoice>
    {
        Binding(
            get: {
                ReaderTextOrientationChoice(
                    rawValue: japaneseOrientationRawValue
                ) ?? .publication
            },
            set: { japaneseOrientationRawValue = $0.rawValue }
        )
    }

    private var customBackgroundEnabledBinding: Binding<Bool> {
        Binding(
            get: { !customBackgroundHex.isEmpty },
            set: {
                customBackgroundHex =
                    $0 ? ReaderCustomBackground.initialHex : ""
            }
        )
    }

    private var customBackgroundColorBinding: Binding<Color> {
        Binding(
            get: {
                Color(
                    uiColor: ReaderCustomBackground.uiColor(
                        hex: customBackgroundHex
                    ) ?? UIColor(
                        red: 0.93,
                        green: 0.96,
                        blue: 0.98,
                        alpha: 1
                    )
                )
            },
            set: {
                customBackgroundHex =
                    ReaderCustomBackground.hex(from: UIColor($0))
                    ?? ReaderCustomBackground.initialHex
            }
        )
    }

    private var customSelectionColorEnabledBinding: Binding<Bool> {
        Binding(
            get: { !customSelectionColorHex.isEmpty },
            set: {
                customSelectionColorHex = $0 ? "#317DC2" : ""
            }
        )
    }

    private var customSelectionColorBinding: Binding<Color> {
        Binding(
            get: {
                Color(
                    uiColor: ReaderCustomBackground.uiColor(
                        hex: customSelectionColorHex
                    ) ?? UIColor(
                        red: 0.19,
                        green: 0.49,
                        blue: 0.76,
                        alpha: 1
                    )
                )
            },
            set: {
                customSelectionColorHex =
                    ReaderCustomBackground.hex(from: UIColor($0))
                    ?? ""
            }
        )
    }

    private var currentEditorPreferences: ReaderAppearancePreferences {
        ReaderAppearancePreferences(
            fontSize: ReaderFontSizing.scale(fromPointSize: fontPointSize),
            theme: themeBinding.wrappedValue,
            readingMode: readingModeBinding.wrappedValue,
            fontChoice: fontBinding.wrappedValue,
            lineHeight: lineHeight,
            paragraphSpacing: paragraphSpacing,
            pageMargins: pageMargins,
            customBackgroundHex: customBackgroundHex,
            customSelectionColorHex: customSelectionColorHex,
            japaneseTextOrientation: japaneseOrientationBinding.wrappedValue
        )
    }

    private func applyToExistingBooks(showsConfirmation: Bool = true) {
        let preferences = currentEditorPreferences
        for book in books {
            ReaderAppearanceDefaults.apply(preferences, to: book)
        }
        do {
            try modelContext.save()
            didApply = showsConfirmation
        } catch {
            modelContext.rollback()
            applyErrorMessage = "无法将当前排版写入书架，本次修改已撤销。请检查可用存储空间后重试。"
        }
    }

    private func scheduleSynchronization() {
        synchronizationTask?.cancel()
        synchronizationTask = Task { @MainActor in
            do {
                try await Task.sleep(for: .milliseconds(350))
                guard !Task.isCancelled, appliesToExistingBooks else { return }
                applyToExistingBooks(showsConfirmation: false)
            } catch {
                return
            }
        }
    }
}

struct TranslationSettingsDetailView: View {
    @ObservedObject var settings: TranslationSettingsStore
    @Binding var quickSentenceEnabled: Bool
    @Binding var disablesTapPageTurnsDuringQuickTranslation: Bool
    let showsQuickSentenceToggle: Bool

    var body: some View {
        Form {
            TranslationPreferencesSection(
                settings: settings,
                quickSentenceEnabled: $quickSentenceEnabled,
                disablesTapPageTurnsDuringQuickTranslation:
                    $disablesTapPageTurnsDuringQuickTranslation,
                showsQuickSentenceToggle: showsQuickSentenceToggle
            )
            Section {
                NavigationLink("离线资源") { OfflineDictionaryView() }
            } footer: {
                Text("基础查词无需下载。系统句段翻译的语言包由 iOS 在首次使用时管理，扩展词典在此按需准备。")
            }
        }
        .scrollContentBackground(.hidden)
        .background(JerreaderCanvasBackground())
        .scrollDismissesKeyboard(.interactively)
        .navigationTitle("翻译与 AI")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct DataPrivacySettingsView: View {
    var body: some View {
        Form {
            Section("本机数据") {
                JerreaderLabel(title: "书籍、书架与学习记录默认只保存在本机", icon: "books.vertical")
                JerreaderLabel(title: "API Key 只保存在本机安全存储", icon: "lock")
                JerreaderLabel(title: "在线 AI 只接收你主动选择的文字与必要上下文", icon: "checkmark.shield")
                JerreaderLabel(title: "导入的书籍是只读输入，阅读与翻译不会改写它", icon: "character.book.closed")
            }
            Section {
                JerreaderLabel(title: "备份需要你先授权一个文件夹", icon: "square.and.arrow.down")
            } header: { Text("写出副本") } footer: {
                Text("密钥不会写入书籍、日志或安装包。备份只写入你选择的位置。")
            }
        }
        .scrollContentBackground(.hidden)
        .background(JerreaderCanvasBackground())
        .navigationTitle("数据与隐私")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct SettingsSectionHeader: View {
    let title: String
    let systemImage: String

    var body: some View {
        JerreaderLabel(title: title, icon: systemImage)
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(JerreaderTheme.accent)
            .textCase(nil)
            .accessibilityAddTraits(.isHeader)
    }
}

struct TranslationPreferencesSection: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ObservedObject var settings: TranslationSettingsStore
    @Binding var quickSentenceEnabled: Bool
    @Binding var disablesTapPageTurnsDuringQuickTranslation: Bool
    let showsQuickSentenceToggle: Bool
    @State private var connectionTestState: TranslationConnectionTestState = .idle
    @State private var connectionTestTask: Task<Void, Never>?

    var body: some View {
        Section {
            if showsQuickSentenceToggle {
                Toggle("轻点正文快速翻译", isOn: $quickSentenceEnabled)

                Picker("翻译范围", selection: $settings.quickTranslationUnit) {
                    ForEach(ReaderQuickTranslationUnit.allCases) { unit in
                        Text(unit.title).tag(unit)
                    }
                }
                .pickerStyle(.segmented)
                .disabled(!quickSentenceEnabled)

                Toggle(
                    "轻点翻译时禁用点按翻页",
                    isOn: $disablesTapPageTurnsDuringQuickTranslation
                )
                .disabled(!quickSentenceEnabled)

                Text(
                    quickSentenceEnabled
                        ? quickSentenceInteractionDescription
                        : "关闭后，EPUB 中轻点词语直接查词并记入学习记录，轻点空白处控制阅读界面；长按和拖动选区仍可翻译。"
                )
                .font(.caption)
                .foregroundStyle(.secondary)
            } else {
                Text("PDF 可轻点句子或段落直接翻译；没有文字层的扫描页会在本机按需 OCR。长按仍可精确框选已有文字层的内容。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Picker("翻译服务", selection: $settings.provider) {
                ForEach(TranslationProviderChoice.allCases) { provider in
                    Text(provider.title).tag(provider)
                }
            }

            Picker("原文语言", selection: $settings.sourceLanguageChoice) {
                ForEach(TranslationSourceLanguageChoice.allCases) { language in
                    Text(language.title).tag(language)
                }
            }

            Picker("目标语言", selection: $settings.targetLanguage) {
                ForEach(LanguageCode.allCases, id: \.self) { language in
                    Text(language.displayName).tag(language)
                }
            }

            if settings.sourceLanguageChoice.languageCode
                == settings.targetLanguage
            {
                Label(
                    "原文和目标语言不能相同，请选择另一个目标语言或使用自动识别。",
                    systemImage: "exclamationmark.triangle"
                )
                .font(.caption)
                .foregroundStyle(.orange)
            } else {
                Text("支持中文、英语与日语之间任意方向互译；自动识别会结合选中文字和书籍语言。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Picker("译文位置", selection: $settings.displayMode) {
                ForEach(TranslationDisplayMode.allCases) { mode in
                    Text(mode.title).tag(mode)
                }
            }

            Text(settings.displayMode.detail)
                .font(.caption)
                .foregroundStyle(.secondary)

            Toggle("翻译完成时触感反馈", isOn: $settings.translationHapticsEnabled)

            Text(
                settings.translationHapticsEnabled
                    ? "译文或 AI 句子结构分析完成时提供轻触反馈。"
                    : "翻译相关操作不会触发振动。"
            )
            .font(.caption)
            .foregroundStyle(.secondary)

            Toggle("短暂失败自动重试一次", isOn: $settings.automaticRetryEnabled)

            Picker("备用翻译服务", selection: $settings.fallbackProvider) {
                ForEach(TranslationFallbackChoice.allCases) { provider in
                    Text(provider.title).tag(provider)
                }
            }

            Text(fallbackDescription)
                .font(.caption)
                .foregroundStyle(.secondary)

            if settings.provider != .apple {
                DisclosureGroup("提示词与 AI 行为") {
                    VStack(alignment: .leading, spacing: 14) {
                        promptEditor(
                            title: "翻译提示词",
                            text: $settings.translationPromptTemplate,
                            reset: settings.resetTranslationPrompt
                        )
                        Divider()
                        promptEditor(
                            title: "句子结构分析提示词",
                            text: $settings.grammarAnalysisPromptTemplate,
                            reset: settings.resetGrammarAnalysisPrompt
                        )
                        Text("可使用 {source_language}、{target_language} 和 {response_language} 占位符。修改提示词后会生成新的缓存版本，不会误用旧结果。")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 8)
                }
            }

            if settings.provider == .directAPI {
                // Providers, keys and models live on their own page, as in the
                // settings home's AI 大模型 row.
                NavigationLink {
                    AIModelsView(store: settings)
                } label: {
                    LabeledContent("AI 大模型") {
                        Text(activeModelSummary).lineLimit(1)
                    }
                }
                .accessibilityIdentifier("aiModelsLink")

                configurationStatus(
                    message: settings.directAPIConfigurationMessage,
                    isReady: settings.directAPIConfiguration != nil
                )

                connectionTestButton
            } else if settings.provider == .backendProxy {
                TextField(
                    "https://example.com/translate",
                    text: $settings.backendEndpoint
                )
                .textContentType(.URL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.URL)

                TextField("模型名称（可选）", text: $settings.backendModel)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()

                SecureField("代理访问凭据（可选）", text: $settings.backendAccessToken)
                    .textContentType(.password)

                configurationStatus(
                    message: settings.backendConfigurationMessage,
                    isReady: settings.backendConfiguration != nil
                )

                connectionTestButton

                DisclosureGroup("AI 代理接入说明") {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("代理凭据只保存在本机钥匙串中。请填写你自己控制的 HTTPS 代理，不要直接填写供应商原始 API Key。")
                        Text("代理需接受 text、可选 context、sourceLanguage、targetLanguage、model，并返回 translatedText 或 translation。")
                    }
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .padding(.vertical, 6)
                }
            } else {
                Label(
                    "Apple 翻译首次使用时可能需要数分钟下载语言包；请保持 App 在前台。若长时间无进展，可先在 Apple“翻译”App 中下载对应语言。",
                    systemImage: "iphone.and.arrow.forward"
                )
                .font(.caption)
                .foregroundStyle(.secondary)
            }
        } header: {
            SettingsSectionHeader(title: "翻译", systemImage: "character.bubble")
        }
        .animation(
            reduceMotion ? nil : JerreaderMotion.stateChange,
            value: settings.provider
        )
        .sensoryFeedback(.selection, trigger: settings.provider)
        .sensoryFeedback(
            .success,
            trigger: connectionTestState == .success
        )
        .onChange(of: settings.provider) {
            resetConnectionTest()
        }
        .onChange(of: settings.sourceLanguageChoice) {
            resetConnectionTest()
        }
        .onChange(of: settings.targetLanguage) {
            resetConnectionTest()
        }
        .onChange(of: settings.aiProviders) {
            resetConnectionTest()
        }
        .onChange(of: settings.keyedProviderIDs) {
            resetConnectionTest()
        }
        .onChange(of: settings.backendEndpoint) {
            resetConnectionTest()
        }
        .onChange(of: settings.backendModel) {
            resetConnectionTest()
        }
        .onChange(of: settings.backendAccessToken) {
            resetConnectionTest()
        }
        .onChange(of: settings.translationPromptTemplate) {
            resetConnectionTest()
        }
        .onChange(of: settings.grammarAnalysisPromptTemplate) {
            resetConnectionTest()
        }
        .onDisappear {
            connectionTestTask?.cancel()
            connectionTestTask = nil
        }
    }

    private var quickSentenceInteractionDescription: String {
        let target = settings.quickTranslationUnit == .sentence
            ? "句子"
            : "段落"
        if disablesTapPageTurnsDuringQuickTranslation {
            return "轻点模式下单击翻译当前\(target)，滑动仍可翻页。关闭后，长按框选与左右点按翻页可同时使用。"
        }
        return "轻点正文翻译当前\(target)；点按左右边缘翻页。长按可智能补全，拖动手柄后按最终框选内容翻译。"
    }

    /// The provider and model in use, as the AI 大模型 row shows them.
    private var activeModelSummary: String {
        let entry = settings.aiProviders.active
        let model = entry.model.trimmingCharacters(in: .whitespacesAndNewlines)
        return model.isEmpty ? entry.displayName : "\(entry.displayName) · \(model)"
    }

    private func configurationStatus(message: String, isReady: Bool) -> some View {
        Label(
            message,
            systemImage: isReady ? "checkmark.shield" : "exclamationmark.triangle"
        )
        .font(.caption)
        .foregroundStyle(isReady ? AnyShapeStyle(JerreaderTheme.accent) : AnyShapeStyle(.orange))
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            isReady ? JerreaderTheme.accentFill : Color.orange.opacity(0.10),
            in: RoundedRectangle(cornerRadius: 11, style: .continuous)
        )
    }

    private func promptEditor(
        title: String,
        text: Binding<String>,
        reset: @escaping () -> Void
    ) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                Spacer()
                Button("恢复默认", action: reset)
                    .font(.caption.weight(.semibold))
                    .buttonStyle(.plain)
            }
            TextEditor(text: text)
                .font(.footnote)
                .frame(minHeight: 128)
                .padding(6)
                .background(
                    JerreaderTheme.paper,
                    in: RoundedRectangle(cornerRadius: 10)
                )
                .overlay {
                    RoundedRectangle(cornerRadius: 10)
                        .stroke(JerreaderTheme.line, lineWidth: 0.75)
                }
        }
    }

    @ViewBuilder
    private var connectionTestButton: some View {
        Button {
            testConnection()
        } label: {
            HStack(spacing: 9) {
                if connectionTestState == .testing {
                    ProgressView()
                        .controlSize(.small)
                } else {
                    Image(systemName: connectionTestState.symbol)
                }
                Text(connectionTestState.title)
            }
        }
        .buttonStyle(.bordered)
        .buttonBorderShape(.roundedRectangle(radius: 11))
        .disabled(!canTestConnection || connectionTestState == .testing)

        if let message = connectionTestState.detail {
            Text(message)
                .font(.caption)
                .foregroundStyle(connectionTestState.isFailure ? .red : .secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var canTestConnection: Bool {
        guard settings.sourceLanguageChoice.languageCode
            != settings.targetLanguage
        else { return false }
        switch settings.provider {
        case .apple:
            return false
        case .directAPI:
            return settings.directAPIConfiguration != nil
        case .backendProxy:
            return settings.backendConfiguration != nil
        }
    }

    private var fallbackDescription: String {
        guard let fallback = settings.fallbackProvider.provider else {
            return "缓存始终优先；网络短暂中断时可自动重试一次，但不会把文字发送给其他服务。"
        }
        if fallback == settings.provider {
            return "备用服务与当前服务相同，不会重复切换；请选择另一个已配置的服务。"
        }
        switch fallback {
        case .apple:
            return "主服务短暂失败或超时后，会自动尝试 Apple 系统翻译。"
        case .directAPI:
            return settings.directAPIConfiguration == nil
                ? "请先在「设置 → AI 大模型」填好 API Key，否则备用切换不会执行。"
                : "主服务短暂失败或超时后，会把同一段选中文字发送给正在用的 AI 大模型。"
        case .backendProxy:
            return settings.backendConfiguration == nil
                ? "请先完成 AI 代理配置，否则备用切换不会执行。"
                : "主服务短暂失败或超时后，会把同一段选中文字发送给已配置的 AI 代理。"
        }
    }

    private func testConnection() {
        connectionTestTask?.cancel()
        connectionTestState = .testing

        let service: (any TranslationService)?
        switch settings.provider {
        case .apple:
            service = nil
        case .directAPI:
            service = settings.directAPIConfiguration.map {
                DirectAITranslationService(configuration: $0)
            }
        case .backendProxy:
            service = settings.backendConfiguration.map {
                BackendTranslationService(configuration: $0)
            }
        }

        guard let service else {
            connectionTestState = .failure("配置尚未完成。")
            return
        }

        let target = settings.targetLanguage
        let source: LanguageCode =
            settings.sourceLanguageChoice.languageCode
            ?? (target == .english ? .japanese : .english)
        let sample = source == .japanese ? "こんにちは。" : "Hello."
        connectionTestTask = Task { @MainActor in
            do {
                _ = try await service.translate(
                    text: sample,
                    sourceLanguage: source,
                    targetLanguage: target
                )
                guard !Task.isCancelled else { return }
                connectionTestState = .success
            } catch is CancellationError {
                return
            } catch {
                guard !Task.isCancelled else { return }
                connectionTestState = .failure(
                    (error as? LocalizedError)?.errorDescription
                        ?? "连接失败，请检查网络、模型和凭据。"
                )
            }
            connectionTestTask = nil
        }
    }

    private func resetConnectionTest() {
        connectionTestTask?.cancel()
        connectionTestTask = nil
        connectionTestState = .idle
    }
}

private enum TranslationConnectionTestState: Equatable {
    case idle
    case testing
    case success
    case failure(String)

    var title: String {
        switch self {
        case .idle: return "测试连接"
        case .testing: return "正在测试…"
        case .success: return "连接成功"
        case .failure: return "重新测试"
        }
    }

    var symbol: String {
        switch self {
        case .idle: return "bolt.horizontal.circle"
        case .testing: return "clock"
        case .success: return "checkmark.circle.fill"
        case .failure: return "arrow.clockwise.circle"
        }
    }

    var detail: String? {
        switch self {
        case .idle:
            return "测试只发送固定短句，不会发送书籍内容。"
        case .testing:
            return "正在验证地址、模型和凭据。"
        case .success:
            return "配置可正常返回译文。"
        case let .failure(message):
            return message
        }
    }

    var isFailure: Bool {
        if case .failure = self { return true }
        return false
    }
}
