import SwiftUI

// MARK: - Shared wording

extension DirectAIProviderChoice {
    /// What a provider still needs before it can translate.
    var missingCredentialText: String {
        usesAccountSignIn ? "还没有登录 ChatGPT" : "还没有填 API Key"
    }

    /// The short status shown in provider lists.
    var missingCredentialTag: String { usesAccountSignIn ? "未登录" : "未填密钥" }
}

extension TranslationSettingsStore {
    /// The name shown for a model: a plan model by the name its account
    /// gives it, any other by its id.
    func modelTitle(_ id: String, in entry: AIProviderEntry) -> String {
        entry.kind.usesAccountSignIn ? chatGPTPlanModelName(id) : id
    }
}

// MARK: - 套餐 page

/// The 套餐 tab of 添加供应商: plans that pay for translations without an
/// API key, kept to what each provider's terms allow.
struct PlanOptionsList: View {
    @ObservedObject var store: TranslationSettingsStore
    var finish: (String) -> Void
    @State private var busy = false
    @State private var error: String?
    @State private var job: Task<Void, Never>?
    @State private var showWelcome = false
    @State private var pendingNotice: String?
    @AppStorage(ChatGPTPlanRegistration.welcomeShownKey) private var welcomeShown = false

    private var account: ChatGPTPlanAccount? { store.chatGPTPlanAccount }
    private var claudeAdded: Bool { store.aiProviders.hasSetUp(.anthropic, keyed: store.keyedProviderIDs) }

    var body: some View {
        List {
            // The ChatGPT card is the page's hero, on its own clear row; once
            // signed in, the account rows follow as a paper group of their own.
            Section {
                chatGPTCard
                    .listRowInsets(EdgeInsets())
                    .listRowBackground(Color.clear)
            } header: { Text("ChatGPT 套餐").sectionTitle() } footer: {
                if account == nil { chatGPTFooter }
            }

            if account != nil {
                Section {
                    NavigationLink {
                        ChatGPTPlanDetailView(store: store, entryID: TranslationSettingsStore.chatGPTPlanID)
                    } label: { Text("账号与模型") }
                    .accessibilityIdentifier("planAccountLink")
                    ExternalLinkRow(title: "管理用量", url: ChatGPTPlan.manageUsageURL)
                } footer: { chatGPTFooter }
            }

            Section {
                claudeCard
                ExternalLinkRow(title: "打开 claude.ai 的账单设置", url: PlanLinks.claudeBilling)
                ExternalLinkRow(title: "去 Claude 控制台创建 API Key", url: PlanLinks.claudeKeys)
                if claudeAdded {
                    HStack {
                        Text("Claude 已添加").foregroundStyle(.secondary)
                        Spacer()
                        JerreaderSymbol(name: "checkmark", size: 18).foregroundStyle(JerreaderTheme.jade)
                    }
                    .accessibilityIdentifier("planClaudeAdded")
                } else {
                    NavigationLink {
                        ProviderSetupForm(store: store, kind: .anthropic, finish: finish)
                    } label: { Text("添加 Claude 并填入 API Key") }
                    .accessibilityIdentifier("planAddClaude")
                }
            } header: { Text("Claude 套餐额度").sectionTitle() } footer: {
                Text("额度挂在 Console 组织上，用法和普通 API Key 一样；费用先从额度里扣，扣完后是否继续按量付费，看 Console 里的设置。")
            }

            Section {
            } footer: {
                Text("Coding Plan、Token Plan 这类编程套餐的条款只允许在编程工具里使用，阅读翻译不在其列，所以这里不提供。")
                    .accessibilityIdentifier("planCodingNote")
            }
        }
        .settingsList(icons: true)
        .safeAreaInset(edge: .top, spacing: 0) {
            VStack(spacing: 0) {
                if busy { SettingsBusyBar { job?.cancel() } }
                SettingsFeedback(error: error, notice: nil)
            }
        }
        // The welcome closes first; then the whole 添加供应商 sheet does.
        .sheet(isPresented: $showWelcome, onDismiss: { if let pendingNotice { finish(pendingNotice) } }) {
            ChatGPTPlanWelcomeView()
        }
        .onDisappear { job?.cancel() }
    }

    /// Why the sign-in is marked 实验: under the card, or under the account rows once signed in.
    private var chatGPTFooter: some View {
        Text("走 OpenAI 为开源应用提供的“Sign in with ChatGPT”。手机上的登录方式 OpenAI 还没有写进文档，所以标为实验；登录不上时请改用 API Key。")
    }

    /// The hero card: the theme colour running into ink, white text, and the
    /// sign-in button in white.
    private var chatGPTCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            PlanCardHeader(provider: .chatGPTPlan, title: "ChatGPT 套餐", tag: "实验",
                           detail: account.map(Self.accountLine) ?? "用 ChatGPT 账号登录，不用 API Key",
                           onHero: true)
            PlanFacts(items: [
                "翻译和语法解释用你的 Plus、Pro 等套餐额度，不另外收费",
                "Plus 的 5 小时额度和 ChatGPT、Codex 等应用共用",
                "额度用完就暂停，不会改成按量付费",
                "密码只在 OpenAI 的页面输入，登录信息只存在本机钥匙串",
            ], onHero: true)
            if account == nil {
                ContinueWithChatGPTButton(busy: busy, onHero: true) { signIn() }
                    .padding(.top, 2)
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(JerreaderHeroBackground())
        // A container, so the sign-in button inside keeps its own identifier.
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("planChatGPTCard")
    }

    private var claudeCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            PlanCardHeader(provider: .anthropic, title: "Claude Max / Ultra 的 API 额度", tag: nil,
                           detail: "订阅每月附带的 API 额度，用 Console 的 API Key 使用")
            PlanSteps(items: [
                "在 claude.ai 的“设置 › 账单 › API 额度”领取，并关联一个 Console 组织（关联后不能改；新订阅要等 7 天）",
                "到 Claude 控制台新建一个 API Key；建议单独建一个工作区并设好消费上限",
                "回到这里添加 Claude，填上这个 Key",
            ])
            PlanFacts(items: [
                "Max 每月 $100、Ultra 每月 $200，每个账单周期刷新，不累积",
                "只用于 API，不能给 Claude Code 用",
            ])
        }
        .padding(.vertical, 6)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("planClaudeCard")
    }

    static func accountLine(_ account: ChatGPTPlanAccount) -> String {
        [account.email, account.planTitle].compactMap { $0 }.joined(separator: " · ").ifEmpty("已登录")
    }

    private func signIn() {
        busy = true
        error = nil
        job = Task {
            defer { busy = false }
            do {
                let notice = try await ChatGPTPlanActions.signIn(store: store)
                if welcomeShown {
                    finish(notice)
                } else {
                    welcomeShown = true
                    pendingNotice = notice
                    showWelcome = true
                }
            } catch is CancellationError {
            } catch ChatGPTPlanError.cancelled {
            } catch {
                self.error = AIProviderRequests.message(for: error)
            }
        }
    }
}

enum PlanLinks {
    static let claudeBilling = URL(string: "https://claude.ai/settings/billing")!
    static let claudeKeys = URL(string: "https://platform.claude.com/settings/keys")!
}

private extension String {
    func ifEmpty(_ fallback: String) -> String { isEmpty ? fallback : self }
}

/// Icon, title, tag and one line under it.
struct PlanCardHeader: View {
    var provider: DirectAIProviderChoice
    var title: String
    var tag: String?
    var detail: String
    /// White type, a larger title and a white-edged mark, for the hero card.
    var onHero = false

    var body: some View {
        HStack(spacing: 13) {
            ProviderIcon(provider: provider, size: 44, edge: onHero ? Color.white.opacity(0.35) : nil)
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 7) {
                    Text(title).font(onHero ? Font.title2.weight(.bold) : Font.headline)
                        .foregroundStyle(onHero ? Color.white : Color.primary).lineLimit(2)
                    if let tag { TagLabel(text: tag, tint: onHero ? Color.white : nil) }
                }
                Text(detail).font(.footnote)
                    .foregroundStyle(onHero ? Color.white.opacity(0.78) : Color.secondary).lineLimit(2)
            }
            .alignmentGuide(.listRowSeparatorLeading) { $0[.leading] }
        }
        .accessibilityElement(children: .combine)
    }
}

/// Short facts with a check in front.
struct PlanFacts: View {
    var items: [String]
    /// White type and checks, for the hero card.
    var onHero = false

    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            ForEach(items, id: \.self) { item in
                Label {
                    Text(item).font(.subheadline)
                        .foregroundStyle(onHero ? Color.white.opacity(0.92) : Color.primary)
                        .fixedSize(horizontal: false, vertical: true)
                } icon: {
                    // An SF Symbol, not a drawn one: it sits on the text's baseline.
                    Image(systemName: "checkmark.circle.fill").font(.footnote)
                        .foregroundStyle(onHero ? Color.white : JerreaderTheme.accent)
                }
                .labelStyle(PlanFactLabelStyle())
            }
        }
    }
}

/// Numbered steps.
struct PlanSteps: View {
    var items: [String]

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            ForEach(Array(items.enumerated()), id: \.offset) { index, item in
                HStack(alignment: .firstTextBaseline, spacing: 10) {
                    // The digit takes the colour drawn on the accent, which is dark on
                    // the light dark-mode accents.
                    Text("\(index + 1)")
                        .font(.system(.caption, design: .rounded).weight(.bold).monospacedDigit())
                        .foregroundStyle(JerreaderTheme.onPrimaryAction)
                        .frame(width: 22, height: 22)
                        .background(JerreaderTheme.accent, in: Circle())
                        .alignmentGuide(.firstTextBaseline) { $0[VerticalAlignment.center] + 4 }
                    Text(item).font(.subheadline).foregroundStyle(Color.primary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .accessibilityElement(children: .combine)
            }
        }
    }
}

private struct PlanFactLabelStyle: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            configuration.icon
            configuration.title
        }
    }
}

/// The sign-in button OpenAI's guidelines ask for: its own words, as
/// prominent as any other way to sign in.
struct ContinueWithChatGPTButton: View {
    var busy = false
    /// On the hero card the button is white in either appearance.
    var onHero = false
    var action: () -> Void
    @Environment(\.colorScheme) private var colorScheme

    /// White with black type on the hero and in dark mode; black otherwise.
    private var isWhite: Bool { onHero || colorScheme == .dark }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                if busy {
                    ProgressView().tint(isWhite ? .black : .white)
                } else {
                    Image(systemName: "person.crop.circle.badge.checkmark").font(.body.weight(.semibold))
                }
                Text("Continue with ChatGPT").font(.body.weight(.semibold))
            }
            .frame(maxWidth: .infinity, minHeight: 50)
            .foregroundStyle(isWhite ? Color.black : Color.white)
            .background(isWhite ? Color.white : Color.black, in: Capsule())
            .contentShape(Capsule())
        }
        .buttonStyle(JerreaderPressButtonStyle())
        .disabled(busy)
        .accessibilityIdentifier("continueWithChatGPTButton")
    }
}

// MARK: - Welcome

/// Shown once, after the first sign-in that puts the plan to use: a hero card
/// whose mark springs in (`hero`), then the three facts on paper, revealed in
/// turn, and a success haptic.
struct ChatGPTPlanWelcomeView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var appeared = false

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    hero
                        .jerreaderReveal(order: 0)
                    VStack(alignment: .leading, spacing: 0) {
                        welcomeRow("chart.bar", tint: JerreaderTheme.accent, "用的是套餐额度",
                                   "不另外收费。Plus 的 5 小时额度和 ChatGPT、Codex 等应用共用，Pro 没有这个限制。",
                                   first: true)
                            .jerreaderReveal(order: 1)
                        welcomeRow("hand.raised", tint: SettingsTint.reading, "用完就暂停",
                                   "额度用完时翻译会停下并提示你，不会改成按量付费。")
                            .jerreaderReveal(order: 2)
                        welcomeRow("lock", tint: SettingsTint.privacy, "只发送需要的文字",
                                   "只把你选中或输入的文字发给 OpenAI；登录信息只存在本机钥匙串，不进备份。")
                            .jerreaderReveal(order: 3)
                    }
                    .jerreaderPaperCard(padding: 0, radius: JerreaderRadius.card)
                }
                .padding(.horizontal, 20).padding(.top, 28).padding(.bottom, 16)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            VStack(spacing: 12) {
                Button { dismiss() } label: { Text("开始使用") }
                    .buttonStyle(JerreaderPrimaryButtonStyle())
                    .accessibilityIdentifier("planWelcomeDone")
                Link("管理用量", destination: ChatGPTPlan.manageUsageURL).font(.subheadline.weight(.medium))
            }
            .padding(.horizontal, 20).padding(.top, 8).padding(.bottom, 20)
        }
        .background(JerreaderCanvasBackground())
        .presentationDetents([.large])
        .interactiveDismissDisabled()
        .sensoryFeedback(.success, trigger: appeared)
        .onAppear {
            withAnimation(JerreaderMotion.choose(JerreaderMotion.hero, reduceMotion: reduceMotion)) { appeared = true }
        }
    }

    private var hero: some View {
        VStack(alignment: .leading, spacing: 16) {
            ProviderIcon(provider: .chatGPTPlan, size: 64, edge: Color.white.opacity(0.35))
                .scaleEffect(appeared || reduceMotion ? 1 : 0.6).opacity(appeared ? 1 : 0)
            VStack(alignment: .leading, spacing: 8) {
                Text("已连接 ChatGPT 套餐").font(.title2.weight(.bold)).foregroundStyle(.white)
                Text("Jerreader 现在用你的 ChatGPT 套餐翻译和讲解语法。")
                    .font(.body).foregroundStyle(Color.white.opacity(0.82))
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(JerreaderHeroBackground())
    }

    private func welcomeRow(_ symbol: String, tint: Color, _ title: String, _ text: String,
                            first: Bool = false) -> some View {
        HStack(alignment: .top, spacing: 14) {
            JerreaderIconTile(systemImage: symbol, tint: tint, size: 36)
            VStack(alignment: .leading, spacing: 3) {
                Text(title).font(.headline)
                Text(text).font(.subheadline).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
        }
        .padding(16)
        .overlay(alignment: .top) {
            if !first {
                Rectangle().fill(JerreaderTheme.line).frame(height: 0.5).padding(.leading, 66)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

// MARK: - The plan's own page

/// The ChatGPT plan as a provider: its account, its models, sign-in and
/// sign-out. Opened from the providers list like any other provider.
struct ChatGPTPlanDetailView: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var store: TranslationSettingsStore
    let entryID: String
    @State private var busy = false
    @State private var notice: String?
    @State private var error: String?
    @State private var job: Task<Void, Never>?
    @State private var confirmSignOut = false
    @State private var confirmDelete = false
    @State private var showWelcome = false
    @State private var pendingNotice: String?
    @AppStorage(ChatGPTPlanRegistration.welcomeShownKey) private var welcomeShown = false

    private var entry: AIProviderEntry? { store.aiProviders.entry(entryID) }
    private var account: ChatGPTPlanAccount? { store.chatGPTPlanAccount }
    private var isActive: Bool { store.aiProviders.activeID == entryID }

    var body: some View {
        List {
            Section {
                ProviderHeader(provider: .chatGPTPlan, title: "ChatGPT 套餐",
                               detail: account.map(PlanOptionsList.accountLine) ?? "未登录")
            }
            Section {
                if let account {
                    LabeledContent("账号", value: account.email ?? "已登录")
                        .accessibilityIdentifier("planAccountEmail")
                    LabeledContent("套餐", value: account.planTitle ?? "未知")
                    ExternalLinkRow(title: "管理用量", url: ChatGPTPlan.manageUsageURL)
                    Button("重新登录") { signIn(hint: account.email) }
                        .disabled(busy).accessibilityIdentifier("planSignInAgain")
                    Button("退出登录", role: .destructive) { confirmSignOut = true }
                        .disabled(busy).accessibilityIdentifier("planSignOut")
                } else {
                    ContinueWithChatGPTButton(busy: busy) { signIn(hint: nil) }
                        .listRowInsets(EdgeInsets(top: 12, leading: 16, bottom: 12, trailing: 16))
                }
            } header: { Text("账号").sectionTitle() } footer: {
                Text("翻译用这个账号的 ChatGPT 套餐额度。Plus 的 5 小时额度和 ChatGPT、Codex 等共用；用完会暂停，不会改成按量付费。")
            }
            Section {
                ForEach(entry?.models ?? []) { model in
                    NavigationLink {
                        ModelDetailView(store: store, entryID: entryID, modelID: model.id)
                    } label: { modelLabel(model) }
                    .accessibilityIdentifier("model-" + model.id)
                }
                if (entry?.models ?? []).isEmpty {
                    Text(account == nil ? "登录后显示这个账号能用的模型。" : "还没有模型，点“刷新模型”。")
                        .font(.subheadline).foregroundStyle(.secondary)
                }
                Button("刷新模型") { refresh() }
                    .disabled(busy || account == nil).accessibilityIdentifier("planRefreshModels")
            } header: { Text("模型").sectionTitle() } footer: {
                Text("列表来自 OpenAI，只列这个账号能用的模型。换了账号或套餐后点“刷新模型”。")
            }
            Section {
                ExternalLinkRow(title: "Sign in with ChatGPT 说明", url: ChatGPTPlan.documentationURL)
            }
            Section {
                Button("删除这个供应商", role: .destructive) { confirmDelete = true }
                    .disabled(isActive || busy).accessibilityIdentifier("deleteProviderButton")
            } footer: {
                if isActive { Text("正在用的供应商不能删除。先用“换模型”换到别的供应商。") }
            }
        }
        .settingsList()
        .navigationTitle("ChatGPT 套餐").navigationBarTitleDisplayMode(.inline)
        .safeAreaInset(edge: .top, spacing: 0) {
            VStack(spacing: 0) {
                if busy { SettingsBusyBar { job?.cancel() } }
                SettingsFeedback(error: error, notice: notice)
            }
        }
        .confirmationDialog("退出 ChatGPT 登录？", isPresented: $confirmSignOut, titleVisibility: .visible) {
            Button("退出登录", role: .destructive) { signOut() }
        } message: {
            Text("本机删除登录信息，并请 OpenAI 作废这次登录。模型列表会保留，重新登录后接着用。")
        }
        .confirmationDialog("删除 ChatGPT 套餐？", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("删除", role: .destructive) { remove() }
        } message: {
            Text("会退出登录，并从本机移除它的模型设置。")
        }
        .sheet(isPresented: $showWelcome, onDismiss: { notice = pendingNotice }) {
            ChatGPTPlanWelcomeView()
        }
        .task { await ChatGPTPlanActions.sync(store: store) }
        .onDisappear { job?.cancel() }
    }

    private func modelLabel(_ model: AIModelEntry) -> some View {
        let name = store.chatGPTPlanModelName(model.id)
        var parts: [String] = []
        if name != model.id { parts.append(model.id) }
        if let reasoning = model.reasoning { parts.append("思考" + reasoning.title) }
        return ModelLabel(id: name, detail: parts.isEmpty ? nil : parts.joined(separator: " · "),
                          tag: store.aiProviders.isInUse(model: model.id, of: entryID) ? "正在用" : nil)
    }

    private func signIn(hint: String?) {
        run {
            let message = try await ChatGPTPlanActions.signIn(store: store, loginHint: hint)
            if welcomeShown {
                notice = message
            } else {
                welcomeShown = true
                pendingNotice = message
                showWelcome = true
            }
        }
    }

    private func refresh() {
        run { notice = try await ChatGPTPlanActions.refreshModels(store: store) }
    }

    private func signOut() {
        run {
            await ChatGPTPlanActions.signOut(store: store)
            notice = "已退出 ChatGPT 登录。"
        }
    }

    private func remove() {
        do {
            try store.removeProvider(entryID)
            Task { await ChatGPTPlanSession.shared.signOut() }
            dismiss()
        } catch {
            self.error = AIProviderRequests.message(for: error)
            notice = nil
        }
    }

    private func run(_ work: @escaping @MainActor () async throws -> Void) {
        busy = true
        error = nil
        notice = nil
        job = Task {
            defer { busy = false }
            do {
                try await work()
            } catch is CancellationError {
            } catch ChatGPTPlanError.cancelled {
            } catch {
                if (error as? ChatGPTPlanError) == .signInRequired { store.chatGPTPlanSignedOut() }
                self.error = AIProviderRequests.message(for: error)
            }
        }
    }
}

// MARK: - Near the composer

/// “正在使用 ChatGPT 套餐 · 管理用量”, shown where text is typed for
/// translation while the plan pays for it.
struct ChatGPTPlanIndicator: View {
    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: "sparkles").font(.caption2.weight(.semibold)).accessibilityHidden(true)
            Text("正在使用 ChatGPT 套餐").font(.caption.weight(.medium))
            Text("·").font(.caption).accessibilityHidden(true)
            Link("管理用量", destination: ChatGPTPlan.manageUsageURL).font(.caption.weight(.semibold))
        }
        .foregroundStyle(.secondary)
        .padding(.horizontal, 10).padding(.vertical, 5)
        .background(.thinMaterial, in: Capsule())
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("chatGPTPlanIndicator")
    }
}
