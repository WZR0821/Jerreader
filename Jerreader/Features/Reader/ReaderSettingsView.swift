import SwiftUI

struct ReaderSettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let model: EPUBReaderViewModel
    @ObservedObject private var translationSettings: TranslationSettingsStore
    @AppStorage(ReaderInterfaceDefaults.immersiveReadingKey)
    private var immersiveReading = true
    @AppStorage(ReaderInterfaceDefaults.layoutModeKey)
    private var layoutModeRawValue = ReaderLayoutMode.standard.rawValue
    @AppStorage(ReaderInterfaceDefaults.topTransparencyKey)
    private var topTransparency = ReaderInterfaceDefaults.barTransparency
    @AppStorage(ReaderInterfaceDefaults.bottomTransparencyKey)
    private var bottomTransparency = ReaderInterfaceDefaults.barTransparency
    @AppStorage(ReaderInterfaceDefaults.japaneseFuriganaKey)
    private var japaneseFuriganaEnabled = false
    @AppStorage(ReaderInterfaceDefaults.verticalMarginsKey)
    private var verticalMargins = 1.0

    init(model: EPUBReaderViewModel) {
        self.model = model
        _translationSettings = ObservedObject(
            wrappedValue: model.translationSettings
        )
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: JerreaderSpacing.xl) {
                    paginationSection
                    interfaceSection
                    interactionSection
                    if model.isReflowableBook {
                        typographySection
                        spacingSection
                    }
                    backgroundSection
                    translationSection
                }
                .padding(.horizontal, JerreaderTheme.pagePadding)
                .padding(.top, JerreaderSpacing.xs)
                .padding(.bottom, JerreaderSpacing.xxl)
                .frame(maxWidth: 680)
                .frame(maxWidth: .infinity)
            }
            .scrollDismissesKeyboard(.interactively)
            .background(JerreaderCanvasBackground())
            .navigationTitle("阅读与翻译")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
        }
        .tint(JerreaderTheme.accent)
        // The swatches are plain buttons; the pill pickers tick their own
        // selection haptic, so 文字方向 no longer needs one here.
        .sensoryFeedback(.selection, trigger: model.theme)
    }

    /// Rows appear and disappear with the cards' own spring (a cross-fade
    /// with Reduce Motion), as Form rows used to.
    private var rowAnimation: Animation {
        JerreaderMotion.choose(JerreaderMotion.stateChange, reduceMotion: reduceMotion)
    }

    /// A row title over a control, as on 翻页方式.
    private func controlTitle(_ title: String) -> some View {
        Text(title)
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(.primary)
    }

    private func detailText(_ text: String) -> some View {
        Text(text)
            .font(.caption)
            .foregroundStyle(.secondary)
            .fixedSize(horizontal: false, vertical: true)
    }

    private var paginationSection: some View {
        ReaderSettingsCard(
            "阅读方式",
            footer: "这一页的改动只作用于《\(model.book.title)》。要改所有新书的默认值，请到「设置 → 默认阅读排版」。",
            hasRows: model.book.format == .pdf || model.isReflowableBook
        ) {
            if model.book.format == .pdf {
                detailText("PDF 按单页显示：点按左右边缘翻页，点正文句子翻译；没有文字层的扫描页会在本机按页 OCR。")
                    .readerSettingsRow()

                ReaderSettingsDivider()

                VStack(alignment: .leading, spacing: JerreaderSpacing.xs) {
                    Toggle("论文双栏模式", isOn: pdfPaperModeBinding)
                    detailText("开启后，轻点识别句子、段落或 OCR 文字时只使用所点的左栏或右栏，避免选区横跨中缝。系统长按手柄仍保持 PDFKit 原生规则。")
                }
                .readerSettingsRow()
            }

            if model.isReflowableBook {
                VStack(alignment: .leading, spacing: 10) {
                    controlTitle("翻页方式")
                    JerreaderPillPicker(
                        options: ReaderReadingMode.allCases.map { mode in
                            JerreaderPillPicker<ReaderReadingMode>.Option(value: mode, title: mode.title)
                        },
                        selection: readingModeBinding
                    )
                    detailText(model.readingMode.detail)
                }
                .readerSettingsRow()
            }

            // The override is offered only for books that actually render
            // vertically. Keying it off "is reflowable" put a picker titled
            // 日文版式 on English novels, where neither option changed
            // anything. `publicationLayout` is the detected truth and already
            // covers books whose dc:language is missing or wrong.
            if model.isReflowableBook, showsTextOrientationPicker {
                ReaderSettingsDivider()

                VStack(alignment: .leading, spacing: 10) {
                    controlTitle("文字方向")
                    JerreaderPillPicker(
                        options: [ReaderTextOrientationChoice.publication, .horizontal].map { orientation in
                            JerreaderPillPicker<ReaderTextOrientationChoice>.Option(
                                value: orientation,
                                title: orientation.title
                            )
                        },
                        selection: textOrientationBinding
                    )
                    detailText(model.textOrientation.detail)
                }
                .readerSettingsRow()
            }
        }
    }

    private var interfaceSection: some View {
        ReaderSettingsCard(
            "阅读界面",
            footer: "只调整控制栏背景，按钮保持清晰。日语注音仅显示在翻译卡片中；这些选项对所有书生效。"
        ) {
            // A pill picker like the sheet's other choices; the UI tests find
            // each option by its readerLayout-<mode> identifier.
            VStack(alignment: .leading, spacing: 10) {
                controlTitle("界面布局")
                    .accessibilityHidden(true)
                JerreaderPillPicker(
                    options: ReaderLayoutMode.allCases.map { mode in
                        JerreaderPillPicker<ReaderLayoutMode>.Option(
                            value: mode,
                            title: mode.title,
                            identifier: "readerLayout-\(mode.rawValue)"
                        )
                    },
                    selection: layoutModeBinding
                )
                .accessibilityElement(children: .contain)
                .accessibilityLabel("界面布局")
                detailText(layoutMode.detail)
            }
            .readerSettingsRow()

            // The immersive layout always hides the status bar, so the switch
            // only means something in the standard one.
            if layoutMode == .standard {
                ReaderSettingsDivider()
                Toggle("隐藏状态栏", isOn: $immersiveReading)
                    .readerSettingsRow()
            }

            ReaderSettingsDivider()
            transparencySlider("顶部栏透明度", value: $topTransparency)
                .readerSettingsRow()

            ReaderSettingsDivider()
            transparencySlider("底部栏透明度", value: $bottomTransparency)
                .readerSettingsRow()

            ReaderSettingsDivider()
            Toggle("日语注音", isOn: $japaneseFuriganaEnabled)
                .readerSettingsRow()
        }
        .animation(rowAnimation, value: layoutModeRawValue)
    }

    private var layoutMode: ReaderLayoutMode {
        ReaderLayoutMode(rawValue: layoutModeRawValue) ?? .standard
    }

    private var layoutModeBinding: Binding<ReaderLayoutMode> {
        Binding(
            get: { layoutMode },
            set: { layoutModeRawValue = $0.rawValue }
        )
    }

    private func transparencySlider(_ title: String, value: Binding<Double>) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(title)
                Spacer()
                Text((1 - ReaderInterfaceDefaults.backgroundOpacity(value.wrappedValue)).formatted(.percent.precision(.fractionLength(0))))
                    .foregroundStyle(.secondary).monospacedDigit()
            }
            Slider(value: Binding(get: { 1 - ReaderInterfaceDefaults.backgroundOpacity(value.wrappedValue) }, set: { value.wrappedValue = $0 }), in: 0...1, step: 0.05)
                .accessibilityLabel(title)
        }
    }

    /// Vertical publications, plus any book already switched to the horizontal
    /// override, so the user can always switch back.
    private var showsTextOrientationPicker: Bool {
        model.publicationLayout.verticalText
            || model.textOrientation == .horizontal
    }

    private var interactionSection: some View {
        ReaderSettingsCard(
            "阅读交互",
            footer: interactionDetail,
            hasRows: model.supportsQuickSentenceTranslation
        ) {
            if model.supportsQuickSentenceTranslation {
                Toggle("轻点正文快速翻译", isOn: quickSentenceTranslationBinding)
                    .readerSettingsRow()

                ReaderSettingsDivider()

                VStack(alignment: .leading, spacing: 10) {
                    controlTitle("翻译范围")
                    JerreaderPillPicker(
                        options: ReaderQuickTranslationUnit.allCases.map { unit in
                            JerreaderPillPicker<ReaderQuickTranslationUnit>.Option(value: unit, title: unit.title)
                        },
                        selection: quickTranslationUnitBinding
                    )
                }
                .disabled(!model.isQuickSentenceTranslationEnabled)
                .opacity(model.isQuickSentenceTranslationEnabled ? 1 : 0.45)
                .readerSettingsRow()

                ReaderSettingsDivider()

                Toggle(
                    "轻点翻译时禁用点按翻页",
                    isOn: disablesTapPageTurnsDuringQuickTranslationBinding
                )
                .disabled(!model.isQuickSentenceTranslationEnabled)
                .readerSettingsRow()
            }
        }
        .animation(rowAnimation, value: model.isQuickSentenceTranslationEnabled)
    }

    private var interactionDetail: String {
        guard model.supportsQuickSentenceTranslation else {
            return "长按正文可选词或选句翻译，拖动系统选区手柄可精确调整范围。"
        }
        guard model.isQuickSentenceTranslationEnabled else {
            return model.isReflowableBook
                ? "已关闭：轻点词语直接查词并记入学习记录，轻点空白处显示或隐藏阅读界面；长按选词、拖动选区仍可翻译。"
                : "已关闭：轻点正文只显示或隐藏阅读界面；长按选词、拖动选区仍可翻译。"
        }
        let target = model.quickTranslationUnit == .sentence
            ? "当前句子"
            : "当前段落"
        return model.disablesTapPageTurnsDuringQuickTranslation
            ? "轻点正文任意位置都会翻译\(target)；翻页请用横向轻扫或底部按钮。"
            : "轻点正文中间翻译\(target)；轻点左右边缘仍然翻页。"
    }

    private var typographySection: some View {
        ReaderSettingsCard("字体与字号") {
            HStack(spacing: JerreaderSpacing.sm) {
                Text("字体")
                    .accessibilityHidden(true)
                Spacer(minLength: JerreaderSpacing.sm)
                Picker("字体", selection: fontChoiceBinding) {
                    ForEach(ReaderFontChoice.allCases) { font in
                        Text(font.title).tag(font)
                    }
                }
                .pickerStyle(.menu)
                .labelsHidden()
            }
            .readerSettingsRow()

            ReaderSettingsDivider()

            // 字号 as "A − value + A", in the slider's old 0.5pt steps.
            HStack(spacing: JerreaderSpacing.sm) {
                Text("字号")
                Spacer(minLength: JerreaderSpacing.sm)
                ReaderFontSizeStepper(
                    value: model.fontPointSize,
                    range: ReaderFontSizing.pointSizeRange,
                    step: ReaderFontSizing.pointSizeStep,
                    valueText: fontPointSizeLabel,
                    onStep: { pointSize in
                        model.updateFontPointSize(pointSize)
                    }
                )
            }
            .readerSettingsRow()
        }
    }

    private var spacingSection: some View {
        ReaderSettingsCard("版面") {
            spacingSlider(
                "行间距",
                valueText: model.lineHeight.formatted(.number.precision(.fractionLength(1))),
                value: lineHeightBinding,
                range: 1.0 ... 2.2
            )
            .readerSettingsRow()

            ReaderSettingsDivider()

            spacingSlider(
                "段间距",
                valueText: paragraphSpacingLabel,
                value: paragraphSpacingBinding,
                range: 0.0 ... 2.0
            )
            .readerSettingsRow()

            ReaderSettingsDivider()

            spacingSlider(
                "左右边距",
                valueText: pageMarginLabel,
                value: pageMarginsBinding,
                range: 0.5 ... 2.0
            )
            .readerSettingsRow()

            ReaderSettingsDivider()

            VStack(alignment: .leading, spacing: JerreaderSpacing.xs) {
                spacingSlider(
                    "上下边距",
                    valueText: ReaderInterfaceDefaults.verticalMarginsLabel(verticalMargins),
                    value: verticalMarginsBinding,
                    range: ReaderInterfaceDefaults.verticalMarginsRange
                )
                detailText("正文离屏幕上下两端（标准布局下是上下栏）的留白，对所有书生效；PDF 按页面本身显示，不受影响。")
            }
            .readerSettingsRow()
        }
    }

    /// The title and value over a 0.1-step slider, as the 版面 rows were.
    private func spacingSlider(
        _ title: String,
        valueText: String,
        value: Binding<Double>,
        range: ClosedRange<Double>
    ) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(title)
                Spacer()
                Text(valueText)
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
            }
            Slider(value: value, in: range, step: 0.1)
                .accessibilityLabel(title)
        }
    }

    private var backgroundSection: some View {
        ReaderSettingsCard("背景") {
            HStack(spacing: 0) {
                ForEach(ReaderThemeChoice.allCases) { theme in
                    let isSelected = model.customBackgroundHex.isEmpty && model.theme == theme
                    Button {
                        withAnimation(reduceMotion ? nil : JerreaderMotion.stateChange) {
                            model.updateCustomBackgroundHex("")
                            model.updateTheme(theme)
                        }
                    } label: {
                        ReaderThemeSwatch(
                            color: theme.swatch,
                            title: theme.title,
                            isSelected: isSelected,
                            checkColor: theme == .dark ? Color.white : JerreaderTheme.accent
                        )
                    }
                    .buttonStyle(JerreaderPressButtonStyle(pressedScale: 0.94))
                    .accessibilityLabel("\(theme.title)背景")
                    .accessibilityAddTraits(isSelected ? .isSelected : [])
                }
            }
            .padding(.horizontal, JerreaderSpacing.xs)
            .padding(.vertical, JerreaderSpacing.sm)

            ReaderSettingsDivider()

            Toggle("自定义背景颜色", isOn: customBackgroundEnabledBinding)
                .readerSettingsRow()

            if !model.customBackgroundHex.isEmpty {
                ReaderSettingsDivider()
                ColorPicker(
                    "选择背景颜色",
                    selection: customBackgroundColorBinding,
                    supportsOpacity: false
                )
                .readerSettingsRow()
            }

            ReaderSettingsDivider()

            Toggle(
                "自定义选区颜色",
                isOn: customSelectionColorEnabledBinding
            )
            .readerSettingsRow()

            if !model.customSelectionColorHex.isEmpty {
                ReaderSettingsDivider()
                ColorPicker(
                    "选择选区颜色",
                    selection: customSelectionColorBinding,
                    supportsOpacity: false
                )
                .readerSettingsRow()
            }
        }
        .animation(rowAnimation, value: model.customBackgroundHex.isEmpty)
        .animation(rowAnimation, value: model.customSelectionColorHex.isEmpty)
    }

    private var translationSection: some View {
        ReaderSettingsCard("翻译", footer: "翻译服务与密钥对所有书籍通用。") {
            NavigationLink {
                // The interaction toggles now live in this sheet's own
                // 阅读交互 section, so the detail view is purely about the
                // translation service itself.
                TranslationSettingsDetailView(
                    settings: translationSettings,
                    quickSentenceEnabled: quickSentenceTranslationBinding,
                    disablesTapPageTurnsDuringQuickTranslation:
                        disablesTapPageTurnsDuringQuickTranslationBinding,
                    showsQuickSentenceToggle: false
                )
            } label: {
                HStack(spacing: JerreaderSpacing.sm) {
                    JerreaderIconTile(systemImage: "character.bubble", size: 32)
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 3) {
                        Text("翻译与 AI")
                            .foregroundStyle(.primary)
                        Text("服务商、三语互译与提示词")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer(minLength: JerreaderSpacing.xs)
                    JerreaderSymbol(name: "chevron.forward", size: 14)
                        .foregroundStyle(.tertiary)
                        .accessibilityHidden(true)
                }
                .readerSettingsRow()
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
    }

    private var pageMarginLabel: String {
        switch model.pageMargins {
        case ..<0.8: return "窄"
        case 0.8 ..< 1.3: return "标准"
        default: return "宽"
        }
    }

    private var paragraphSpacingLabel: String {
        if model.paragraphSpacing == 0 {
            return "紧凑"
        }
        return "\(model.paragraphSpacing.formatted(.number.precision(.fractionLength(1)))) em"
    }

    private var fontChoiceBinding: Binding<ReaderFontChoice> {
        Binding(get: { model.fontChoice }, set: { model.updateFontChoice($0) })
    }

    private var fontPointSizeBinding: Binding<Double> {
        Binding(
            get: { model.fontPointSize },
            set: { model.updateFontPointSize($0) }
        )
    }

    private var fontPointSizeLabel: String {
        let pointSize = model.fontPointSize
        let precision = pointSize.rounded() == pointSize ? 0 : 1
        return "\(pointSize.formatted(.number.precision(.fractionLength(precision)))) 号"
    }

    private var lineHeightBinding: Binding<Double> {
        Binding(get: { model.lineHeight }, set: { model.updateLineHeight($0) })
    }

    private var paragraphSpacingBinding: Binding<Double> {
        Binding(
            get: { model.paragraphSpacing },
            set: { model.updateParagraphSpacing($0) }
        )
    }

    private var pageMarginsBinding: Binding<Double> {
        Binding(get: { model.pageMargins }, set: { model.updatePageMargins($0) })
    }

    /// Stored to one decimal, so the value survives a backup round trip and
    /// reads the same on Android.
    private var verticalMarginsBinding: Binding<Double> {
        Binding(
            get: { ReaderInterfaceDefaults.clampedVerticalMargins(verticalMargins) },
            set: {
                verticalMargins = (ReaderInterfaceDefaults.clampedVerticalMargins($0) * 10)
                    .rounded() / 10
            }
        )
    }

    private var textOrientationBinding: Binding<ReaderTextOrientationChoice> {
        Binding(get: { model.textOrientation }, set: { model.updateTextOrientation($0) })
    }

    private var readingModeBinding: Binding<ReaderReadingMode> {
        Binding(get: { model.readingMode }, set: { model.updateReadingMode($0) })
    }

    private var customBackgroundEnabledBinding: Binding<Bool> {
        Binding(
            get: { !model.customBackgroundHex.isEmpty },
            set: { enabled in
                model.updateCustomBackgroundHex(
                    enabled ? ReaderCustomBackground.initialHex : ""
                )
            }
        )
    }

    private var customBackgroundColorBinding: Binding<Color> {
        Binding(
            get: {
                Color(
                    uiColor: ReaderCustomBackground.uiColor(
                        hex: model.customBackgroundHex
                    ) ?? UIColor(
                        red: 0.93,
                        green: 0.96,
                        blue: 0.98,
                        alpha: 1
                    )
                )
            },
            set: { color in
                if let hex = ReaderCustomBackground.hex(from: UIColor(color)) {
                    model.updateCustomBackgroundHex(hex)
                }
            }
        )
    }

    private var customSelectionColorEnabledBinding: Binding<Bool> {
        Binding(
            get: { !model.customSelectionColorHex.isEmpty },
            set: { model.updateCustomSelectionColorHex($0 ? "#317DC2" : "") }
        )
    }

    private var customSelectionColorBinding: Binding<Color> {
        Binding(
            get: {
                Color(
                    uiColor: ReaderCustomBackground.uiColor(
                        hex: model.customSelectionColorHex
                    ) ?? UIColor(
                        red: 0.19,
                        green: 0.49,
                        blue: 0.76,
                        alpha: 1
                    )
                )
            },
            set: { color in
                if let hex = ReaderCustomBackground.hex(from: UIColor(color)) {
                    model.updateCustomSelectionColorHex(hex)
                }
            }
        )
    }

    private var quickSentenceTranslationBinding: Binding<Bool> {
        Binding(
            get: { model.isQuickSentenceTranslationEnabled },
            set: { model.setQuickSentenceTranslationEnabled($0) }
        )
    }

    private var quickTranslationUnitBinding:
        Binding<ReaderQuickTranslationUnit>
    {
        Binding(
            get: { model.quickTranslationUnit },
            set: { model.setQuickTranslationUnit($0) }
        )
    }

    private var disablesTapPageTurnsDuringQuickTranslationBinding: Binding<Bool> {
        Binding(
            get: { model.disablesTapPageTurnsDuringQuickTranslation },
            set: { model.setDisablesTapPageTurnsDuringQuickTranslation($0) }
        )
    }

    private var pdfPaperModeBinding: Binding<Bool> {
        Binding(
            get: { model.isPDFPaperModeEnabled },
            set: { model.setPDFPaperModeEnabled($0) }
        )
    }
}

private extension ReaderThemeChoice {
    var swatch: Color {
        switch self {
        case .light: return .white
        case .sepia: return Color(red: 0.98, green: 0.95, blue: 0.88)
        case .coolGray: return Color(red: 0.93, green: 0.96, blue: 0.98)
        case .dark: return Color(red: 0.08, green: 0.10, blue: 0.13)
        }
    }
}
