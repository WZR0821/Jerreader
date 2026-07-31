import SwiftData
import SwiftUI

/// Free-form translation between the three languages the app targets.
///
/// The reader translates what you tap inside a book; this is the counterpart
/// for text you type or paste — a sentence from a paper dictionary, a message,
/// a caption. Direction is explicit here (rather than inferred from the book)
/// because the input has no surrounding document to infer it from.
struct TranslateToolView: View {
    @Environment(\.modelContext) private var modelContext
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ObservedObject var translationSettings: TranslationSettingsStore

    @State private var sourceChoice: TranslationSourceLanguageChoice = .automatic
    @State private var targetLanguage: LanguageCode = .simplifiedChinese
    @State private var inputText = ""
    @State private var result: TranslationResult?
    @State private var errorMessage: String?
    @State private var isTranslating = false
    @State private var translateTask: Task<Void, Never>?
    @State private var didCopy = false
    @State private var didSaveFavorite = false
    @FocusState private var isInputFocused: Bool

    /// Matches the per-request ceiling every translation service enforces.
    static let maximumCharacterCount = 2_000

    /// Favourites need a book identity; translations typed here belong to no
    /// book, so they share one stable namespace instead of a random UUID that
    /// would defeat de-duplication.
    private static let standaloneBookID = UUID(
        uuidString: "00000000-0000-0000-0000-00000000A17E"
    ) ?? UUID()

    private var trimmedInput: String {
        inputText.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var canTranslate: Bool {
        !trimmedInput.isEmpty && !isTranslating
            && trimmedInput.count <= Self.maximumCharacterCount
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                directionCard
                inputCard
                if let result {
                    resultCard(result)
                }
                if let errorMessage {
                    errorCard(errorMessage)
                }
            }
            .padding(.horizontal, JerreaderTheme.pagePadding)
            .padding(.vertical, 16)
            .frame(maxWidth: 680)
            .frame(maxWidth: .infinity)
        }
        .scrollDismissesKeyboard(.interactively)
        .onDisappear { translateTask?.cancel() }
    }

    // MARK: - Direction

    private var directionCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                languageMenu(
                    title: sourceChoice.title,
                    caption: "原文"
                ) {
                    ForEach(TranslationSourceLanguageChoice.allCases) { choice in
                        Button(choice.title) { sourceChoice = choice }
                    }
                }

                Button {
                    swapLanguages()
                } label: {
                    Image(systemName: "arrow.left.arrow.right")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(
                            canSwapLanguages ? JerreaderTheme.accent : Color.secondary
                        )
                        .frame(width: 38, height: 38)
                        .background(JerreaderTheme.accentFill, in: Circle())
                }
                .buttonStyle(JerreaderPressButtonStyle(pressedScale: 0.9))
                .disabled(!canSwapLanguages)
                .accessibilityLabel("互换原文与译文语言")

                languageMenu(
                    title: targetLanguage.displayName,
                    caption: "译文"
                ) {
                    ForEach(LanguageCode.allCases, id: \.self) { language in
                        Button(language.displayName) { targetLanguage = language }
                    }
                }
            }

            Text(providerDescription)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .padding(14)
        .background(JerreaderTheme.paper, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(JerreaderTheme.line, lineWidth: 0.75)
        }
    }

    private func languageMenu(
        title: String,
        caption: String,
        @ViewBuilder content: () -> some View
    ) -> some View {
        Menu {
            content()
        } label: {
            VStack(alignment: .leading, spacing: 2) {
                Text(caption)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                HStack(spacing: 4) {
                    Text(title)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                    Image(systemName: "chevron.down")
                        .font(.caption2.weight(.bold))
                        .foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(
                JerreaderTheme.accentFill.opacity(0.55),
                in: RoundedRectangle(cornerRadius: 12, style: .continuous)
            )
        }
        .buttonStyle(.plain)
    }

    private var canSwapLanguages: Bool {
        sourceChoice.languageCode != nil
    }

    private func swapLanguages() {
        guard let source = sourceChoice.languageCode else { return }
        withAnimation(reduceMotion ? nil : JerreaderMotion.stateChange) {
            let newSource = TranslationSourceLanguageChoice.allCases.first {
                $0.languageCode == targetLanguage
            } ?? .automatic
            sourceChoice = newSource
            targetLanguage = source
            if let result {
                inputText = result.translatedText
                self.result = nil
            }
        }
    }

    // MARK: - Input

    private var inputCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            TextField(
                "输入或粘贴要翻译的文字",
                text: $inputText,
                axis: .vertical
            )
            .lineLimit(4 ... 10)
            .focused($isInputFocused)
            .font(.body)

            HStack(spacing: 10) {
                Text("\(trimmedInput.count) / \(Self.maximumCharacterCount)")
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(
                        trimmedInput.count > Self.maximumCharacterCount
                            ? Color.red
                            : .secondary
                    )

                Spacer()

                if !inputText.isEmpty {
                    Button("清空") {
                        inputText = ""
                        result = nil
                        errorMessage = nil
                    }
                    .font(.subheadline)
                }

                Button(action: translate) {
                    HStack(spacing: 6) {
                        if isTranslating {
                            ProgressView().controlSize(.small).tint(.white)
                        }
                        Text(isTranslating ? "翻译中" : "翻译")
                            .font(.subheadline.weight(.semibold))
                    }
                    .foregroundStyle(.white)
                    .padding(.horizontal, 18)
                    .frame(height: 38)
                    .background(
                        canTranslate
                            ? AnyShapeStyle(JerreaderTheme.primaryAction)
                            : AnyShapeStyle(Color.gray.opacity(0.55)),
                        in: RoundedRectangle(cornerRadius: 12, style: .continuous)
                    )
                }
                .buttonStyle(JerreaderPressButtonStyle(pressedScale: 0.96))
                .disabled(!canTranslate)
            }
        }
        .padding(14)
        .background(JerreaderTheme.paper, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(JerreaderTheme.line, lineWidth: 0.75)
        }
    }

    // MARK: - Result

    private func resultCard(_ result: TranslationResult) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                Label(targetLanguage.displayName, systemImage: "text.bubble")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(JerreaderTheme.accent)
                Spacer()
                if result.isFromCache {
                    Text("缓存")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }

            Text(result.translatedText)
                .font(.body)
                .textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)

            HStack(spacing: 12) {
                Button {
                    UIPasteboard.general.string = result.translatedText
                    didCopy = true
                    Task {
                        try? await Task.sleep(for: .seconds(2))
                        didCopy = false
                    }
                } label: {
                    Label(didCopy ? "已复制" : "复制", systemImage: didCopy ? "checkmark" : "doc.on.doc")
                        .font(.subheadline)
                }
                .buttonStyle(.plain)
                .foregroundStyle(JerreaderTheme.accent)

                Button {
                    saveToFavorites(result)
                } label: {
                    Label(
                        didSaveFavorite ? "已收藏" : "收藏",
                        systemImage: didSaveFavorite ? "star.fill" : "star"
                    )
                        .font(.subheadline)
                }
                .buttonStyle(.plain)
                .foregroundStyle(JerreaderTheme.accent)

                Spacer()
            }
        }
        .padding(14)
        .background(JerreaderTheme.paper, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(JerreaderTheme.line, lineWidth: 0.75)
        }
        .transition(.opacity)
    }

    private func errorCard(_ message: String) -> some View {
        Label(message, systemImage: "exclamationmark.triangle")
            .font(.subheadline)
            .foregroundStyle(.orange)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(14)
            .background(
                Color.orange.opacity(0.10),
                in: RoundedRectangle(cornerRadius: 14, style: .continuous)
            )
    }

    // MARK: - Behaviour

    private var providerDescription: String {
        switch translationSettings.provider {
        case .apple:
            return "使用系统翻译，无需联网账号；首次使用某个语言方向可能需要下载语言包。"
        case .directAPI:
            return "使用 \(translationSettings.directAPIProvider.title)。可在「设置 → 翻译与 AI」中更换服务或模型。"
        case .backendProxy:
            return "使用已配置的 HTTPS 代理。可在「设置 → 翻译与 AI」中更改。"
        }
    }

    private var resolvedService: (any TranslationService)? {
        if let configuration = translationSettings.directAPIConfiguration {
            return DirectAITranslationService(configuration: configuration)
        }
        if let configuration = translationSettings.backendConfiguration {
            return BackendTranslationService(configuration: configuration)
        }
        return nil
    }

    private func translate() {
        let text = trimmedInput
        guard !text.isEmpty else { return }
        isInputFocused = false
        errorMessage = nil
        translateTask?.cancel()

        guard let service = resolvedService else {
            // Apple's translation runs through the reader's session-based API,
            // which is not available outside a book; say so instead of failing
            // silently with a generic error.
            errorMessage = translationSettings.provider == .apple
                ? "系统翻译目前只能在阅读界面里使用。在「设置 → 翻译与 AI」中选择一个 AI 服务后，这里就可以翻译任意文字。"
                : "还没有配置可用的翻译服务，请先到「设置 → 翻译与 AI」中配置。"
            return
        }

        isTranslating = true
        let source = sourceChoice.languageCode
        let target = targetLanguage
        translateTask = Task { @MainActor in
            defer { isTranslating = false }
            do {
                let output = try await service.translate(
                    text: text,
                    sourceLanguage: source,
                    targetLanguage: target
                )
                guard !Task.isCancelled else { return }
                withAnimation(reduceMotion ? nil : JerreaderMotion.stateChange) {
                    result = output
                }
            } catch is CancellationError {
                return
            } catch {
                guard !Task.isCancelled else { return }
                errorMessage = (error as? LocalizedError)?.errorDescription
                    ?? "翻译没有完成，请稍后重试。"
            }
        }
    }

    private func saveToFavorites(_ result: TranslationResult) {
        let store = TranslationFavoriteStore(modelContext: modelContext)
        _ = try? store.setFavorite(
            true,
            bookID: Self.standaloneBookID,
            bookTitle: "翻译工具",
            locatorJSON: nil,
            result: result
        )
        didSaveFavorite = true
        Task {
            try? await Task.sleep(for: .seconds(2))
            didSaveFavorite = false
        }
    }
}
