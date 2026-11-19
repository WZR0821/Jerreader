import SwiftData
import SwiftUI
import UIKit
@preconcurrency import Translation

struct EPUBReaderScreen: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase

    // `@State` owns the observable model for the life of the screen.
    @State private var model: EPUBReaderViewModel
    @State private var isShowingNavigation = false
    @State private var navigationSection: ReaderNavigationSection = .contents
    @State private var isShowingSettings = false
    @State private var scrubbedProgress: Double?
    @State private var isScrubbing = false
    @State private var scrubSettleTask: Task<Void, Never>?
    @AppStorage(ReaderAppearanceDefaults.showsProgressKey)
    private var showsReadingProgress = true
    @AppStorage(ReaderInterfaceDefaults.immersiveReadingKey)
    private var immersiveReading = true
    @AppStorage(ReaderInterfaceDefaults.topTransparencyKey)
    private var topBarTransparency = ReaderInterfaceDefaults.barTransparency
    @AppStorage(ReaderInterfaceDefaults.bottomTransparencyKey)
    private var bottomBarTransparency = ReaderInterfaceDefaults.barTransparency
    @AppStorage(ReaderInterfaceDefaults.layoutModeKey)
    private var layoutModeRawValue = ReaderLayoutMode.standard.rawValue
    @AppStorage(ReaderInterfaceDefaults.verticalMarginsKey)
    private var verticalMargins = 1.0
    /// Measured, so the page sits exactly between the bars in the standard
    /// layout whatever the text size or the progress-row setting.
    @State private var topChromeHeight: CGFloat = 44
    /// The 2.0 bar with its progress rows (6 + 18 + 26 + 48), so the first
    /// layout already matches the measured height.
    @State private var bottomChromeHeight: CGFloat = 78
    /// Bumped by the bookmark button only, so the bounce and the haptic
    /// follow a tap rather than every page that happens to be bookmarked.
    @State private var bookmarkAddedCount = 0
    @State private var bookmarkRemovedCount = 0

    init(
        book: BookRecord,
        modelContext: ModelContext,
        translationSettings: TranslationSettingsStore,
        initialLocatorJSON: String? = nil
    ) {
        _model = State(
            initialValue: EPUBReaderViewModel(
                book: book,
                modelContext: modelContext,
                translationSettings: translationSettings,
                initialLocatorJSON: initialLocatorJSON
            )
        )
    }

    var body: some View {
        ZStack {
            // Only the layout mode moves the page: showing or hiding the bars
            // in the immersive layout must never make Readium paginate again.
            readerContent
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .clipped()
                .padding(.top, reservesChromeSpace ? topChromeHeight : 0)
                .padding(.bottom, reservesChromeSpace ? bottomChromeHeight : 0)

            // Each bar fades and slides on its own (see `readerChrome`); the
            // pair stops taking touches and leaves VoiceOver as one.
            readerChrome
                .allowsHitTesting(model.isChromeVisible)
                .accessibilityHidden(!model.isChromeVisible)
                .zIndex(1)

            if model.layoutMode == .immersive, !model.isChromeVisible {
                immersiveRevealZones
                    .zIndex(1)
            }

            if model.isQuickSentenceTranslationEnabled,
               model.isQuickTranslationIndicatorVisible,
               !model.isChromeVisible,
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
                ReaderTranslationLayer(
                    model: model,
                    topChromeHeight: topChromeHeight,
                    bottomChromeHeight: bottomChromeHeight
                )
                .transition(translationLayerTransition)
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
        // The immersive layout is full screen by definition; the standard one
        // keeps the separate status-bar choice.
        .statusBarHidden(immersiveReading || model.layoutMode == .immersive)
        .preferredColorScheme(prefersDarkReaderChrome ? .dark : .light)
        // The bookmark haptic now belongs to the bookmark button, so turning
        // onto a bookmarked page no longer buzzes.
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
            ReaderNavigationSheet(model: model, initialSection: navigationSection) {
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
        .sheet(
            isPresented: $model.isShowingContextualLookup,
            onDismiss: { model.contextualLookupDismissed() }
        ) {
            NavigationStack {
                LookupView(translationSettings: model.translationSettings,
                    initialWord: model.shortTapLookup?.word
                        ?? model.translationState.request?.sourceText ?? "",
                    initialContext: model.shortTapLookup?.context
                        ?? model.translationState.request?.contextText
                        ?? model.translationState.request?.sourceText ?? "",
                    language: model.shortTapLookup?.language
                        ?? model.translationState.request?.sourceLanguage,
                    sourceBookID: model.book.id, sourceBookTitle: model.book.title)
                    .navigationTitle("语境查词")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar { ToolbarItem(placement: .confirmationAction) {
                        Button("关闭") { model.isShowingContextualLookup = false }
                    } }
            }
        }
        .sheet(isPresented: $model.isShowingContextExplanation) {
            ReaderContextExplanationSheet(model: model)
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
        .modifier(ReaderCompanionSheets(companion: model.companion) { term in
            model.search(term)
            navigationSection = .search
            isShowingNavigation = true
        })
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
        .onChange(of: layoutModeRawValue) {
            model.setLayoutMode(ReaderLayoutMode(rawValue: layoutModeRawValue) ?? .standard)
        }
        .onChange(of: verticalMargins) {
            model.controller?.refreshContentInsets()
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
            .multilineTextAlignment(.center)
            .jerreaderPaperCard(padding: 26, radius: JerreaderRadius.hero, hasShadow: true)
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

    /// The standard layout lays the page out between the bars; the immersive
    /// one lets them float over a full-screen page.
    private var reservesChromeSpace: Bool {
        model.layoutMode == .standard
    }

    /// The translation layer makes its own entrance (`jerreaderReveal` in
    /// ReaderTranslationOverlay), so only its removal is a transition.
    private var translationLayerTransition: AnyTransition {
        .asymmetric(
            insertion: .identity,
            removal: reduceMotion ? .opacity : .opacity.combined(with: .scale(scale: 0.98))
        )
    }

    private var readerChrome: some View {
        VStack(spacing: 0) {
            // Heights are measured before the show/hide offset, which never
            // moves the layout frame, so the page keeps its place.
            topChromeBar
                .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { height in
                    if abs(height - topChromeHeight) > 0.5 { topChromeHeight = height }
                }
                .readerChromeBar(isVisible: model.isChromeVisible, edge: .top)

            Spacer(minLength: 0)

            bottomChromeBar
                .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { height in
                    if abs(height - bottomChromeHeight) > 0.5 { bottomChromeHeight = height }
                }
                .readerChromeBar(isVisible: model.isChromeVisible, edge: .bottom)
        }
    }

    /// In the immersive layout a tap on the text looks a word up or
    /// translates, so the blank strips above and below the text bring the bars
    /// back. Each covers the safe area and the page's own edge inset, never a
    /// line of text.
    private var immersiveRevealZones: some View {
        GeometryReader { geometry in
            // The strip follows 上下边距 so it never reaches a line of text, but
            // keeps a height a finger can find when the margin is set to none
            // on a phone with no safe area at that edge.
            let edge = max(
                (model.isReflowableBook
                    ? ReaderInterfaceDefaults.pageEdgeInset(verticalMargins: verticalMargins)
                    : ReaderInterfaceDefaults.contentEdgeInset) - 2,
                0
            )
            VStack(spacing: 0) {
                immersiveRevealZone(
                    height: max(geometry.safeAreaInsets.top + edge, 12),
                    identifier: "reader-reveal-top"
                )
                Spacer(minLength: 0)
                immersiveRevealZone(
                    height: max(geometry.safeAreaInsets.bottom + edge, 12),
                    identifier: "reader-reveal-bottom"
                )
            }
            .ignoresSafeArea()
        }
    }

    private func immersiveRevealZone(height: CGFloat, identifier: String) -> some View {
        Button {
            model.showReaderControls()
        } label: {
            Color.clear
                .frame(maxWidth: .infinity)
                .frame(height: height)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("显示阅读控制")
        .accessibilityIdentifier(identifier)
    }

    /// Close, the book and chapter, then 目录, 书签, AI and Aa. 44pt tall: the
    /// title block is capped at xxxLarge so the bar does not crowd the page.
    private var topChromeBar: some View {
        HStack(spacing: 0) {
            chromeButton(systemImage: "xmark") {
                model.flushProgress()
                dismiss()
            }
            .accessibilityLabel("关闭阅读器")

            VStack(alignment: .leading, spacing: 1) {
                Text(model.book.title)
                    .font(.headline)
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                Text(model.chapterTitle)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
            .padding(.leading, 4)

            Spacer(minLength: 4)

            chromeButton(systemImage: "list.bullet") {
                navigationSection = .contents
                isShowingNavigation = true
            }
            .accessibilityLabel("目录与阅读导航")

            // Bookmarking is a one-tap, frequently repeated action and its
            // state is worth showing continuously; burying it in a menu
            // hid both the action and whether the page was already saved.
            // Adding one bounces the glyph with a success haptic; removing
            // one gives a light tap.
            Button {
                model.noteChromeInteraction()
                let wasBookmarked = model.isCurrentLocationBookmarked
                model.toggleBookmark()
                if model.isCurrentLocationBookmarked != wasBookmarked {
                    if model.isCurrentLocationBookmarked {
                        bookmarkAddedCount += 1
                    } else {
                        bookmarkRemovedCount += 1
                    }
                }
            } label: {
                JerreaderSymbol(
                    name: model.isCurrentLocationBookmarked ? "bookmark.fill" : "bookmark",
                    size: ReaderChromeMetrics.iconSize
                )
                .jerreaderBounce(trigger: bookmarkAddedCount)
            }
            .buttonStyle(ReaderBarIconButtonStyle(isOn: model.isCurrentLocationBookmarked))
            .accessibilityLabel(
                model.isCurrentLocationBookmarked
                    ? "移除当前书签"
                    : "添加当前书签"
            )
            .accessibilityAddTraits(
                model.isCurrentLocationBookmarked ? .isSelected : []
            )
            .sensoryFeedback(.success, trigger: bookmarkAddedCount)
            .sensoryFeedback(.impact(weight: .light), trigger: bookmarkRemovedCount)

            ReaderCompanionChromeButton(companion: model.companion, ringColor: readerBackground) {
                model.noteChromeInteraction()
                model.presentReadingCompanion()
            }

            chromeButton(systemImage: "textformat") {
                isShowingSettings = true
            }
            .accessibilityLabel("阅读设置")
        }
        .padding(.horizontal, 6)
        .frame(maxWidth: 780)
        .frame(maxWidth: .infinity)
        .background {
            ReaderBarSurface(
                pageColor: readerBackground,
                opacity: ReaderInterfaceDefaults.backgroundOpacity(topBarTransparency),
                edge: .top
            )
        }
    }

    /// Three rows: the chapter and the percentage, the progress slider, then
    /// the page buttons around the filled 轻点翻译 circle.
    private var bottomChromeBar: some View {
        VStack(spacing: 0) {
            if showsReadingProgress {
                // One compact row, as in 1.7.0: the bars stay small so the page
                // keeps its height. While dragging, the chapter the thumb is
                // over matters far more than the raw percentage, so it takes
                // the accent colour.
                HStack(spacing: 10) {
                    Text(scrubbedProgress == nil ? currentChapterLabel : scrubTargetLabel)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(
                            scrubbedProgress == nil ? .secondary : JerreaderTheme.accent
                        )
                        .lineLimit(1)
                        .truncationMode(.middle)
                        .frame(maxWidth: 104, alignment: .leading)
                        .animation(nil, value: scrubbedProgress)

                    ReaderProgressSlider(
                        value: readerProgressBinding,
                        accessibilityLabelText: "快速调整阅读进度",
                        accessibilityValueText: scrubbedProgress == nil
                            ? "\(currentChapterLabel)，\(progressLabel)"
                            : "\(scrubTargetLabel)，\(progressLabel)",
                        onEditingChanged: { isEditing in
                            handleProgressEditing(isEditing)
                        }
                    )
                    .onChange(of: model.progress) {
                        releaseScrubbedProgress()
                    }

                    Text(progressLabel)
                        .font(.caption.monospacedDigit().weight(.semibold))
                        .foregroundStyle(.secondary)
                        .contentTransition(.numericText())
                        .frame(minWidth: 34, alignment: .trailing)
                }
                .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                .padding(.horizontal, 6)
                .frame(minHeight: 32)
            }

            HStack(spacing: 0) {
                if model.canGoToAdjacentChapter {
                    pageButton(systemImage: "chevron.backward.2") {
                        model.goToPreviousChapter()
                    }
                    .accessibilityLabel("上一章")
                }

                pageButton(systemImage: "chevron.left") {
                    model.goBackward()
                }
                .accessibilityLabel("上一页")

                if model.supportsQuickSentenceTranslation {
                    Button {
                        model.noteChromeInteraction()
                        model.setQuickSentenceTranslationEnabled(
                            !model.isQuickSentenceTranslationEnabled
                        )
                    } label: {
                        JerreaderSymbol(
                            name: model.isQuickSentenceTranslationEnabled
                                ? "character.bubble.fill"
                                : "character.bubble",
                            size: ReaderChromeMetrics.iconSize
                        )
                        .jerreaderBounce(trigger: model.isQuickSentenceTranslationEnabled)
                    }
                    .buttonStyle(
                        ReaderTranslateToggleButtonStyle(isOn: model.isQuickSentenceTranslationEnabled)
                    )
                    .accessibilityLabel(
                        model.isQuickSentenceTranslationEnabled
                            ? "关闭轻点翻译"
                            : "开启轻点翻译"
                    )
                    .accessibilityHint(
                        model.isQuickSentenceTranslationEnabled && model.isReflowableBook
                            ? "关闭后轻点词语直接查词"
                            : "开启后轻点正文即可翻译当前\(model.quickTranslationUnit.title)"
                    )
                }

                pageButton(systemImage: "chevron.right") {
                    model.goForward()
                }
                .accessibilityLabel("下一页")

                if model.canGoToAdjacentChapter {
                    pageButton(systemImage: "chevron.forward.2") {
                        model.goToNextChapter()
                    }
                    .accessibilityLabel("下一章")
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.top, 2)
        .frame(maxWidth: 660)
        .frame(maxWidth: .infinity)
        .background {
            ReaderBarSurface(
                pageColor: readerBackground,
                opacity: ReaderInterfaceDefaults.backgroundOpacity(bottomBarTransparency),
                edge: .bottom
            )
        }
    }

    /// A top-bar icon (22pt drawn glyph in a 36pt circle, 44pt hit frame).
    private func chromeButton(
        systemImage: String,
        action: @escaping () -> Void
    ) -> some View {
        Button {
            model.noteChromeInteraction()
            action()
        } label: {
            JerreaderSymbol(name: systemImage, size: ReaderChromeMetrics.iconSize)
        }
        .buttonStyle(ReaderBarIconButtonStyle())
    }

    /// A bare page-turn glyph in the page's own colours.
    private func pageButton(
        systemImage: String,
        action: @escaping () -> Void
    ) -> some View {
        Button {
            model.noteChromeInteraction()
            action()
        } label: {
            JerreaderSymbol(name: systemImage, size: ReaderChromeMetrics.iconSize)
        }
        .buttonStyle(ReaderPageButtonStyle())
    }

    private func readerFailure(_ message: String) -> some View {
        VStack(spacing: 18) {
            Image(systemName: "exclamationmark.book.closed.fill")
                .font(.system(size: 30, weight: .medium))
                .foregroundStyle(JerreaderTheme.vermilion)
                .frame(width: 68, height: 68)
                .background(
                    JerreaderTheme.vermilion.opacity(0.12),
                    in: RoundedRectangle(cornerRadius: JerreaderRadius.card, style: .continuous)
                )
                .accessibilityHidden(true)
            Text("无法打开这本书")
                .font(.title2.weight(.bold))
            Text(message)
                .font(.body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            HStack(spacing: 12) {
                Button("返回书架") { dismiss() }
                    .buttonStyle(JerreaderTintedButtonStyle())

                Button {
                    Task { await model.load() }
                } label: {
                    JerreaderLabel(title: "重新打开", icon: "arrow.clockwise", size: 16)
                }
                .buttonStyle(JerreaderPrimaryButtonStyle(height: 44, fullWidth: false))
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
        model.setChromeAutoHideSuspended(isEditing)
        isScrubbing = isEditing
        scrubSettleTask?.cancel()
        scrubSettleTask = nil
        if isEditing {
            if scrubbedProgress == nil {
                scrubbedProgress = model.progress
            }
            return
        }
        guard let target = scrubbedProgress else { return }
        model.seek(to: target)
        // The new page arrives a moment after the finger lifts. The thumb
        // stays where it was dropped until then; letting go of it at once
        // sent it back to the old position first and then forward again.
        scrubSettleTask = Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(1200))
            guard !Task.isCancelled, !isScrubbing else { return }
            scrubbedProgress = nil
        }
    }

    /// The page the drag asked for has been reported, so the slider can
    /// follow the book again.
    private func releaseScrubbedProgress() {
        guard !isScrubbing, scrubbedProgress != nil else { return }
        scrubSettleTask?.cancel()
        scrubSettleTask = nil
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
                JerreaderSymbol(name: "character.bubble.fill", size: 18)
                    .foregroundStyle(JerreaderTheme.accent)
                    .frame(width: 44, height: 44)
                    .background(JerreaderTheme.paper, in: Circle())
                    .overlay {
                        Circle()
                            .strokeBorder(JerreaderTheme.accent.opacity(0.28), lineWidth: 1)
                    }
                    .jerreaderElevation(.card, cornerRadius: 22)
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
            .padding(.horizontal, 16)
            .frame(minHeight: 42)
            .background(JerreaderTheme.paper, in: Capsule())
            .overlay {
                Capsule()
                    .strokeBorder(JerreaderTheme.line, lineWidth: 0.75)
            }
            .jerreaderElevation(.raised, cornerRadius: 21)

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
    let model: EPUBReaderViewModel
    /// The bars' measured heights, which the card keeps clear of while they show.
    let topChromeHeight: CGFloat
    let bottomChromeHeight: CGFloat
    @ObservedObject private var settings: TranslationSettingsStore
    @State private var cardHeight: CGFloat = 108
    @State private var manualPosition: CGPoint?
    @State private var isSelectionMoving = false
    @State private var latestSelectionMovementAt = Date.distantPast
    @State private var selectionSettleTask: Task<Void, Never>?
    @GestureState private var dragState = TranslationOverlayDragState.inactive

    init(model: EPUBReaderViewModel, topChromeHeight: CGFloat, bottomChromeHeight: CGFloat) {
        self.model = model
        self.topChromeHeight = topChromeHeight
        self.bottomChromeHeight = bottomChromeHeight
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
            ReaderTranslationOverlay(
                model: model,
                maximumCardHeight: overlayLayout.maximumCardHeight,
                usesCompactHeader: cardWidth < 238,
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
#if DEBUG
                    .preference(key: TranslationCardFrameKey.self,
                                value: cardGeometry.frame(in: .global))
#endif
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
        max(geometry.safeAreaInsets.top, model.isChromeVisible ? topChromeHeight + 12 : 12)
    }

    private func bottomInset(in geometry: GeometryProxy) -> CGFloat {
        max(geometry.safeAreaInsets.bottom, model.isChromeVisible ? bottomChromeHeight + 12 : 12)
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
    static let defaultValue: CGFloat = 0

    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        let next = nextValue()
        if next.isFinite, next > 0 { value = next }
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

private struct ReaderTranslationOverlay<HandleGesture: Gesture>: View {
    @AppStorage(ReaderInterfaceDefaults.japaneseFuriganaKey)
    private var japaneseFuriganaEnabled = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorScheme) private var colorScheme
    let model: EPUBReaderViewModel
    let maximumCardHeight: CGFloat
    let usesCompactHeader: Bool
    let isDragging: Bool
    let handleGesture: HandleGesture
    @State private var translatedContentHeight: CGFloat = 54
    /// Bumped by the star button only: its bounce and success haptic.
    @State private var favoriteAddedCount = 0

    /// Header and action row heights, reserved around the scrolling text.
    private static var headerHeight: CGFloat { 44 }
    private static var actionRowHeight: CGFloat { 44 }

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: JerreaderRadius.card, style: .continuous)
        VStack(spacing: 0) {
            overlayHeader

            translationBody

            if showsActionRow {
                actionRow
            } else {
                // A small breathing room keeps the last line from visually
                // sitting on the rounded bottom edge.
                Color.clear
                    .frame(height: 12)
            }
        }
        .frame(minHeight: 108, alignment: .top)
        .background(JerreaderTheme.paper, in: shape)
        .clipShape(shape)
        .overlay {
            shape
                .strokeBorder(JerreaderTheme.line, lineWidth: 0.75)
                .allowsHitTesting(false)
        }
        // e3 on light paper; none on dark paper or while the card moves.
        .shadow(
            color: showsShadow
                ? JerreaderTheme.shadowTint.opacity(JerreaderElevation.floating.opacity)
                : .clear,
            radius: showsShadow ? JerreaderElevation.floating.radius : 0,
            y: showsShadow ? JerreaderElevation.floating.y : 0
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
        .jerreaderReveal(order: 0)
        .accessibilityElement(children: .contain)
    }

    private var showsShadow: Bool {
        colorScheme != .dark && !isDragging
    }

    /// Every state with a request has the action row (the 更多 menu works
    /// while a translation loads or after it fails, as before).
    private var showsActionRow: Bool {
        model.translationState.request != nil
    }

    /// The language eyebrow over a drag zone that fills the header, with the
    /// close button on top. The eyebrow lets touches through and the drag
    /// zone sits underneath, so the two hit targets cannot overlap whatever
    /// the card's width (the compact card can be 144pt wide). A 32×4 grabber
    /// at the top centre shows the card can be moved.
    private var overlayHeader: some View {
        ZStack(alignment: .leading) {
            dragHandle

            HStack(spacing: 8) {
                eyebrow
                    .allowsHitTesting(false)
                Spacer(minLength: 0)
                closeButton
            }
            .padding(.leading, JerreaderSpacing.md)
            .padding(.trailing, 2)
        }
        .frame(height: Self.headerHeight)
        .overlay(alignment: .top) {
            Capsule(style: .continuous)
                .fill(Color.primary.opacity(0.16))
                .frame(width: 32, height: 4)
                .padding(.top, 6)
                .allowsHitTesting(false)
                .accessibilityHidden(true)
        }
    }

    private var dragHandle: some View {
        Color.clear
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .contentShape(Rectangle())
            .gesture(handleGesture)
            .accessibilityElement()
            .accessibilityLabel("移动翻译浮窗")
            .accessibilityHint("拖动到屏幕中的其他位置")
    }

    private var eyebrow: some View {
        JerreaderEyebrow(
            text: eyebrowText,
            tint: isFailure ? JerreaderTheme.vermilion : JerreaderTheme.accent
        )
        .lineLimit(1)
        .truncationMode(.tail)
        .accessibilityLabel(eyebrowAccessibilityLabel)
    }

    /// Favourite and (when speech is on) read aloud on the left, 更多 on the
    /// right, under a hairline.
    private var actionRow: some View {
        HStack(spacing: 0) {
            if case .success = model.translationState {
                favoriteButton
            }

            if SpeechFeatureAvailability.isEnabled,
               model.translationState.request?.sourceLanguage != nil
            {
                speechButton
            }

            Spacer(minLength: 0)

            translationActionsMenu
        }
        .padding(.horizontal, 6)
        .frame(height: Self.actionRowHeight)
        .overlay(alignment: .top) {
            Rectangle()
                .fill(JerreaderTheme.line)
                .frame(height: 0.5)
                .padding(.horizontal, JerreaderSpacing.md)
                .accessibilityHidden(true)
        }
    }

    private var speechButton: some View {
        Button {
            model.toggleTranslationSpeech()
        } label: {
            Image(
                systemName: model.isSpeakingTranslationSource
                    ? "stop.fill"
                    : "speaker.wave.2.fill"
            )
            .font(.footnote.weight(.semibold))
        }
        .buttonStyle(ReaderCardIconButtonStyle(isOn: model.isSpeakingTranslationSource))
        .accessibilityLabel(
            model.isSpeakingTranslationSource ? "停止朗读" : "朗读当前句段"
        )
    }

    private var favoriteButton: some View {
        Button {
            let wasFavorite = model.isCurrentTranslationFavorite
            model.toggleTranslationFavorite()
            if !wasFavorite, model.isCurrentTranslationFavorite {
                favoriteAddedCount += 1
            }
        } label: {
            JerreaderSymbol(
                name: model.isCurrentTranslationFavorite ? "star.fill" : "star",
                size: 18
            )
            .jerreaderBounce(trigger: favoriteAddedCount)
        }
        .buttonStyle(ReaderCardIconButtonStyle(isOn: model.isCurrentTranslationFavorite))
        .accessibilityLabel(
            model.isCurrentTranslationFavorite ? "取消收藏译文" : "收藏译文"
        )
        .sensoryFeedback(.success, trigger: favoriteAddedCount)
    }

    private var closeButton: some View {
        Button {
            model.dismissTranslation()
        } label: {
            JerreaderSymbol(name: "xmark", size: 15)
        }
        .buttonStyle(ReaderCardIconButtonStyle(circle: 28))
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

            Button {
                model.requestContextExplanation(lensMode: .comprehension)
            } label: {
                Label("AI 阅读透镜", systemImage: "sparkle.magnifyingglass")
            }

            Button { model.isShowingContextualLookup = true } label: {
                Label("语境查词 / 修改词语", systemImage: "character.book.closed")
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

            if model.translationState.request?.sourceLanguage == .japanese
                || model.translationState.request?.targetLanguage == .japanese {
                Toggle("日语注音", isOn: $japaneseFuriganaEnabled)
                    .accessibilityIdentifier("reader-furigana-toggle")
            }

            Button {
                model.retryTranslation()
            } label: {
                Label("重新翻译", systemImage: "arrow.clockwise")
            }
            .disabled(!model.canRetryTranslation)
        } label: {
            ReaderCardIconLabel(systemImage: "ellipsis")
        }
        .accessibilityLabel("更多翻译操作")
    }

    private var isFailure: Bool {
        if case .failure = model.translationState { return true }
        return false
    }

    /// The language direction, as the result reports it once it is in.
    private var eyebrowText: String {
        switch model.translationState {
        case .idle:
            return "译文"
        case let .loading(request):
            return directionText(from: request.sourceLanguage, to: request.targetLanguage)
        case let .success(_, result, _):
            return directionText(from: result.sourceLanguage, to: result.targetLanguage)
        case .failure:
            return "翻译未完成"
        }
    }

    private var eyebrowAccessibilityLabel: String {
        switch model.translationState {
        case let .loading(request):
            return spokenDirection(from: request.sourceLanguage, to: request.targetLanguage)
        case let .success(_, result, _):
            return spokenDirection(from: result.sourceLanguage, to: result.targetLanguage)
        case .idle, .failure:
            return eyebrowText
        }
    }

    private func directionText(from source: LanguageCode?, to target: LanguageCode) -> String {
        let from: String
        if let source {
            from = languageName(source)
        } else {
            from = "自动识别"
        }
        return "\(from) → \(languageName(target))"
    }

    private func spokenDirection(from source: LanguageCode?, to target: LanguageCode) -> String {
        guard let source else { return "自动识别语言，译为\(target.displayName)" }
        return "\(source.displayName)译为\(target.displayName)"
    }

    /// One character on the compact card, which can be 144pt wide.
    private func languageName(_ language: LanguageCode) -> String {
        guard usesCompactHeader else { return language.displayName }
        switch language {
        case .japanese: return "日"
        case .english: return "英"
        case .simplifiedChinese: return "中"
        }
    }

    private var maximumContentHeight: CGFloat {
        // The header (44) and the action row (44), or 12pt of breathing room
        // without one, are outside the ScrollView. Reserve both so the last
        // translated line never lands under them; a long card then fills
        // `maximumCardHeight` exactly.
        let reserved = Self.headerHeight + (showsActionRow ? Self.actionRowHeight : 12)
        return max(maximumCardHeight - reserved, 54)
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
        VStack(alignment: .leading, spacing: 10) {
            if japaneseFuriganaEnabled,
               let request = model.translationState.request,
               request.sourceLanguage == .japanese {
                Text("原文注音").font(.caption).foregroundStyle(.secondary)
                JapaneseFuriganaText(text: request.sourceText)
                    .accessibilityIdentifier("japanese-source-furigana")
                Divider()
            }
            if japaneseFuriganaEnabled,
               model.translationState.request?.targetLanguage == .japanese {
                JapaneseFuriganaText(text: text)
            } else {
                Text(text).font(.body).lineSpacing(5).textSelection(.enabled)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 16)
        // The eyebrow above already gives the text its top margin.
        .padding(.top, 2)
        .padding(.bottom, 14)
        .accessibilityLabel("译文：\(text)")
    }

    private var invalidTranslationContent: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("译文内容为空，请重新翻译。")
                .font(.subheadline)
            Button {
                model.retryTranslation()
            } label: {
                JerreaderLabel(title: "重新翻译", icon: "arrow.clockwise", size: 14)
            }
            .buttonStyle(JerreaderTintedButtonStyle(height: 36))
            .disabled(!model.canRetryTranslation)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 16)
        .padding(.top, 4)
        .padding(.bottom, 14)
    }

    private var statusContent: some View {
        translationContent
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 14)
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
                        JerreaderLabel(title: "重新翻译", icon: "arrow.clockwise", size: 14)
                    }
                    .buttonStyle(JerreaderTintedButtonStyle(height: 36))
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
    let model: EPUBReaderViewModel

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if activeLensMode != nil {
                    lensSelector
                    Divider()
                }
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
            }
            .background(JerreaderCanvasBackground())
            .navigationTitle(activeLensMode == nil ? "AI 句子结构分析" : "AI 阅读透镜")
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
            JerreaderLoadingGlyph(systemImage: "SPARKLES", size: 72)

            VStack(spacing: 6) {
                Text(request.lens.map { "正在生成“\($0.mode.title)”…" } ?? "正在分析句子结构与语法…")
                    .font(.headline)
                Text(request.lens == nil ? "前后文只用于判断省略、指代与词义" : "只依据选中文字和有限前后文，不剧透")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            focusedText(request.focusedText)
        }
        .frame(maxWidth: 560)
        .padding(28)
        .jerreaderPaperCard(padding: 0, radius: JerreaderRadius.card, hasShadow: true)
        .padding(24)
    }

    private func explanationResult(
        request: ReaderContextExplanationRequest,
        result: ContextExplanationResult
    ) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                focusedText(request.focusedText)
                // Only grammar answers belong in the grammar notebook.
                if request.lens == nil || request.lens?.mode == .grammar {
                    SaveGrammarNoteButton(draft: model.grammarNoteDraft(request: request, result: result))
                }

                VStack(alignment: .leading, spacing: 10) {
                    HStack(spacing: 11) {
                        JerreaderSymbol(name: "SPARKLES", size: 18)
                            .foregroundStyle(JerreaderTheme.onPrimaryAction)
                            .frame(width: 34, height: 34)
                            .background(
                                JerreaderTheme.primaryAction,
                                in: RoundedRectangle(cornerRadius: JerreaderRadius.small, style: .continuous)
                            )

                        VStack(alignment: .leading, spacing: 2) {
                            Text(request.lens?.mode.title ?? "语法与结构分析")
                                .font(.headline)
                            Text(request.lens?.mode.detail ?? "拆解句子成分、修饰关系与重点语法")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                    }

                    if let basis = model.readingLensBasis(for: request) {
                        Text(basis)
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }

                    Text(result.explanation)
                        .font(.body)
                        .lineSpacing(6)
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .jerreaderPaperCard(padding: 20, radius: JerreaderRadius.card)
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
            JerreaderSymbol(name: "WARNING", size: 32)
                .foregroundStyle(JerreaderTheme.vermilion)
                .frame(width: 72, height: 72)
                .background(
                    JerreaderTheme.vermilion.opacity(0.12),
                    in: RoundedRectangle(cornerRadius: JerreaderRadius.card, style: .continuous)
                )
            Text(request?.lens == nil ? "暂时无法完成语法分析" : "AI 阅读透镜暂不可用")
                .font(.title3.bold())
            Text(error.message)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            if request != nil, error.canRetry {
                Button {
                    model.retryContextExplanation()
                } label: {
                    JerreaderLabel(title: "重新生成", icon: "arrow.clockwise", size: 16)
                }
                .buttonStyle(JerreaderPrimaryButtonStyle(height: 44, fullWidth: false))
            }
        }
        .padding(28)
        .frame(maxWidth: 520)
        .jerreaderPaperCard(padding: 0, radius: JerreaderRadius.hero, hasShadow: true)
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

extension ReaderContextExplanationSheet {
    fileprivate var activeLensMode: AIReadingLensMode? {
        switch model.contextExplanationState {
        case .idle:
            return nil
        case let .loading(request), let .success(request, _):
            return request.lens?.mode
        case let .failure(request, _):
            return request?.lens?.mode
        }
    }

    /// Switching lens asks the same passage a different question.
    fileprivate var lensSelector: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(AIReadingLensMode.allCases) { mode in
                    let isActive = mode == activeLensMode
                    Button {
                        guard !isActive else { return }
                        model.requestContextExplanation(lensMode: mode)
                    } label: {
                        Text(mode.title)
                            .font(.subheadline.weight(.semibold))
                            .padding(.horizontal, 14)
                            .frame(minHeight: 36)
                            .foregroundStyle(isActive ? JerreaderTheme.onPrimaryAction : Color.primary.opacity(0.72))
                            .background(
                                isActive ? JerreaderTheme.primaryAction : JerreaderTheme.paper,
                                in: Capsule()
                            )
                            .overlay {
                                Capsule()
                                    .strokeBorder(JerreaderTheme.line, lineWidth: isActive ? 0 : 0.75)
                            }
                            .contentShape(Capsule())
                    }
                    .buttonStyle(JerreaderPressButtonStyle())
                    .accessibilityHint(mode.detail)
                    .accessibilityAddTraits(isActive ? .isSelected : [])
                    .accessibilityIdentifier("ai_reading_lens_\(mode.rawValue)")
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
        }
    }
}

private struct ReaderNavigationSheet: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let model: EPUBReaderViewModel
    var initialSection: ReaderNavigationSection = .contents
    let onNavigate: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var section: ReaderNavigationSection = .contents
    @State private var query = ""
    /// Edited on top of this sheet. The reader screen has its own editor
    /// sheet, but it cannot open while this one is up.
    @State private var editingDraft: ReaderAnnotationEditorDraft?

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                // The pill picker ticks its own selection haptic.
                JerreaderPillPicker(
                    options: ReaderNavigationSection.allCases.map { item in
                        JerreaderPillPicker<ReaderNavigationSection>.Option(
                            value: item,
                            title: item.title,
                            systemImage: item.systemImage
                        )
                    },
                    selection: $section
                )
                .accessibilityElement(children: .contain)
                .accessibilityLabel("阅读导航")
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
        .onAppear {
            section = initialSection
            query = model.lastSearchQuery
            model.resumeInterruptedSearch()
        }
        .onDisappear {
            model.cancelSearch()
        }
        .sheet(item: $editingDraft) { draft in
            ReaderAnnotationEditor(
                draft: draft,
                onSave: { note, color in
                    model.saveAnnotation(draft, noteText: note, color: color)
                },
                onDelete: {
                    guard let annotation = model.annotations.first(where: {
                        $0.id == draft.annotationID
                    }) else { return }
                    model.deleteAnnotation(annotation)
                }
            )
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
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
                            editingDraft = model.annotationDraft(for: annotation)
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
                            editingDraft = model.annotationDraft(for: annotation)
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
