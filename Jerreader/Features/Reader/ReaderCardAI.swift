import SwiftUI

// The AI action bar of the translation card (2.1.0 §4–§7): the chips under a
// translation, the selected chip's answer as it streams, the follow-up
// conversation, the source badges, and the sheets they open. The reader's
// view model asks the models (EPUBReaderViewModel, "Translation card AI");
// this file keeps what the card shows and draws it.
//
// Android keeps the same behaviour in androidApp/.../reader/ReaderAIActions.kt
// and shared/.../ui/TranslationAIBar.kt and TranslationAISheets.kt.

// MARK: - Values

/// Who answered, and what was sent to it (2.1.0 §6): under every AI result
/// and under a translation an AI model wrote.
struct ReaderAIAnswerSource: Identifiable, Equatable, Sendable {
    /// The model id, the plan's model title, the proxy's model or 「AI 代理」;
    /// for the system translator, 「Apple 翻译」.
    let modelName: String
    let isAIModel: Bool
    let sentSource: String
    let sentContext: String?

    var id: String {
        [modelName, sentSource, sentContext ?? ""].joined(separator: "\u{1F}")
    }

    /// The system translator sends nothing to a model, so it never says 含上下文.
    var usedContext: Bool {
        isAIModel && AIReadingActionPolicy.usesContext(sentContext)
    }

    /// 「模型名 · 含上下文」, 「模型名」, or the system translator's name.
    var badge: String {
        guard isAIModel else {
            return modelName.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return AIReadingActionPolicy.badge(model: modelName, usedContext: usedContext)
    }

    /// The context section of 「发送给模型的内容」; nil leaves the section out.
    var shownContext: String? {
        guard isAIModel,
              let context = sentContext?.trimmingCharacters(in: .whitespacesAndNewlines),
              !context.isEmpty
        else { return nil }
        return context
    }
}

/// The card's own words. The failure texts are the ones Android shows.
enum ReaderCardAICopy {
    static let stopped = "已停止生成。"
    static let quiet = "AI 超过 70 秒未返回，请重试或更换 AI 服务。"
    static let tooLong = "AI 回答超过 3 分钟仍未完成，请重试。"
    static let unavailable = "AI 暂时不可用，请稍后重试。"
    static let generating = "生成中…"
    static let stop = "停止"
    static let retry = "重试"
    static let saveNote = "存为笔记"
    static let expand = "展开看全文"
    static let saveGrammar = "保存到语法知识库"
    static let noteSaved = "已存为笔记"
    static let noteFailed = "笔记没有存好，请再试一次。"
    static let noteUnavailable = "这段选区不能存笔记"
    static let buttonsHidden = "可在阅读设置里重新打开"
    static let placeholder = "问问这句话…"
    static let followUpNoteTitle = "追问"
}

/// Where one answer is: being written, written, or stopped short.
enum ReaderCardAIPhase: Equatable, Sendable {
    case generating(String)
    case completed(String)
    /// `partial` is what had been written; only some failures can be retried.
    case failed(message: String, partial: String, canRetry: Bool)

    var text: String {
        switch self {
        case let .generating(text), let .completed(text):
            return text
        case let .failed(_, partial, _):
            return partial
        }
    }

    var isGenerating: Bool {
        switch self {
        case .generating:
            return true
        case .completed, .failed:
            return false
        }
    }

    var completedText: String? {
        switch self {
        case let .completed(text):
            return text
        case .generating, .failed:
            return nil
        }
    }

    /// 停止: the text written so far stays, with 重试.
    var stopped: ReaderCardAIPhase {
        switch self {
        case let .generating(text):
            return .failed(message: ReaderCardAICopy.stopped, partial: text, canRetry: true)
        case .completed, .failed:
            return self
        }
    }

    /// A failed question once another is asked: only the newest can be asked again.
    var withoutRetry: ReaderCardAIPhase {
        switch self {
        case let .failed(message, partial, _):
            return .failed(message: message, partial: partial, canRetry: false)
        case .generating, .completed:
            return self
        }
    }
}

/// A finished 结构, 读懂 or 指代 answer as the explanation page shows it,
/// for 展开看全文 and 保存到语法知识库.
struct ReaderCardAIExplanation: Equatable, Sendable {
    let request: ReaderContextExplanationRequest
    let result: ContextExplanationResult
}

/// The answer of one chip on this card.
struct ReaderCardAIAnswer: Equatable, Sendable {
    let requestID: UUID
    var phase: ReaderCardAIPhase
    let source: ReaderAIAnswerSource
    var explanation: ReaderCardAIExplanation?
}

/// One question of the follow-up conversation and its answer.
struct ReaderCardFollowUp: Identifiable, Equatable, Sendable {
    let id: UUID
    let question: String
    var requestID: UUID
    var phase: ReaderCardAIPhase
    var source: ReaderAIAnswerSource
}

/// 展开看全文 of an answer the explanation page does not show (直译).
struct ReaderCardAIFullText: Identifiable, Equatable {
    let id = UUID()
    let title: String
    let text: String
}

struct ReaderCardAIToast: Identifiable, Equatable {
    let id = UUID()
    let text: String
}

// MARK: - State

/// What the AI bar of the open translation card shows. One chip's answer is
/// open at a time; choosing another cancels the answer still being written,
/// and finished answers stay, per chip, until the card closes or shows
/// another sentence. Every partial or final text is written only while its
/// request is still the one in flight, so a late piece of an old answer never
/// lands on a new card.
@MainActor
@Observable
final class ReaderCardAIModel {
    /// The sentence and translation the answers are about (see
    /// `EPUBReaderViewModel.cardAIKey`); the bar shows nothing of another card.
    private(set) var cardKey: String?
    private(set) var selectedAction: AIReadingAction?
    private(set) var answers: [AIReadingAction: ReaderCardAIAnswer] = [:]
    private(set) var followUps: [ReaderCardFollowUp] = []
    private(set) var toast: ReaderCardAIToast?
    /// The follow-up field has the keyboard: the card then sits above it.
    var isComposing = false
    var isShowingSetup = false
    var presentedSource: ReaderAIAnswerSource?
    var presentedFullText: ReaderCardAIFullText?
    var presentedGrammarDraft: GrammarNoteDraft?

    /// The chip that opened the setup sheet; it runs once a model is set up.
    @ObservationIgnored private(set) var pendingAction: AIReadingAction?
    @ObservationIgnored private var activeRequestID: UUID?
    @ObservationIgnored private var activeTask: Task<Void, Never>?
    @ObservationIgnored private var activeWatchdog: Task<Void, Never>?
    @ObservationIgnored private var activeThrottle: AIPartialTextThrottle?
    @ObservationIgnored private var toastTask: Task<Void, Never>?

    var selectedAnswer: ReaderCardAIAnswer? {
        selectedAction.flatMap { answers[$0] }
    }

    /// Whether an answer or a question is being written now.
    var isGenerating: Bool {
        answers.values.contains { $0.phase.isGenerating }
            || followUps.contains { $0.phase.isGenerating }
    }

    func hasCompletedAnswer(for action: AIReadingAction) -> Bool {
        answers[action]?.phase.completedText != nil
    }

    func isShowing(card key: String?) -> Bool {
        key != nil && cardKey == key
    }

    /// The answers belong to `key` from now on; those of another card go.
    func adopt(_ key: String) {
        guard cardKey != key else { return }
        reset()
        cardKey = key
    }

    /// The card closed or shows another sentence: its answers go with it.
    /// A toast stays until its time is up.
    func reset() {
        cancelActive()
        cardKey = nil
        selectedAction = nil
        if !answers.isEmpty { answers = [:] }
        if !followUps.isEmpty { followUps = [] }
        isComposing = false
        isShowingSetup = false
        presentedSource = nil
        presentedFullText = nil
        presentedGrammarDraft = nil
        pendingAction = nil
    }

    /// An AI chip while no model is set up: 先接入一个 AI 模型.
    func requestSetup(for action: AIReadingAction) {
        pendingAction = action
        isShowingSetup = true
    }

    func takePendingAction() -> AIReadingAction? {
        defer { pendingAction = nil }
        return pendingAction
    }

    func clearPendingAction() {
        pendingAction = nil
    }

    /// Opens the result area of `action`; an unfinished answer of another
    /// chip is dropped, so coming back asks again.
    func open(_ action: AIReadingAction) {
        interrupt()
        selectedAction = action
    }

    /// Closes the result area.
    func collapse() {
        interrupt()
        selectedAction = nil
        isComposing = false
    }

    /// The answer of `action` is being written by `requestID`.
    func begin(_ action: AIReadingAction, requestID: UUID, source: ReaderAIAnswerSource) {
        cancelActive()
        activeRequestID = requestID
        answers[action] = ReaderCardAIAnswer(
            requestID: requestID,
            phase: .generating(""),
            source: source,
            explanation: nil
        )
    }

    /// Asks `question` after the questions already answered.
    func beginFollowUp(question: String, requestID: UUID, source: ReaderAIAnswerSource) {
        interrupt()
        selectedAction = .followUp
        var entries = followUps.map { entry -> ReaderCardFollowUp in
            var entry = entry
            entry.phase = entry.phase.withoutRetry
            return entry
        }
        entries.append(
            ReaderCardFollowUp(
                id: UUID(),
                question: question,
                requestID: requestID,
                phase: .generating(""),
                source: source
            )
        )
        followUps = entries
        activeRequestID = requestID
    }

    /// 重试 of the newest question; false unless it failed.
    @discardableResult
    func restartLastFollowUp(requestID: UUID, source: ReaderAIAnswerSource) -> Bool {
        guard let last = followUps.last, case .failed = last.phase else { return false }
        interrupt()
        var entry = last
        entry.requestID = requestID
        entry.phase = .generating("")
        entry.source = source
        followUps[followUps.count - 1] = entry
        activeRequestID = requestID
        return true
    }

    /// The conversation the newest question goes with: the answered
    /// questions before it, then the question itself.
    func followUpTurns() -> [AIFollowUpTurn] {
        guard let last = followUps.last else { return [] }
        let earlier = followUps.dropLast().compactMap { entry -> AIFollowUpTurn? in
            entry.phase.completedText.map { AIFollowUpTurn(question: entry.question, answer: $0) }
        }
        return earlier + [AIFollowUpTurn(question: last.question)]
    }

    /// Keeps the work of the request in flight, so 停止, another chip or a
    /// closed card can cancel it. Work of a request already replaced is
    /// cancelled at once.
    func attach(
        requestID: UUID,
        task: Task<Void, Never>,
        watchdog: Task<Void, Never>,
        throttle: AIPartialTextThrottle
    ) {
        guard activeRequestID == requestID else {
            task.cancel()
            watchdog.cancel()
            throttle.cancel()
            return
        }
        activeTask = task
        activeWatchdog = watchdog
        activeThrottle = throttle
    }

    func isActive(_ requestID: UUID) -> Bool {
        activeRequestID == requestID
    }

    /// The text so far of the request in flight.
    func show(partial text: String, for requestID: UUID) {
        guard activeRequestID == requestID else { return }
        update(requestID) { phase in
            phase.isGenerating ? .generating(text) : phase
        }
    }

    func complete(requestID: UUID, text: String, explanation: ReaderCardAIExplanation? = nil) {
        guard activeRequestID == requestID else { return }
        cancelActive()
        update(requestID, explanation: explanation) { _ in .completed(text) }
    }

    func fail(requestID: UUID, message: String, canRetry: Bool) {
        guard activeRequestID == requestID else { return }
        cancelActive()
        update(requestID) { phase in
            .failed(message: message, partial: phase.text, canRetry: canRetry)
        }
    }

    /// 停止: what was written stays, and can be asked again.
    func stop() {
        cancelActive()
        if answers.values.contains(where: { $0.phase.isGenerating }) {
            answers = answers.mapValues { answer in
                var answer = answer
                answer.phase = answer.phase.stopped
                return answer
            }
        }
        stopFollowUps()
    }

    func showToast(_ text: String, duration: Duration = .seconds(2)) {
        toastTask?.cancel()
        let toast = ReaderCardAIToast(text: text)
        self.toast = toast
        toastTask = Task { @MainActor [weak self] in
            try? await Task.sleep(for: duration)
            guard !Task.isCancelled, let self, self.toast?.id == toast.id else { return }
            self.toast = nil
        }
    }

    /// Another chip or question takes over: an unfinished chip answer is
    /// dropped, an unfinished question stays as stopped.
    private func interrupt() {
        cancelActive()
        if answers.values.contains(where: { $0.phase.isGenerating }) {
            answers = answers.filter { !$0.value.phase.isGenerating }
        }
        stopFollowUps()
    }

    private func stopFollowUps() {
        guard followUps.contains(where: { $0.phase.isGenerating }) else { return }
        followUps = followUps.map { entry in
            var entry = entry
            entry.phase = entry.phase.stopped
            return entry
        }
    }

    private func cancelActive() {
        activeRequestID = nil
        activeTask?.cancel()
        activeTask = nil
        activeWatchdog?.cancel()
        activeWatchdog = nil
        activeThrottle?.cancel()
        activeThrottle = nil
    }

    private func update(
        _ requestID: UUID,
        explanation: ReaderCardAIExplanation? = nil,
        _ change: (ReaderCardAIPhase) -> ReaderCardAIPhase
    ) {
        if let action = answers.first(where: { $0.value.requestID == requestID })?.key,
           var answer = answers[action]
        {
            answer.phase = change(answer.phase)
            if let explanation {
                answer.explanation = explanation
            }
            answers[action] = answer
            return
        }
        if let index = followUps.firstIndex(where: { $0.requestID == requestID }) {
            followUps[index].phase = change(followUps[index].phase)
        }
    }
}

// MARK: - Bar

/// Chips in rows that wrap, so a narrow card (the 144–220 pt side card of
/// vertical text) never scrolls them sideways. A chip wider than the card
/// gets the card's width.
struct ReaderCardFlowLayout: Layout {
    var spacing: CGFloat = 6
    var lineSpacing: CGFloat = 6

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let rows = rows(of: subviews, width: proposal.width ?? .infinity)
        let height = rows.reduce(0) { $0 + $1.height }
            + lineSpacing * CGFloat(max(rows.count - 1, 0))
        let width = rows.map(\.width).max() ?? 0
        return CGSize(width: width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        for row in rows(of: subviews, width: bounds.width) {
            var x = bounds.minX
            for item in row.items {
                subviews[item.index].place(
                    at: CGPoint(x: x, y: y + (row.height - item.size.height) / 2),
                    anchor: .topLeading,
                    proposal: ProposedViewSize(item.size)
                )
                x += item.size.width + spacing
            }
            y += row.height + lineSpacing
        }
    }

    private struct Item {
        let index: Int
        let size: CGSize
    }

    private struct Row {
        var items: [Item] = []
        var width: CGFloat = 0
        var height: CGFloat = 0
    }

    private func rows(of subviews: Subviews, width: CGFloat) -> [Row] {
        var rows: [Row] = []
        var row = Row()
        for index in subviews.indices {
            var size = subviews[index].sizeThatFits(.unspecified)
            if size.width > width {
                size = subviews[index].sizeThatFits(ProposedViewSize(width: width, height: nil))
                size.width = min(size.width, width)
            }
            if !row.items.isEmpty, row.width + spacing + size.width > width {
                rows.append(row)
                row = Row()
            }
            row.width = row.items.isEmpty ? size.width : row.width + spacing + size.width
            row.height = max(row.height, size.height)
            row.items.append(Item(index: index, size: size))
        }
        if !row.items.isEmpty {
            rows.append(row)
        }
        return rows
    }
}

/// A chip: paper and a hairline, or the accent wash and accent text when
/// selected. 30 pt high, 44 pt to touch.
struct ReaderCardAIChip: View {
    let title: String
    let isSelected: Bool
    /// Presets can take two lines on a narrow card; the action chips keep one.
    var allowsWrapping = false
    var identifier: String?
    let action: () -> Void

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: JerreaderRadius.small, style: .continuous)
        Button(action: action) {
            Text(title)
                .font(.system(size: 13, weight: .medium))
                .lineLimit(allowsWrapping ? 2 : 1)
                .multilineTextAlignment(.leading)
                .fixedSize(horizontal: false, vertical: true)
                .foregroundStyle(isSelected ? JerreaderTheme.accent : Color.primary)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .frame(minHeight: 30)
                .background(isSelected ? JerreaderTheme.accentFill : JerreaderTheme.paper, in: shape)
                .overlay {
                    shape.strokeBorder(
                        isSelected ? JerreaderTheme.accentFill : JerreaderTheme.line,
                        lineWidth: 1
                    )
                }
                .contentShape(.interaction, Rectangle().inset(by: -7))
                .animation(JerreaderMotion.selection, value: isSelected)
        }
        .buttonStyle(JerreaderPressButtonStyle(pressedScale: 0.95))
        .accessibilityAddTraits(isSelected ? .isSelected : [])
        .jerreaderIdentifier(identifier)
    }
}

/// The chips between the translation and the action row, and the selected
/// chip's answer under them.
struct ReaderCardAIBar: View {
    let model: EPUBReaderViewModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let cardAI = model.cardAI
        let isCurrent = cardAI.isShowing(card: model.cardAIKey)
        let selected = isCurrent ? cardAI.selectedAction : nil
        VStack(alignment: .leading, spacing: 10) {
            ReaderCardFlowLayout(spacing: 6, lineSpacing: 6) {
                ForEach(model.cardAIActions) { action in
                    ReaderCardAIChip(
                        title: action.title,
                        isSelected: selected == action,
                        identifier: "card-ai-chip-\(action.rawValue)"
                    ) {
                        model.selectCardAIAction(action)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            if let selected, let title = selected.resultTitle {
                VStack(alignment: .leading, spacing: 8) {
                    JerreaderEyebrow(text: title)
                        .accessibilityAddTraits(.isHeader)
                    if selected == .followUp {
                        ReaderCardFollowUpView(model: model)
                    } else if let answer = cardAI.answers[selected] {
                        ReaderCardAIAnswerView(
                            model: model,
                            action: selected,
                            title: title,
                            answer: answer
                        )
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .id(selected)
                .jerreaderReveal(order: 0, offset: 6)
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("card-ai-result")
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .animation(
            JerreaderMotion.choose(JerreaderMotion.stateChange, reduceMotion: reduceMotion),
            value: selected
        )
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("card-ai-bar")
    }
}

/// One chip's answer: its text, then 生成中… and 停止, the error and 重试,
/// or the buttons and the source badge once it is done.
private struct ReaderCardAIAnswerView: View {
    let model: EPUBReaderViewModel
    let action: AIReadingAction
    let title: String
    let answer: ReaderCardAIAnswer

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if !answer.phase.text.isEmpty {
                ReaderCardAIAnswerText(action: action, text: answer.phase.text)
            }
            switch answer.phase {
            case .generating:
                ReaderCardAIGeneratingRow { model.stopCardAI() }
            case let .failed(message, _, canRetry):
                ReaderCardAIFailedRow(message: message, canRetry: canRetry) {
                    model.retryCardAI()
                }
            case let .completed(text):
                ReaderCardAIFooter(
                    source: answer.source,
                    noteUnavailableReason: model.cardAINoteUnavailableReason,
                    onShowSource: { model.cardAI.presentedSource = $0 }
                ) {
                    ReaderCardAIFooterButton(
                        title: ReaderCardAICopy.saveNote,
                        systemImage: "NOTE",
                        isEnabled: model.cardAINoteUnavailableReason == nil,
                        identifier: "card-ai-save-note"
                    ) {
                        model.saveCardAIResultAsNote(title: title, body: text)
                    }
                    ReaderCardAIFooterButton(
                        title: ReaderCardAICopy.expand,
                        systemImage: "DOC_PLAIN",
                        identifier: "card-ai-expand"
                    ) {
                        model.showCardAIFullText(for: action)
                    }
                    if action == .structure, let explanation = answer.explanation {
                        ReaderCardAIFooterButton(
                            title: ReaderCardAICopy.saveGrammar,
                            systemImage: "BOOKMARK_OUTLINE",
                            identifier: "card-ai-save-grammar"
                        ) {
                            model.cardAI.presentedGrammarDraft = model.grammarNoteDraft(
                                request: explanation.request,
                                result: explanation.result
                            )
                        }
                    }
                }
            }
        }
    }
}

/// The presets while the conversation is empty, then each question and its
/// answer, then the field.
private struct ReaderCardFollowUpView: View {
    let model: EPUBReaderViewModel

    var body: some View {
        let cardAI = model.cardAI
        VStack(alignment: .leading, spacing: 10) {
            if cardAI.followUps.isEmpty {
                ReaderCardFlowLayout(spacing: 6, lineSpacing: 6) {
                    ForEach(Array(model.cardAIPresets.enumerated()), id: \.offset) { index, preset in
                        ReaderCardAIChip(
                            title: preset,
                            isSelected: false,
                            allowsWrapping: true,
                            identifier: "card-ai-preset-\(index)"
                        ) {
                            model.sendCardAIFollowUp(preset)
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }

            ForEach(Array(cardAI.followUps.enumerated()), id: \.element.id) { index, entry in
                if index > 0 {
                    Rectangle()
                        .fill(JerreaderTheme.line)
                        .frame(height: 0.5)
                        .accessibilityHidden(true)
                }
                ReaderCardFollowUpEntryView(model: model, entry: entry)
            }

            ReaderCardFollowUpField(model: model)
        }
    }
}

private struct ReaderCardFollowUpEntryView: View {
    let model: EPUBReaderViewModel
    let entry: ReaderCardFollowUp

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(entry.question)
                .font(.subheadline.weight(.semibold))
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
            if !entry.phase.text.isEmpty {
                ReaderCardAIAnswerText(action: .followUp, text: entry.phase.text)
            }
            switch entry.phase {
            case .generating:
                ReaderCardAIGeneratingRow { model.stopCardAI() }
            case let .failed(message, _, canRetry):
                ReaderCardAIFailedRow(message: message, canRetry: canRetry) {
                    model.retryCardAI()
                }
            case let .completed(text):
                ReaderCardAIFooter(
                    source: entry.source,
                    noteUnavailableReason: model.cardAINoteUnavailableReason,
                    onShowSource: { model.cardAI.presentedSource = $0 }
                ) {
                    ReaderCardAIFooterButton(
                        title: ReaderCardAICopy.saveNote,
                        systemImage: "NOTE",
                        isEnabled: model.cardAINoteUnavailableReason == nil,
                        identifier: "card-ai-save-follow-up"
                    ) {
                        model.saveCardAIResultAsNote(
                            title: ReaderCardAICopy.followUpNoteTitle,
                            body: AIReadingActionPolicy.followUpNote(
                                question: entry.question,
                                answer: text
                            )
                        )
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// One line, 问问这句话…, and a round send button. Sending puts the
/// keyboard away; nothing is sent while an answer is being written.
private struct ReaderCardFollowUpField: View {
    let model: EPUBReaderViewModel
    @State private var text = ""
    @FocusState private var isFocused: Bool

    private var question: String {
        text.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var canSend: Bool {
        !model.cardAI.isGenerating && !question.isEmpty
    }

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: JerreaderRadius.small, style: .continuous)
        HStack(spacing: 4) {
            TextField(ReaderCardAICopy.placeholder, text: $text)
                .font(.subheadline)
                .focused($isFocused)
                .submitLabel(.send)
                .onSubmit(send)
                .padding(.vertical, 9)
                .accessibilityIdentifier("追问输入框")

            Button(action: send) {
                Image(systemName: "arrow.up")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(JerreaderTheme.onPrimaryAction)
                    .frame(width: 28, height: 28)
                    .background(
                        JerreaderTheme.accent.opacity(canSend ? 1 : 0.3),
                        in: Circle()
                    )
                    .frame(width: 36, height: 36)
                    .contentShape(Rectangle())
            }
            .buttonStyle(JerreaderPressButtonStyle(pressedScale: 0.9))
            .disabled(!canSend)
            .accessibilityLabel("发送追问")
            .accessibilityIdentifier("发送追问")
        }
        .padding(.leading, 12)
        .padding(.trailing, 4)
        .frame(minHeight: 40)
        .background(JerreaderTheme.paper, in: shape)
        .overlay {
            shape.strokeBorder(JerreaderTheme.line, lineWidth: 1)
                .allowsHitTesting(false)
        }
        .onChange(of: isFocused) { _, focused in
            model.cardAI.isComposing = focused
        }
        .onDisappear {
            // The card can close with the keyboard up.
            model.cardAI.isComposing = false
        }
    }

    private func send() {
        guard canSend else { return }
        model.sendCardAIFollowUp(question)
        text = ""
        isFocused = false
    }
}

/// The answer in body text; a 直译 answer that kept its format shows its
/// two lines apart, and one that did not shows as it came.
struct ReaderCardAIAnswerText: View {
    let action: AIReadingAction
    let text: String

    var body: some View {
        Group {
            if action == .literal, let literal = AIReadingActionPolicy.literalAnswer(text) {
                VStack(alignment: .leading, spacing: 8) {
                    labelled("直译", literal.literal)
                    labelled("差别", literal.difference)
                }
            } else {
                answer(text)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityIdentifier("card-ai-result-text")
    }

    private func labelled(_ label: String, _ text: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
            answer(text)
        }
        .accessibilityElement(children: .combine)
    }

    private func answer(_ text: String) -> some View {
        Text(text)
            .font(.body)
            .lineSpacing(5)
            .textSelection(.enabled)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// 生成中… on the left and 停止 on the right.
private struct ReaderCardAIGeneratingRow: View {
    let onStop: () -> Void

    var body: some View {
        HStack(spacing: JerreaderSpacing.xs) {
            Text(ReaderCardAICopy.generating)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityIdentifier("card-ai-generating")
            ReaderCardAIFooterButton(
                title: ReaderCardAICopy.stop,
                identifier: "card-ai-stop",
                action: onStop
            )
        }
    }
}

private struct ReaderCardAIFailedRow: View {
    let message: String
    let canRetry: Bool
    let onRetry: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(message)
                .font(.subheadline)
                .foregroundStyle(JerreaderTheme.vermilion)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityIdentifier("card-ai-error")
            if canRetry {
                ReaderCardAIFooterButton(
                    title: ReaderCardAICopy.retry,
                    systemImage: "RESTORE",
                    identifier: "card-ai-retry",
                    action: onRetry
                )
            }
        }
    }
}

/// The buttons on the left and the source badge on the right, or the badge
/// on a line of its own when they do not fit beside each other. A greyed
/// 存为笔记 explains itself in a caption under them.
private struct ReaderCardAIFooter<Buttons: View>: View {
    let source: ReaderAIAnswerSource
    let noteUnavailableReason: String?
    let onShowSource: (ReaderAIAnswerSource) -> Void
    @ViewBuilder let buttons: () -> Buttons

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 8) {
                    HStack(spacing: 4) { buttons() }
                    Spacer(minLength: 0)
                    badge
                }
                VStack(alignment: .leading, spacing: 2) {
                    ReaderCardFlowLayout(spacing: 4, lineSpacing: 2) { buttons() }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    badge
                        .frame(maxWidth: .infinity, alignment: .trailing)
                }
            }
            if let noteUnavailableReason {
                Text(noteUnavailableReason)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("card-ai-note-unavailable")
            }
        }
    }

    private var badge: some View {
        ReaderAISourceBadge(source: source, onShowSource: onShowSource)
    }
}

/// A small accent text action of a result: 存为笔记, 展开看全文, 停止, 重试.
private struct ReaderCardAIFooterButton: View {
    let title: String
    var systemImage: String?
    var isEnabled = true
    var identifier: String?
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 4) {
                if let systemImage {
                    JerreaderSymbol(name: systemImage, size: 14)
                }
                Text(title)
                    .font(.system(size: 13, weight: .semibold))
                    .lineLimit(1)
            }
            .foregroundStyle(isEnabled ? JerreaderTheme.accent : Color.secondary.opacity(0.6))
            .padding(.horizontal, 4)
            .frame(height: 30)
            .contentShape(.interaction, Rectangle().inset(by: -7))
        }
        .buttonStyle(JerreaderPressButtonStyle(pressedScale: 0.95))
        .disabled(!isEnabled)
        .jerreaderIdentifier(identifier)
    }
}

/// 「模型名 · 含上下文」 under an answer or an AI translation. An AI model's
/// badge opens 「发送给模型的内容」; the system translator sent nothing to a
/// model, so its badge is only text.
struct ReaderAISourceBadge: View {
    let source: ReaderAIAnswerSource
    var onShowSource: ((ReaderAIAnswerSource) -> Void)?
    var identifier = "card-ai-source-badge"

    private var opens: Bool {
        onShowSource != nil && source.isAIModel
    }

    var body: some View {
        if opens {
            Button {
                onShowSource?(source)
            } label: {
                content
                    .contentShape(.interaction, Rectangle().inset(by: -10))
            }
            .buttonStyle(JerreaderPressButtonStyle(pressedScale: 0.96))
            .accessibilityLabel("来源：\(source.badge)")
            .accessibilityHint("查看发送给模型的内容")
            .accessibilityIdentifier(identifier)
        } else {
            content
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("来源：\(source.badge)")
                .accessibilityIdentifier(identifier)
        }
    }

    private var content: some View {
        HStack(spacing: 3) {
            if source.isAIModel {
                JerreaderSymbol(name: "SPARKLES", size: 11)
            }
            Text(source.badge)
                .font(.caption)
                .lineLimit(1)
                .truncationMode(.tail)
            if opens {
                JerreaderSymbol(name: "CHEVRON_FORWARD", size: 10)
            }
        }
        .foregroundStyle(.secondary)
        .frame(minHeight: 24)
    }
}

// MARK: - Sheets and toast

/// The setup, sent-content, full-text and grammar-note sheets of the AI bar.
struct ReaderCardAISheets: ViewModifier {
    let model: EPUBReaderViewModel
    @Bindable var cardAI: ReaderCardAIModel

    func body(content: Content) -> some View {
        content
            .sheet(
                isPresented: $cardAI.isShowingSetup,
                onDismiss: { model.cardAISetupDismissed() }
            ) {
                ReaderAISetupSheet(model: model)
                    .presentationDetents([.medium, .large])
                    .presentationDragIndicator(.visible)
            }
            .sheet(item: $cardAI.presentedSource) { source in
                ReaderAISentContentSheet(source: source)
                    .presentationDetents([.medium, .large])
                    .presentationDragIndicator(.visible)
            }
            .sheet(item: $cardAI.presentedFullText) { content in
                ReaderAIFullTextSheet(content: content)
                    .presentationDetents([.medium, .large])
                    .presentationDragIndicator(.visible)
            }
            .sheet(item: $cardAI.presentedGrammarDraft) { draft in
                GrammarNoteEditor(draft: draft)
            }
    }
}

/// 先接入一个 AI 模型 (2.1.0 §7): what an AI chip opens while no AI model is
/// set up. 查词 never comes here, because it needs no model.
private struct ReaderAISetupSheet: View {
    let model: EPUBReaderViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var isSigningIn = false
    @State private var message: String?
    @State private var signInTask: Task<Void, Never>?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button(action: signIn) {
                        HStack(spacing: JerreaderSpacing.sm) {
                            Text("用 ChatGPT 账号登录")
                                .foregroundStyle(Color.primary)
                            Spacer(minLength: 0)
                            if isSigningIn {
                                ProgressView()
                                    .controlSize(.small)
                                    .tint(JerreaderTheme.accent)
                            } else {
                                chevron
                            }
                        }
                        .contentShape(Rectangle())
                    }
                    .disabled(isSigningIn)
                    .accessibilityIdentifier("ai-setup-sign-in")

                    NavigationLink {
                        AIModelsView(store: model.translationSettings)
                    } label: {
                        Text("填 API Key")
                    }
                    .disabled(isSigningIn)
                    .accessibilityIdentifier("ai-setup-api-key")

                    Button {
                        model.hideCardAIBar()
                    } label: {
                        Text("不再显示这些按钮")
                            .foregroundStyle(Color.primary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .contentShape(Rectangle())
                    }
                    .disabled(isSigningIn)
                    .accessibilityIdentifier("ai-setup-hide-buttons")
                } footer: {
                    if let message {
                        Text(message)
                            .foregroundStyle(JerreaderTheme.vermilion)
                            .accessibilityIdentifier("ai-setup-error")
                    }
                }
            }
            .navigationTitle("先接入一个 AI 模型")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
            }
        }
        .tint(JerreaderTheme.accent)
        .onDisappear {
            signInTask?.cancel()
        }
    }

    private var chevron: some View {
        JerreaderSymbol(name: "CHEVRON_FORWARD", size: 14)
            .foregroundStyle(.tertiary)
    }

    /// The plan's own sign-in; its notice shows over the reader afterwards.
    private func signIn() {
        guard !isSigningIn else { return }
        message = nil
        isSigningIn = true
        signInTask = Task { @MainActor in
            defer { isSigningIn = false }
            do {
                let notice = try await ChatGPTPlanActions.signIn(store: model.translationSettings)
                model.cardAI.showToast(notice, duration: .seconds(4))
                dismiss()
            } catch is CancellationError {
            } catch ChatGPTPlanError.cancelled {
            } catch {
                message = AIProviderRequests.message(for: error)
            }
        }
    }
}

/// 发送给模型的内容 (2.1.0 §6), from a source badge: the model, the passage
/// and, when one went along, the context.
private struct ReaderAISentContentSheet: View {
    let source: ReaderAIAnswerSource
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: JerreaderSpacing.lg) {
                    ReaderAISheetSection(
                        title: "模型",
                        text: modelText
                    )
                    ReaderAISheetSection(
                        title: "原文",
                        text: source.sentSource.trimmingCharacters(in: .whitespacesAndNewlines)
                    )
                    if let context = source.shownContext {
                        ReaderAISheetSection(title: "上下文", text: context)
                    }
                }
                .padding(.horizontal, JerreaderSpacing.lg)
                .padding(.vertical, JerreaderSpacing.md)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .background(JerreaderCanvasBackground())
            .navigationTitle("发送给模型的内容")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
        }
        .tint(JerreaderTheme.accent)
        .accessibilityIdentifier("ai-source-sheet")
    }

    private var modelText: String {
        let name = source.modelName.trimmingCharacters(in: .whitespacesAndNewlines)
        return name.isEmpty ? source.badge : name
    }
}

/// 展开看全文 (2.1.0 §4) of a 直译 answer: the whole text under its result title.
private struct ReaderAIFullTextSheet: View {
    let content: ReaderCardAIFullText
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                Text(content.text.trimmingCharacters(in: .whitespacesAndNewlines))
                    .font(.body)
                    .lineSpacing(5)
                    .textSelection(.enabled)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .jerreaderPaperCard(padding: JerreaderSpacing.md)
                    .padding(.horizontal, JerreaderSpacing.lg)
                    .padding(.vertical, JerreaderSpacing.md)
            }
            .background(JerreaderCanvasBackground())
            .navigationTitle(content.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
        }
        .tint(JerreaderTheme.accent)
        .accessibilityIdentifier("ai-full-text-sheet")
    }
}

/// An eyebrow over selectable text on paper.
private struct ReaderAISheetSection: View {
    let title: String
    let text: String

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            JerreaderEyebrow(text: title)
                .padding(.horizontal, JerreaderSpacing.xxs)
                .accessibilityAddTraits(.isHeader)
            Text(text)
                .font(.subheadline)
                .textSelection(.enabled)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
                .jerreaderPaperCard(padding: JerreaderSpacing.md)
        }
    }
}

/// 已存为笔记 and the like, at the top of the reader for a moment.
struct ReaderCardAIToastView: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.caption.weight(.semibold))
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 16)
            .padding(.vertical, 11)
            .frame(minHeight: 42)
            .background(JerreaderTheme.paper, in: Capsule())
            .overlay {
                Capsule()
                    .strokeBorder(JerreaderTheme.line, lineWidth: 0.75)
            }
            .jerreaderElevation(.raised, cornerRadius: 21)
            .padding(.horizontal, JerreaderSpacing.xl)
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("card-ai-toast")
            .onAppear {
                AccessibilityNotification.Announcement(text).post()
            }
    }
}
