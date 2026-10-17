import SwiftUI

struct AIModelPicker: View {
    let configuration: AIModelCatalogConfiguration
    @Binding var selectedModel: String
    var service: any AIModelCatalogService = RemoteAIModelCatalogService()
    @State private var catalog: AIModelCatalog?
    @State private var message: String?
    @State private var loading = false
    @State private var search = ""
    @State private var showing = false
    @State private var requestID = UUID()
    @State private var task: Task<Void, Never>?
    @State private var watchdog = StandaloneTranslationWatchdog()

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("当前模型：\(selectedModel.isEmpty ? "未填写" : selectedModel)")
                .font(.subheadline).textSelection(.enabled)
            ViewThatFits(in: .horizontal) {
                HStack { actions }
                VStack(alignment: .leading) { actions }
            }
            if let catalog {
                Text("来源：\(catalog.source) · \(catalog.refreshedAt.formatted(date: .abbreviated, time: .shortened)) · \(catalog.models.count) 个模型")
                    .font(.caption).foregroundStyle(.secondary)
                if catalog.truncated { Text("列表较长，仅展示已获取的部分模型。").font(.caption) }
            }
            if let message { Text(message).font(.caption).foregroundStyle(.secondary) }
            Text("刷新获取当前账号可见的模型；选择后才会更换。列表不保证兼容翻译，请用连接测试确认。")
                .font(.caption).foregroundStyle(.secondary)
        }
        .onChange(of: configuration) { reset() }
        .onDisappear { stop() }
        .sheet(isPresented: $showing) {
            NavigationStack {
                List {
                    if filtered.isEmpty { Text("没有匹配的模型，可刷新或手动填写模型 ID。") }
                    ForEach(filtered) { model in
                        Button {
                            selectedModel = model.id
                            showing = false
                        } label: {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(model.displayName).foregroundStyle(.primary)
                                if model.id != model.displayName { Text(model.id).font(.caption).foregroundStyle(.secondary) }
                                if selectedModel == model.id { Label("当前模型", systemImage: "checkmark").font(.caption) }
                            }.frame(minHeight: 44)
                        }
                    }
                }
                .searchable(text: $search, prompt: "搜索模型名称或 ID")
                .navigationTitle("选择模型")
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("关闭") { showing = false } } }
            }
        }
    }
    @ViewBuilder private var actions: some View {
        Button(loading ? "刷新中…" : "刷新模型列表", action: refresh)
            .disabled(loading || configuration.key.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            .frame(minHeight: 44).accessibilityIdentifier("model-refresh")
        Button("选择模型") { search = ""; showing = true }
            .disabled(catalog?.models.isEmpty != false || loading).frame(minHeight: 44)
    }
    private var filtered: [AIModelOption] {
        (catalog?.models ?? []).filter { search.isEmpty || $0.id.localizedCaseInsensitiveContains(search) || $0.displayName.localizedCaseInsensitiveContains(search) }
    }
    private func stop() { requestID = UUID(); task?.cancel(); task = nil; watchdog.cancel(); loading = false }
    private func reset() { stop(); catalog = nil; message = nil; showing = false; search = "" }
    private func refresh() {
        stop()
        let id = requestID
        let snapshot = configuration
        loading = true; message = nil
        watchdog.start(id: id, systemTranslation: false, restart: {}) { _ in
            guard requestID == id else { return }
            stop(); message = "模型列表等待超时，请重试；当前模型保持不变。"
        }
        task = Task { @MainActor in
            defer { if requestID == id { loading = false; watchdog.complete(id: id) } }
            do {
                let result = try await service.fetch(snapshot)
                try Task.checkCancellation()
                guard requestID == id, configuration == snapshot else { return }
                catalog = result
                if result.models.isEmpty { message = "服务未返回可选模型，可手动填写模型 ID。" }
            } catch is CancellationError { }
            catch {
                guard requestID == id else { return }
                message = (error as? AIModelCatalogError)?.localizedDescription ?? "无法获取模型列表；可稍后刷新或手动填写模型 ID。"
            }
        }
    }
}
