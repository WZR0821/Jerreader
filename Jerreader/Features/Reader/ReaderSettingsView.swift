import SwiftUI

struct ReaderSettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ObservedObject var model: EPUBReaderViewModel
    @ObservedObject private var translationSettings: TranslationSettingsStore

    init(model: EPUBReaderViewModel) {
        self.model = model
        _translationSettings = ObservedObject(
            wrappedValue: model.translationSettings
        )
    }

    var body: some View {
        NavigationStack {
            Form {
                paginationSection
                interactionSection
                if model.isReflowableBook {
                    typographySection
                    spacingSection
                }
                backgroundSection
                translationSection
            }
            .scrollContentBackground(.hidden)
            .background(JerreaderCanvasBackground())
            .listSectionSpacing(18)
            .scrollDismissesKeyboard(.interactively)
            .navigationTitle("阅读与翻译")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
        }
        .tint(JerreaderTheme.accent)
        .sensoryFeedback(.selection, trigger: model.theme)
        .sensoryFeedback(.selection, trigger: model.textOrientation)
    }

    private var paginationSection: some View {
        Section {
            if model.book.format == .pdf {
                Text("PDF 按单页显示：点按左右边缘翻页，点正文句子翻译；没有文字层的扫描页会在本机按页 OCR。")
                    .font(.caption)
                    .foregroundStyle(.secondary)

                Toggle("论文双栏模式", isOn: pdfPaperModeBinding)

                Text("开启后，轻点识别句子、段落或 OCR 文字时只使用所点的左栏或右栏，避免选区横跨中缝。系统长按手柄仍保持 PDFKit 原生规则。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            if model.isReflowableBook {
                Picker("翻页方式", selection: readingModeBinding) {
                    ForEach(ReaderReadingMode.allCases) { mode in
                        Text(mode.title).tag(mode)
                    }
                }
                .pickerStyle(.segmented)

                Text(model.readingMode.detail)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            // The override is offered only for books that actually render
            // vertically. Keying it off "is reflowable" put a picker titled
            // 日文版式 on English novels, where neither option changed
            // anything. `publicationLayout` is the detected truth and already
            // covers books whose dc:language is missing or wrong.
            if model.isReflowableBook, showsTextOrientationPicker {
                Picker("文字方向", selection: textOrientationBinding) {
                    ForEach(
                        [ReaderTextOrientationChoice.publication, .horizontal]
                    ) { orientation in
                        Text(orientation.title).tag(orientation)
                    }
                }
                .pickerStyle(.segmented)

                Text(model.textOrientation.detail)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        } header: {
            Text("阅读方式")
        } footer: {
            Text("这一页的改动只作用于《\(model.book.title)》。要改所有新书的默认值，请到「设置 → 默认阅读排版」。")
        }
    }

    /// Vertical publications, plus any book already switched to the horizontal
    /// override, so the user can always switch back.
    private var showsTextOrientationPicker: Bool {
        model.publicationLayout.verticalText
            || model.textOrientation == .horizontal
    }

    private var interactionSection: some View {
        Section {
            if model.supportsQuickSentenceTranslation {
                Toggle("轻点正文快速翻译", isOn: quickSentenceTranslationBinding)

                Picker("翻译范围", selection: quickTranslationUnitBinding) {
                    ForEach(ReaderQuickTranslationUnit.allCases) { unit in
                        Text(unit.title).tag(unit)
                    }
                }
                .pickerStyle(.segmented)
                .disabled(!model.isQuickSentenceTranslationEnabled)

                Toggle(
                    "轻点翻译时禁用点按翻页",
                    isOn: disablesTapPageTurnsDuringQuickTranslationBinding
                )
                .disabled(!model.isQuickSentenceTranslationEnabled)
            }

            Text(interactionDetail)
                .font(.caption)
                .foregroundStyle(.secondary)
        } header: {
            Text("阅读交互")
        }
    }

    private var interactionDetail: String {
        guard model.supportsQuickSentenceTranslation else {
            return "长按正文可选词或选句翻译，拖动系统选区手柄可精确调整范围。"
        }
        guard model.isQuickSentenceTranslationEnabled else {
            return "已关闭：轻点正文只显示或隐藏阅读界面；长按选词、拖动选区仍可翻译。"
        }
        let target = model.quickTranslationUnit == .sentence
            ? "当前句子"
            : "当前段落"
        return model.disablesTapPageTurnsDuringQuickTranslation
            ? "轻点正文任意位置都会翻译\(target)；翻页请用横向轻扫或底部按钮。"
            : "轻点正文中间翻译\(target)；轻点左右边缘仍然翻页。"
    }

    private var typographySection: some View {
        Section("字体与字号") {
            Picker("字体", selection: fontChoiceBinding) {
                ForEach(ReaderFontChoice.allCases) { font in
                    Text(font.title).tag(font)
                }
            }

            LabeledContent("字号") {
                Text(fontPointSizeLabel)
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
            }
            HStack(spacing: 14) {
                Image(systemName: "textformat.size.smaller")
                    .foregroundStyle(.secondary)
                Slider(
                    value: fontPointSizeBinding,
                    in: ReaderFontSizing.pointSizeRange,
                    step: ReaderFontSizing.pointSizeStep
                )
                Image(systemName: "textformat.size.larger")
                    .foregroundStyle(.secondary)
            }
        }
    }

    private var spacingSection: some View {
        Section("版面") {
            LabeledContent("行间距") {
                Text(model.lineHeight.formatted(.number.precision(.fractionLength(1))))
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
            }
            Slider(value: lineHeightBinding, in: 1.0 ... 2.2, step: 0.1)

            LabeledContent("段间距") {
                Text(paragraphSpacingLabel)
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
            }
            Slider(value: paragraphSpacingBinding, in: 0.0 ... 2.0, step: 0.1)

            LabeledContent("页边距") {
                Text(pageMarginLabel)
                    .foregroundStyle(.secondary)
            }
            Slider(value: pageMarginsBinding, in: 0.5 ... 2.0, step: 0.1)
        }
    }

    private var backgroundSection: some View {
        Section("背景") {
            HStack(spacing: 12) {
                ForEach(ReaderThemeChoice.allCases) { theme in
                    Button {
                        withAnimation(reduceMotion ? nil : JerreaderMotion.stateChange) {
                            model.updateCustomBackgroundHex("")
                            model.updateTheme(theme)
                        }
                    } label: {
                        VStack(spacing: 7) {
                            Circle()
                                .fill(theme.swatch)
                                .frame(width: 38, height: 38)
                                .overlay {
                                    Circle()
                                        .stroke(
                                            model.customBackgroundHex.isEmpty
                                                && model.theme == theme
                                                ? JerreaderTheme.accent
                                                : Color.secondary.opacity(0.22),
                                            lineWidth: model.customBackgroundHex.isEmpty
                                                && model.theme == theme
                                                ? 3
                                                : 1
                                        )
                                }
                            Text(theme.title)
                                .font(.caption2)
                                .foregroundStyle(.primary)
                        }
                        .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(JerreaderPressButtonStyle(pressedScale: 0.94))
                    .accessibilityLabel("\(theme.title)背景")
                    .accessibilityAddTraits(
                        model.customBackgroundHex.isEmpty && model.theme == theme
                            ? .isSelected
                            : []
                    )
                }
            }
            .padding(.vertical, 4)

            Toggle("自定义背景颜色", isOn: customBackgroundEnabledBinding)

            if !model.customBackgroundHex.isEmpty {
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
            if !model.customSelectionColorHex.isEmpty {
                ColorPicker(
                    "选择选区颜色",
                    selection: customSelectionColorBinding,
                    supportsOpacity: false
                )
            }
        }
    }

    private var translationSection: some View {
        Section {
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
                Label {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("翻译与 AI")
                        Text("服务商、三语互译与提示词")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                } icon: {
                    Image(systemName: "character.bubble")
                        .foregroundStyle(JerreaderTheme.accent)
                }
            }
        } header: {
            Text("翻译")
        } footer: {
            Text("翻译服务与密钥对所有书籍通用。")
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
