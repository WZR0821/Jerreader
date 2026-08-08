import SwiftData
import SwiftUI
import UIKit

enum LexicalLookupLanguageChoice: String, CaseIterable, Identifiable, Sendable {
    case automatic
    case japanese
    case english

    var id: Self { self }

    var title: String {
        switch self {
        case .automatic: return "自动"
        case .japanese: return "日语"
        case .english: return "英语"
        }
    }

    var fixedLanguage: LanguageCode? {
        switch self {
        case .automatic: return nil
        case .japanese: return .japanese
        case .english: return .english
        }
    }
}

enum LexicalLookupLanguageResolver {
    static func resolve(
        choice: LexicalLookupLanguageChoice,
        word: String,
        sentenceContext: String?
    ) -> LanguageCode {
        if let fixed = choice.fixedLanguage { return fixed }

        let scalars = word.unicodeScalars
        if scalars.contains(where: {
            (0x3040 ... 0x30FF).contains($0.value)
                || (0x31F0 ... 0x31FF).contains($0.value)
        }) {
            return .japanese
        }
        let containsLatin = scalars.contains {
            (0x0041 ... 0x005A).contains($0.value)
                || (0x0061 ... 0x007A).contains($0.value)
        }
        let containsCJK = scalars.contains {
            (0x3400 ... 0x4DBF).contains($0.value)
                || (0x4E00 ... 0x9FFF).contains($0.value)
        }
        if containsLatin, !containsCJK { return .english }

        if let sentenceContext,
           let contextLanguage = ReaderLanguageDetector.detect(
               text: sentenceContext,
               bookLanguage: nil
           ),
           contextLanguage == .japanese || contextLanguage == .english
        {
            return contextLanguage
        }
        if let detected = ReaderLanguageDetector.detect(
            text: word,
            bookLanguage: nil
        ), detected == .japanese || detected == .english {
            return detected
        }

        // The current online lexical adapter has Japanese and English
        // dictionaries only. A short Han-only word is intrinsically ambiguous
        // (for example 「勉強」), so prefer Japanese rather than sending
        // an unsupported Chinese code and failing before the lookup begins.
        return .japanese
    }
}

@MainActor
struct LookupView: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.modelContext) private var modelContext
    @Query private var lookupRecords: [WordLookupRecord]

    @State private var query = ""
    @State private var sentenceContext = ""
    @State private var selectedLanguage: LexicalLookupLanguageChoice = .automatic
    @State private var resolvedLookupLanguage: LanguageCode?
    @State private var addToVocabulary = false
    @State private var resultID: UUID?
    @State private var isLoading = false
    @State private var requestID: UUID?
    @State private var lookupTask: Task<Void, Never>?
    @State private var alert: LearningAlert?
    @State private var referenceDictionaryTerm: ReferenceDictionaryTerm?
    @State private var isLoadingAIAnalysis = false
    @State private var aiAnalysisTask: Task<Void, Never>?
    @FocusState private var focusedField: LookupField?

    private let lookupService: any LexicalLookupService
    @ObservedObject private var translationSettings: TranslationSettingsStore

    private var result: WordLookupRecord? {
        guard let resultID else { return nil }
        return lookupRecords.first { $0.id == resultID }
    }

    init(
        translationSettings: TranslationSettingsStore,
        lookupService: any LexicalLookupService = WiktionaryLexicalLookupService()
    ) {
        _translationSettings = ObservedObject(wrappedValue: translationSettings)
        self.lookupService = lookupService
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                lookupHeader
                    .jerreaderReveal()
                lookupForm
                    .jerreaderReveal(order: 1)

                Group {
                    if isLoading {
                        loadingCard
                    } else if let result {
                        VStack(alignment: .leading, spacing: 12) {
                            WordRecordCard(
                                record: result,
                                onSpeak: { speak(result) },
                                onCopy: { copy(result) },
                                onToggleFavorite: { toggleFavorite(result) }
                            )

                            if result.language == .japanese {
                                aiAnalysisControls(for: result)
                            }

                            if result.usageNote?.contains("中文维基词典") == true {
                                Link(destination: URL(string: "https://zh.wiktionary.org/")!) {
                                    Label("词典数据：中文维基词典（CC BY-SA）", systemImage: "link")
                                        .font(.caption)
                                }
                                .padding(.horizontal, 4)
                            }

                            Text("此结果已自动加入查词历史。重复查询同一基本形时会更新记录，而不会创建重复词条。")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .padding(.horizontal, 4)
                        }
                    } else {
                        introductionCard
                    }
                }
                .id(lookupContentIdentity)
                .transition(
                    .opacity.combined(with: .scale(scale: 0.985, anchor: .top))
                )
                .jerreaderReveal(order: 2)
            }
            .frame(maxWidth: 760)
            .frame(maxWidth: .infinity)
            .padding(.horizontal, JerreaderTheme.pagePadding)
            .padding(.top, 12)
            .padding(.bottom, 34)
            .contentShape(Rectangle())
            .onTapGesture {
                focusedField = nil
            }
        }
        .background(JerreaderTheme.canvas)
        .scrollDismissesKeyboard(.interactively)
        .tint(JerreaderTheme.accent)
        .animation(
            reduceMotion ? nil : JerreaderMotion.stateChange,
            value: lookupContentIdentity
        )
        .sensoryFeedback(.success, trigger: resultID)
        .sheet(item: $referenceDictionaryTerm) { item in
            SystemDictionaryView(term: item.term)
                .ignoresSafeArea()
        }
        .alert(item: $alert) { item in
            Alert(
                title: Text(item.title),
                message: Text(item.message),
                dismissButton: .default(Text("好"))
            )
        }
        .onChange(of: selectedLanguage) {
            focusedField = nil
            cancelLookup()
            cancelAIAnalysis()
            resultID = nil
            query = ""
            sentenceContext = ""
        }
        .onDisappear {
            focusedField = nil
            cancelLookup()
            cancelAIAnalysis()
        }
    }

    private var lookupHeader: some View {
        HStack(spacing: 14) {
            Text(selectedLanguage == .english ? "Aa" : "あ")
                .font(.system(.title2, design: .serif).weight(.semibold))
                .foregroundStyle(JerreaderTheme.accent)
                .frame(width: 54, height: 54)
                .background(JerreaderTheme.accentFill, in: RoundedRectangle(cornerRadius: 16, style: .continuous))

            VStack(alignment: .leading, spacing: 4) {
                Text("读懂一个词，也保留它的语境。")
                    .font(.system(.headline, design: .serif).weight(.semibold))
                Text("查询结果、收藏与上下文只保存在本机")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(17)
        .background(JerreaderTheme.paper)
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .stroke(JerreaderTheme.line, lineWidth: 0.75)
        }
        .accessibilityElement(children: .combine)
    }

    private var lookupForm: some View {
        VStack(alignment: .leading, spacing: 16) {
            Picker("查询语言", selection: $selectedLanguage) {
                ForEach(LexicalLookupLanguageChoice.allCases) { choice in
                    Text(choice.title).tag(choice)
                }
            }
            .pickerStyle(.segmented)

            if selectedLanguage == .automatic {
                Text(
                    resolvedLookupLanguage.map {
                        "本次识别为\($0.displayName)。纯汉字短词无法可靠区分中日文，会优先尝试日语词典；也可在上方手动指定。"
                    } ?? "会结合词形和上下文自动选择日语或英语词典。"
                )
                .font(.caption)
                .foregroundStyle(.secondary)
            }

            VStack(alignment: .leading, spacing: 8) {
                Text(selectedLanguage == .english ? "英语单词或词形" : "日语或英语单词/词形")
                    .font(.subheadline.weight(.semibold))

                TextField(
                    selectedLanguage == .english ? "例如：went" : "例如：食べました",
                    text: $query
                )
                .focused($focusedField, equals: .query)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.search)
                .onSubmit(performLookup)
                .padding(14)
                .background(JerreaderTheme.canvas, in: RoundedRectangle(cornerRadius: 13, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: 13, style: .continuous)
                        .stroke(JerreaderTheme.line, lineWidth: 0.75)
                }
            }

            VStack(alignment: .leading, spacing: 8) {
                Text("上下文（可选）")
                    .font(.subheadline.weight(.semibold))

                TextField("输入词语所在的句子，有助于保留学习语境", text: $sentenceContext, axis: .vertical)
                    .focused($focusedField, equals: .context)
                    .lineLimit(2...4)
                    .padding(14)
                    .background(JerreaderTheme.canvas, in: RoundedRectangle(cornerRadius: 13, style: .continuous))
                    .overlay {
                        RoundedRectangle(cornerRadius: 13, style: .continuous)
                            .stroke(JerreaderTheme.line, lineWidth: 0.75)
                    }
            }

            HStack(spacing: 10) {
                Text("试一试")
                    .font(.caption)
                    .foregroundStyle(.secondary)

                ForEach(sampleTerms, id: \.self) { sample in
                    Button(sample) {
                        query = sample
                        sentenceContext = sampleContext(for: sample)
                        performLookup()
                    }
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(JerreaderTheme.accent)
                    .padding(.horizontal, 11)
                    .padding(.vertical, 7)
                    .background(JerreaderTheme.accentFill, in: Capsule())
                    .buttonStyle(JerreaderPressButtonStyle(pressedScale: 0.96))
                }
            }

            Toggle(isOn: $addToVocabulary) {
                VStack(alignment: .leading, spacing: 3) {
                    Text("同时加入生词本")
                        .font(.subheadline.weight(.semibold))
                    Text(addToVocabulary ? "查到结果后自动收藏" : "只查词并保留查询历史")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            Button(action: performLookup) {
                Label(
                    addToVocabulary ? "查询并加入生词本" : "查询词语",
                    systemImage: addToVocabulary ? "bookmark.fill" : "magnifyingglass"
                )
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
                    .foregroundStyle(JerreaderTheme.onPrimaryAction)
                    .background(JerreaderTheme.primaryAction, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            }
            .buttonStyle(JerreaderPressButtonStyle(pressedScale: 0.985))
            .disabled(isLoading || query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            .opacity(isLoading || query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? 0.45 : 1)

            Button(action: openSystemDictionary) {
                Label("使用 iOS 系统词典", systemImage: "books.vertical")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .disabled(query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
        .jerreaderPaperCard(padding: 18, hasShadow: true)
    }

    private var loadingCard: some View {
        HStack(spacing: 14) {
            JerreaderLoadingGlyph(systemImage: "text.magnifyingglass", size: 46)
            VStack(alignment: .leading, spacing: 3) {
                Text("正在查询…")
                    .font(.headline)
                Text("查询其他词语时，旧请求会自动取消。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .jerreaderPaperCard(padding: 20, radius: 18)
    }

    private var lookupContentIdentity: String {
        if isLoading {
            return "loading"
        }
        if let resultID {
            return "result-\(resultID.uuidString)"
        }
        return "introduction"
    }

    private var introductionCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("真实词典查询已启用", systemImage: "checkmark.shield")
                .font(.headline)
                .foregroundStyle(JerreaderTheme.accent)

            Text("默认联网查询中文维基词典，并自动还原常见日语、英语词形。无网络时，可改用本机已安装的 iOS 系统词典。")
                .font(.subheadline)
                .foregroundStyle(.secondary)

            Label("查询结果会保存在本机，收藏和历史可直接使用", systemImage: "iphone")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(18)
        .background(JerreaderTheme.accentFill, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(JerreaderTheme.line, lineWidth: 0.75)
        }
    }

    @ViewBuilder
    private func aiAnalysisControls(for record: WordLookupRecord) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 10) {
                Image(systemName: "sparkles")
                    .foregroundStyle(JerreaderTheme.accent)

                VStack(alignment: .leading, spacing: 2) {
                    Text("AI 日语深度解析")
                        .font(.subheadline.weight(.semibold))
                    Text(aiConfigurationDescription)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Spacer(minLength: 8)

                if isLoadingAIAnalysis {
                    ProgressView()
                } else {
                    Button(record.aiAnalysis == nil ? "开始解析" : "重新解析") {
                        requestAIAnalysis(for: record)
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.small)
                    .disabled(resolvedAIExplanationService == nil)
                }
            }

            if resolvedAIExplanationService == nil {
                Label("在“设置 → 翻译与 AI”中选择 GPT、Claude、Kimi 等服务并输入 API Key 后即可使用。", systemImage: "key")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .jerreaderPaperCard(padding: 15, radius: 17)
    }

    private var sampleTerms: [String] {
        selectedLanguage == .english ? ["went"] : ["食べました"]
    }

    private func sampleContext(for term: String) -> String {
        switch term {
        case "食べました":
            return "昨日、家でご飯を食べました。"
        case "went":
            return "She went home before sunset."
        default:
            return ""
        }
    }

    private func performLookup() {
        focusedField = nil
        let normalizedQuery = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalizedQuery.isEmpty else {
            alert = LearningAlert(title: "请输入词语", message: "输入一个日语或英语词语后再查询。")
            return
        }

        lookupTask?.cancel()
        let currentRequestID = UUID()
        requestID = currentRequestID
        isLoading = true
        resultID = nil

        let normalizedContext = sentenceContext.trimmingCharacters(in: .whitespacesAndNewlines)
        let context = normalizedContext.isEmpty ? nil : normalizedContext
        let language = LexicalLookupLanguageResolver.resolve(
            choice: selectedLanguage,
            word: normalizedQuery,
            sentenceContext: context
        )
        resolvedLookupLanguage = language
        let service = lookupService
        let shouldAddToVocabulary = addToVocabulary

        lookupTask = Task { @MainActor in
            defer {
                if requestID == currentRequestID {
                    isLoading = false
                    lookupTask = nil
                }
            }

            do {
                let explanation = try await service.lookup(
                    word: normalizedQuery,
                    sentenceContext: context,
                    language: language
                )
                try Task.checkCancellation()
                guard requestID == currentRequestID else { return }

                let savedRecord = try WordLookupStore.record(explanation, in: modelContext)
                if shouldAddToVocabulary, !savedRecord.isFavorite {
                    try WordLookupStore.setFavorite(true, for: savedRecord, in: modelContext)
                }
                resultID = savedRecord.id
            } catch is CancellationError {
                return
            } catch {
                guard requestID == currentRequestID else { return }
                if UIReferenceLibraryViewController.dictionaryHasDefinition(forTerm: normalizedQuery) {
                    referenceDictionaryTerm = ReferenceDictionaryTerm(term: normalizedQuery)
                } else {
                    alert = LearningAlert(
                        title: "无法完成查词",
                        message: (error as? LocalizedError)?.errorDescription ?? "请检查输入后重试。"
                    )
                }
            }
        }
    }

    private func openSystemDictionary() {
        focusedField = nil
        let term = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !term.isEmpty else { return }
        guard UIReferenceLibraryViewController.dictionaryHasDefinition(forTerm: term) else {
            alert = LearningAlert(
                title: "系统词典暂无释义",
                message: "请先在 iOS 的词典管理中下载需要的日语或英语词典，或联网使用默认查询。"
            )
            return
        }
        referenceDictionaryTerm = ReferenceDictionaryTerm(term: term)
    }

    private func cancelLookup() {
        lookupTask?.cancel()
        lookupTask = nil
        requestID = nil
        isLoading = false
    }

    private var resolvedAIExplanationService: (any ContextExplanationService)? {
        if let configuration = translationSettings.directAPIConfiguration {
            return DirectAITranslationService(configuration: configuration)
        }
        if let configuration = translationSettings.backendConfiguration {
            return BackendTranslationService(configuration: configuration)
        }
        return nil
    }

    private var aiConfigurationDescription: String {
        if translationSettings.directAPIConfiguration != nil {
            return "使用 \(translationSettings.directAPIProvider.title) 分析活用、语法角色和语境"
        }
        if translationSettings.backendConfiguration != nil {
            return "使用已配置的 AI 代理进行语法与活用分析"
        }
        return "需要先配置一个 AI 接口"
    }

    private func requestAIAnalysis(for record: WordLookupRecord) {
        guard let service = resolvedAIExplanationService else { return }
        cancelAIAnalysis()
        isLoadingAIAnalysis = true
        let recordID = record.id
        let context = record.sentenceContext
        let focusedText = record.surfaceForm
        aiAnalysisTask = Task { @MainActor in
            defer {
                isLoadingAIAnalysis = false
                aiAnalysisTask = nil
            }
            do {
                let rawResult = try await service.explain(
                    ContextExplanationRequest(
                        focusedText: focusedText,
                        contextText: context,
                        sourceLanguage: .japanese,
                        responseLanguage: .simplifiedChinese
                    )
                )
                try Task.checkCancellation()
                guard resultID == recordID,
                      let result = ContextExplanationOutputPolicy.validated(rawResult)
                else { return }
                record.aiAnalysis = result.explanation
                record.aiProviderIdentifier = result.providerIdentifier
                try modelContext.save()
            } catch is CancellationError {
                return
            } catch {
                alert = LearningAlert(
                    title: "AI 解析未完成",
                    message: (error as? LocalizedError)?.errorDescription
                        ?? "请检查 API 配置或网络后重试。"
                )
            }
        }
    }

    private func cancelAIAnalysis() {
        aiAnalysisTask?.cancel()
        aiAnalysisTask = nil
        isLoadingAIAnalysis = false
    }

    private func speak(_ record: WordLookupRecord) {
        Task {
            do {
                try await SystemSpeechService.shared.speak(
                    record.reading ?? record.surfaceForm,
                    language: record.language
                )
            } catch {
                alert = LearningAlert(
                    title: "无法播放发音",
                    message: (error as? LocalizedError)?.errorDescription ?? "系统语音暂时不可用。"
                )
            }
        }
    }

    private func copy(_ record: WordLookupRecord) {
        UIPasteboard.general.string = record.copyText
        alert = LearningAlert(title: "已复制", message: "词条内容已复制到剪贴板。")
    }

    private func toggleFavorite(_ record: WordLookupRecord) {
        do {
            try WordLookupStore.setFavorite(!record.isFavorite, for: record, in: modelContext)
        } catch {
            alert = LearningAlert(
                title: "无法更新收藏",
                message: (error as? LocalizedError)?.errorDescription ?? "请稍后重试。"
            )
        }
    }

    private enum LookupField: Hashable {
        case query
        case context
    }
}

struct ReferenceDictionaryTerm: Identifiable {
    let id = UUID()
    let term: String
}

struct SystemDictionaryView: UIViewControllerRepresentable {
    let term: String

    func makeUIViewController(context: Context) -> UIReferenceLibraryViewController {
        UIReferenceLibraryViewController(term: term)
    }

    func updateUIViewController(
        _ uiViewController: UIReferenceLibraryViewController,
        context: Context
    ) {}
}
