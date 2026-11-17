import SwiftUI
import UIKit

/// A provider's mark: a letter or symbol on the provider's colour. No
/// third-party logos are bundled; a logo image of the provider's asset name is
/// used when one is added.
struct ProviderIcon: View {
    var provider: DirectAIProviderChoice
    /// The user's own name for a custom service; its first character becomes the mark.
    var name: String? = nil
    var size: CGFloat = 34

    private var customInitial: String? {
        guard provider.isCustom, let first = name?.trimmingCharacters(in: .whitespacesAndNewlines).first else { return nil }
        return String(first).uppercased()
    }

    var body: some View {
        // The mark is an overlay: a list draws its row separator from the first
        // text in the row, and that should be the provider's name, not this letter.
        RoundedRectangle(cornerRadius: size * 0.27, style: .continuous)
            .fill(logo == nil ? AnyShapeStyle(provider.tint.gradient) : AnyShapeStyle(Color.white))
            .frame(width: size, height: size)
            .overlay {
                if let logo {
                    Image(uiImage: logo).resizable().scaledToFit().padding(size * 0.16)
                } else {
                    Group {
                        if let letter = customInitial ?? provider.monogram {
                            Text(letter).font(.system(size: size * 0.46, weight: .bold, design: .rounded))
                        } else {
                            Image(systemName: provider.symbol).font(.system(size: size * 0.44, weight: .semibold))
                        }
                    }
                    .foregroundStyle(.white)
                }
            }
            .accessibilityHidden(true)
    }

    private var logo: UIImage? { UIImage(named: provider.localLogoAssetName) }
}

extension DirectAIProviderChoice {
    fileprivate var monogram: String? {
        switch self {
        case .anthropic: return "C"
        case .openAI: return "G"
        case .deepSeek: return "D"
        case .qwen: return "通"
        case .doubao: return "豆"
        case .kimi: return "K"
        case .zhipu: return "智"
        case .minimax: return "M"
        case .gemini, .openAICompatible: return nil
        }
    }

    fileprivate var symbol: String { self == .gemini ? "sparkle" : "server.rack" }

    fileprivate var tint: Color {
        let hex: Int
        switch self {
        case .anthropic: hex = 0xC96442
        case .openAI: hex = 0x0E8F6F
        case .deepSeek: hex = 0x4D6BFE
        case .qwen: hex = 0x6A4DF4
        case .doubao: hex = 0x1F7AE0
        case .kimi: hex = 0x2B2B33
        case .zhipu: hex = 0x2F54EB
        case .minimax: hex = 0xD9365E
        case .gemini: hex = 0x3B7DED
        case .openAICompatible: hex = 0x6E7380
        }
        return Color(
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255
        )
    }
}

/// A small capsule label such as 推荐 or 正在用.
struct TagLabel: View {
    var text: String

    var body: some View {
        Text(text).font(.caption2.weight(.semibold)).foregroundStyle(JerreaderTheme.accent)
            .padding(.horizontal, 7).padding(.vertical, 2.5)
            .background(JerreaderTheme.accent.opacity(0.14), in: Capsule())
            .fixedSize()
    }
}

/// Icon, name and one line of detail: the row used for providers.
struct ProviderRowLabel: View {
    var provider: DirectAIProviderChoice
    var name: String?
    var title: String
    var detail: String
    var detailColor: Color = .secondary
    var tag: String? = nil
    var iconSize: CGFloat = 34

    var body: some View {
        HStack(spacing: 13) {
            ProviderIcon(provider: provider, name: name, size: iconSize)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 7) {
                    Text(title).foregroundStyle(Color.primary).lineLimit(1)
                    if let tag { TagLabel(text: tag) }
                }
                Text(detail).font(.footnote).foregroundStyle(detailColor).lineLimit(2)
            }
            .alignmentGuide(.listRowSeparatorLeading) { $0[.leading] }
        }
        .accessibilityElement(children: .combine)
    }
}

/// A model id with one line of detail under it.
struct ModelLabel: View {
    var id: String
    var detail: String?
    var dimmed = false
    var tag: String? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 7) {
                Text(id).foregroundStyle(dimmed ? Color.secondary : Color.primary).lineLimit(1)
                if let tag { TagLabel(text: tag) }
            }
            if let detail { Text(detail).font(.footnote).foregroundStyle(Color.secondary).lineLimit(1) }
        }
        .padding(.vertical, ListMetrics.twoLinePadding)
    }
}

/// A row that shows a saved value and opens its editor.
struct ValueRow: View {
    var title: String
    var value: String
    var warn = false

    var body: some View {
        HStack {
            Text(title).foregroundStyle(Color.primary)
            Spacer()
            Text(value).lineLimit(1).foregroundStyle(warn ? Color.orange : Color.secondary)
            Image(systemName: "chevron.right").font(.footnote.weight(.semibold))
                .foregroundStyle(Color(uiColor: .tertiaryLabel)).accessibilityHidden(true)
        }
        .contentShape(Rectangle())
    }
}

/// A row that folds the rows after it. Unlike `DisclosureGroup` it does not
/// indent them.
struct DisclosureRow: View {
    var title: String
    @Binding var isExpanded: Bool

    var body: some View {
        Button { withAnimation(JerreaderMotion.stateChange) { isExpanded.toggle() } } label: {
            HStack {
                Text(title).foregroundStyle(Color.primary)
                Spacer()
                Image(systemName: "chevron.down").font(.footnote.weight(.semibold))
                    .foregroundStyle(Color(uiColor: .tertiaryLabel))
                    .rotationEffect(.degrees(isExpanded ? 0 : -90)).accessibilityHidden(true)
            }
            .contentShape(Rectangle())
        }
        .accessibilityValue(isExpanded ? "已展开" : "已收起")
        .accessibilityIdentifier("disclosure-" + title)
    }
}

/// A row that opens a web page outside the app.
struct ExternalLinkRow: View {
    var title: String
    var url: URL

    var body: some View {
        Link(destination: url) {
            HStack {
                Text(title)
                Spacer()
                Image(systemName: "arrow.up.right").font(.footnote.weight(.semibold))
                    .foregroundStyle(Color(uiColor: .tertiaryLabel)).accessibilityHidden(true)
            }
        }
    }
}

/// The header card of a provider or model page.
struct ProviderHeader: View {
    var provider: DirectAIProviderChoice
    var name: String?
    var caption: String? = nil
    var title: String
    var detail: String

    var body: some View {
        HStack(spacing: 15) {
            ProviderIcon(provider: provider, name: name, size: 48)
            VStack(alignment: .leading, spacing: 3) {
                if let caption { Text(caption).font(.footnote).foregroundStyle(.secondary) }
                Text(title).font(.title3.weight(.bold)).lineLimit(2).minimumScaleFactor(0.7)
                Text(detail).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }
}

struct ErrorBanner: View {
    var message: String

    var body: some View {
        // Red marks the state; the message itself stays in the primary colour so
        // a long provider message remains easy to read.
        Label {
            Text(message).font(.subheadline).foregroundStyle(.primary)
                .fixedSize(horizontal: false, vertical: true)
        } icon: {
            Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.red)
        }
        .padding(.horizontal, 14).padding(.vertical, 12).frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.red.opacity(0.1), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .accessibilityIdentifier("errorBanner")
    }
}

/// Error and notice shown above a settings list.
struct SettingsFeedback: View {
    var error: String?
    var notice: String?

    var body: some View {
        if error != nil || notice != nil {
            VStack(spacing: 8) {
                if let error { ErrorBanner(message: error) }
                if let notice {
                    Label(notice, systemImage: "checkmark.circle.fill")
                        .font(.subheadline).foregroundStyle(JerreaderTheme.accent)
                        .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 4).padding(.vertical, 6)
                        .accessibilityIdentifier("settingsNotice")
                }
            }
            .padding(.horizontal, 16).padding(.bottom, 6).background(JerreaderTheme.canvas)
        }
    }
}

/// The bar shown while a request to the provider is under way.
struct SettingsBusyBar: View {
    var cancel: () -> Void

    var body: some View {
        HStack {
            ProgressView()
            Text("正在连接…")
            Spacer()
            Button("取消", action: cancel)
        }
        .font(.subheadline).padding(.horizontal, 20).padding(.vertical, 10).background(JerreaderTheme.canvas)
    }
}

/// An API key field with a show/hide button.
struct APIKeyField: View {
    @Binding var key: String
    @State private var reveal = false
    var focus: FocusState<Bool>.Binding

    var body: some View {
        HStack {
            Group {
                if reveal { TextField("API Key", text: $key) } else { SecureField("API Key", text: $key) }
            }
            .textContentType(.password)
            .textInputAutocapitalization(.never).autocorrectionDisabled().privacySensitive().focused(focus)
            .accessibilityIdentifier("apiKeyField")
            Button { reveal.toggle() } label: {
                Image(systemName: reveal ? "eye.slash" : "eye").frame(width: 44, height: 44)
            }
            .buttonStyle(.borderless).accessibilityLabel(reveal ? "隐藏密钥" : "显示密钥")
        }
        .onDisappear { reveal = false }
    }
}

/// Requests to a provider made from the settings pages. Nothing here changes
/// saved settings.
enum AIProviderRequests {
    /// Sends one short sentence through a model and returns how long it took.
    @MainActor
    static func check(_ configuration: DirectAITranslationConfiguration) async throws -> TimeInterval {
        let started = Date()
        _ = try await DirectAITranslationService(configuration: configuration).translate(
            text: "Hello.",
            sourceLanguage: .english,
            targetLanguage: .simplifiedChinese
        )
        try Task.checkCancellation()
        return Date().timeIntervalSince(started)
    }

    /// The model ids the account can use, newest first.
    static func models(baseURL: String, style: AIAPIStyle, key: String) async throws -> [String] {
        guard let endpoint = AIEndpoint.url(base: baseURL, style: style) else {
            throw AIProviderSettingsError.invalidAddress
        }
        let api: AIModelCatalogAPI
        switch style {
        case .anthropicMessages: api = .anthropic
        case .geminiNative: api = .gemini
        case .chatCompletions, .responses: api = .standard
        }
        let catalog = try await RemoteAIModelCatalogService().fetch(
            AIModelCatalogConfiguration(api: api, endpoint: endpoint.absoluteString, key: key)
        )
        return catalog.models.map(\.id)
    }

    static func message(for error: any Error) -> String {
        (error as? LocalizedError)?.errorDescription ?? "连接失败，请检查网络、模型和密钥。"
    }
}

/// Model ids returned by the service, with search.
struct FetchedModelPicker: View {
    @Environment(\.dismiss) private var dismiss
    var models: [String]
    /// Ids that are already kept; they are shown as added.
    var kept: Set<String> = []
    var choose: (String) -> Void
    @State private var query = ""

    private var shown: [String] {
        query.isEmpty ? models : models.filter { $0.localizedCaseInsensitiveContains(query) }
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(shown, id: \.self) { model in
                        Button { choose(model); dismiss() } label: {
                            HStack {
                                Text(model).foregroundStyle(kept.contains(model) ? Color.secondary : Color.primary)
                                Spacer()
                                if kept.contains(model) {
                                    Text("已添加").font(.subheadline).foregroundStyle(Color.secondary)
                                }
                            }
                        }
                        .disabled(kept.contains(model))
                        .accessibilityIdentifier("model-" + model)
                    }
                    if shown.isEmpty {
                        Text(models.isEmpty ? "服务没有返回可用模型，可以手动填写模型 ID。" : "没有匹配的模型。")
                            .font(.subheadline).foregroundStyle(.secondary)
                    }
                } header: { Text("账户返回的模型").sectionTitle() }
            }
            .settingsList()
            .navigationTitle("选择模型").navigationBarTitleDisplayMode(.inline)
            .searchable(text: $query, prompt: "搜索模型")
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("取消") { dismiss() } } }
        }
    }
}

extension View {
    /// The look of the AI settings lists: inset groups on the Jerreader
    /// canvas with the system rows, a little tighter than the default.
    func settingsList(icons: Bool = false) -> some View {
        modifier(SettingsListStyle(icons: icons))
    }

    /// A group title in a grouped list: the system look, without forced capitals
    /// and with less empty space above it. `listSectionSpacing` alone leaves the
    /// title's own padding in place, which is most of the gap between two groups.
    func sectionTitle() -> some View {
        textCase(nil).listRowInsets(EdgeInsets(top: ListMetrics.titleTop, leading: 20,
                                               bottom: ListMetrics.titleBottom, trailing: 20))
    }
}

/// Spacing of the settings lists. Two groups with a title between them end up
/// `sectionSpacing + titleTop + titleBottom` plus the title's own line apart,
/// and two groups without one exactly `sectionSpacing`.
enum ListMetrics {
    /// Between two groups; a group's title adds its own height to this.
    static let sectionSpacing: CGFloat = 20
    /// Above the first group under an inline navigation bar.
    static let topMargin: CGFloat = 12
    static let titleTop: CGFloat = 2
    static let titleBottom: CGFloat = 6
    /// The least height of a row of text, and of a row that carries an icon.
    static let rowHeight: CGFloat = 52
    static let iconRowHeight: CGFloat = 60
    /// Above and below a label of two lines, which makes its row a little taller than a one-line row.
    static let twoLinePadding: CGFloat = 2
}

private struct SettingsListStyle: ViewModifier {
    @ScaledMetric private var rowHeight: CGFloat

    init(icons: Bool) {
        _rowHeight = ScaledMetric(wrappedValue: icons ? ListMetrics.iconRowHeight : ListMetrics.rowHeight,
                                  relativeTo: .body)
    }

    func body(content: Content) -> some View {
        content
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .background(JerreaderCanvasBackground())
            .environment(\.defaultMinListRowHeight, rowHeight)
            .listSectionSpacing(ListMetrics.sectionSpacing)
            .environment(\.defaultMinListHeaderHeight, 0)
            // Setting the margin replaces the system's own space above the first group.
            .contentMargins(.top, ListMetrics.topMargin, for: .scrollContent)
            .tint(JerreaderTheme.accent)
    }
}
