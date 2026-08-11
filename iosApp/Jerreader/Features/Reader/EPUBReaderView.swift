import SwiftData
import SwiftUI
import UIKit
@preconcurrency import Translation

struct EPUBReaderScreen: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase

    @StateObject private var model: EPUBReaderViewModel
    @State private var isShowingNavigation = false
    @State private var isShowingSettings = false
    @State private var scrubbedProgress: Double?
    @AppStorage(ReaderAppearanceDefaults.showsProgressKey)
    private var showsReadingProgress = true

    init(
        book: BookRecord,
        modelContext: ModelContext,
        translationSettings: TranslationSettingsStore
    ) {
        _model = StateObject(
            wrappedValue: EPUBReaderViewModel(
                book: book,
                modelContext: modelContext,
                translationSettings: translationSettings
            )
        )
    }

    var body: some View {
        ZStack {
            readerContent
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .clipped()
                .ignoresSafeArea()

            readerChrome
                .opacity(model.controlsVisible ? 1 : 0)
                .scaleEffect(model.controlsVisible ? 1 : 0.985)
                .allowsHitTesting(model.controlsVisible)
                .accessibilityHidden(!model.controlsVisible)
                .animation(
                    reduceMotion ? nil : JerreaderMotion.stateChange,
                    value: model.controlsVisible
                )
                .zIndex(1)

            if model.isQuickSentenceTranslationEnabled,
               model.isQuickTranslationIndicatorVisible,
               !model.controlsVisible,
               model.presentedTranslationRequest == nil,
               model.readerActivityMessage == nil
            {
                quickTranslationIndicator
                    .onAppear {
                        model.quickTranslationIndicatorDidAppear()
                    }
                    .onDisappear {
                        model.quickTranslationIndicatorDidDisappear()
                    }
                    .zIndex(1)
            }

            if model.presentedTranslationRequest != nil {
                ReaderTranslationLayer(model: model)
                .transition(.opacity.combined(with: .scale(scale: 0.96)))
                .zIndex(2)
            }

            if let activity = model.readerActivityMessage {
                readerActivityIndicator(activity)
                    .transition(.opacity.combined(with: .scale(scale: 0.96)))
                    .zIndex(3)
            }
        }
        .background(readerBackground.ignoresSafeArea())
        .animation(
            reduceMotion ? nil : JerreaderMotion.stateChange,
            value: model.presentedTranslationRequest?.id
        )
        .animation(
            reduceMotion ? nil : JerreaderMotion.stateChange,
            value: model.readerActivityMessage
        )
        .preferredColorScheme(prefersDarkReaderChrome ? .dark : .light)
        .sensoryFeedback(
            .impact(weight: .light),
            trigger: model.isCurrentLocationBookmarked
        )
        .sensoryFeedback(
            .selection,
            trigger: model.isQuickSentenceTranslationEnabled
        )
        .task {
            model.setReadingActive(scenePhase == .active)
            await model.load()
            model.setReadingActive(scenePhase == .active)
#if DEBUG
            await model.prepareSelectionUITestIfNeeded()
#endif
        }
        .translationTask(model.translationConfiguration) { session in
            let service: any TranslationService = AppleTranslationService(session: session)
            await model.performPendingTranslation(using: service)
        }
        .sheet(isPresented: $isShowingNavigation) {
            ReaderNavigationSheet(model: model) {
                isShowingNavigation = false
            }
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $isShowingSettings) {
            ReaderSettingsView(model: model)
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
        }
        .sheet(item: $model.presentedAnnotationEditor) { draft in
            ReaderAnnotationEditor(
                draft: draft,
                onSave: { note, color in
                    model.saveAnnotation(draft, noteText: note, color: color)
                },
                onDelete: draft.annotationID == nil
                    ? nil
                    : { model.deletePresentedAnnotation() }
            )
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $model.isShowingContextExplanation) {
            ReaderContextExplanationSheet(model: model)
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
        .alert(
            "阅读器提示",
            isPresented: Binding(
                get: { model.readerAlertMessage != nil },
                set: { if !$0 { model.readerAlertMessage = nil } }
            )
        ) {
            Button("好") { model.readerAlertMessage = nil }
        } message: {
            Text(model.readerAlertMessage ?? "")
        }
        .onChange(of: scenePhase) {
            model.setReadingActive(scenePhase == .active)
        }
        .onDisappear {
            model.close()
        }
    }

    @ViewBuilder
    private var readerContent: some View {
        switch model.loadState {
        case .loading:
            VStack(spacing: 16) {
                JerreaderLoadingGlyph(systemImage: "book.pages.fill", size: 68)
                Text("正在打开《\(model.book.title)》")
                    .font(.headline)
                Text("正在由 Readium 解析 \(model.book.format.displayName) 内容")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .padding(26)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 24))
            .overlay {
                RoundedRectangle(cornerRadius: 24)
                    .stroke(JerreaderTheme.line, lineWidth: 0.75)
            }
            .shadow(color: JerreaderTheme.shadow, radius: 16, y: 7)
            .padding(28)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .ready:
            if let controller = model.controller {
                PublicationReaderContainer(controller: controller)
            } else {
                readerFailure(ReaderError.navigatorUnavailable.localizedDescription)
            }
        case let .failed(message):
            readerFailure(message)
        }
    }

    private var readerChrome: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                chromeButton(systemImage: "xmark") {
                    model.flushProgress()
                    dismiss()
                }
                .accessibilityLabel("关闭阅读器")

                VStack(alignment: .leading, spacing: 1) {
                    Text(model.chapterTitle)
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                    Text(model.book.title)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }

                Spacer(minLength: 4)

                chromeButton(systemImage: "list.bullet.rectangle") {
                    isShowingNavigation = true
                }
                .accessibilityLabel("目录与阅读导航")

                // Bookmarking is a one-tap, frequently repeated action and its
                // state is worth showing continuously; burying it in a menu
                // hid both the action and whether the page was already saved.
                chromeButton(
                    systemImage: model.isCurrentLocationBookmarked
                        ? "bookmark.fill"
                        : "bookmark",
                    isActive: model.isCurrentLocationBookmarked
                ) {
                    model.toggleBookmark()
                }
                .accessibilityLabel(
                    model.isCurrentLocationBookmarked
                        ? "移除当前书签"
                        : "添加当前书签"
                )
                .accessibilityAddTraits(
                    model.isCurrentLocationBookmarked ? .isSelected : []
                )

                chromeButton(systemImage: "textformat") {
                    isShowingSettings = true
                }
                .accessibilityLabel("阅读设置")
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 4)
            .frame(maxWidth: 780)
            .frame(maxWidth: .infinity)
            .background(.thinMaterial)
            .overlay(alignment: .bottom) {
                Rectangle()
                    .fill(JerreaderTheme.line)
                    .frame(height: 0.5)
            }

            Spacer()

            VStack(spacing: 8) {
                if showsReadingProgress {
                    // While dragging, the chapter the thumb is over matters far
                    // more than the raw percentage, so it gets the prominent
                    // line and the percentage becomes secondary.
                    HStack(spacing: 6) {
                        Text(scrubbedProgress == nil ? currentChapterLabel : scrubTargetLabel)
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(
                                scrubbedProgress == nil ? .secondary : JerreaderTheme.accent
                            )
                            .lineLimit(1)
                            .truncationMode(.middle)
                            .animation(nil, value: scrubbedProgress)

                        Spacer(minLength: 6)

                        Text(progressLabel)
                            .font(.caption.monospacedDigit().weight(.semibold))
                            .foregroundStyle(.secondary)
                            .contentTransition(.numericText())
                    }
                    .padding(.horizontal, 2)

                    Slider(
                        value: readerProgressBinding,
                        in: 0 ... 1,
                        onEditingChanged: handleProgressEditing
                    )
                    .tint(JerreaderTheme.accent)
                    .accessibilityLabel("快速调整阅读进度")
                    .accessibilityValue(
                        scrubbedProgress == nil
                            ? "\(currentChapterLabel)，\(progressLabel)"
                            : "\(scrubTargetLabel)，\(progressLabel)"
                    )
                }

                HStack(spacing: 12) {
                    if model.canGoToAdjacentChapter {
                        pageButton(systemImage: "chevron.left.2") {
                            model.goToPreviousChapter()
                        }
                        .accessibilityLabel("上一章")
                    }

                    pageButton(systemImage: "chevron.left") {
                        model.goBackward()
                    }
                    .accessibilityLabel("上一页")

                    if model.supportsQuickSentenceTranslation {
                        pageButton(
                            systemImage: model.isQuickSentenceTranslationEnabled
                                ? "character.bubble.fill"
                                : "character.bubble",
                            isActive: model.isQuickSentenceTranslationEnabled
                        ) {
                            model.setQuickSentenceTranslationEnabled(
                                !model.isQuickSentenceTranslationEnabled
                            )
                        }
                        .accessibilityLabel(
                            model.isQuickSentenceTranslationEnabled
                                ? "关闭轻点翻译"
                                : "开启轻点翻译"
                        )
                        .accessibilityHint(
                            "开启后轻点正文即可翻译当前\(model.quickTranslationUnit.title)"
                        )
                    }

                    pageButton(systemImage: "chevron.right") {
                        model.goForward()
                    }
                    .accessibilityLabel("下一页")

                    if model.canGoToAdjacentChapter {
                        pageButton(systemImage: "chevron.right.2") {
                            model.goToNextChapter()
                        }
                        .accessibilityLabel("下一章")
                    }
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .frame(maxWidth: 660)
            .frame(maxWidth: .infinity)
            .background(.thinMaterial)
            .overlay(alignment: .top) {
                Rectangle()
                    .fill(JerreaderTheme.line)
                    .frame(height: 0.5)
            }
        }
    }

    private func chromeButton(
        systemImage: String,
        isActive: Bool = false,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.body.weight(.semibold))
                .foregroundStyle(isActive ? JerreaderTheme.onPrimaryAction : Color.primary)
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
                .background(
                    isActive ? JerreaderTheme.accent : Color.clear,
                    in: Circle()
                )
        }
        .buttonStyle(JerreaderPressButtonStyle(pressedScale: 0.90))
    }

    private func pageButton(
        systemImage: String,
        isActive: Bool = false,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.title3.weight(.bold))
                .foregroundStyle(isActive ? JerreaderTheme.onPrimaryAction : Color.primary)
                .frame(maxWidth: .infinity)
                .frame(minHeight: 48)
                .contentShape(Rectangle())
                .background(
                    isActive ? JerreaderTheme.accent : JerreaderTheme.accentFill.opacity(0.72),
                    in: RoundedRectangle(cornerRadius: 12, style: .continuous)
                )
        }
        .buttonStyle(JerreaderPressButtonStyle(pressedScale: 0.965))
    }

    private func readerFailure(_ message: String) -> some View {
        VStack(spacing: 18) {
            Image(systemName: "exclamationmark.book.closed.fill")
                .font(.system(size: 42))
                .foregroundStyle(.secondary)
            Text("无法打开这本书")
                .font(.title2.weight(.bold))
            Text(message)
                .font(.body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            HStack(spacing: 12) {
                Button("返回书架") { dismiss() }
                    .buttonStyle(.bordered)

                Button {
                    Task { await model.load() }
                } label: {
                    Label("重新打开", systemImage: "arrow.clockwise")
                }
                .buttonStyle(.borderedProminent)
                .tint(JerreaderTheme.accent)
            }
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var progressLabel: String {
        "\(Int(((scrubbedProgress ?? model.progress) * 100).rounded()))%"
    }

    /// Chapter shown when the reader is not scrubbing. Falls back to the
    /// running chapter title Readium reports for books without an outline.
    private var currentChapterLabel: String {
        model.currentOutlineItem?.title
            ?? model.chapterTitle.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Chapter under the thumb during a drag. Resolved from the outline without
    /// navigating, so dragging stays cheap and the book does not jump around
    /// until the finger lifts.
    private var scrubTargetLabel: String {
        guard let target = scrubbedProgress else { return currentChapterLabel }
        return model.outlineItem(atProgression: target)?.title ?? currentChapterLabel
    }

    private var readerProgressBinding: Binding<Double> {
        Binding(
            get: { scrubbedProgress ?? model.progress },
            set: { scrubbedProgress = min(max($0, 0), 1) }
        )
    }

    private func handleProgressEditing(_ isEditing: Bool) {
        if isEditing {
            if scrubbedProgress == nil {
                scrubbedProgress = model.progress
            }
            return
        }
        guard let target = scrubbedProgress else { return }
        model.seek(to: target)
        scrubbedProgress = nil
    }

    private var readerBackground: Color {
        if let color = ReaderCustomBackground.uiColor(
            hex: model.customBackgroundHex
        ) {
            return Color(uiColor: color)
        }
        switch model.theme {
        case .light: return .white
        case .sepia: return Color(red: 0.98, green: 0.96, blue: 0.91)
        case .coolGray: return Color(red: 0.93, green: 0.96, blue: 0.98)
        case .dark: return .black
        }
    }

    private var prefersDarkReaderChrome: Bool {
        if !model.customBackgroundHex.isEmpty {
            return ReaderCustomBackground.prefersLightText(
                hex: model.customBackgroundHex
            )
        }
        return model.theme == .dark
    }

    private var quickTranslationIndicator: some View {
        GeometryReader { geometry in
            Button {
                model.showReaderControls()
            } label: {
                Image(systemName: "character.bubble.fill")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(JerreaderTheme.accent)
                    .frame(width: 44, height: 44)
                    .background(.regularMaterial, in: Capsule())
                    .overlay {
                        Capsule()
                            .stroke(JerreaderTheme.accent.opacity(0.28), lineWidth: 1)
                    }
            }
            .buttonStyle(.plain)
            .accessibilityLabel(
                "\(model.quickTranslationUnit.shortTitle)翻译已开启"
            )
            .accessibilityHint("轻点显示阅读控制；提示图标会自动淡出，但轻点翻译保持开启")
            // Keep the persistent mode indicator in the page's outer gutter,
            // rather than over the first line of every page. The compact icon
            // uses much less reading area than the previous top title pill.
            .position(
                x: max(18, geometry.size.width - 18),
                y: min(
                    geometry.size.height - geometry.safeAreaInsets.bottom - 72,
                    max(geometry.safeAreaInsets.top + 72, geometry.size.height * 0.30)
                )
            )
        }
        .allowsHitTesting(true)
    }

    private func readerActivityIndicator(_ message: String) -> some View {
        VStack {
            HStack(spacing: 10) {
                ProgressView()
                    .controlSize(.small)
                    .tint(JerreaderTheme.accent)
                Text(message)
                    .font(.caption.weight(.semibold))
            }
            .padding(.horizontal, 15)
            .frame(minHeight: 42)
            .background(.regularMaterial, in: Capsule())
            .overlay {
                Capsule()
                    .stroke(JerreaderTheme.accent.opacity(0.24), lineWidth: 0.8)
            }
            .shadow(color: Color.black.opacity(0.1), radius: 8, y: 3)

            Spacer()
        }
        .safeAreaPadding(.top, 8)
        .allowsHitTesting(false)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(message)
    }

}

private struct ReaderTranslationLayer: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ObservedObject var model: EPUBReaderViewModel
    @ObservedObject private var settings: TranslationSettingsStore
    @State private var cardHeight: CGFloat = 108
    @State private var manualPosition: CGPoint?
    @State private var isSelectionMoving = false
    @State private var latestSelectionMovementAt = Date.distantPast
    @State private var selectionSettleTask: Task<Void, Never>?
    @GestureState private var dragState = TranslationOverlayDragState.inactive

    init(model: EPUBReaderViewModel) {
        self.model = model
        _settings = ObservedObject(wrappedValue: model.translationSettings)
    }

    var body: some View {
        GeometryReader { geometry in
            let proposedCardWidth = preferredCardWidth(in: geometry)
            let overlayLayout = layout(
                in: geometry,
                cardWidth: proposedCardWidth
            )
            let cardWidth = overlayLayout.cardWidth
            // Position against the card's measured height. The expanded
            // policy only raises the *maximum* available to long translations;
            // it must never force a short result to fill that viewport.
            let positioningCardHeight = min(
                max(cardHeight, 108),
                overlayLayout.maximumCardHeight
            )
            let automaticPosition = overlayLayout.position
            let basePosition = manualPosition ?? automaticPosition
            let targetPosition = clamp(
                CGPoint(
                    x: basePosition.x + dragState.translation.width,
                    y: basePosition.y + dragState.translation.height
                ),
                in: geometry,
                cardWidth: cardWidth,
                cardHeight: positioningCardHeight
            )
#if DEBUG
            let readerFrame = geometry.frame(in: .global)
            let resolvedCardFrameInWindow = CGRect(
                x: readerFrame.minX + targetPosition.x - cardWidth / 2,
                y: readerFrame.minY + targetPosition.y - positioningCardHeight / 2,
                width: cardWidth,
                height: positioningCardHeight
            )
#endif

            ReaderTranslationOverlay(
                model: model,
                maximumCardHeight: overlayLayout.maximumCardHeight,
                cardWidth: cardWidth,
                isDragging: dragState.isActive || isSelectionMoving,
                handleGesture: dragGesture(
                    from: basePosition,
                    in: geometry,
                    cardWidth: cardWidth,
                    cardHeight: positioningCardHeight
                )
            )
            .frame(width: cardWidth)
            // `position` otherwise proposes the whole viewport height to the
            // card. Hugging the ideal height keeps short status/error text
            // compact instead of turning every result into a large panel.
            .fixedSize(horizontal: false, vertical: true)
            .background {
                GeometryReader { cardGeometry in
                    Color.clear.preference(
                        key: TranslationCardHeightKey.self,
                        value: cardGeometry.size.height
                    )
                }
            }
            .position(targetPosition)
            // While the native selection handles are moving, make the card
            // almost transparent and non-interactive so it cannot trap a
            // handle underneath itself. It returns after the frame settles.
            .opacity(isSelectionMoving ? 0.16 : 1)
            .scaleEffect(isSelectionMoving ? 0.97 : 1)
            .allowsHitTesting(!isSelectionMoving)
            .animation(
                reduceMotion || dragState.isActive || isSelectionMoving || manualPosition != nil
                    ? nil
                    : .spring(response: 0.22, dampingFraction: 0.9),
                value: automaticPosition
            )
            .animation(
                reduceMotion ? nil : .easeOut(duration: 0.12),
                value: isSelectionMoving
            )
            .transaction { transaction in
                if dragState.isActive || isSelectionMoving {
                    transaction.disablesAnimations = true
                    transaction.animation = nil
                }
            }
#if DEBUG
            Color.clear
                .preference(
                    key: TranslationCardFrameKey.self,
                    value: resolvedCardFrameInWindow
                )
                .allowsHitTesting(false)
#endif
        }
        .onPreferenceChange(TranslationCardHeightKey.self) { height in
            // SwiftUI delivers preference callbacks from a Sendable closure
            // under Swift 6. Hop explicitly to the main actor before touching
            // view state; the tolerance prevents measurement feedback loops.
            Task { @MainActor in
                guard height > 0,
                      height.isFinite,
                      abs(height - cardHeight) > 0.5
                else { return }
                cardHeight = height
            }
        }
#if DEBUG
        .onPreferenceChange(TranslationCardFrameKey.self) { frame in
            guard !frame.isNull, !frame.isEmpty else { return }
            Task { @MainActor in
                model.installTranslationUITestCard(frameInWindow: frame)
            }
        }
#endif
        .onChange(of: model.translationAnchorFrame) {
            manualPosition = nil
            beginSelectionAdjustment()
        }
        .onChange(of: model.presentedTranslationRequest?.id) {
            manualPosition = nil
        }
        .onChange(of: settings.displayMode) {
            manualPosition = nil
            isSelectionMoving = false
            selectionSettleTask?.cancel()
            selectionSettleTask = nil
        }
        .onDisappear {
            selectionSettleTask?.cancel()
            selectionSettleTask = nil
        }
    }

    private func layout(
        in geometry: GeometryProxy,
        cardWidth: CGFloat
    ) -> ReaderTranslationOverlayPlacement.Layout {
        let usesVerticalSideAvoidance = usesVerticalSideAvoidance(in: geometry)
        let allowsHorizontalAvoidance = usesVerticalSideAvoidance
            || prefersExpandedMaximumHeight
        let preferredMaximumHeight = preferredMaximumCardHeight(in: geometry)
        let proposedCardHeight = min(
            max(cardHeight, 108),
            preferredMaximumHeight
        )
        return ReaderTranslationOverlayPlacement.layout(
            selectionFrame: localSelectionFrame(in: geometry),
            horizontalAvoidanceFrame: usesVerticalSideAvoidance
                ? localFocusFrame(in: geometry)
                : nil,
            viewportSize: geometry.size,
            cardSize: CGSize(width: cardWidth, height: proposedCardHeight),
            topInset: topInset(in: geometry),
            bottomInset: bottomInset(in: geometry),
            horizontalInset: horizontalInset(in: geometry),
            gap: ReaderTranslationLayoutPolicy.selectionGap(
                isParagraph: isQuickParagraphTranslation,
                usesVerticalSideAvoidance: usesVerticalSideAvoidance
            ),
            minimumCardHeight: 108,
            preferredMaximumCardHeight: preferredMaximumHeight,
            prefersTop: settings.displayMode == .topBanner
                && !usesVerticalSideAvoidance,
            prefersHorizontalAvoidance: allowsHorizontalAvoidance,
            minimumHorizontalCardWidth: usesVerticalSideAvoidance ? 144 : 220
        )
    }

    private func localSelectionFrame(in geometry: GeometryProxy) -> CGRect? {
        guard let windowFrame = model.translationAnchorFrame
                ?? model.presentedTranslationRequest?.selectionFrame
        else { return nil }

        return ReaderTranslationOverlayPlacement.localSelectionFrame(
            windowFrame,
            relativeTo: geometry.frame(in: .global)
        )
    }

    private func localFocusFrame(in geometry: GeometryProxy) -> CGRect? {
        ReaderTranslationOverlayPlacement.localSelectionFrame(
            model.presentedTranslationRequest?.focusFrame,
            relativeTo: geometry.frame(in: .global)
        )
    }

    private func preferredCardWidth(in geometry: GeometryProxy) -> CGFloat {
        let availableWidth = max(geometry.size.width - 32, 1)
        let maximumWidth = min(
            availableWidth,
            geometry.size.width >= 700 ? 440 : 376
        )
        let minimumWidth = min(maximumWidth, 260)
        let preferredWidth: CGFloat

        switch model.translationState {
        case .idle:
            preferredWidth = minimumWidth
        case .loading:
            preferredWidth = 304
        case .failure:
            preferredWidth = 340
        case let .success(_, result, _):
            switch result.translatedText.count {
            case ...24:
                preferredWidth = 300
            case ...90:
                preferredWidth = 334
            default:
                preferredWidth = geometry.size.width >= 700 ? 420 : 376
            }
        }

        let resolvedWidth = min(
            max(preferredWidth, minimumWidth),
            maximumWidth
        )
        guard usesVerticalSideAvoidance(in: geometry) else {
            return resolvedWidth
        }

        // Keep this as a normal floating window. In vertical books it starts
        // narrower so it can sit immediately to the left or right of the
        // original column; the placement solver can shrink it further on an
        // iPhone without docking it to an edge.
        return min(
            resolvedWidth,
            geometry.size.width >= 700 ? 320 : 220
        )
    }

    private func usesVerticalSideAvoidance(
        in geometry: GeometryProxy
    ) -> Bool {
        ReaderTranslationLayoutPolicy.usesVerticalSideAvoidance(
            isReflowable: model.isReflowableBook,
            preservesPublicationOrientation: model.textOrientation == .publication,
            publicationIsVertical: model.publicationLayout.verticalText,
            isJapaneseBook: model.isJapaneseBook,
            selectionFrame: localSelectionFrame(in: geometry)
        )
    }

    private var isQuickParagraphTranslation: Bool {
        model.presentedTranslationRequest?.trigger == .quickSentence
            && model.quickTranslationUnit == .paragraph
    }

    private var prefersExpandedMaximumHeight: Bool {
        guard case let .success(request, result, _) = model.translationState else {
            return false
        }
        return ReaderTranslationViewportPolicy.prefersExpandedMaximumHeight(
            sourceCharacterCount: request.sourceText.count,
            translatedCharacterCount: result.translatedText.count,
            isParagraph: isQuickParagraphTranslation
        )
    }

    private func preferredMaximumCardHeight(
        in geometry: GeometryProxy
    ) -> CGFloat {
        if prefersExpandedMaximumHeight {
            return min(
                max(geometry.size.height * 0.38, 260),
                geometry.size.width >= 700 ? 360 : 300
            )
        }
        return min(
            max(geometry.size.height * 0.30, 166),
            geometry.size.width >= 700 ? 286 : 246
        )
    }

    private func horizontalInset(in geometry: GeometryProxy) -> CGFloat {
        usesVerticalSideAvoidance(in: geometry) ? 12 : 16
    }

    private func dragGesture(
        from basePosition: CGPoint,
        in geometry: GeometryProxy,
        cardWidth: CGFloat,
        cardHeight: CGFloat
    ) -> some Gesture {
        DragGesture(minimumDistance: 2, coordinateSpace: .global)
            .updating($dragState) { value, state, transaction in
                transaction.disablesAnimations = true
                transaction.animation = nil
                state = TranslationOverlayDragState(
                    translation: value.translation,
                    isActive: true
                )
            }
            .onEnded { value in
                let proposed = CGPoint(
                    x: basePosition.x + value.translation.width,
                    y: basePosition.y + value.translation.height
                )
                var transaction = Transaction(animation: nil)
                transaction.disablesAnimations = true
                withTransaction(transaction) {
                    manualPosition = clamp(
                        proposed,
                        in: geometry,
                        cardWidth: cardWidth,
                        cardHeight: cardHeight
                    )
                }
            }
    }

    private func clamp(
        _ position: CGPoint,
        in geometry: GeometryProxy,
        cardWidth: CGFloat,
        cardHeight: CGFloat
    ) -> CGPoint {
        ReaderTranslationOverlayPlacement.clampedPosition(
            position,
            viewportSize: geometry.size,
            cardSize: CGSize(width: cardWidth, height: cardHeight),
            topInset: topInset(in: geometry),
            bottomInset: bottomInset(in: geometry),
            horizontalInset: horizontalInset(in: geometry)
        )
    }

    private func topInset(in geometry: GeometryProxy) -> CGFloat {
        max(geometry.safeAreaInsets.top, model.controlsVisible ? 70 : 12)
    }

    private func bottomInset(in geometry: GeometryProxy) -> CGFloat {
        max(geometry.safeAreaInsets.bottom, model.controlsVisible ? 94 : 12)
    }

    private func beginSelectionAdjustment() {
        guard settings.displayMode == .nearSelection,
              model.presentedTranslationRequest != nil
        else {
            isSelectionMoving = false
            selectionSettleTask?.cancel()
            selectionSettleTask = nil
            return
        }

        latestSelectionMovementAt = Date()
        isSelectionMoving = true
        guard selectionSettleTask == nil else { return }

        selectionSettleTask = Task { @MainActor in
            while !Task.isCancelled {
                let remaining = 0.15 - Date().timeIntervalSince(latestSelectionMovementAt)
                if remaining <= 0 {
                    break
                }
                do {
                    try await Task.sleep(
                        for: .milliseconds(max(Int((remaining * 1_000).rounded(.up)), 1))
                    )
                } catch {
                    return
                }
            }
            guard !Task.isCancelled else { return }
            isSelectionMoving = false
            selectionSettleTask = nil
        }
    }
}

private struct TranslationOverlayDragState: Equatable {
    let translation: CGSize
    let isActive: Bool

    static let inactive = TranslationOverlayDragState(
        translation: .zero,
        isActive: false
    )
}

private struct TranslationCardHeightKey: PreferenceKey {
    static let defaultValue: CGFloat = 126

    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = nextValue()
    }
}

#if DEBUG
private struct TranslationCardFrameKey: PreferenceKey {
    static let defaultValue = CGRect.null

    static func reduce(value: inout CGRect, nextValue: () -> CGRect) {
        let next = nextValue()
        if !next.isNull, !next.isEmpty {
            value = next
        }
    }
}
#endif

private struct TranslationTextHeightKey: PreferenceKey {
    static let defaultValue: CGFloat = 54

    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = max(value, nextValue())
    }
}

/// Measured extents of the translation card's header row.
///
/// SwiftUI overlaps children rather than reporting that a row does not fit, so
/// the header is only safe if its slots are known in advance to add up. These
/// are the real declared frames of the controls in `ReaderTranslationOverlay`,
/// kept here so the arithmetic can be checked without building a view.
enum ReaderTranslationHeaderMetrics {
    static let buttonWidth: CGFloat = 44
    static let buttonSpacing: CGFloat = 6
    static let titleIconWidth: CGFloat = 25
    /// Enough for "翻译中" plus its ellipsis — below this the title is noise.
    static let titleTextMinimum: CGFloat = 46
    static let spacing: CGFloat = 7
    static let horizontalPadding: CGFloat = 10

    /// The grabber owns a full-width row of its own, the way a system sheet's
    /// does. Sharing a row with the title and the buttons meant its centre was
    /// whatever those two happened to leave behind, so it only ever looked
    /// centred at the handful of card widths the arithmetic worked out at.
    static let handleRowHeight: CGFloat = 22
    static let handleWidth: CGFloat = 36
    static let actionRowHeight: CGFloat = 44
    static let height: CGFloat = handleRowHeight + actionRowHeight

    static func trailingWidth(buttonCount: Int) -> CGFloat {
        let count = CGFloat(max(buttonCount, 0))
        return count * buttonWidth + max(count - 1, 0) * buttonSpacing
    }

    /// Width left for the title once padding and the trailing buttons have
    /// taken theirs.
    static func flexibleWidth(cardWidth: CGFloat, buttonCount: Int) -> CGFloat {
        cardWidth - horizontalPadding * 2 - trailingWidth(buttonCount: buttonCount) - spacing
    }

    static let titleWidth: CGFloat = titleIconWidth + spacing + titleTextMinimum

    /// Whether the buttons alone clear the card. Below this the row would draw
    /// over itself, so an optional button has to go.
    static func fitsTrailing(cardWidth: CGFloat, buttonCount: Int) -> Bool {
        horizontalPadding * 2 + trailingWidth(buttonCount: buttonCount) <= cardWidth
    }

    /// Whether the title fits alongside those buttons.
    static func fitsTitle(cardWidth: CGFloat, buttonCount: Int) -> Bool {
        flexibleWidth(cardWidth: cardWidth, buttonCount: buttonCount) >= titleWidth
    }
}

private struct ReaderTranslationOverlay<HandleGesture: Gesture>: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ObservedObject var model: EPUBReaderViewModel
    let maximumCardHeight: CGFloat
    let cardWidth: CGFloat
    let isDragging: Bool
    let handleGesture: HandleGesture
    @State private var translatedContentHeight: CGFloat = 54

    var body: some View {
        VStack(spacing: 0) {
            overlayHeader

            Rectangle()
                .fill(JerreaderTheme.line)
                .frame(height: 0.5)

            translationBody

            // A small breathing room keeps the last line and actions from
            // visually sitting on the rounded bottom edge without turning the
            // compact overlay into a large sheet.
            Color.clear
                .frame(height: 12)
        }
        .frame(minHeight: 108, alignment: .top)
        .background(.regularMaterial)
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(JerreaderTheme.line, lineWidth: 0.8)
        }
        .shadow(
            color: JerreaderTheme.deepShadow.opacity(isDragging ? 0 : 0.58),
            radius: isDragging ? 0 : 12,
            y: isDragging ? 0 : 5
        )
        .animation(
            reduceMotion || isDragging ? nil : JerreaderMotion.stateChange,
            value: translationPhaseIdentity
        )
        .sensoryFeedback(
            .success,
            trigger: translationPhaseIdentity
        ) { oldValue, newValue in
            model.translationSettings.translationHapticsEnabled
                && oldValue != newValue
                && newValue.hasPrefix("success")
        }
        .onChange(of: translationPhaseIdentity) {
            translatedContentHeight = 54
        }
        .accessibilityElement(children: .contain)
    }

    private var overlayHeader: some View {
        // The grabber gets a row to itself, above the actions. Sharing a row
        // with the title and the trailing buttons meant it was centred in
        // whatever slot they left over, not in the card — so it drifted left or
        // right with every change of card width or button count, and on the
        // narrow cards it overlapped the "..." outright. Its own row is centred
        // by construction at every width, and nothing can be laid over it.
        VStack(spacing: 0) {
            dragHandle

            HStack(spacing: headerSpacing) {
                if headerShowsTitle {
                    HStack(spacing: headerSpacing) {
                        Image(systemName: headerSymbol)
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(JerreaderTheme.accent)
                            .frame(
                                width: Metrics.titleIconWidth,
                                height: Metrics.titleIconWidth
                            )
                            .background(
                                JerreaderTheme.accent.opacity(0.10),
                                in: Circle()
                            )

                        Text(headerTitle)
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                            .truncationMode(.tail)
                    }
                    .frame(maxWidth: headerFlexibleWidth, alignment: .leading)
                }

                Spacer(minLength: 0)

                trailingHeaderButtons
                    .fixedSize(horizontal: true, vertical: false)
            }
            .buttonStyle(JerreaderPressButtonStyle(pressedScale: 0.90))
            .frame(height: Metrics.actionRowHeight)
            .padding(.horizontal, headerHorizontalPadding)
        }
        .frame(height: Metrics.height)
        .background(Color.primary.opacity(0.035))
        .contentShape(Rectangle())
        // The grabber row alone is a 22pt strip to hit. The whole header takes
        // the drag instead; the buttons keep their taps, because a drag is only
        // recognised once the finger has moved.
        .gesture(handleGesture)
    }

    /// Never varies with the translation's phase. The star used to appear here
    /// the moment a translation succeeded, so the header gained an icon after
    /// the card had already been read and everything shifted left; favouriting
    /// lives in the actions menu now, which carries the same item either way.
    private var trailingHeaderButtons: some View {
        HStack(spacing: Metrics.buttonSpacing) {
            if headerShowsSpeechButton {
                speechButton
            }

            if model.translationState.request != nil {
                translationActionsMenu
            }

            closeButton
        }
    }

    // MARK: - Header slot arithmetic
    //
    // SwiftUI will happily overlap children rather than report that a row does
    // not fit, so the row is only safe if the slots are known to add up. Every
    // number below is a real measured extent, not a guess: the buttons and the
    // handle's hit area are all declared with explicit frames a few lines up.

    private typealias Metrics = ReaderTranslationHeaderMetrics

    private var headerSpacing: CGFloat { Metrics.spacing }
    private var headerHorizontalPadding: CGFloat { Metrics.horizontalPadding }

    /// The button count is deliberately independent of the translation's
    /// phase — see `trailingHeaderButtons`. The close button is always there;
    /// the menu appears with the request that it acts on, before the card has
    /// anything to show.
    private var headerRequiredButtonCount: Int {
        var count = 1 // close
        if model.translationState.request != nil { count += 1 }
        return count
    }

    /// The only optional button, and it folds on width alone rather than on
    /// what the card is currently showing.
    private var headerShowsSpeechButton: Bool {
        guard SpeechFeatureAvailability.isEnabled,
              model.translationState.request?.sourceLanguage != nil
        else { return false }
        return Metrics.fitsTrailing(
            cardWidth: cardWidth,
            buttonCount: headerRequiredButtonCount + 1
        )
    }

    private var headerTrailingButtonCount: Int {
        headerRequiredButtonCount + (headerShowsSpeechButton ? 1 : 0)
    }

    private var headerTrailingWidth: CGFloat {
        Metrics.trailingWidth(buttonCount: headerTrailingButtonCount)
    }

    /// What is left for the title once the padding and the buttons have taken
    /// their share. Clamped at zero so a `maxWidth` is never negative.
    private var headerFlexibleWidth: CGFloat {
        max(
            Metrics.flexibleWidth(
                cardWidth: cardWidth,
                buttonCount: headerTrailingButtonCount
            ),
            0
        )
    }

    /// The title is decoration; the buttons are function. On a card too narrow
    /// for both it is the thing that goes.
    private var headerShowsTitle: Bool {
        Metrics.fitsTitle(
            cardWidth: cardWidth,
            buttonCount: headerTrailingButtonCount
        )
    }

    /// The grabber, centred in a full-width row of its own.
    ///
    /// Purely the grabber's look; the drag itself is taken by the whole header
    /// so the target is generous however narrow the card gets. It sits above
    /// the actions rather than beside them, so nothing can overlap it.
    private var dragHandle: some View {
        Capsule(style: .continuous)
            .fill(JerreaderTheme.accent.opacity(0.38))
            .frame(width: Metrics.handleWidth, height: 4)
            .frame(maxWidth: .infinity)
            .frame(height: Metrics.handleRowHeight)
            .accessibilityLabel("移动翻译浮窗")
            .accessibilityHint("拖动到屏幕中的其他位置")
    }

    private var speechButton: some View {
        Button {
            model.toggleTranslationSpeech()
        } label: {
            ZStack {
                Image(
                    systemName: model.isSpeakingTranslationSource
                        ? "stop.fill"
                        : "speaker.wave.2.fill"
                )
                .font(.caption.weight(.semibold))
                .foregroundStyle(
                    model.isSpeakingTranslationSource
                        ? JerreaderTheme.onPrimaryAction
                        : JerreaderTheme.accent
                )
                .frame(width: 30, height: 30)
                .background(
                    model.isSpeakingTranslationSource
                        ? JerreaderTheme.accent
                        : JerreaderTheme.accentFill,
                    in: Circle()
                )
            }
            .frame(width: 44, height: 44)
            .contentShape(Circle())
        }
        .accessibilityLabel(
            model.isSpeakingTranslationSource ? "停止朗读" : "朗读当前句段"
        )
    }

    private var closeButton: some View {
        Button {
            model.dismissTranslation()
        } label: {
            ZStack {
                Image(systemName: "xmark")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(.secondary)
                    .frame(width: 30, height: 30)
                    .background(Color.primary.opacity(0.055), in: Circle())
            }
            .frame(width: 44, height: 44)
            .contentShape(Circle())
        }
        .accessibilityLabel("关闭翻译")
    }

    private var translationActionsMenu: some View {
        Menu {
            if case .success = model.translationState {
                Button {
                    model.toggleTranslationFavorite()
                } label: {
                    Label(
                        model.isCurrentTranslationFavorite ? "取消收藏译文" : "收藏译文",
                        systemImage: model.isCurrentTranslationFavorite
                            ? "star.slash"
                            : "star"
                    )
                }

                Divider()
            }

            if model.canAnnotateCurrentSelection {
                Button {
                    if model.isCurrentSelectionAnnotated {
                        model.editCurrentSelectionAnnotation()
                    } else {
                        model.addHighlightForCurrentSelection()
                    }
                } label: {
                    Label(
                        model.isCurrentSelectionAnnotated ? "编辑划线与批注" : "划线",
                        systemImage: model.isCurrentSelectionAnnotated
                            ? "pencil.line"
                            : "highlighter"
                    )
                }

                Button {
                    model.editCurrentSelectionAnnotation()
                } label: {
                    Label("添加笔记", systemImage: "note.text.badge.plus")
                }
            }

            Button {
                model.requestContextExplanation()
            } label: {
                Label("AI 句子结构分析", systemImage: "text.line.magnify")
            }

            if model.canAddCurrentSelectionToVocabulary {
                Button {
                    model.addCurrentSelectionToVocabulary()
                } label: {
                    Label(
                        model.isAddingCurrentSelectionToVocabulary
                            ? "正在查询词形…"
                            : model.isCurrentSelectionInVocabulary
                            ? "已加入生词本"
                            : "加入生词本",
                        systemImage: model.isAddingCurrentSelectionToVocabulary
                            ? "hourglass"
                            : model.isCurrentSelectionInVocabulary
                            ? "bookmark.fill"
                            : "bookmark"
                    )
                }
                .disabled(
                    model.isCurrentSelectionInVocabulary
                        || model.isAddingCurrentSelectionToVocabulary
                )
            }

            Button {
                model.expandTranslationAcrossPage()
            } label: {
                Label(
                    model.isExpandingCrossPageTranslation
                        ? "正在扩展跨页句段…"
                        : "扩展跨页句段",
                    systemImage: "rectangle.split.2x1"
                )
            }
            .disabled(!model.canExpandTranslationAcrossPage)

            Divider()

            Button {
                model.retryTranslation()
            } label: {
                Label("重新翻译", systemImage: "arrow.clockwise")
            }
            .disabled(!model.canRetryTranslation)
        } label: {
            ZStack {
                Image(systemName: "ellipsis")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(Color.secondary)
                    .frame(width: 30, height: 30)
                    .background(Color.primary.opacity(0.055), in: Circle())
            }
            .frame(width: 44, height: 44)
            .contentShape(Circle())
        }
        .accessibilityLabel("更多翻译操作")
    }

    private var headerTitle: String {
        switch model.translationState {
        case .idle, .success: return "译文"
        case .loading: return "翻译中"
        case .failure: return "未完成"
        }
    }

    private var headerSymbol: String {
        switch model.translationState {
        case .idle, .loading: return "character.bubble"
        case .success: return "character.bubble.fill"
        case .failure: return "exclamationmark"
        }
    }

    private var maximumContentHeight: CGFloat {
        // The header, the divider, and the bottom breathing room are outside
        // the ScrollView. Reserve all of them so the last translated line never
        // lands under the rounded bottom edge.
        max(maximumCardHeight - (Metrics.height + 13), 54)
    }

    @ViewBuilder
    private var translationBody: some View {
        Group {
            switch model.translationState {
            case let .success(_, result, _):
                if let text = TranslationOutputPolicy.displayText(
                    result.translatedText
                ) {
                    ScrollView(.vertical) {
                        translatedText(text)
                            .background {
                                GeometryReader { proxy in
                                    Color.clear.preference(
                                        key: TranslationTextHeightKey.self,
                                        value: proxy.size.height
                                    )
                                }
                            }
                    }
                    .frame(
                        height: ReaderTranslationContentMetric.height(
                            measured: translatedContentHeight,
                            maximum: maximumContentHeight
                        )
                    )
                    .scrollIndicators(
                        translatedContentHeight > maximumContentHeight + 0.5
                            ? .visible
                            : .hidden
                    )
                    .scrollBounceBehavior(.basedOnSize)
                    .accessibilityIdentifier("translation-result-scroll-view")
                    .scrollDisabled(
                        translatedContentHeight
                            <= ReaderTranslationContentMetric.height(
                                measured: translatedContentHeight,
                                maximum: maximumContentHeight
                            ) + 0.5
                    )
                    .onPreferenceChange(TranslationTextHeightKey.self) { value in
                        Task { @MainActor in
                            guard value.isFinite,
                                  value > 0,
                                  abs(value - translatedContentHeight) > 0.5
                            else { return }
                            translatedContentHeight = value
                        }
                    }
                } else {
                    invalidTranslationContent
                        .frame(maxHeight: maximumContentHeight)
                }
            default:
                statusContent
                    .frame(maxHeight: maximumContentHeight)
            }
        }
        .id(translationPhaseIdentity)
        .transition(
            .opacity.combined(with: .scale(scale: 0.985, anchor: .top))
        )
    }

    private func translatedText(_ text: String) -> some View {
        Text(text)
            .font(.body)
            .lineSpacing(5)
            .textSelection(.enabled)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 16)
        .padding(.top, 15)
        .padding(.bottom, 20)
        .accessibilityLabel("译文：\(text)")
    }

    private var invalidTranslationContent: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("译文内容为空，请重新翻译。")
                .font(.subheadline)
            Button {
                model.retryTranslation()
            } label: {
                Label("重新翻译", systemImage: "arrow.clockwise")
                    .font(.caption.weight(.semibold))
            }
            .buttonStyle(.bordered)
            .disabled(!model.canRetryTranslation)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
    }

    private var statusContent: some View {
        translationContent
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .fixedSize(horizontal: false, vertical: true)
    }

    @ViewBuilder
    private var translationContent: some View {
        switch model.translationState {
        case .idle:
            EmptyView()
        case .loading:
            HStack(spacing: 12) {
                JerreaderLoadingGlyph(
                    systemImage: "character.bubble.fill",
                    size: 38
                )

                VStack(alignment: .leading, spacing: 3) {
                    Text(loadingTitle)
                        .font(.subheadline.weight(.semibold))
                    Text(loadingDetail)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .accessibilityLabel("正在翻译")
        case .success:
            EmptyView()
        case let .failure(_, error):
            VStack(alignment: .leading, spacing: 11) {
                Text(error.message)
                    .font(.subheadline)
                    .fixedSize(horizontal: false, vertical: true)

                if error.canRetry {
                    Button {
                        model.retryTranslation()
                    } label: {
                        Label("重新翻译", systemImage: "arrow.clockwise")
                            .font(.caption.weight(.semibold))
                    }
                    .buttonStyle(.bordered)
                    .tint(JerreaderTheme.accent)
                    .disabled(!model.canRetryTranslation)
                    .accessibilityHint(
                        model.isTranslationRetryCoolingDown
                            ? "刚刚已经发起请求，请稍候再试"
                            : "重新请求当前选中的文字"
                    )
                }
            }
        }
    }

    private var loadingDetail: String {
        guard let provider = model.translationState.request?.provider else {
            return "正在准备翻译服务"
        }
        return provider == .apple
            ? "首次使用可能需要数分钟下载语言包；请确认系统提示并保持 App 在前台。"
            : "正在安全请求所选 AI 服务"
    }

    private var loadingTitle: String {
        model.translationState.request?.provider == .apple
            ? "正在准备 Apple 翻译…"
            : "正在翻译…"
    }

    private var translationPhaseIdentity: String {
        switch model.translationState {
        case .idle:
            return "idle"
        case let .loading(request):
            return "loading-\(request.id.uuidString)"
        case let .success(request, _, _):
            return "success-\(request.id.uuidString)"
        case let .failure(request, _):
            return "failure-\(request.id.uuidString)"
        }
    }
}

private struct ReaderAnnotationEditor: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let draft: ReaderAnnotationEditorDraft
    let onSave: (String, ReadingAnnotationColor) -> Void
    let onDelete: (() -> Void)?

    @State private var noteText: String
    @State private var color: ReadingAnnotationColor

    init(
        draft: ReaderAnnotationEditorDraft,
        onSave: @escaping (String, ReadingAnnotationColor) -> Void,
        onDelete: (() -> Void)?
    ) {
        self.draft = draft
        self.onSave = onSave
        self.onDelete = onDelete
        _noteText = State(initialValue: draft.noteText)
        _color = State(initialValue: draft.color)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("原文") {
                    Text(draft.selectedText)
                        .font(.body)
                        .textSelection(.enabled)
                }

                Section("划线颜色") {
                    HStack(spacing: 18) {
                        ForEach(ReadingAnnotationColor.allCases) { option in
                            Button {
                                withAnimation(reduceMotion ? nil : JerreaderMotion.stateChange) {
                                    color = option
                                }
                            } label: {
                                Circle()
                                    .fill(option.tintColor)
                                    .frame(width: 30, height: 30)
                                    .overlay {
                                        if color == option {
                                            Image(systemName: "checkmark")
                                                .font(.caption.bold())
                                                .foregroundStyle(.white)
                                        }
                                    }
                                    .overlay {
                                        Circle()
                                            .stroke(
                                                color == option
                                                    ? Color.primary.opacity(0.42)
                                                    : Color.clear,
                                                lineWidth: 2
                                            )
                                    }
                            }
                            .buttonStyle(JerreaderPressButtonStyle(pressedScale: 0.90))
                            .accessibilityLabel(option.title)
                            .accessibilityAddTraits(color == option ? .isSelected : [])
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.vertical, 4)
                }

                Section("笔记") {
                    TextEditor(text: $noteText)
                        .frame(minHeight: 130)
                        .scrollContentBackground(.hidden)
                        .accessibilityLabel("笔记内容")
                }

                if let onDelete {
                    Section {
                        Button("删除划线与笔记", role: .destructive) {
                            onDelete()
                            dismiss()
                        }
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(JerreaderCanvasBackground())
            .navigationTitle(draft.annotationID == nil ? "新建批注" : "编辑批注")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        onSave(noteText, color)
                        dismiss()
                    }
                    .fontWeight(.semibold)
                }
            }
        }
        .tint(JerreaderTheme.accent)
        .frame(maxWidth: 680)
        .sensoryFeedback(.selection, trigger: color)
    }
}

private struct ReaderContextExplanationSheet: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var model: EPUBReaderViewModel

    var body: some View {
        NavigationStack {
            Group {
                switch model.contextExplanationState {
                case .idle:
                    ProgressView()
                case let .loading(request):
                    explanationLoading(request)
                case let .success(request, result):
                    explanationResult(request: request, result: result)
                case let .failure(request, error):
                    explanationFailure(request: request, error: error)
                }
            }
            .id(explanationPhaseIdentity)
            .transition(
                .opacity.combined(with: .scale(scale: 0.985, anchor: .top))
            )
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(JerreaderCanvasBackground())
            .navigationTitle("AI 句子结构分析")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
        }
        .tint(JerreaderTheme.accent)
        .frame(maxWidth: 720)
        .animation(
            reduceMotion ? nil : JerreaderMotion.stateChange,
            value: explanationPhaseIdentity
        )
        .sensoryFeedback(
            .success,
            trigger: explanationPhaseIdentity
        ) { oldValue, newValue in
            model.translationSettings.translationHapticsEnabled
                && oldValue != newValue
                && newValue.hasPrefix("success")
        }
        .onDisappear {
            model.dismissContextExplanation()
        }
    }

    private func explanationLoading(
        _ request: ReaderContextExplanationRequest
    ) -> some View {
        VStack(spacing: 20) {
            JerreaderLoadingGlyph(systemImage: "sparkles", size: 72)

            VStack(spacing: 6) {
                Text("正在分析句子结构与语法…")
                    .font(.headline)
                Text("前后文只用于判断省略、指代与词义")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            focusedText(request.focusedText)
        }
        .frame(maxWidth: 560)
        .padding(28)
        .jerreaderPaperCard(padding: 0, radius: 16, hasShadow: true)
        .padding(24)
    }

    private func explanationResult(
        request: ReaderContextExplanationRequest,
        result: ContextExplanationResult
    ) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                focusedText(request.focusedText)

                VStack(alignment: .leading, spacing: 10) {
                    HStack(spacing: 11) {
                        Image(systemName: "sparkles")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.white)
                            .frame(width: 34, height: 34)
                            .background(
                                JerreaderTheme.primaryAction,
                                in: RoundedRectangle(cornerRadius: 11, style: .continuous)
                            )

                        VStack(alignment: .leading, spacing: 2) {
                            Text("语法与结构分析")
                                .font(.headline)
                            Text("拆解句子成分、修饰关系与重点语法")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                    }

                    Text(result.explanation)
                        .font(.body)
                        .lineSpacing(6)
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(20)
                .background(JerreaderTheme.raisedPaper)
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .stroke(JerreaderTheme.line, lineWidth: 0.75)
                }
                .shadow(color: JerreaderTheme.shadow, radius: 8, y: 3)
                .jerreaderReveal(order: 1)

                JerreaderStatusPill(
                    title: "只发送所选文字与有限前后文",
                    systemImage: "lock.shield"
                )
            }
            .frame(maxWidth: 680)
            .frame(maxWidth: .infinity)
            .padding(20)
        }
    }

    private func explanationFailure(
        request: ReaderContextExplanationRequest?,
        error: ReaderContextExplanationError
    ) -> some View {
        VStack(spacing: 18) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.system(size: 34, weight: .medium))
                .foregroundStyle(.orange)
                .frame(width: 72, height: 72)
                .background(Color.orange.opacity(0.10), in: RoundedRectangle(cornerRadius: 22))
            Text("暂时无法完成语法分析")
                .font(.title3.bold())
            Text(error.message)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            if request != nil, error.canRetry {
                Button {
                    model.retryContextExplanation()
                } label: {
                    Label("重新生成", systemImage: "arrow.clockwise")
                }
                .buttonStyle(.borderedProminent)
            }
        }
        .padding(28)
        .frame(maxWidth: 520)
        .jerreaderPaperCard(padding: 0, radius: 24, hasShadow: true)
        .padding(24)
    }

    private func focusedText(_ text: String) -> some View {
        HStack(alignment: .top, spacing: 11) {
            Image(systemName: "quote.opening")
                .font(.caption.weight(.bold))
                .foregroundStyle(JerreaderTheme.accent)
                .padding(.top, 2)

            Text(text)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .lineLimit(5)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(15)
        .background(
            JerreaderTheme.accentFill.opacity(0.58),
            in: RoundedRectangle(cornerRadius: 15, style: .continuous)
        )
        .overlay {
            RoundedRectangle(cornerRadius: 15, style: .continuous)
                .stroke(JerreaderTheme.accent.opacity(0.12), lineWidth: 0.75)
        }
        .accessibilityLabel("所选原文：\(text)")
    }

    private var explanationPhaseIdentity: String {
        switch model.contextExplanationState {
        case .idle:
            return "idle"
        case let .loading(request):
            return "loading-\(request.id.uuidString)"
        case let .success(request, _):
            return "success-\(request.id.uuidString)"
        case let .failure(request, _):
            return "failure-\(request?.id.uuidString ?? "none")"
        }
    }
}

private struct ReaderNavigationSheet: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ObservedObject var model: EPUBReaderViewModel
    let onNavigate: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var section: ReaderNavigationSection = .contents
    @State private var query = ""

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Picker("阅读导航", selection: $section) {
                    ForEach(ReaderNavigationSection.allCases) { section in
                        Label(section.title, systemImage: section.systemImage)
                            .tag(section)
                    }
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 16)
                .padding(.vertical, 10)

                Divider()

                navigationContent
                    .id(section)
                    .transition(
                        .opacity.combined(with: .scale(scale: 0.99, anchor: .top))
                    )
            }
            .background(JerreaderCanvasBackground())
            .navigationTitle("阅读导航")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
        }
        .tint(JerreaderTheme.accent)
        .animation(
            reduceMotion ? nil : JerreaderMotion.stateChange,
            value: section
        )
        .sensoryFeedback(.selection, trigger: section)
        .onAppear {
            query = model.lastSearchQuery
        }
        .onDisappear {
            model.cancelSearch()
        }
    }

    @ViewBuilder
    private var navigationContent: some View {
        switch section {
        case .contents:
            contentsView
        case .search:
            searchView
        case .bookmarks:
            bookmarksView
        case .annotations:
            annotationsView
        }
    }

    @ViewBuilder
    private var contentsView: some View {
        if model.outline.isEmpty {
            ContentUnavailableView(
                "这本书没有目录",
                systemImage: "list.bullet.rectangle",
                description: Text("这份文件既没有目录，也只有一个正文文件。仍可使用页面左右区域、底部按钮或进度条阅读。")
            )
        } else {
            let currentID = model.currentOutlineItem?.id
            ScrollViewReader { proxy in
                List(model.outline) { item in
                    let isCurrent = item.id == currentID
                    Button {
                        model.go(to: item)
                        onNavigate()
                    } label: {
                        HStack(spacing: 10) {
                            // The marker is a filled bar rather than a check:
                            // it reads as "you are here", not "done".
                            RoundedRectangle(cornerRadius: 1.5)
                                .fill(isCurrent ? JerreaderTheme.accent : .clear)
                                .frame(width: 3)

                            Text(item.title)
                                .foregroundStyle(isCurrent ? JerreaderTheme.accent : .primary)
                                .fontWeight(isCurrent ? .semibold : .regular)
                                .padding(.leading, CGFloat(item.depth) * 16)
                                .frame(maxWidth: .infinity, alignment: .leading)

                            if let start = item.startProgression {
                                Text("\(Int((start * 100).rounded()))%")
                                    .font(.caption.monospacedDigit())
                                    .foregroundStyle(.secondary)
                            }
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .listRowBackground(
                        isCurrent
                            ? JerreaderTheme.accent.opacity(0.10)
                            : Color.clear
                    )
                    .id(item.id)
                    .accessibilityAddTraits(isCurrent ? .isSelected : [])
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
                .onAppear {
                    // Opening the contents of a book you are 60% through must
                    // not start at chapter one.
                    guard let currentID else { return }
                    proxy.scrollTo(currentID, anchor: .center)
                }
            }
        }
    }

    private var searchView: some View {
        VStack(spacing: 0) {
            HStack(spacing: 10) {
                Image(systemName: "magnifyingglass")
                    .foregroundStyle(.secondary)

                TextField("搜索整本书", text: $query)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.search)
                    .onSubmit { model.search(query) }

                if !query.isEmpty {
                    Button {
                        query = ""
                        model.search("")
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundStyle(.secondary)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("清除搜索")
                }

                Button("搜索") { model.search(query) }
                    .font(.subheadline.weight(.semibold))
                    .disabled(query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            .padding(12)
            .background(JerreaderTheme.paper, in: RoundedRectangle(cornerRadius: 14))
            .padding(14)

            if model.isSearching {
                VStack(spacing: 12) {
                    ProgressView()
                    Text("正在搜索整本书…")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if let message = model.searchErrorMessage {
                ContentUnavailableView(
                    "无法搜索",
                    systemImage: "magnifyingglass",
                    description: Text(message)
                )
            } else if !model.searchResults.isEmpty {
                List(model.searchResults) { result in
                    Button {
                        model.go(to: result)
                        onNavigate()
                    } label: {
                        VStack(alignment: .leading, spacing: 7) {
                            HStack {
                                Text(result.chapterTitle)
                                    .font(.caption.weight(.semibold))
                                    .foregroundStyle(JerreaderTheme.accent)
                                    .lineLimit(1)
                                Spacer()
                                if let progress = result.progress {
                                    Text("\(Int((progress * 100).rounded()))%")
                                        .font(.caption2.monospacedDigit())
                                        .foregroundStyle(.tertiary)
                                }
                            }

                            let text = result.text
                            (
                                Text(text.before ?? "") +
                                Text(text.highlight ?? "").bold().foregroundColor(JerreaderTheme.accent) +
                                Text(text.after ?? "")
                            )
                            .font(.subheadline)
                            .foregroundStyle(.primary)
                            .lineLimit(3)
                        }
                        .padding(.vertical, 4)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
            } else if !model.lastSearchQuery.isEmpty {
                ContentUnavailableView.search(text: model.lastSearchQuery)
            } else {
                ContentUnavailableView(
                    "搜索书中内容",
                    systemImage: "text.magnifyingglass",
                    description: Text("输入词语或句子，可从结果直接跳转到原文位置。")
                )
            }
        }
    }

    @ViewBuilder
    private var bookmarksView: some View {
        if model.bookmarks.isEmpty {
            ContentUnavailableView(
                "还没有书签",
                systemImage: "bookmark",
                description: Text("回到正文后，点按顶部书签按钮即可保存当前页。")
            )
        } else {
            List {
                ForEach(model.bookmarks) { bookmark in
                    Button {
                        model.go(to: bookmark)
                        onNavigate()
                    } label: {
                        VStack(alignment: .leading, spacing: 7) {
                            HStack {
                                Text(bookmark.chapterTitle)
                                    .font(.subheadline.weight(.semibold))
                                    .foregroundStyle(.primary)
                                    .lineLimit(1)
                                Spacer()
                                Text("\(Int((bookmark.progress * 100).rounded()))%")
                                    .font(.caption.monospacedDigit())
                                    .foregroundStyle(JerreaderTheme.accent)
                            }
                            if let excerpt = bookmark.excerpt {
                                Text(excerpt)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(2)
                            }
                            Text(bookmark.createdAt, format: .dateTime.month().day().hour().minute())
                                .font(.caption2)
                                .foregroundStyle(.tertiary)
                        }
                        .padding(.vertical, 4)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .swipeActions {
                        Button(role: .destructive) {
                            model.deleteBookmark(bookmark)
                        } label: {
                            Label("删除", systemImage: "trash")
                        }
                    }
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
        }
    }

    @ViewBuilder
    private var annotationsView: some View {
        if model.annotations.isEmpty {
            ContentUnavailableView(
                "还没有划线或笔记",
                systemImage: "highlighter",
                description: Text("选择正文后，从译文浮窗的更多菜单添加划线或笔记。")
            )
        } else {
            List {
                ForEach(model.annotations) { annotation in
                    Button {
                        model.go(to: annotation)
                        onNavigate()
                    } label: {
                        HStack(alignment: .top, spacing: 11) {
                            Circle()
                                .fill(annotation.color.tintColor)
                                .frame(width: 9, height: 9)
                                .padding(.top, 6)

                            VStack(alignment: .leading, spacing: 7) {
                                HStack {
                                    Text(annotation.chapterTitle)
                                        .font(.caption.weight(.semibold))
                                        .foregroundStyle(JerreaderTheme.accent)
                                        .lineLimit(1)
                                    Spacer()
                                    Text("\(Int((annotation.progress * 100).rounded()))%")
                                        .font(.caption2.monospacedDigit())
                                        .foregroundStyle(.tertiary)
                                }

                                Text(annotation.selectedText)
                                    .font(.subheadline)
                                    .foregroundStyle(.primary)
                                    .lineLimit(3)

                                if !annotation.noteText.isEmpty {
                                    Label(annotation.noteText, systemImage: "note.text")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                        .lineLimit(2)
                                }
                            }
                        }
                        .padding(.vertical, 4)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .swipeActions(edge: .leading, allowsFullSwipe: false) {
                        Button {
                            model.editAnnotation(annotation)
                        } label: {
                            Label("编辑", systemImage: "pencil")
                        }
                        .tint(JerreaderTheme.accent)
                    }
                    .swipeActions {
                        Button(role: .destructive) {
                            model.deleteAnnotation(annotation)
                        } label: {
                            Label("删除", systemImage: "trash")
                        }
                    }
                    .contextMenu {
                        Button {
                            model.editAnnotation(annotation)
                        } label: {
                            Label("编辑批注", systemImage: "pencil")
                        }
                    }
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
        }
    }
}

private extension ReadingAnnotationColor {
    var tintColor: Color {
        switch self {
        case .yellow: return Color(red: 0.90, green: 0.66, blue: 0.16)
        case .blue: return Color(red: 0.22, green: 0.55, blue: 0.88)
        case .mint: return Color(red: 0.20, green: 0.69, blue: 0.58)
        case .pink: return Color(red: 0.91, green: 0.43, blue: 0.52)
        case .purple: return Color(red: 0.53, green: 0.43, blue: 0.82)
        }
    }
}

private enum ReaderNavigationSection: String, CaseIterable, Identifiable {
    case contents
    case search
    case bookmarks
    case annotations

    var id: Self { self }

    var title: String {
        switch self {
        case .contents: return "目录"
        case .search: return "搜索"
        case .bookmarks: return "书签"
        case .annotations: return "批注"
        }
    }

    var systemImage: String {
        switch self {
        case .contents: return "list.bullet"
        case .search: return "magnifyingglass"
        case .bookmarks: return "bookmark"
        case .annotations: return "highlighter"
        }
    }
}
