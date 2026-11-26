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
    @Environment(\.colorScheme) private var colorScheme

    private var themeColor: JerreaderThemeColorChoice {
        JerreaderThemeColorChoice(rawValue: themeColorRawValue) ?? .ocean
    }

    /// The home page fills one screen exactly: its rows share whatever height
    /// a taller phone leaves over, so nothing scrolls and no gap is left above
    /// the tab bar. A system list cannot do that, so the groups are drawn here
    /// to look like one: each row reports its frame, and one paper card per
    /// group is drawn behind the rows. Large text or a short screen still scrolls.
    var body: some View {
        NavigationStack {
            OneScreenPage(identifier: "settingsHome") { size in
                // The margin the system gives a grouped list at this width.
                let margin: CGFloat = size.width >= 400 ? 20 : 16
                FillStack {
                    header
                    Group {
                        groupTitle("阅读")
                        row("界面主题", value: themeColor.title, icon: "PALETTE_FILLED",
                            tint: JerreaderTheme.accent(for: themeColor), id: "themeSettingsLink",
                            group: 0, position: .first, edge: margin) {
                            AppThemeColorSettingsView()
                        }
                        row("默认阅读排版", value: readerDefaultsSummary, icon: "textformat.size",
                            tint: SettingsTint.reading, id: "readerDefaultsLink",
                            group: 0, position: .last, edge: margin) {
                            GlobalReaderDefaultsView()
                        }
                    }
                    Group {
                        groupTitle("翻译")
                        row("翻译与 AI", value: translationSettings.provider.title,
                            icon: "character.bubble.fill", tint: SettingsTint.translation,
                            id: "translationSettingsLink", group: 1, position: .first, edge: margin) {
                            TranslationSettingsDetailView(
                                settings: translationSettings,
                                quickSentenceEnabled: quickSentenceBinding,
                                disablesTapPageTurnsDuringQuickTranslation:
                                    disablesTapPageTurnsDuringQuickTranslationBinding,
                                showsQuickSentenceToggle: true
                            )
                        }
                        row("AI 大模型", value: aiModelsSummary, warn: aiModelsNeedKey, icon: "SPARKLES",
                            tint: SettingsTint.models, id: "aiModelsSettingsLink",
                            group: 1, position: .last, edge: margin) {
                            AIModelsView(store: translationSettings)
                        }
                    }
                    Group {
                        groupTitle("数据与帮助")
                        row("备份与恢复", icon: "EXTERNAL_DRIVE_FILLED", tint: SettingsTint.backup,
                            id: "backupSettingsLink", group: 2, position: .first, edge: margin) {
                            LibraryBackupSettingsView(translationSettings: translationSettings)
                        }
                        row("数据与隐私", icon: "HAND_FILLED", tint: SettingsTint.privacy,
                            id: "privacySettingsLink", group: 2, position: .middle, edge: margin) {
                            DataPrivacySettingsView()
                        }
                        row("操作指南", icon: "questionmark.circle", tint: SettingsTint.guide,
                            id: "guideSettingsLink", group: 2, position: .last, edge: margin) {
                            AppGuideView()
                        }
                    }
                    credit
                }
                .backgroundPreferenceValue(SettingsGroupFrames.self) { groups in
                    SettingsGroupCards(groups: groups)
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

    /// The app's own card: its mark, name and what it does, on paper with a
    /// faint wash of the theme colour from the top-leading corner. Its natural
    /// height (80 pt) is the one the old brand row had, so the page budget holds;
    /// a taller phone gives it up to 20 pt more.
    private var header: some View {
        let accent = JerreaderTheme.accent(for: themeColor)
        let wash = colorScheme == .dark ? 0.16 : 0.1
        return HStack(spacing: 14) {
            Image("BrandMark").resizable().scaledToFill().frame(width: 52, height: 52)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .strokeBorder(JerreaderTheme.line, lineWidth: 0.5)
                }
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 3) {
                Text("Jerreader").font(.title3.weight(.bold))
                Text("原版阅读 · 查词 · 翻译").font(.subheadline).foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16).padding(.vertical, 14)
        .frame(maxHeight: .infinity)
        .background {
            RoundedRectangle(cornerRadius: JerreaderRadius.card, style: .continuous)
                .fill(RadialGradient(colors: [accent.opacity(wash), accent.opacity(0)],
                                     center: .topLeading, startRadius: 0, endRadius: 280))
        }
        .jerreaderPaperCard(padding: 0, radius: JerreaderRadius.card)
        .accessibilityElement(children: .combine)
        .stretch(20)
    }

    /// A group title, level with the icon tiles below it. On a taller phone it
    /// takes up to 8 pt more above itself, which separates the groups.
    private func groupTitle(_ title: String) -> some View {
        Text(title).settingsHeader()
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomLeading)
            .padding(.horizontal, 16).padding(.top, 21).padding(.bottom, 5)
            .accessibilityAddTraits(.isHeader)
            .stretch(8)
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

    /// A row in the style of the system Settings app: coloured icon tile, title,
    /// and the current value on the trailing side.
    /// - Parameters:
    ///   - group: which paper card the row sits on.
    ///   - edge: the space after the chevron, which follows the page margin as in a system list.
    private func row<Destination: View>(
        _ title: String, value: String? = nil, warn: Bool = false, icon: String, tint: Color,
        id: String, group: Int, position: GroupPosition, edge: CGFloat,
        @ViewBuilder destination: () -> Destination
    ) -> some View {
        // Large text puts the value under the title instead of squeezing both onto one line.
        let text = typeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 2)) : AnyLayout(HStackLayout(spacing: 8))
        return NavigationLink(destination: destination()) {
            HStack(spacing: 14) {
                JerreaderIconTile(systemImage: icon, tint: tint, size: 30)
                text {
                    Text(title).foregroundStyle(Color.primary).layoutPriority(1)
                    if !typeSize.isAccessibilitySize { Spacer(minLength: 0) }
                    if let value {
                        Text(value).foregroundStyle(warn ? Color.orange : Color.secondary).lineLimit(1)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                JerreaderSymbol(name: "chevron.right", size: 16)
                    .foregroundStyle(Color(uiColor: .tertiaryLabel))
            }
            .padding(.leading, 16).padding(.trailing, edge).padding(.vertical, 7)
            // The row takes whatever height the page hands it.
            .frame(maxHeight: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(SettingsGroupRowStyle(position: position, separatorInset: 60))
        .accessibilityIdentifier(id)
        .anchorPreference(key: SettingsGroupFrames.self, value: .bounds) { [group: [$0]] }
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

/// The frames of the settings home rows, by group, so that one paper card can
/// be drawn behind each group.
private struct SettingsGroupFrames: PreferenceKey {
    static var defaultValue: [Int: [Anchor<CGRect>]] { [:] }

    static func reduce(value: inout [Int: [Anchor<CGRect>]], nextValue: () -> [Int: [Anchor<CGRect>]]) {
        value.merge(nextValue()) { $0 + $1 }
    }
}

/// One paper card per group of the settings home, behind its rows: paper,
/// a 0.75 pt hairline and, in light mode, the e1 shadow.
private struct SettingsGroupCards: View {
    let groups: [Int: [Anchor<CGRect>]]

    var body: some View {
        GeometryReader { proxy in
            ForEach(groups.keys.sorted(), id: \.self) { key in
                card(in: frame(of: key, in: proxy))
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    private func frame(of key: Int, in proxy: GeometryProxy) -> CGRect {
        (groups[key] ?? []).reduce(CGRect.null) { $0.union(proxy[$1]) }
    }

    @ViewBuilder
    private func card(in frame: CGRect) -> some View {
        if !frame.isNull, frame.width > 0, frame.height > 0 {
            Color.clear
                .frame(width: frame.width, height: frame.height)
                .jerreaderPaperCard(padding: 0, radius: JerreaderRadius.card)
                .position(x: frame.midX, y: frame.midY)
        }
    }
}

/// A row of a settings home group. The group's card is drawn behind the rows,
/// so a row adds only its pressed tint and the hairline above it.
private struct SettingsGroupRowStyle: ButtonStyle {
    var position: GroupPosition
    /// Where the hairline above the row starts: level with the row's title.
    var separatorInset: CGFloat

    func makeBody(configuration: Configuration) -> some View {
        let top: CGFloat = position.roundsTop ? JerreaderRadius.card : 0
        let bottom: CGFloat = position.roundsBottom ? JerreaderRadius.card : 0
        configuration.label
            .background {
                UnevenRoundedRectangle(topLeadingRadius: top, bottomLeadingRadius: bottom,
                                       bottomTrailingRadius: bottom, topTrailingRadius: top,
                                       style: .continuous)
                    .fill(Color.primary.opacity(configuration.isPressed ? 0.07 : 0))
            }
            .overlay(alignment: .top) {
                if !position.roundsTop { SettingsHairline().padding(.leading, separatorInset) }
            }
            // The tint comes at once and fades out after the finger lifts.
            .animation(configuration.isPressed ? nil : JerreaderMotion.quick, value: configuration.isPressed)
    }
}

/// A one-pixel line in the hairline colour.
private struct SettingsHairline: View {
    @Environment(\.displayScale) private var displayScale

    var body: some View {
        Rectangle().fill(JerreaderTheme.line).frame(height: 1 / max(displayScale, 1))
    }
}

/// 界面主题 (2.0.1). The page used to be one row of five swatches; it now
/// covers everything the app's own pages can be set to:
///
/// 1. 预览 — a small continue-reading card and two buttons in the chosen
///    colours, so a change shows before the reader leaves the page;
/// 2. 外观模式 — follow the system, or hold light or dark;
/// 3. 主题色系 — eight colours, four to a row. The chosen one carries a 2 pt
///    ring in its own colour and a check; the switch springs (`stateChange`)
///    and ticks (selection haptic);
/// 4. 背景光晕 — the faint accent wash at the top of every page;
/// 5. 阅读纸张 — a way to 默认阅读排版, where the reader's paper is set. It is
///    a link rather than a control so that page's 同步应用到现有书籍 rule
///    stays the only way a paper reaches the books.
///
/// Android: `SettingsRoute.THEME` in `SettingsScreen.kt`, the same five blocks.
private struct AppThemeColorSettingsView: View {
    @AppStorage(JerreaderThemePreferences.storageKey)
    private var selectedRawValue = JerreaderThemeColorChoice.ocean.rawValue
    @AppStorage(JerreaderThemePreferences.appearanceModeKey)
    private var appearanceModeRawValue = JerreaderAppearanceMode.system.rawValue
    @AppStorage(JerreaderThemePreferences.backdropGlowKey)
    private var backdropGlow = true
    @AppStorage(ReaderAppearanceDefaults.themeKey)
    private var readerThemeRawValue = ReaderThemeChoice.light.rawValue
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var typeSize

    private var selected: JerreaderThemeColorChoice {
        JerreaderThemeColorChoice(rawValue: selectedRawValue) ?? .ocean
    }

    private var appearanceMode: Binding<JerreaderAppearanceMode> {
        Binding(
            get: { JerreaderAppearanceMode(rawValue: appearanceModeRawValue) ?? .system },
            set: { appearanceModeRawValue = $0.rawValue }
        )
    }

    private static let swatchColumns = Array(repeating: GridItem(.flexible(), spacing: 0), count: 4)

    var body: some View {
        Form {
            Section {
                ThemePreviewCard(choice: selected)
                    .listRowInsets(EdgeInsets(top: 14, leading: 14, bottom: 14, trailing: 14))
            } header: {
                Text("预览").settingsHeader()
            }

            Section {
                if typeSize.isAccessibilitySize {
                    // Large text: one row per mode, as the system lists them.
                    Picker("外观模式", selection: appearanceMode) {
                        ForEach(JerreaderAppearanceMode.allCases) { mode in
                            Text(mode.title).tag(mode)
                        }
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                } else {
                    JerreaderPillPicker(
                        options: JerreaderAppearanceMode.allCases.map { mode in
                            JerreaderPillPicker<JerreaderAppearanceMode>.Option(
                                value: mode,
                                title: mode.title,
                                identifier: "appearanceMode-\(mode.rawValue)"
                            )
                        },
                        selection: appearanceMode
                    )
                    .accessibilityElement(children: .contain)
                    .accessibilityLabel("外观模式")
                }
            } header: {
                Text("外观模式").settingsHeader()
            } footer: {
                Text("只影响书架、学习和设置等页面。阅读页的明暗跟随所选的阅读纸张。")
            }

            Section {
                if typeSize.isAccessibilitySize {
                    // Large text: one row per colour, so every name and detail fits.
                    ForEach(JerreaderThemeColorChoice.allCases) { choice in
                        listRow(choice)
                    }
                } else {
                    LazyVGrid(columns: Self.swatchColumns, spacing: 14) {
                        ForEach(JerreaderThemeColorChoice.allCases) { choice in
                            swatch(choice)
                        }
                    }
                    .padding(.vertical, 6)
                    .animation(JerreaderMotion.choose(JerreaderMotion.stateChange, reduceMotion: reduceMotion),
                               value: selectedRawValue)
                    .listRowInsets(EdgeInsets(top: 8, leading: 8, bottom: 8, trailing: 8))
                    VStack(alignment: .leading, spacing: 3) {
                        Text(selected.title).font(.headline)
                        Text(selected.detail).font(.subheadline).foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 4)
                    .accessibilityElement(children: .combine)
                    .accessibilityIdentifier("themeSelectedSummary")
                }
            } header: {
                Text("主题色系").settingsHeader()
            } footer: {
                Text("色系会同时调整强调色、按钮和卡片上的点缀。")
            }

            Section {
                Toggle("背景光晕", isOn: $backdropGlow)
                    .accessibilityIdentifier("themeBackdropGlow")
            } header: {
                Text("背景").settingsHeader()
            } footer: {
                Text("在每页顶部铺一层很淡的主题色。关闭后页面是纯净的纸色。")
            }

            Section {
                NavigationLink {
                    GlobalReaderDefaultsView()
                } label: {
                    LabeledContent("阅读纸张") {
                        Text((ReaderThemeChoice(rawValue: readerThemeRawValue) ?? .light).title)
                    }
                }
                .accessibilityIdentifier("themeReaderPaperLink")
            } header: {
                Text("阅读页").settingsHeader()
            } footer: {
                Text("阅读页的纸张、字体和间距不随界面主题变化，在“默认阅读排版”里单独设置。")
            }
        }
        .scrollContentBackground(.hidden)
        .background(JerreaderCanvasBackground())
        .navigationTitle("界面主题")
        .navigationBarTitleDisplayMode(.inline)
        .tint(JerreaderTheme.accent(for: selected))
        .sensoryFeedback(.selection, trigger: selectedRawValue)
        .sensoryFeedback(.selection, trigger: backdropGlow)
    }

    /// A 44 pt colour circle inside a 54 pt ring, with the colour's name under it.
    private func swatch(_ choice: JerreaderThemeColorChoice) -> some View {
        let isSelected = choice == selected
        let tint = JerreaderTheme.accent(for: choice)
        return Button {
            select(choice)
        } label: {
            VStack(spacing: 8) {
                ZStack {
                    Circle()
                        .strokeBorder(tint, lineWidth: 2)
                        .frame(width: 54, height: 54)
                        .scaleEffect(isSelected || reduceMotion ? 1 : 0.84)
                        .opacity(isSelected ? 1 : 0)
                    Circle()
                        .fill(tint)
                        .frame(width: 44, height: 44)
                        .overlay { Circle().strokeBorder(Color.primary.opacity(0.08), lineWidth: 0.5) }
                    JerreaderSymbol(name: "checkmark", size: 20)
                        .foregroundStyle(JerreaderTheme.onPrimaryAction)
                        .scaleEffect(isSelected || reduceMotion ? 1 : 0.5)
                        .opacity(isSelected ? 1 : 0)
                }
                .frame(width: 54, height: 54)
                Text(choice.title)
                    .font(.caption.weight(isSelected ? .semibold : .regular))
                    .foregroundStyle(isSelected ? Color.primary : Color.secondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
            }
            .frame(maxWidth: .infinity)
            .contentShape(Rectangle())
        }
        .buttonStyle(JerreaderPressButtonStyle(pressedScale: 0.94))
        .accessibilityLabel("\(choice.title)，\(choice.detail)")
        .accessibilityAddTraits(isSelected ? .isSelected : [])
        .accessibilityIdentifier("themeSwatch-\(choice.rawValue)")
    }

    /// The large-text row: swatch, name and detail, and the check on the chosen one.
    private func listRow(_ choice: JerreaderThemeColorChoice) -> some View {
        let isSelected = choice == selected
        let tint = JerreaderTheme.accent(for: choice)
        return Button {
            select(choice)
        } label: {
            HStack(spacing: 14) {
                Circle()
                    .fill(tint)
                    .frame(width: 44, height: 44)
                    .overlay { Circle().strokeBorder(Color.primary.opacity(0.08), lineWidth: 0.5) }
                    .padding(3)
                    .overlay { Circle().strokeBorder(tint, lineWidth: 2).opacity(isSelected ? 1 : 0) }
                VStack(alignment: .leading, spacing: 3) {
                    Text(choice.title).foregroundStyle(.primary)
                    Text(choice.detail).font(.caption).foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
                if isSelected {
                    JerreaderSymbol(name: "checkmark", size: 20).foregroundStyle(tint)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
        .accessibilityIdentifier("themeSwatch-\(choice.rawValue)")
    }

    private func select(_ choice: JerreaderThemeColorChoice) {
        guard choice != selected else { return }
        selectedRawValue = choice.rawValue
    }
}

/// The preview at the top of 界面主题: the continue-reading hero card and the
/// two kinds of button, drawn in `choice`'s colours. Every colour is taken
/// from `choice` rather than from the stored theme, so the card follows the
/// swatch the moment it is tapped. Nothing here can be pressed; VoiceOver
/// reads it as one description.
private struct ThemePreviewCard: View {
    let choice: JerreaderThemeColorChoice

    private static let sampleProgress = 0.42

    var body: some View {
        let accent = JerreaderTheme.accent(for: choice)
        VStack(alignment: .leading, spacing: 12) {
            hero
            HStack(spacing: 8) {
                Text("开始练习")
                    .foregroundStyle(JerreaderTheme.onPrimaryAction)
                    .padding(.horizontal, 16)
                    .frame(minHeight: 34)
                    .background(accent, in: Capsule())
                Text("加入生词本")
                    .foregroundStyle(accent)
                    .padding(.horizontal, 16)
                    .frame(minHeight: 34)
                    .background(JerreaderTheme.accentFill(for: choice), in: Capsule())
                Spacer(minLength: 0)
            }
            .font(.subheadline.weight(.semibold))
            .lineLimit(1)
            .minimumScaleFactor(0.7)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(choice.title)主题的界面预览")
        .accessibilityIdentifier("themePreview")
    }

    private var hero: some View {
        let shape = RoundedRectangle(cornerRadius: JerreaderRadius.control, style: .continuous)
        return VStack(alignment: .leading, spacing: 6) {
            Text("继续阅读")
                .font(.caption.weight(.semibold))
                .tracking(0.6)
                .foregroundStyle(Color.white.opacity(0.76))
            Text("こころ")
                .font(.system(.title3, design: .serif).weight(.semibold))
                .foregroundStyle(Color.white)
            HStack(spacing: 10) {
                GeometryReader { proxy in
                    ZStack(alignment: .leading) {
                        Capsule().fill(Color.white.opacity(0.22))
                        Capsule().fill(Color.white)
                            .frame(width: proxy.size.width * Self.sampleProgress)
                    }
                }
                .frame(height: 4)
                Text("42%")
                    .font(.caption.weight(.semibold))
                    .monospacedDigit()
                    .foregroundStyle(Color.white.opacity(0.86))
            }
            .padding(.top, 4)
        }
        .lineLimit(1)
        .minimumScaleFactor(0.7)
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background {
            // The hero gradient of 继续阅读 and 今日练习, in this colour.
            shape
                .fill(
                    LinearGradient(
                        colors: [JerreaderTheme.accentDeep(for: choice), JerreaderTheme.ink],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                )
                .overlay {
                    shape.fill(
                        RadialGradient(
                            colors: [Color.white.opacity(0.16), Color.white.opacity(0)],
                            center: .topLeading,
                            startRadius: 0,
                            endRadius: 260
                        )
                    )
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
    @AppStorage(ReaderInterfaceDefaults.verticalMarginsKey)
    private var verticalMargins = 1.0
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
                    Label {
                        Text("已自动同步到 \(books.count) 本现有书籍").foregroundStyle(.secondary)
                    } icon: {
                        JerreaderSymbol(name: "checkmark.circle.fill", size: 20)
                            .foregroundStyle(JerreaderTheme.jade)
                    }
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
                Text("应用范围").settingsHeader()
            }

            Section {
                // The 2.0 pill control in place of the system segments, so the
                // chosen option carries the theme colour.
                JerreaderPillPicker(
                    options: ReaderReadingMode.allCases.map { mode in
                        JerreaderPillPicker<ReaderReadingMode>.Option(value: mode, title: mode.title)
                    },
                    selection: readingModeBinding
                )
                .accessibilityElement(children: .contain)
                .accessibilityLabel("翻页方式")
                Text(readingModeBinding.wrappedValue.detail)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Toggle("显示可拖动阅读进度", isOn: $showsReadingProgress)
            } header: {
                Text("翻页与导航").settingsHeader()
            } footer: {
                Text(
                    appliesToExistingBooks
                        ? "当前排版会同步到现有书籍；阅读位置、书签与批注不受影响。"
                        : "当前排版只会自动用于以后导入的新书。"
                )
            }

            Section {
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
            } header: {
                Text("字体与字号").settingsHeader()
            }

            Section {
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
                LabeledContent("左右边距") {
                    Text(pageMargins.formatted(.number.precision(.fractionLength(1))))
                        .monospacedDigit()
                }
                Slider(value: $pageMargins, in: 0.5 ... 2, step: 0.1)
                    .accessibilityLabel("左右边距")
                LabeledContent("上下边距") {
                    Text(verticalMargins.formatted(.number.precision(.fractionLength(1))))
                        .monospacedDigit()
                }
                Slider(
                    value: $verticalMargins,
                    in: ReaderInterfaceDefaults.verticalMarginsRange,
                    step: 0.1
                )
                .accessibilityLabel("上下边距")
                .accessibilityIdentifier("readerDefaultsVerticalMargins")
            } header: {
                Text("间距").settingsHeader()
            } footer: {
                Text("上下边距是正文离屏幕上下两端的留白，对所有书立即生效，不分单本；PDF 按页面本身显示，不受影响。")
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
            } header: {
                Text("背景").settingsHeader()
            }

            Section {
                JerreaderPillPicker(
                    options: ReaderTextOrientationChoice.allCases.map { orientation in
                        JerreaderPillPicker<ReaderTextOrientationChoice>.Option(
                            value: orientation, title: orientation.title
                        )
                    },
                    selection: japaneseOrientationBinding
                )
                .accessibilityElement(children: .contain)
                .accessibilityLabel("默认版式")
                Text(japaneseOrientationBinding.wrappedValue.detail)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } header: {
                Text("日文原版排版").settingsHeader()
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
    /// The reader's own 翻译与 AI also has 翻译卡片显示 AI 按钮 (2.1.0 §7).
    var showsAIActionBarToggle = false

    var body: some View {
        Form {
            TranslationPreferencesSection(
                settings: settings,
                quickSentenceEnabled: $quickSentenceEnabled,
                disablesTapPageTurnsDuringQuickTranslation:
                    $disablesTapPageTurnsDuringQuickTranslation,
                showsQuickSentenceToggle: showsQuickSentenceToggle,
                showsAIActionBarToggle: showsAIActionBarToggle
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
            Section {
                fact("书籍、书架与学习记录默认只保存在本机", icon: "books.vertical", tint: JerreaderTheme.accent)
                fact("API Key 只保存在本机安全存储", icon: "lock", tint: SettingsTint.guide)
                fact("在线 AI 只接收你主动选择的文字与必要上下文", icon: "checkmark.shield", tint: SettingsTint.privacy)
                fact("导入的书籍是只读输入，阅读与翻译不会改写它", icon: "character.book.closed",
                     tint: SettingsTint.reading)
            } header: {
                Text("本机数据").settingsHeader()
            }
            Section {
                fact("备份需要你先授权一个文件夹", icon: "square.and.arrow.down", tint: SettingsTint.backup)
            } header: { Text("写出副本").settingsHeader() } footer: {
                Text("密钥不会写入书籍、日志或安装包。备份只写入你选择的位置。")
            }
        }
        .scrollContentBackground(.hidden)
        .background(JerreaderCanvasBackground())
        .navigationTitle("数据与隐私")
        .navigationBarTitleDisplayMode(.inline)
    }

    /// One promise about the data: a 30 pt tile and the sentence beside it.
    private func fact(_ title: String, icon: String, tint: Color) -> some View {
        HStack(spacing: 14) {
            JerreaderIconTile(systemImage: icon, tint: tint, size: 30)
            Text(LocalizedStringKey(title))
                .frame(maxWidth: .infinity, alignment: .leading)
                .alignmentGuide(.listRowSeparatorLeading) { $0[.leading] }
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
    }
}

private struct SettingsSectionHeader: View {
    let title: String
    let systemImage: String

    var body: some View {
        JerreaderLabel(title: title, icon: systemImage, size: 15)
            .settingsHeader()
            .accessibilityAddTraits(.isHeader)
    }
}

struct TranslationPreferencesSection: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ObservedObject var settings: TranslationSettingsStore
    @Binding var quickSentenceEnabled: Bool
    @Binding var disablesTapPageTurnsDuringQuickTranslation: Bool
    let showsQuickSentenceToggle: Bool
    var showsAIActionBarToggle = false
    @AppStorage(ReaderInterfaceDefaults.aiActionBarHiddenKey)
    private var aiActionBarHidden = false
    @State private var connectionTestState: TranslationConnectionTestState = .idle
    @State private var connectionTestTask: Task<Void, Never>?

    var body: some View {
        Section {
            if showsQuickSentenceToggle {
                Toggle("轻点正文快速翻译", isOn: $quickSentenceEnabled)

                JerreaderPillPicker(
                    options: ReaderQuickTranslationUnit.allCases.map { unit in
                        JerreaderPillPicker<ReaderQuickTranslationUnit>.Option(value: unit, title: unit.title)
                    },
                    selection: $settings.quickTranslationUnit
                )
                .disabled(!quickSentenceEnabled)
                .opacity(quickSentenceEnabled ? 1 : 0.45)
                .accessibilityElement(children: .contain)
                .accessibilityLabel("翻译范围")

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
                Label {
                    Text("原文和目标语言不能相同，请选择另一个目标语言或使用自动识别。")
                } icon: {
                    JerreaderSymbol(name: "exclamationmark.triangle", size: 15)
                }
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

            if showsAIActionBarToggle {
                Toggle(
                    "翻译卡片显示 AI 按钮",
                    isOn: Binding(
                        get: { !aiActionBarHidden },
                        set: { aiActionBarHidden = !$0 }
                    )
                )
                .accessibilityIdentifier("reader-ai-action-bar-toggle")
            }

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
                Label {
                    Text("Apple 翻译首次使用时可能需要数分钟下载语言包；请保持 App 在前台。若长时间无进展，可先在 Apple“翻译”App 中下载对应语言。")
                } icon: {
                    JerreaderSymbol(name: "arrow.down.to.line", size: 15)
                }
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

    /// Ready reads in jade (the success colour), missing setup in orange.
    private func configurationStatus(message: String, isReady: Bool) -> some View {
        let tint = isReady ? JerreaderTheme.jade : Color.orange
        return Label {
            Text(message)
        } icon: {
            JerreaderSymbol(name: isReady ? "checkmark.shield" : "exclamationmark.triangle", size: 16)
        }
        .font(.caption)
        .foregroundStyle(tint)
        .padding(.horizontal, 12)
        .padding(.vertical, 9)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(tint.opacity(0.12), in: RoundedRectangle(cornerRadius: JerreaderRadius.small, style: .continuous))
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
        .buttonStyle(JerreaderTintedButtonStyle(height: 40))
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
