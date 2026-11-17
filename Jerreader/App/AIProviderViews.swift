import SwiftUI

/// 添加供应商: one list of presets, Claude, ChatGPT and DeepSeek first, with
/// no domestic/overseas split, plus a form for any other compatible service.
struct AddProviderView: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var store: TranslationSettingsStore
    var onAdded: (String) -> Void
    @State private var mode = Mode.preset
    @State private var showHelp = false

    private enum Mode: String, CaseIterable, Identifiable {
        case preset = "预设", custom = "自定义"
        var id: String { rawValue }
    }

    var body: some View {
        NavigationStack {
            Group {
                if mode == .preset {
                    presets
                } else {
                    ProviderSetupForm(store: store, kind: .openAICompatible, finish: finish)
                }
            }
            .safeAreaInset(edge: .top, spacing: 0) {
                Picker("添加方式", selection: $mode) {
                    ForEach(Mode.allCases) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented).padding(.horizontal, 16).padding(.top, 6).padding(.bottom, 10)
                .background(JerreaderTheme.canvas).accessibilityIdentifier("addProviderMode")
            }
            .navigationTitle("添加供应商").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("取消") { dismiss() }.accessibilityIdentifier("cancelAddProvider")
                }
                if mode == .preset {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button { showHelp = true } label: { Image(systemName: "questionmark.circle") }
                            .accessibilityLabel("说明")
                    }
                }
            }
            .sheet(isPresented: $showHelp) { AIHelpView() }
        }
    }

    private var presets: some View {
        List {
            Section {
                ForEach(DirectAIProviderChoice.presets) { provider in
                    if store.aiProviders.hasSetUp(provider, keyed: store.keyedProviderIDs) {
                        HStack {
                            ProviderRowLabel(provider: provider, title: provider.title, detail: provider.subtitle)
                            Spacer()
                            Text("已添加").font(.subheadline).foregroundStyle(.secondary)
                        }
                        .opacity(0.55)
                        .accessibilityElement(children: .combine)
                        .accessibilityIdentifier("preset-" + provider.rawValue)
                    } else {
                        NavigationLink {
                            ProviderSetupForm(store: store, kind: provider, finish: finish)
                        } label: {
                            ProviderRowLabel(provider: provider, title: provider.title, detail: provider.subtitle,
                                             tag: provider.isRecommended ? "推荐" : nil)
                        }
                        .accessibilityIdentifier("preset-" + provider.rawValue)
                    }
                }
            } footer: {
                Text("选一家，填上它的 API Key。没有列出的服务，用上面的“自定义”添加。")
            }
        }
        .settingsList(icons: true)
    }

    private func finish(_ message: String) {
        onAdded(message)
        dismiss()
    }
}

/// Key, models and address for one new provider.
struct ProviderSetupForm: View {
    @ObservedObject var store: TranslationSettingsStore
    let kind: DirectAIProviderChoice
    var finish: (String) -> Void

    @State private var customID = AIProviderEntry.newCustomID()
    @State private var name = ""
    @State private var key = ""
    @State private var baseURL = ""
    @State private var style = AIAPIStyle.chatCompletions
    @State private var chosen: Set<String> = []
    @State private var modelID = ""
    @State private var fetched: [String] = []
    @State private var showFetched = false
    @State private var advanced = false
    @State private var loaded = false
    @State private var busy = false
    @State private var notice: String?
    @State private var error: String?
    @State private var job: Task<Void, Never>?
    @FocusState private var editing: Bool

    private var isCustom: Bool { kind.isCustom }
    private var modelIDs: [String] {
        if isCustom {
            let id = modelID.trimmingCharacters(in: .whitespacesAndNewlines)
            return id.isEmpty ? [] : [id]
        }
        return kind.modelPresets.map(\.id).filter(chosen.contains)
    }
    private var canAdd: Bool {
        !key.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !modelIDs.isEmpty
            && !baseURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    /// What would be saved; the first chosen model becomes the provider's model in use.
    private var draft: AIProviderEntry {
        let label = name.trimmingCharacters(in: .whitespacesAndNewlines)
        return AIProviderEntry(
            id: isCustom ? customID : kind.rawValue,
            kind: kind,
            name: isCustom && !label.isEmpty ? label : nil,
            baseURL: baseURL.trimmingCharacters(in: .whitespacesAndNewlines),
            model: modelIDs.first ?? "",
            models: modelIDs.map { AIModelEntry(id: $0, style: style) }
        )
    }

    var body: some View {
        Form {
            if isCustom { customFields } else { presetFields }
            Section {
                Button("测试连接") { testConnection() }.accessibilityIdentifier("testConnectionButton")
            } footer: {
                Text("用上面填写的内容发送一句“Hello.”，可能产生少量 API 费用。")
            }
            .disabled(busy)
            if !isCustom {
                Section { ExternalLinkRow(title: "\(kind.title) 官方文档", url: kind.documentationURL) }
            }
        }
        .settingsList()
        .navigationTitle(isCustom ? "添加供应商" : kind.title).navigationBarTitleDisplayMode(.inline)
        .safeAreaInset(edge: .top, spacing: 0) { feedback }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("添加") { add() }.fontWeight(.semibold).disabled(busy || !canAdd)
                    .accessibilityIdentifier("confirmAddProviderButton")
            }
            ToolbarItemGroup(placement: .keyboard) { Spacer(); Button("完成") { editing = false } }
        }
        .sheet(isPresented: $showFetched) {
            FetchedModelPicker(models: fetched) { modelID = $0 }
        }
        .onAppear {
            guard !loaded else { return }
            loaded = true
            baseURL = kind.defaultBaseURL
            style = kind.defaultStyle
            chosen = Set(kind.modelPresets.map(\.id))
        }
        .onDisappear { job?.cancel() }
        // Typed details are not lost to a stray swipe; 取消 still closes the sheet.
        .interactiveDismissDisabled(busy || !(key.isEmpty && name.isEmpty && modelID.isEmpty))
    }

    @ViewBuilder private var presetFields: some View {
        Section {
            ProviderHeader(provider: kind, title: kind.title, detail: kind.subtitle)
        }
        Section {
            APIKeyField(key: $key, focus: $editing)
            if let keyURL = kind.keyURL { ExternalLinkRow(title: "去 \(kind.title) 控制台申请密钥", url: keyURL) }
        } header: { Text("API Key").sectionTitle() } footer: {
            Text("密钥只保存在本机钥匙串中，不会写进备份。")
        }
        .disabled(busy)
        Section {
            ForEach(kind.modelPresets) { model in
                Button { toggle(model.id) } label: {
                    HStack {
                        ModelLabel(id: model.id, detail: model.title + " · " + model.detail)
                        Spacer()
                        Image(systemName: chosen.contains(model.id) ? "checkmark.circle.fill" : "circle")
                            .font(.title3)
                            .foregroundStyle(chosen.contains(model.id)
                                ? JerreaderTheme.accent : Color(uiColor: .tertiaryLabel))
                    }
                }
                .accessibilityIdentifier("model-" + model.id)
                .accessibilityAddTraits(chosen.contains(model.id) ? .isSelected : [])
            }
        } header: { Text("模型").sectionTitle() } footer: {
            Text("勾上的模型会一起加入，排在最前的那个先用。以后可以在供应商页面里增删。")
        }
        .disabled(busy)
        Section {
            DisclosureRow(title: "高级", isExpanded: $advanced)
            if advanced {
                addressField
                stylePicker
            }
        } footer: {
            Text(kind.setupNote)
        }
        .disabled(busy)
    }

    @ViewBuilder private var customFields: some View {
        Section {
            TextField("名称（可不填）", text: $name).autocorrectionDisabled().focused($editing)
                .accessibilityIdentifier("providerNameField")
            addressField
            stylePicker
        } header: { Text("服务").sectionTitle() } footer: {
            Text("填服务商文档里的 API 地址，一般以 /v1 结尾，必须是 HTTPS。接口格式照文档选；测试连接通了就对。")
        }
        .disabled(busy)
        Section {
            APIKeyField(key: $key, focus: $editing)
        } header: { Text("API Key").sectionTitle() } footer: {
            Text("密钥只保存在本机钥匙串中，不会写进备份。")
        }
        .disabled(busy)
        Section {
            TextField("模型 ID", text: $modelID)
                .textInputAutocapitalization(.never).autocorrectionDisabled().focused($editing)
                .accessibilityIdentifier("modelField")
            Button("获取可用模型") { fetchModels() }.accessibilityIdentifier("fetchModelsButton")
        } header: { Text("模型").sectionTitle() } footer: {
            Text("直接填模型 ID，或在填好地址和密钥后从服务返回的列表里选。")
        }
        .disabled(busy)
    }

    private var addressField: some View {
        TextField("https://…", text: $baseURL)
            .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
            .focused($editing).accessibilityLabel("API 地址").accessibilityIdentifier("apiURLField")
    }

    private var stylePicker: some View {
        Picker("接口格式", selection: $style) {
            ForEach(AIAPIStyle.selectable) { Text($0.displayName).tag($0) }
        }
        .accessibilityIdentifier("apiStylePicker")
    }

    private var feedback: some View {
        VStack(spacing: 0) {
            if busy { SettingsBusyBar { job?.cancel() } }
            SettingsFeedback(error: error, notice: notice)
        }
    }

    private func toggle(_ id: String) {
        if chosen.contains(id) { chosen.remove(id) } else { chosen.insert(id) }
    }

    private func add() {
        editing = false
        let value = draft
        do {
            let activated = try store.addProvider(value, key: key)
            let title = value.displayName
            if !activated {
                finish("已添加 \(title)。要用它，点“换模型”。")
            } else if store.provider == .directAPI {
                finish("已添加 \(title)，现在用它翻译。")
            } else {
                finish("已添加 \(title)，它现在是正在用的模型。")
            }
        } catch {
            self.error = AIProviderRequests.message(for: error)
            notice = nil
        }
    }

    private func fetchModels() {
        editing = false; busy = true; error = nil; notice = nil
        let value = draft, apiKey = key, format = style
        job = Task {
            defer { busy = false }
            do {
                fetched = try await AIProviderRequests.models(baseURL: value.baseURL, style: format, key: apiKey)
                try Task.checkCancellation()
                showFetched = true
            } catch is CancellationError {
            } catch {
                self.error = AIProviderRequests.message(for: error)
            }
        }
    }

    private func testConnection() {
        editing = false; error = nil; notice = nil
        let value = draft
        let configuration: DirectAITranslationConfiguration
        do {
            configuration = try store.configuration(entry: value, model: value.activeModel, key: key)
        } catch {
            self.error = AIProviderRequests.message(for: error)
            return
        }
        busy = true
        job = Task {
            defer { busy = false }
            do {
                let seconds = try await AIProviderRequests.check(configuration)
                notice = String(format: "连接正常，用时 %.1f 秒。点“添加”保存。", seconds)
            } catch is CancellationError {
            } catch {
                self.error = AIProviderRequests.message(for: error)
            }
        }
    }
}

/// One provider: its key and address, and the models kept under it.
struct ProviderDetailView: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var store: TranslationSettingsStore
    let entryID: String
    @State private var showCredentials = false
    @State private var showAddModel = false
    @State private var confirmDelete = false
    @State private var notice: String?
    @State private var error: String?

    private var entry: AIProviderEntry {
        store.aiProviders.entry(entryID)
            ?? .preset(DirectAIProviderChoice(rawValue: entryID) ?? .openAICompatible, models: [])
    }
    private var kind: DirectAIProviderChoice { entry.kind }
    private var isActive: Bool { store.aiProviders.activeID == entryID }
    private var hasKey: Bool { store.keyedProviderIDs.contains(entryID) }

    var body: some View {
        List {
            Section {
                ProviderHeader(provider: kind, name: entry.name, title: entry.displayName,
                               detail: kind.isCustom ? entry.host : kind.subtitle)
            }
            Section {
                // Both rows open the same editor.
                Button { showCredentials = true } label: {
                    ValueRow(title: "API Key", value: hasKey ? "已填写" : "未填写", warn: !hasKey)
                }
                .accessibilityIdentifier("editCredentialsButton")
                Button { showCredentials = true } label: { ValueRow(title: "API 地址", value: entry.host) }
                    .accessibilityIdentifier("editAddressButton")
            } header: { Text("密钥与地址").sectionTitle() } footer: {
                Text(kind.isCustom ? "点一下修改名称、密钥或地址。" : kind.setupNote)
            }
            Section {
                ForEach(entry.models) { model in
                    NavigationLink {
                        ModelDetailView(store: store, entryID: entryID, modelID: model.id)
                    } label: { modelLabel(model) }
                    .accessibilityIdentifier("model-" + model.id)
                }
                Button("添加模型") { showAddModel = true }.accessibilityIdentifier("addModelButton")
            } header: { Text("模型").sectionTitle() } footer: {
                Text("点开模型可改接口格式、思考强度，或设为正在用。")
            }
            Section {
                ExternalLinkRow(title: kind.isCustom ? "OpenAI 兼容接口说明" : "\(kind.title) 官方文档",
                                url: kind.documentationURL)
            }
            Section {
                Button("删除这个供应商", role: .destructive) { confirmDelete = true }
                    .disabled(isActive).accessibilityIdentifier("deleteProviderButton")
            } footer: {
                if isActive { Text("正在用的供应商不能删除。先用“换模型”换到别的供应商。") }
            }
        }
        .settingsList()
        .navigationTitle(entry.displayName).navigationBarTitleDisplayMode(.inline)
        .safeAreaInset(edge: .top, spacing: 0) { SettingsFeedback(error: error, notice: notice) }
        .sheet(isPresented: $showCredentials) {
            ProviderCredentialsEditor(store: store, entryID: entryID) { notice = $0; error = nil }
        }
        .sheet(isPresented: $showAddModel) {
            AddModelView(store: store, entryID: entryID) { notice = $0; error = nil }
        }
        .confirmationDialog("删除 \(entry.displayName)？", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("删除", role: .destructive) { remove() }
        } message: {
            Text("它的 API Key、地址和模型设置都会从本机移除，无法恢复。")
        }
    }

    private func modelLabel(_ model: AIModelEntry) -> some View {
        let preset = kind.modelPresets.first { $0.id == model.id }
        var parts: [String] = []
        if let preset { parts.append(preset.title + " · " + preset.detail) }
        parts.append(model.style.displayName)
        if let reasoning = model.reasoning { parts.append("思考" + reasoning.title) }
        return ModelLabel(id: model.id, detail: parts.joined(separator: " · "),
                          tag: store.aiProviders.isInUse(model: model.id, of: entryID) ? "正在用" : nil)
    }

    private func remove() {
        do {
            try store.removeProvider(entryID)
            dismiss()
        } catch {
            self.error = AIProviderRequests.message(for: error)
            notice = nil
        }
    }
}

/// Edits a provider's key and address. Nothing changes until 保存.
struct ProviderCredentialsEditor: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var store: TranslationSettingsStore
    let entryID: String
    var onSaved: (String) -> Void
    @State private var name = ""
    @State private var key = ""
    @State private var baseURL = ""
    @State private var loaded = false
    @State private var error: String?
    @FocusState private var editing: Bool

    private var saved: AIProviderEntry? { store.aiProviders.entry(entryID) }
    private var kind: DirectAIProviderChoice { saved?.kind ?? .openAICompatible }
    private var changed: Bool {
        key != store.apiKey(for: entryID) || baseURL != (saved?.baseURL ?? "") || name != (saved?.name ?? "")
    }

    var body: some View {
        NavigationStack {
            Form {
                if kind.isCustom {
                    Section {
                        TextField("自定义 API", text: $name).autocorrectionDisabled().focused($editing)
                            .accessibilityIdentifier("providerNameField")
                    } header: { Text("名称").sectionTitle() }
                }
                Section {
                    APIKeyField(key: $key, focus: $editing)
                } header: { Text("API Key").sectionTitle() } footer: {
                    Text("密钥只保存在本机钥匙串中。清空后保存会删除已存的密钥。")
                }
                Section {
                    TextField("https://…", text: $baseURL)
                        .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                        .focused($editing).accessibilityLabel("API 地址").accessibilityIdentifier("apiURLField")
                    if !kind.isCustom, baseURL != kind.defaultBaseURL {
                        Button("恢复官方地址") { baseURL = kind.defaultBaseURL }
                    }
                } header: { Text("API 地址").sectionTitle() } footer: {
                    Text(kind.isCustom ? "服务商文档里的 API 地址，必须是 HTTPS。" : kind.setupNote)
                }
            }
            .settingsList()
            .navigationTitle("密钥与地址").navigationBarTitleDisplayMode(.inline)
            .safeAreaInset(edge: .top, spacing: 0) { SettingsFeedback(error: error, notice: nil) }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("取消") { dismiss() }.accessibilityIdentifier("cancelCredentialsButton")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("保存") { save() }.fontWeight(.semibold).disabled(!changed)
                        .accessibilityIdentifier("saveCredentialsButton")
                }
                ToolbarItemGroup(placement: .keyboard) { Spacer(); Button("完成") { editing = false } }
            }
        }
        .onAppear {
            guard !loaded else { return }
            loaded = true
            name = saved?.name ?? ""
            key = store.apiKey(for: entryID)
            baseURL = saved?.baseURL ?? ""
        }
        // With unsaved edits the sheet closes only through 取消 or 保存.
        .interactiveDismissDisabled(loaded && changed)
    }

    private func save() {
        editing = false
        var address = baseURL.trimmingCharacters(in: .whitespacesAndNewlines)
        if address.isEmpty { address = kind.defaultBaseURL }
        do {
            try store.saveProvider(entryID, name: name, baseURL: address, key: key)
            onSaved("已保存。")
            dismiss()
        } catch {
            self.error = AIProviderRequests.message(for: error)
        }
    }
}

/// Adds a model to a provider: a preset, one the account can use, or a typed id.
struct AddModelView: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var store: TranslationSettingsStore
    let entryID: String
    var onAdded: (String) -> Void
    @State private var modelID = ""
    @State private var fetched: [String] = []
    @State private var showFetched = false
    @State private var busy = false
    @State private var error: String?
    @State private var job: Task<Void, Never>?
    @FocusState private var editing: Bool

    private var entry: AIProviderEntry? { store.aiProviders.entry(entryID) }
    private var kind: DirectAIProviderChoice { entry?.kind ?? .openAICompatible }
    private var kept: Set<String> { Set((entry?.models ?? []).map(\.id)) }
    private var typed: String { modelID.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var presets: [AIModelPreset] { kind.modelPresets.filter { !kept.contains($0.id) } }

    var body: some View {
        NavigationStack {
            Form {
                if !presets.isEmpty {
                    Section {
                        ForEach(presets) { preset in
                            Button { add(preset.id) } label: {
                                ModelLabel(id: preset.id, detail: preset.title + " · " + preset.detail)
                            }
                            .accessibilityIdentifier("model-" + preset.id)
                        }
                    } header: { Text("常用模型").sectionTitle() }
                }
                Section {
                    TextField("模型 ID", text: $modelID)
                        .textInputAutocapitalization(.never).autocorrectionDisabled().focused($editing)
                        .accessibilityIdentifier("modelField")
                    Button("添加这个模型") { add(typed) }
                        .disabled(typed.isEmpty || kept.contains(typed))
                        .accessibilityIdentifier("addTypedModelButton")
                } header: { Text("手动填写").sectionTitle() } footer: {
                    Text(kept.contains(typed) ? "这个模型已经在列表里了。" : "照服务商文档里的模型 ID 填写。")
                }
                Section {
                    Button("获取可用模型") { fetchModels() }.accessibilityIdentifier("fetchModelsButton")
                } footer: {
                    Text("用已保存的密钥向服务商查询这个账户能用的模型。")
                }
            }
            .disabled(busy)
            .settingsList()
            .navigationTitle("添加模型").navigationBarTitleDisplayMode(.inline)
            .safeAreaInset(edge: .top, spacing: 0) {
                VStack(spacing: 0) {
                    if busy { SettingsBusyBar { job?.cancel() } }
                    SettingsFeedback(error: error, notice: nil)
                }
            }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("取消") { dismiss() }.accessibilityIdentifier("cancelAddModel")
                }
                ToolbarItemGroup(placement: .keyboard) { Spacer(); Button("完成") { editing = false } }
            }
            .sheet(isPresented: $showFetched) {
                FetchedModelPicker(models: fetched, kept: kept) { add($0) }
            }
        }
        .onDisappear { job?.cancel() }
    }

    private func add(_ id: String) {
        editing = false
        guard !id.isEmpty, !kept.contains(id) else { return }
        store.changeProviders { providers in
            providers.update(entryID) { provider in
                // A new model starts with the format the provider's other models use.
                provider.upsert(AIModelEntry(id: id, style: provider.styleForNewModel))
            }
        }
        onAdded("已添加 \(id)。")
        dismiss()
    }

    private func fetchModels() {
        guard let entry else { return }
        editing = false; busy = true; error = nil
        let apiKey = store.apiKey(for: entryID)
        job = Task {
            defer { busy = false }
            do {
                fetched = try await AIProviderRequests.models(
                    baseURL: entry.baseURL, style: entry.styleForNewModel, key: apiKey
                )
                try Task.checkCancellation()
                showFetched = true
            } catch is CancellationError {
            } catch {
                self.error = AIProviderRequests.message(for: error)
            }
        }
    }
}

/// One model: its interface format, reasoning depth, a connection test, and
/// the switches to put it in use or delete it.
struct ModelDetailView: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var store: TranslationSettingsStore
    let entryID: String
    let modelID: String
    @State private var advanced = true
    @State private var testing = false
    @State private var outcome: Outcome?
    @State private var job: Task<Void, Never>?
    @State private var confirmDelete = false
    @State private var error: String?

    private enum Outcome: Equatable {
        case passed(TimeInterval), failed(String)
    }

    private var entry: AIProviderEntry? { store.aiProviders.entry(entryID) }
    private var kind: DirectAIProviderChoice { entry?.kind ?? .openAICompatible }
    private var model: AIModelEntry? { entry?.models.first { $0.id == modelID } }
    private var inUse: Bool { store.aiProviders.isInUse(model: modelID, of: entryID) }
    private var hasKey: Bool { store.keyedProviderIDs.contains(entryID) }
    /// Gemini's own format is offered only to a model that already uses it.
    private var styles: [AIAPIStyle] {
        AIAPIStyle.selectable + (model?.style == .geminiNative ? [.geminiNative] : [])
    }

    var body: some View {
        List {
            Section {
                ProviderHeader(provider: kind, name: entry?.name,
                               title: modelID, detail: store.aiProviders.displayName(for: entryID))
            }
            Section {
                Picker("接口格式", selection: style) {
                    ForEach(styles) { Text($0.displayName).tag($0) }
                }
                .accessibilityIdentifier("apiStylePicker")
            } header: { Text("接口格式").sectionTitle() } footer: {
                Text("照服务商文档里写的格式选；测试连接通了就对。")
            }
            if model?.style.supportsReasoning ?? true {
                Section {
                    DisclosureRow(title: "高级", isExpanded: $advanced)
                    if advanced {
                        Picker("思考强度", selection: reasoning) {
                            Text("自动").tag(AIReasoningEffort?.none)
                            ForEach(kind.reasoningOptions) { Text($0.title).tag(Optional($0)) }
                        }
                        .accessibilityIdentifier("reasoningPicker")
                    }
                } footer: {
                    if advanced {
                        Text(kind.reasoningNote + "强度越高越慢、费用越高。模型不支持所选档位时会显示服务商原话，改回自动即可。")
                    }
                }
            }
            Section {
                Button { test() } label: {
                    HStack {
                        Text("测试连接")
                        Spacer()
                        if testing { ProgressView() }
                    }
                }
                .disabled(testing).accessibilityIdentifier("testConnectionButton")
                testStatus
            } footer: {
                Text("发送一句“Hello.”看这个模型能不能用，可能产生少量 API 费用。")
            }
            Section {
                Button { activate() } label: {
                    HStack {
                        Text(inUse ? "正在使用" : "设为正在用")
                        Spacer()
                        if inUse {
                            Image(systemName: "checkmark").fontWeight(.semibold)
                                .foregroundStyle(JerreaderTheme.accent)
                                .accessibilityHidden(true)
                        }
                    }
                }
                .disabled(inUse || !hasKey).accessibilityIdentifier("setActiveModelButton")
            } footer: {
                // The fresh default is already in use without a key; it then needs the key to translate.
                if !hasKey {
                    Text(inUse ? "这个供应商还没有填 API Key，填好后才能翻译。" : "这个供应商还没有填 API Key，填好后才能设为正在用。")
                }
            }
            Section {
                Button("删除这个模型", role: .destructive) { confirmDelete = true }
                    .disabled(inUse).accessibilityIdentifier("deleteModelButton")
            } footer: {
                if inUse { Text("正在用的模型不能删除，先换到别的模型。") }
            }
        }
        .settingsList()
        .navigationTitle("模型").navigationBarTitleDisplayMode(.inline)
        .safeAreaInset(edge: .top, spacing: 0) { SettingsFeedback(error: error, notice: nil) }
        .confirmationDialog("删除 \(modelID)？", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("删除", role: .destructive) { remove() }
        } message: {
            Text("只是从这个供应商的列表里移除，以后可以再添加。")
        }
        .onDisappear { job?.cancel() }
    }

    @ViewBuilder private var testStatus: some View {
        switch outcome {
        case .passed(let seconds):
            Label(String(format: "连接正常，用时 %.1f 秒", seconds), systemImage: "checkmark.circle.fill")
                .font(.subheadline).foregroundStyle(JerreaderTheme.accent)
                .accessibilityIdentifier("connectionResult")
        case .failed(let message):
            Label {
                Text(message).font(.subheadline).foregroundStyle(.primary)
                    .fixedSize(horizontal: false, vertical: true)
            } icon: {
                Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.red)
            }
            .accessibilityIdentifier("connectionResult")
        case nil:
            Text(testing ? "正在等服务商回复…" : "还没有测试过").font(.subheadline).foregroundStyle(.secondary)
                .accessibilityIdentifier("connectionResult")
        }
    }

    private var style: Binding<AIAPIStyle> {
        Binding(get: { model?.style ?? kind.defaultStyle }, set: { value in update { $0.style = value } })
    }

    private var reasoning: Binding<AIReasoningEffort?> {
        Binding(get: { model?.reasoning }, set: { value in update { $0.reasoning = value } })
    }

    /// Saves a change to this model at once; an earlier test result no longer applies.
    private func update(_ change: (inout AIModelEntry) -> Void) {
        guard var item = model else { return }
        change(&item)
        guard item != model else { return }
        let changed = item
        store.changeProviders { providers in
            providers.update(entryID) { $0.upsert(changed) }
        }
        error = nil
        outcome = nil
    }

    private func activate() {
        store.changeProviders { $0.activate(entryID, model: modelID) }
        error = nil
    }

    private func remove() {
        do {
            try store.changeProviders { try $0.removeModel(modelID, from: entryID) }
            dismiss()
        } catch {
            self.error = AIProviderRequests.message(for: error)
        }
    }

    private func test() {
        guard let entry, let item = model else { return }
        let configuration: DirectAITranslationConfiguration
        do {
            configuration = try store.configuration(entry: entry, model: item, key: store.apiKey(for: entryID))
        } catch {
            outcome = .failed(AIProviderRequests.message(for: error))
            return
        }
        testing = true
        outcome = nil
        error = nil
        job = Task {
            defer { testing = false }
            do {
                outcome = .passed(try await AIProviderRequests.check(configuration))
            } catch is CancellationError {
            } catch {
                outcome = .failed(AIProviderRequests.message(for: error))
            }
        }
    }
}
