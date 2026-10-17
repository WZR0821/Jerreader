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

    @State private var lookupWatchdog = StandaloneTranslationWatchdog()
    @State private var aiWatchdog = StandaloneTranslationWatchdog(timing: .init(network: .seconds(70)))
    @State private var contextExpanded = false
    @State private var aiExpanded = false
    @State private var transientResult: WordLookupRecord?
    @State private var localChoices: [JapaneseChineseLexicon.Entry] = []
    @State private var aiRequestID: UUID?
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

    private let sourceBookID: UUID?
    private let sourceBookTitle: String?
    private let opensInReader: Bool
    private let lookupService: any LexicalLookupService
    @ObservedObject private var translationSettings: TranslationSettingsStore

    private var result: WordLookupRecord? {
        guard let resultID else { return transientResult }
        return lookupRecords.first { $0.id == resultID }
    }

    init(
        translationSettings: TranslationSettingsStore,
        lookupService: any LexicalLookupService = OfflineFirstLexicalLookupService(),
        initialWord: String = "", initialContext: String = "",
        language: LanguageCode? = nil, sourceBookID: UUID? = nil, sourceBookTitle: String? = nil
    ) {
        _translationSettings = ObservedObject(wrappedValue: translationSettings)
        self.lookupService = lookupService
        self.sourceBookID = sourceBookID
        self.sourceBookTitle = sourceBookTitle
        self.opensInReader = sourceBookID != nil
        _query = State(initialValue: initialWord.count <= 80 && !initialWord.contains(where: { "。！？!?".contains($0) }) ? initialWord : "")
        _sentenceContext = State(initialValue: initialContext)
        _selectedLanguage = State(initialValue: language == .english ? .english : sourceBookID != nil || language == .japanese ? .japanese : .automatic)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                if opensInReader {
                    readerForm
                } else {
                    lookupHeader
                    lookupForm
                }

                if !localChoices.isEmpty && result == nil && !isLoading {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("选择词条 / 释义").font(.headline)
                        Text("词典可能包含同音词或多个义项，请结合原句选择。").font(.caption).foregroundStyle(.secondary)
                        ForEach(Array(localChoices.enumerated()), id: \.offset) { _, entry in
                            Button { performLookup(entry: entry) } label: {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(entry.display).font(.body.weight(.semibold))
                                    Text(entry.definition).font(.subheadline)
                                }.frame(maxWidth: .infinity, alignment: .leading)
                            }.buttonStyle(.bordered)
                        }
                    }.jerreaderPaperCard()
                }
                Group {
                    if isLoading {
                        loadingCard
                    } else if let result {
                        VStack(alignment: .leading, spacing: 12) {
                            if opensInReader {
                                readerResult(result)
                            } else {
                                WordRecordCard(record: result, onSpeak: { speak(result) },
                                    onCopy: { copy(result) }, onToggleFavorite: { toggleFavorite(result) })
                            }
                            aiAnalysisControls(for: result)
                            if opensInReader, let analysis = result.aiAnalysis {
                                DisclosureGroup("AI 语境建议", isExpanded: $aiExpanded) {
                                    Text(analysis).font(.body).textSelection(.enabled)
                                }.jerreaderPaperCard(padding: 16, radius: 16)
                            }

                            if result.usageNote?.contains("中文维基词典") == true {
                                Link(destination: URL(string: "https://zh.wiktionary.org/")!) {
                                    Label("词典数据：中文维基词典（CC BY-SA）", systemImage: "link")
                                        .font(.caption)
                                }
                                .padding(.horizontal, 4)
                            }

                            Text(recordStatusText(result))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .padding(.horizontal, 4)
                        }
                    } else if localChoices.isEmpty {
                        if opensInReader {
                            Text("输入原句中的词语或短语后查询。").font(.subheadline).foregroundStyle(.secondary)
                        } else { introductionCard }
                    }
                }
                .id(lookupContentIdentity)
                .transition(
                    .opacity.combined(with: .scale(scale: 0.985, anchor: .top))
                )
                .jerreaderReveal(order: 2)
            }
            .frame(maxWidth: 720)
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 16)
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
        .onAppear { if opensInReader && resultID == nil && !query.isEmpty { performLookup() } }
        .onChange(of: query) { invalidateInput() }
        .onChange(of: sentenceContext) { invalidateInput() }
        .onChange(of: selectedLanguage) {
            focusedField = nil
            cancelLookup()
            cancelAIAnalysis()
            resultID = nil
            transientResult = nil
            localChoices = []
            aiExpanded = false
            if !opensInReader { query = ""; sentenceContext = "" }
        }
        .onDisappear {
            focusedField = nil
            cancelLookup()
            cancelAIAnalysis()
        }
    }

    private var readerForm: some View {
        VStack(alignment: .leading, spacing: 12) {
            Picker("查询语言", selection: $selectedLanguage) {
                Text("日语").tag(LexicalLookupLanguageChoice.japanese)
                Text("英语").tag(LexicalLookupLanguageChoice.english)
            }.pickerStyle(.segmented).frame(minHeight: 48)
            if !sentenceContext.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    Text("书中语境").font(.subheadline.weight(.semibold))
                    Text(sentenceContext).font(.body).lineLimit(contextExpanded ? nil : 3).textSelection(.enabled)
                    if sentenceContext.count > 80 {
                        Button(contextExpanded ? "收起语境" : "展开语境") { contextExpanded.toggle() }
                            .font(.subheadline).frame(minHeight: 44)
                    }
                }.jerreaderPaperCard(padding: 16, radius: 16)
            }
            TextField("词语 / 修改选词", text: $query, axis: .vertical)
                .font(.body).lineLimit(1...3).textInputAutocapitalization(.never).autocorrectionDisabled()
                .focused($focusedField, equals: .query).submitLabel(.search).onSubmit { performLookup() }
                .padding(12).frame(minHeight: 48)
                .background(JerreaderTheme.paper, in: RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(JerreaderTheme.line, lineWidth: 0.75))
                .accessibilityIdentifier("contextual-query")
            Text("可粘贴原句中的词语或短语，最多 80 个字符。")
                .font(.caption).foregroundStyle(.secondary)
            Button("查询") { performLookup() }
                .buttonStyle(.borderedProminent).frame(minHeight: 44)
                .disabled(isLoading || !(1...80).contains(query.trimmingCharacters(in: .whitespacesAndNewlines).count))
                .accessibilityIdentifier("contextual-submit")
        }
    }

    private func readerResult(_ record: WordLookupRecord) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(record.surfaceForm).font(.title2.weight(.bold)).textSelection(.enabled)
            Text([record.lemma.map { "词典形：" + $0 }, record.reading, record.partOfSpeech]
                .compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · "))
                .font(.subheadline).foregroundStyle(.secondary)
            Text("词典释义 · 中文").font(.subheadline.weight(.semibold))
            ForEach(Array(record.definitions.enumerated()), id: \.offset) { _, definition in
                Text(definition).font(.body).textSelection(.enabled)
            }
            if let note = record.inflectionNote { Text("活用说明\n" + note).font(.subheadline) }
            if let note = record.usageNote { Text("来源与用法\n" + note).font(.caption).foregroundStyle(.secondary) }
            if !localChoices.isEmpty {
                Button("更换词条 / 释义") {
                    let choices = localChoices
                    invalidateInput()
                    localChoices = choices
                }.buttonStyle(.bordered).frame(minHeight: 44)
            }
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 8) { readerActions(record) }
                VStack(alignment: .leading, spacing: 8) { readerActions(record) }
            }
        }.frame(maxWidth: .infinity, alignment: .leading).jerreaderPaperCard(padding: 16, radius: 16)
    }

    @ViewBuilder private func readerActions(_ record: WordLookupRecord) -> some View {
        Button("复制词条") { copy(record) }.buttonStyle(.bordered).frame(minHeight: 44)
        Button(record.isFavorite ? "取消收藏" : "加入生词本") { toggleFavorite(record) }
            .buttonStyle(.borderedProminent).frame(minHeight: 44)
    }

    private func recordStatusText(_ record: WordLookupRecord) -> String {
        record.modelContext == nil ? "历史保存失败，释义仍可查看。" : "已保存查词历史与本次语境。"
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
                .onSubmit { performLookup() }
                .padding(14)
                .background(JerreaderTheme.canvas, in: RoundedRectangle(cornerRadius: 13, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: 13, style: .continuous)
                        .stroke(JerreaderTheme.line, lineWidth: 0.75)
                }
            }

            VStack(alignment: .leading, spacing: 8) {
                Text(opensInReader ? "书中原句" : "上下文（可选）")
                    .font(.subheadline.weight(.semibold))

                TextField("输入词语所在的句子，有助于保留学习语境", text: $sentenceContext, axis: .vertical)
                    .disabled(opensInReader)
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

                ForEach(opensInReader ? [] : sampleTerms, id: \.self) { sample in
                    Button(sample) {
                        query = sample
                        sentenceContext = sampleContext(for: sample)
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

            Button(action: { performLookup() }) {
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
            .disabled(isLoading || query.count > 80 || query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
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

            Text("优先查询内置中日词典和已安装的英语词库，未命中时联网查询中文维基词典。无网络时，也可使用本机已安装的 iOS 系统词典。")
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

    private func aiAnalysisControls(for record: WordLookupRecord) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("AI 语境建议").font(.headline)
            Text("按需分析本句；建议与词典释义分开保存。").font(.caption).foregroundStyle(.secondary)
            if isLoadingAIAnalysis { ProgressView("解析中…") }
            else {
                Button(record.aiAnalysis == nil ? "开始解析" : "重新解析") { requestAIAnalysis(for: record) }
                    .buttonStyle(.bordered).frame(minHeight: 44)
                    .disabled(resolvedAIExplanationService == nil || record.modelContext == nil)
            }
            if resolvedAIExplanationService == nil {
                Text("请先在设置中配置 AI 服务。").font(.caption).foregroundStyle(.secondary)
            }
        }.frame(maxWidth: .infinity, alignment: .leading).jerreaderPaperCard(padding: 16, radius: 16)
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

    private func invalidateInput() {
        cancelLookup()
        cancelAIAnalysis()
        resultID = nil
        transientResult = nil
        aiExpanded = false
        localChoices = []
    }

    private func performLookup(entry: JapaneseChineseLexicon.Entry? = nil) {
        focusedField = nil
        let normalizedQuery = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalizedQuery.isEmpty, normalizedQuery.count <= 80 else {
            alert = LearningAlert(title: "请输入词语", message: "请输入 1–80 个字符的词语或短语。")
            return
        }

        cancelAIAnalysis()
        lookupTask?.cancel()
        let currentRequestID = UUID()
        requestID = currentRequestID
        isLoading = true
        resultID = nil
        transientResult = nil
        lookupWatchdog.start(id: currentRequestID, systemTranslation: false, restart: {}) { _ in
            guard requestID == currentRequestID else { return }
            cancelLookup()
            alert = LearningAlert(title: "查词等待超时", message: "请检查网络后重试，本地词典仍可使用。")
        }

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
                    lookupWatchdog.complete(id: currentRequestID)
                    isLoading = false
                    lookupTask = nil
                }
            }

            do {
                let matches = language == .japanese ? await Task.detached(priority: .userInitiated) {
                    JapaneseChineseLexicon.shared.matches(normalizedQuery)
                }.value : []
                try Task.checkCancellation()
                guard requestID == currentRequestID else { return }
                localChoices = matches.count > 1 ? matches : []
                if entry == nil && Set(matches.map(\.term)).count > 1 { return }
                if let entry, !matches.contains(where: {
                    $0.term == entry.term && $0.reading == entry.reading && $0.definition == entry.definition
                }) { return }
                let explanation: WordExplanation
                if let chosen = entry ?? matches.first {
                    explanation = WordExplanation(surfaceForm: normalizedQuery, lemma: chosen.term,
                        reading: chosen.reading, language: language, partOfSpeech: chosen.partOfSpeech,
                        definitions: [chosen.definition],
                        inflectionNote: JapaneseChineseLexicon.shared.inflectionNote(normalizedQuery, entry: chosen),
                        usageNote: chosen.attribution + " · 离线释义", sentenceContext: context)
                } else {
                    explanation = try await service.lookup(word: normalizedQuery, sentenceContext: context, language: language)
                }
                try Task.checkCancellation()
                guard requestID == currentRequestID else { return }

                transientResult = WordLookupRecord(explanation: explanation, sourceBookID: sourceBookID, sourceBookTitle: sourceBookTitle)
                let savedRecord = try WordLookupStore.record(explanation, sourceBookID: sourceBookID, sourceBookTitle: sourceBookTitle, in: modelContext)
                if shouldAddToVocabulary, !savedRecord.isFavorite {
                    try WordLookupStore.setFavorite(true, for: savedRecord, in: modelContext)
                }
                resultID = savedRecord.id
                transientResult = nil
            } catch is CancellationError {
                return
            } catch {
                guard requestID == currentRequestID else { return }
                if transientResult != nil {
                    alert = LearningAlert(title: "历史保存失败", message: "释义仍可查看；可点击收藏重试保存。")
                } else if UIReferenceLibraryViewController.dictionaryHasDefinition(forTerm: normalizedQuery) {
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
        lookupWatchdog.cancel()
        lookupTask?.cancel()
        lookupTask = nil
        requestID = nil
        isLoading = false
    }

    private var resolvedAIExplanationService: (any ContextExplanationService)? {
        if translationSettings.provider == .backendProxy, let configuration = translationSettings.backendConfiguration {
            return BackendTranslationService(configuration: configuration)
        }
        if let configuration = translationSettings.directAPIConfiguration {
            return DirectAITranslationService(configuration: configuration)
        }
        if let configuration = translationSettings.backendConfiguration {
            return BackendTranslationService(configuration: configuration)
        }
        return nil
    }

    private func requestAIAnalysis(for record: WordLookupRecord) {
        guard let service = resolvedAIExplanationService else { return }
        cancelAIAnalysis()
        isLoadingAIAnalysis = true
        let token = UUID()
        aiRequestID = token
        let recordID = record.id
        let context = record.sentenceContext
        let focusedText = record.surfaceForm
        let revision = record.lastLookedUpAt
        let snapshot = record.wordExplanation
        let dictionaryEntry = snapshot.contextualDictionaryEntry
        aiWatchdog.start(id: token, systemTranslation: false, restart: {}) { _ in
            guard aiRequestID == token else { return }
            cancelAIAnalysis()
            alert = LearningAlert(title: "AI 解析等待超时", message: "请检查网络后重试。词典释义仍可查看。")
        }
        aiAnalysisTask = Task { @MainActor in
            defer {
                if aiRequestID == token {
                    aiWatchdog.complete(id: token)
                    isLoadingAIAnalysis = false
                    aiAnalysisTask = nil
                }
            }
            do {
                let rawResult = try await service.explain(
                    ContextExplanationRequest(
                        focusedText: focusedText,
                        contextText: context,
                        sourceLanguage: snapshot.language,
                        responseLanguage: .simplifiedChinese,
                        isWordLookup: true, dictionaryEntry: dictionaryEntry
                    )
                )
                try Task.checkCancellation()
                guard aiRequestID == token, resultID == recordID,
                      lookupRecords.contains(where: { $0.id == recordID }),
                      record.wordExplanation == snapshot, record.lastLookedUpAt == revision
                else { return }
                guard let result = ContextExplanationOutputPolicy.validated(rawResult) else {
                    alert = LearningAlert(title: "AI 解析未完成", message: "AI 返回了空白解释，请重试。")
                    return
                }
                let previousAnalysis = record.aiAnalysis
                let previousProvider = record.aiProviderIdentifier
                record.aiAnalysis = result.explanation
                record.aiProviderIdentifier = result.providerIdentifier
                do {
                    try modelContext.save()
                } catch {
                    // Restore this operation only; unrelated reading progress may
                    // still be pending in the shared context.
                    record.aiAnalysis = previousAnalysis
                    record.aiProviderIdentifier = previousProvider
                    throw error
                }
            } catch is CancellationError {
                return
            } catch {
                guard aiRequestID == token, resultID == recordID else { return }
                alert = LearningAlert(
                    title: "AI 解析未完成",
                    message: (error as? LocalizedError)?.errorDescription
                        ?? "请检查 API 配置或网络后重试。"
                )
            }
        }
    }

    private func cancelAIAnalysis() {
        aiWatchdog.cancel()
        aiRequestID = nil
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
            let saved = record.modelContext == nil
                ? try WordLookupStore.record(record.wordExplanation, sourceBookID: sourceBookID, sourceBookTitle: sourceBookTitle, in: modelContext)
                : record
            try WordLookupStore.setFavorite(!saved.isFavorite, for: saved, in: modelContext)
            resultID = saved.id
            transientResult = nil
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
