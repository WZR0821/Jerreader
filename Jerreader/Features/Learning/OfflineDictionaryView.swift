import SwiftUI

struct OfflineDictionaryView: View {
    @State private var installed = false
    @State private var busy = false
    @State private var removing = false
    @State private var status: String?
    @State private var task: Task<Void, Never>?

    var body: some View {
        LearningPage {
            LearningHint(text: "基础词典已内置，无需下载。句段翻译语言包由 iOS 在首次使用时管理。")
            LearningCard {
                Text("英语扩展词典").font(.headline)
                LearningHint(text: "下载 65.9 MB，安装需预留 300 MB，用于离线查词。")
                Text(installed ? "已下载" : "未下载").font(.caption).foregroundStyle(.secondary)
                if busy { ProgressView(removing ? "正在卸载…" : "正在下载并建立索引…") }
                if let status { Text(status).font(.caption).foregroundStyle(.secondary) }
                HStack(spacing: 24) {
                    Button(busy && !removing ? "取消下载" : "下载") {
                        if busy { task?.cancel(); return }
                        busy = true
                        status = nil
                        task = Task {
                            do { try await OfflineDictionaryPackageStore.shared.install(); status = nil }
                            catch is CancellationError { status = "已取消，原有词典保留。" }
                            catch { status = error.localizedDescription }
                            installed = await OfflineDictionaryPackageStore.shared.installed()
                            busy = false
                        }
                    }.disabled(removing || (installed && !busy))
                    Button("卸载", role: .destructive) {
                        busy = true
                        removing = true
                        task = Task {
                            do { try await OfflineDictionaryPackageStore.shared.remove(); installed = false; status = nil }
                            catch { status = error.localizedDescription }
                            busy = false
                            removing = false
                        }
                    }.disabled(busy || !installed)
                }
            }
            HStack(spacing: 16) {
                Link("中日词典 · CC BY-SA 4.0", destination: JapaneseChineseLexicon.licenseURL)
                Link("ECDICT · MIT", destination: OfflineDictionaryManifest.license)
            }.font(.caption).frame(minHeight: 44)
        }
        .navigationTitle("离线资源")
        .task { installed = await OfflineDictionaryPackageStore.shared.installed() }
        .onDisappear { task?.cancel() }
    }
}
