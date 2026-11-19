import SwiftUI

/// 设置 › AI 大模型: the model in use, the providers that were set up, and
/// the way to add more.
struct AIModelsView: View {
    @ObservedObject var store: TranslationSettingsStore
    @State private var showSwitcher = false
    @State private var showAdd = false
    @State private var showHelp = false
    @State private var notice: String?
    @State private var error: String?

    private var providers: AIProviderSettings { store.aiProviders }
    private var active: AIProviderEntry { providers.active }
    private var activeModel: String { active.model.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var activeHasKey: Bool { store.keyedProviderIDs.contains(providers.activeID) }

    var body: some View {
        List {
            Section {
                ProviderHeader(provider: active.kind, name: active.name, caption: "Jerreader 正在用",
                               title: activeModel.isEmpty ? "还没有选模型" : store.modelTitle(activeModel, in: active),
                               detail: active.displayName)
                    // The line under the card runs its whole width, not from the text beside the icon.
                    .alignmentGuide(.listRowSeparatorLeading) { _ in 0 }
                    .accessibilityIdentifier("activeModelCard")
                Button("换模型") { showSwitcher = true }.accessibilityIdentifier("switchModelButton")
                if activeHasKey, store.provider != .directAPI {
                    Button("用它翻译") {
                        store.provider = .directAPI
                        notice = "翻译服务已换成 AI 大模型。"
                        error = nil
                    }
                    .accessibilityIdentifier("useAIModelsButton")
                }
            } footer: { activeFooter }

            Section {
                ForEach(providers.entries) { entry in
                    NavigationLink { ProviderDetailView(store: store, entryID: entry.id) } label: { providerLabel(entry) }
                        .accessibilityIdentifier("provider-" + entry.id)
                }
                Button { showAdd = true } label: {
                    HStack(spacing: 13) {
                        JerreaderIconTile(systemImage: "plus", size: 34)
                        Text("添加供应商").foregroundStyle(Color.primary)
                        Spacer()
                        JerreaderSymbol(name: "chevron.right", size: 16)
                            .foregroundStyle(Color(uiColor: .tertiaryLabel))
                    }
                }
                .accessibilityIdentifier("addProviderButton")
            } header: { Text("供应商").sectionTitle() } footer: {
                Text("每个供应商各自保存密钥、地址和模型。点开可以增删模型，或改接口格式和思考强度。")
            }
        }
        .settingsList(icons: true)
        .navigationTitle("AI 大模型").navigationBarTitleDisplayMode(.inline)
        .safeAreaInset(edge: .top, spacing: 0) { SettingsFeedback(error: error, notice: notice) }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { showHelp = true } label: { Image(systemName: "questionmark.circle") }
                    .accessibilityLabel("说明").accessibilityIdentifier("aiHelpButton")
            }
        }
        .sheet(isPresented: $showSwitcher) { ModelSwitcherView(store: store) { notice = $0; error = nil } }
        .sheet(isPresented: $showAdd) { AddProviderView(store: store) { notice = $0; error = nil } }
        .sheet(isPresented: $showHelp) { AIHelpView() }
        .task { await ChatGPTPlanActions.sync(store: store) }
    }

    @ViewBuilder private var activeFooter: some View {
        if !activeHasKey {
            Label {
                Text(active.kind.usesAccountSignIn ? "ChatGPT 套餐还没有登录，登录后才能翻译。" : "这个供应商还没有填 API Key，填好后才能翻译。")
            } icon: {
                JerreaderSymbol(name: "exclamationmark.triangle", size: 15)
            }
                .foregroundStyle(.orange).accessibilityIdentifier("missingKeyHint")
        } else if store.provider != .directAPI {
            Text("翻译服务目前选的是“\(store.provider.title)”，阅读和学习页暂时不会用这里的模型。")
        }
    }

    private func providerLabel(_ entry: AIProviderEntry) -> some View {
        let keyed = store.keyedProviderIDs.contains(entry.id)
        let count = entry.models.count
        return ProviderRowLabel(provider: entry.kind, name: entry.name,
                                title: entry.displayName,
                                detail: keyed ? "\(count) 个模型" : "\(count) 个模型 · \(entry.kind.missingCredentialTag)",
                                detailColor: keyed ? .secondary : .orange,
                                tag: entry.id == providers.activeID ? "正在用" : nil)
    }
}

/// 换模型: every kept model of every provider; one tap puts it in use.
struct ModelSwitcherView: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var store: TranslationSettingsStore
    var onSwitch: (String) -> Void

    var body: some View {
        NavigationStack {
            List {
                ForEach(store.aiProviders.entries) { entry in section(entry) }
            }
            .settingsList()
            .navigationTitle("换模型").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("完成") { dismiss() }.accessibilityIdentifier("closeModelSwitcher")
                }
            }
        }
    }

    private func section(_ entry: AIProviderEntry) -> some View {
        let keyed = store.keyedProviderIDs.contains(entry.id)
        return Section {
            ForEach(entry.models) { model in
                Button { choose(entry, model.id) } label: {
                    HStack {
                        ModelLabel(id: store.modelTitle(model.id, in: entry),
                                   detail: detail(model.id, in: entry),
                                   dimmed: !keyed)
                        Spacer()
                        if store.aiProviders.isInUse(model: model.id, of: entry.id) {
                            Image(systemName: "checkmark").fontWeight(.semibold)
                                .foregroundStyle(JerreaderTheme.accent)
                                .accessibilityLabel("正在用")
                        }
                    }
                }
                .disabled(!keyed)
                .accessibilityIdentifier("switch-\(entry.id)-\(model.id)")
            }
            if entry.models.isEmpty {
                Text("还没有模型，到这个供应商的页面里添加。").font(.subheadline).foregroundStyle(.secondary)
            }
        } header: {
            Text(entry.displayName).sectionTitle()
        } footer: {
            if !keyed { Text(entry.kind.missingCredentialText + "，暂时不能选。") }
        }
    }

    private func detail(_ id: String, in entry: AIProviderEntry) -> String? {
        if entry.kind.usesAccountSignIn {
            let name = store.modelTitle(id, in: entry)
            return name == id ? nil : id
        }
        return entry.kind.modelPresets.first { $0.id == id }.map { $0.title + " · " + $0.detail }
    }

    private func choose(_ entry: AIProviderEntry, _ model: String) {
        store.changeProviders { $0.activate(entry.id, model: model) }
        onSwitch("已换成 \(entry.displayName) 的 \(store.modelTitle(model, in: entry))。")
        dismiss()
    }
}

struct AIHelpView: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                item("供应商是什么", "提供大模型接口的服务商，比如 Claude、ChatGPT、DeepSeek。Jerreader 自己不提供模型：你在服务商那里申请 API Key 填到这里，翻译时由 Jerreader 直接向它发请求，费用由服务商按用量收取。")
                item("怎么添加", "点“添加供应商”，选一家，填上 API Key 就可以。列表里没有的服务用“自定义”，填它的 API 地址、接口格式和模型 ID。")
                item("套餐", "不想按量付 API 费用，可以在“添加供应商 › 套餐”里用订阅额度：ChatGPT 套餐用 ChatGPT 账号登录，翻译用 Plus、Pro 等套餐的额度（实验功能）；Claude 的 Max、Ultra 每月附带 API 额度，领取后用 Console 的 API Key 添加 Claude 即可。编程专用的套餐条款不允许用在这里，所以没有列出。")
                item("换模型", "一个供应商下面可以留几个模型。“Jerreader 正在用”的那一个负责翻译和句子结构分析；点“换模型”可以在所有填了密钥的供应商之间切换。")
                item("接口格式和思考强度", "点开某个模型可以单独设置。接口格式照服务商文档选，测试连接能通就对；思考强度越高，译得越慢、费用越高，日常翻译用“自动”即可。")
                item("会发送什么", "只发送你选中或输入的文字，以及必要的上下文。API Key 只保存在本机钥匙串里，不会写进备份。")
            }
            .settingsList()
            .navigationTitle("说明").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("完成") { dismiss() }.accessibilityIdentifier("closeAIHelp")
                }
            }
        }
    }

    private func item(_ title: String, _ text: String) -> some View {
        Section {
            VStack(alignment: .leading, spacing: 6) {
                Text(title).font(.headline)
                Text(text).font(.subheadline).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.vertical, 6)
        }
    }
}
