import JerreaderCore
import SwiftData
import SwiftUI
import UIKit

/// Computed rather than a stored `let`: a Kotlin object arrives in Swift as a
/// plain class, so it is not `Sendable`, and Swift 6 rejects it as a global
/// constant. Nothing here is mutable, so re-reading `shared` costs nothing.
private var copy: JerreaderCopy { JerreaderCopy.shared }

struct AppSettingsView: View {
    @ObservedObject var translationSettings: TranslationSettingsStore
    @AppStorage(JerreaderThemePreferences.storageKey)
    private var themeColorRawValue = JerreaderThemeColorChoice.ocean.rawValue
    @AppStorage(LearningModulePreferences.visibilityKey)
    private var learningModuleVisible = true

    private var themeColor: JerreaderThemeColorChoice {
        JerreaderThemeColorChoice(rawValue: themeColorRawValue) ?? .ocean
    }

    var body: some View {
        NavigationStack {
            ZStack {
                JerreaderCanvasBackground()

                ScrollView {
                    settingsContent
                    .padding(.horizontal, JerreaderTheme.pagePadding)
                    .padding(.vertical, 20)
                    .frame(maxWidth: 800, alignment: .top)
                    .frame(maxWidth: .infinity, alignment: .top)
                }
            }
            .navigationTitle(copy.settingsTitle)
            .navigationBarTitleDisplayMode(.large)
        }
        .tint(JerreaderTheme.accent(for: themeColor))
    }

    @ViewBuilder
    private var settingsContent: some View {
        ViewThatFits(in: .horizontal) {
            HStack(alignment: .top, spacing: 18) {
                VStack(spacing: 22) {
                    preferencesSection
                    aboutSection
                }
                .frame(minWidth: 320, maxWidth: .infinity, alignment: .top)

                supportSection
                    .frame(minWidth: 320, maxWidth: .infinity, alignment: .top)
            }
            .frame(maxWidth: .infinity, alignment: .top)

            VStack(spacing: 22) {
                preferencesSection
                supportSection
                aboutSection
            }
        }
    }

    private var preferencesSection: some View {
        settingsSection(
            title: "偏好",
            systemImage: "slider.horizontal.3"
        ) {
            settingsToggle(
                title: "显示学习模块",
                detail: learningModuleVisible
                    ? "主导航显示学习；生词可进入日语复习闭环"
                    : "学习入口已隐藏，已有数据仍保留",
                systemImage: "rectangle.stack.badge.play",
                isOn: $learningModuleVisible
            )
            settingsDivider
            settingsLink(
                title: "界面主题",
                detail: "白天/黑夜/跟随系统，以及界面色系",
                systemImage: "paintpalette"
            ) {
                AppThemeColorSettingsView()
            }
            settingsDivider
            settingsLink(
                title: "默认阅读排版",
                detail: "字体、间距、背景、翻页与日文版式",
                systemImage: "textformat.size"
            ) {
                GlobalReaderDefaultsView()
            }
            settingsDivider
            settingsLink(
                title: "翻译与 AI",
                detail: "服务商、三语互译、提示词与交互",
                systemImage: "character.bubble"
            ) {
                TranslationSettingsDetailView(
                    settings: translationSettings,
                    quickSentenceEnabled: quickSentenceBinding,
                    disablesTapPageTurnsDuringQuickTranslation:
                        disablesTapPageTurnsDuringQuickTranslationBinding,
                    showsQuickSentenceToggle: true
                )
            }
        }
    }

    private var supportSection: some View {
        settingsSection(
            title: "支持",
            systemImage: "info.circle"
        ) {
            settingsLink(
                title: "操作指南",
                detail: "导入、阅读、PDF/文字识别与 API 配置",
                systemImage: "questionmark.circle"
            ) {
                AppGuideView()
            }
            settingsDivider
            settingsLink(
                title: "备份与恢复",
                detail: "自选文件夹，备份书架、进度、生词与批注",
                systemImage: "externaldrive"
            ) {
                LibraryBackupSettingsView(
                    translationSettings: translationSettings
                )
            }
            settingsDivider
            settingsLink(
                title: "数据与隐私",
                detail: "本机数据、在线请求、密钥与词典来源",
                systemImage: "hand.raised"
            ) {
                DataPrivacySettingsView()
            }
        }
    }

    private var aboutSection: some View {
        settingsSection(
            title: "关于",
            systemImage: "info.circle"
        ) {
            aboutRow(title: "Jerreader", value: versionText)
            settingsDivider
            aboutRow(title: "最低系统", value: "iOS 18")
            settingsDivider
            aboutRow(
                title: "作者",
                value: "WANG ZIRUI",
                highlightsValue: true
            )
        }
    }

    private func settingsSection<Content: View>(
        title: String,
        systemImage: String,
        @ViewBuilder content: () -> Content
    ) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 5) {
                Image(systemName: systemImage)
                    .font(.system(size: 12, weight: .semibold))
                    .frame(width: 14, height: 14)
                Text(title)
            }
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
                .padding(.leading, 5)
                .accessibilityElement(children: .combine)

            VStack(spacing: 0) {
                content()
            }
            .background(
                JerreaderTheme.paper,
                in: RoundedRectangle(cornerRadius: 15, style: .continuous)
            )
            .overlay {
                RoundedRectangle(cornerRadius: 15, style: .continuous)
                    .stroke(JerreaderTheme.line, lineWidth: 0.7)
            }
        }
    }

    private func settingsLink<Destination: View>(
        title: String,
        detail: String,
        systemImage: String,
        @ViewBuilder destination: () -> Destination
    ) -> some View {
        NavigationLink {
            destination()
        } label: {
            HStack(spacing: 10) {
                SettingsNavigationLabel(
                    title: title,
                    detail: detail,
                    systemImage: systemImage
                )
                Spacer(minLength: 4)
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
            .padding(.horizontal, 13)
            .frame(minHeight: 60)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    private func settingsToggle(
        title: String,
        detail: String,
        systemImage: String,
        isOn: Binding<Bool>
    ) -> some View {
        HStack(spacing: 10) {
            SettingsNavigationLabel(
                title: title,
                detail: detail,
                systemImage: systemImage
            )
            Spacer(minLength: 4)
            Toggle("", isOn: isOn)
                .labelsHidden()
                .accessibilityLabel(title)
                .accessibilityHint("关闭只隐藏学习入口，不会删除生词、复习进度或历史")
        }
        .padding(.horizontal, 13)
        .frame(minHeight: 64)
    }

    private var settingsDivider: some View {
        Divider().padding(.leading, 45)
    }

    private func aboutRow(
        title: String,
        value: String,
        highlightsValue: Bool = false
    ) -> some View {
        HStack {
            Text(title)
            Spacer()
            Text(value)
                .font(
                    highlightsValue
                        ? .system(.subheadline, design: .serif).weight(.semibold)
                        : .subheadline
                )
                .foregroundStyle(
                    highlightsValue ? JerreaderTheme.accent : .secondary
                )
        }
        .font(.subheadline)
        .padding(.horizontal, 13)
        .frame(minHeight: 52)
        .accessibilityElement(children: .combine)
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
    @AppStorage(JerreaderThemePreferences.appearanceModeKey)
    private var appearanceRawValue = JerreaderAppearanceModeChoice.system.rawValue

    private var selected: JerreaderThemeColorChoice {
        JerreaderThemeColorChoice(rawValue: selectedRawValue) ?? .ocean
    }

    private var appearance: JerreaderAppearanceModeChoice {
        JerreaderAppearanceModeChoice(rawValue: appearanceRawValue) ?? .system
    }

    var body: some View {
        Form {
            Section {
                ForEach(JerreaderAppearanceModeChoice.allCases) { mode in
                    Button {
                        appearanceRawValue = mode.rawValue
                    } label: {
                        HStack(spacing: 14) {
                            Image(systemName: mode.systemImage)
                                .font(.system(size: 17, weight: .medium))
                                .foregroundStyle(JerreaderTheme.accent(for: selected))
                                .frame(width: 32, height: 32)
                                .background(
                                    JerreaderTheme.accentFill(for: selected),
                                    in: RoundedRectangle(cornerRadius: 9, style: .continuous)
                                )
                            VStack(alignment: .leading, spacing: 3) {
                                Text(mode.title)
                                    .foregroundStyle(.primary)
                                Text(mode.detail)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            if appearance == mode {
                                Image(systemName: "checkmark.circle.fill")
                                    .foregroundStyle(JerreaderTheme.accent(for: selected))
                            }
                        }
                        .frame(minHeight: 56)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(appearance == mode ? .isSelected : [])
                }
            } header: {
                Text("明暗模式")
            } footer: {
                Text("只影响 App 界面，不会改动手机的系统设置。阅读页的深浅仍由“默认阅读排版”里的纸张主题决定。")
            }

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
                Text("色系会同时调整强调色、按钮、卡片与背景，并跟着上面的明暗模式走。阅读页的纸张主题仍在“默认阅读排版”中单独设置。")
            }
        }
        .scrollContentBackground(.hidden)
        .background(JerreaderCanvasBackground())
        .navigationTitle("界面主题")
        .navigationBarTitleDisplayMode(.inline)
        .tint(JerreaderTheme.accent(for: selected))
    }
}

private struct SettingsNavigationLabel: View {
    let title: String
    let detail: String
    let systemImage: String

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: systemImage)
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(JerreaderTheme.accent)
                .frame(width: 22, height: 22)

            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .foregroundStyle(.primary)
                Text(detail)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
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
    @AppStorage(ReaderAppearanceDefaults.pageMarginTopKey)
    private var pageMarginTop = 1.0
    @AppStorage(ReaderAppearanceDefaults.pageMarginBottomKey)
    private var pageMarginBottom = 1.0
    @AppStorage(ReaderAppearanceDefaults.pageMarginHorizontalKey)
    private var pageMarginHorizontal = 1.0
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
    @State private var isPageMarginDetailExpanded = false

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
                        Label(
                            "立即应用到书架中的现有书籍",
                            systemImage: "books.vertical"
                        )
                    }
                }

                Text("同步只覆盖阅读排版和选区配色，不会修改阅读位置、书签、划线、批注或笔记。")
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

                DisclosureGroup(isExpanded: $isPageMarginDetailExpanded) {
                    VStack(spacing: 12) {
                        pageMarginControl(
                            title: "上",
                            value: $pageMarginTop
                        )
                        pageMarginControl(
                            title: "下",
                            value: $pageMarginBottom
                        )
                        pageMarginControl(
                            title: "左右",
                            value: $pageMarginHorizontal
                        )
                    }
                    .padding(.top, 6)
                } label: {
                    Label {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("分别设置")
                            Text("上 / 下 / 左右")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    } icon: {
                        Image(systemName: "rectangle.split.3x1")
                            .foregroundStyle(JerreaderTheme.accent)
                    }
                }
            }

            Section {
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

                ReaderColorPresetSection(
                    theme: themeBinding.wrappedValue,
                    backgroundHex: customBackgroundHex,
                    selectionHex: customSelectionColorHex
                ) { backgroundHex, selectionHex in
                    customBackgroundHex = backgroundHex
                    customSelectionColorHex = selectionHex
                }
            } header: {
                Text("背景")
            } footer: {
                Text("当前的背景色和选区色可以随时存成配色套系，两个版本、每本书都能直接套用。没单独挑选区色也能存，会按背景自动配一个。长按套系可以删除。")
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
                // Both halves of the custom pair go, matching the reader sheet
                // and Android: a preset background never keeps a selection
                // colour that was picked for a different page.
                customBackgroundHex = ""
                customSelectionColorHex = ""
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

    private func pageMarginControl(
        title: String,
        value: Binding<Double>
    ) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            LabeledContent(title) {
                Text(value.wrappedValue.formatted(.number.precision(.fractionLength(1))))
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
            }
            Slider(value: value, in: 0.5 ... 2, step: 0.1)
        }
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
        }
        .scrollContentBackground(.hidden)
        .background(JerreaderCanvasBackground())
        .scrollDismissesKeyboard(.interactively)
        .navigationTitle("翻译与 AI")
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// Structured to match the Android 数据与隐私 page: 本机数据 first, then what
/// leaving the device requires. iOS used to show three of these promises in a
/// flat list and omit the other three entirely, so the same app made a
/// different set of commitments depending on which phone you read them on.
///
/// The three lines that name a platform mechanism — keychain vs Android
/// Keystore, IPA vs APK, Apple 系统翻译 vs Android 本机翻译 — stay per-platform,
/// because saying the other one would simply be false here.
private struct DataPrivacySettingsView: View {
    var body: some View {
        List {
            Section {
                Label("书籍、书架与学习记录默认只保存在本机", systemImage: "iphone")
                Label("API Key 只保存在本机钥匙串", systemImage: "key")
                Label("在线 AI 只接收你主动点按或框选的文字", systemImage: "checkmark.shield")
                Label("导入的书籍是只读输入，阅读与翻译不会改写它", systemImage: "book.closed")
                Label("受 DRM 保护的出版物会被拒绝打开，不做任何解密", systemImage: "xmark.octagon")
            } header: {
                Label("本机数据", systemImage: "hand.raised")
            } footer: {
                Text("密钥不会写入书籍、日志或 IPA。选择 Apple 系统翻译时，不会调用你配置的第三方 AI 服务。")
            }

            Section {
                Label("备份需要你先授权一个文件夹", systemImage: "square.and.arrow.down")
            } header: {
                Label("写出副本", systemImage: "square.and.arrow.down")
            } footer: {
                Text("只有你在「备份与恢复」中选择文件夹后，才会把书架与学习记录写到本机以外的位置。")
            }

            Section {
                NavigationLink {
                    DictionarySourcesView()
                } label: {
                    HStack(alignment: .top, spacing: 10) {
                        Image(systemName: "books.vertical")
                            .frame(width: 22, height: 22)
                        VStack(alignment: .leading, spacing: 3) {
                            Text("词典来源")
                            Text("离线 JMdict 数据、联网词典与许可")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            } header: {
                Label("词典", systemImage: "books.vertical")
            } footer: {
                Text("可查看离线数据版本、许可和联网词典的隐私边界。")
            }
        }
        .navigationTitle("数据与隐私")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct DictionarySourcesView: View {
    var body: some View {
        List {
            Section {
                LabeledContent("数据", value: "JMdict 常用词")
                LabeledContent("词典日期", value: "2026-07-20")
                LabeledContent("许可", value: "CC BY-SA 4.0")
                Link(
                    "JMdict / EDICT Dictionary Project",
                    destination: URL(
                        string: "https://www.edrdg.org/wiki/JMdict-EDICT_Dictionary_Project.html"
                    )!
                )
            } header: {
                Text("离线日语词典")
            } footer: {
                Text("离线词条由 EDRDG 的 JMdict 数据生成，提供日语读音、词性和英文释义；联网时仍优先查询中文维基词典。")
            }

            Section {
                Link(
                    "中文维基词典",
                    destination: URL(string: "https://zh.wiktionary.org/")!
                )
            } header: {
                Text("联网词典")
            } footer: {
                Text("联网查询只发送用户主动查询的词语及必要语境。")
            }
        }
        .navigationTitle("词典来源")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct SettingsSectionHeader: View {
    let title: String
    let systemImage: String

    var body: some View {
        Label(title, systemImage: systemImage)
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
                        : "关闭后，轻点正文只控制阅读界面；长按和拖动选区仍可翻译。"
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
                VStack(alignment: .leading, spacing: 10) {
                    Text("选择 AI 服务商")
                        .font(.subheadline.weight(.semibold))

                    LazyVGrid(
                        columns: [
                            GridItem(
                                .adaptive(minimum: 88, maximum: 132),
                                spacing: 9
                            )
                        ],
                        spacing: 8
                    ) {
                        ForEach(DirectAIProviderChoice.allCases) { provider in
                            directProviderButton(provider)
                        }
                    }
                }

                SecureField(
                    settings.directAPIProvider.keyPlaceholder,
                    text: $settings.directAPIKey
                )
                .textContentType(.password)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .privacySensitive()

                configurationStatus(
                    message: settings.directAPIConfigurationMessage,
                    isReady: settings.directAPIConfiguration != nil
                )

                connectionTestButton

                DisclosureGroup("高级配置") {
                    VStack(alignment: .leading, spacing: 10) {
                        TextField("模型名称", text: $settings.directAPIModel)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()

                        if settings.directAPIProvider.usesCustomEndpoint {
                            TextField(
                                "https://example.com/v1/chat/completions",
                                text: $settings.directAPIEndpoint
                            )
                            .textContentType(.URL)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .keyboardType(.URL)
                        }

                        Text(
                            settings.directAPIProvider.usesCustomEndpoint
                                ? "兼容服务需要填写完整的 HTTPS Chat Completions 地址。"
                                : "默认模型已经自动填写；只有服务商更换模型后才需要修改。"
                        )
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 6)
                }

                DisclosureGroup("直接 API 说明") {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("选择 GPT、Claude、Kimi 或其他服务商，再粘贴对应 API Key 即可。官方请求地址与推荐模型会自动填写；只有“其他兼容服务”需要手动填写完整 HTTPS 地址。")
                        Text("Key 按服务商分别保存在本机钥匙串中。直接调用会把本次选中文字发送给所选服务商，并可能产生少量 API 费用。")
                    }
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .padding(.vertical, 6)
                }
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
        .onChange(of: settings.directAPIProvider) {
            resetConnectionTest()
        }
        .onChange(of: settings.directAPIKey) {
            resetConnectionTest()
        }
        .onChange(of: settings.directAPIModel) {
            resetConnectionTest()
        }
        .onChange(of: settings.directAPIEndpoint) {
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

    private func directProviderButton(_ provider: DirectAIProviderChoice) -> some View {
        let isSelected = settings.directAPIProvider == provider
        return Button {
            withAnimation(reduceMotion ? nil : JerreaderMotion.stateChange) {
                settings.directAPIProvider = provider
            }
        } label: {
            ZStack(alignment: .topTrailing) {
                VStack(spacing: 7) {
                    DirectAIProviderMark(
                        provider: provider,
                        isSelected: isSelected
                    )
                    Text(provider.title)
                        .font(.caption.weight(.semibold))
                        .lineLimit(1)
                        .minimumScaleFactor(0.72)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)

                if isSelected {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.caption)
                        .foregroundStyle(JerreaderTheme.onPrimaryAction)
                        .padding(7)
                        .transition(.scale.combined(with: .opacity))
                }
            }
            .foregroundStyle(isSelected ? JerreaderTheme.onPrimaryAction : JerreaderTheme.accent)
            .frame(maxWidth: .infinity)
            .frame(height: 68)
            .background {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(
                        isSelected
                            ? AnyShapeStyle(JerreaderTheme.primaryAction)
                            : AnyShapeStyle(JerreaderTheme.accentFill)
                    )
            }
            .overlay {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .stroke(JerreaderTheme.accent.opacity(isSelected ? 0 : 0.18), lineWidth: 1)
            }
            .shadow(
                color: isSelected ? JerreaderTheme.shadow : .clear,
                radius: 5,
                y: 2
            )
        }
        .buttonStyle(JerreaderPressButtonStyle(pressedScale: 0.96))
        .accessibilityLabel("选择 \(provider.title)")
        .accessibilityAddTraits(isSelected ? .isSelected : [])
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
                ? "请先完成直接 AI API 配置，否则备用切换不会执行。"
                : "主服务短暂失败或超时后，会把同一段选中文字发送给已配置的 AI API。"
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

private struct DirectAIProviderMark: View {
    let provider: DirectAIProviderChoice
    let isSelected: Bool

    var body: some View {
        Group {
            if let image = UIImage(named: provider.localLogoAssetName) {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .padding(4)
            } else {
                Text(provider.fallbackMark)
                    .font(
                        .system(
                            size: provider.fallbackMark.count == 1 ? 14 : 10,
                            weight: .bold,
                            design: .rounded
                        )
                    )
                    .minimumScaleFactor(0.72)
                    .lineLimit(1)
                    .padding(.horizontal, 3)
            }
        }
        .foregroundStyle(isSelected ? JerreaderTheme.onPrimaryAction : JerreaderTheme.accent)
        .frame(width: 28, height: 28)
        .background(
            isSelected ? Color.white.opacity(0.16) : JerreaderTheme.accent.opacity(0.10),
            in: RoundedRectangle(cornerRadius: 8, style: .continuous)
        )
        .accessibilityHidden(true)
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
