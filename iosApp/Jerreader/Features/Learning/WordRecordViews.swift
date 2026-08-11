import SwiftData
import SwiftUI
import UIKit
import class JerreaderCore.VocabularyLearningPolicy
import class JerreaderCore.VocabularyStatus

struct LearningAlert: Identifiable {
    let id = UUID()
    let title: String
    let message: String
}

struct LearningSearchField: View {
    @Binding var text: String
    let prompt: String

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(.secondary)

            TextField(prompt, text: $text)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.search)

            if !text.isEmpty {
                Button {
                    text = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.tertiary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("清除搜索")
            }
        }
        .padding(.horizontal, 12)
        .frame(height: 42)
        .background(
            JerreaderTheme.paper,
            in: RoundedRectangle(cornerRadius: 13, style: .continuous)
        )
        .overlay {
            RoundedRectangle(cornerRadius: 13, style: .continuous)
                .stroke(JerreaderTheme.line, lineWidth: 0.75)
        }
    }
}

struct LearningCountBadge: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let count: Int
    let noun: String

    var body: some View {
        Text("\(count)")
            .font(.caption.weight(.semibold))
            .foregroundStyle(JerreaderTheme.accent)
            .monospacedDigit()
            .contentTransition(.numericText())
            .animation(reduceMotion ? nil : JerreaderMotion.quick, value: count)
            .frame(minWidth: 30, minHeight: 30)
            .padding(.horizontal, 3)
            .background(JerreaderTheme.accentFill, in: Capsule())
            .accessibilityLabel("共 \(count) 个\(noun)")
    }
}

struct LearningToolbarIcon: View {
    let systemImage: String
    let accessibilityLabel: String

    var body: some View {
        Image(systemName: systemImage)
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(JerreaderTheme.accent)
            .frame(width: 42, height: 42)
            .background(
                JerreaderTheme.paper,
                in: RoundedRectangle(cornerRadius: 13, style: .continuous)
            )
            .overlay {
                RoundedRectangle(cornerRadius: 13, style: .continuous)
                    .stroke(JerreaderTheme.line, lineWidth: 0.75)
            }
            .accessibilityLabel(accessibilityLabel)
    }
}

struct WordRecordRow: View {
    @Bindable var record: WordLookupRecord

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(record.surfaceForm)
                    .font(.system(.title3, design: .serif).weight(.semibold))

                if let lemma = record.lemma, lemma != record.surfaceForm {
                    Text("→ \(lemma)")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }

                Spacer(minLength: 8)

                if record.isFavorite {
                    Image(systemName: "star.fill")
                        .foregroundStyle(JerreaderTheme.accent)
                        .accessibilityLabel("已收藏")
                }
            }

            if let reading = record.reading, !reading.isEmpty {
                Text(reading)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            Text(record.definitions.joined(separator: "；"))
                .font(.body)
                .lineLimit(2)

            HStack(spacing: 7) {
                Text(record.language.displayName)
                    .foregroundStyle(JerreaderTheme.accent)
                    .padding(.horizontal, 7)
                    .padding(.vertical, 3)
                    .background(JerreaderTheme.accentFill, in: Capsule())

                if let partOfSpeech = record.partOfSpeech, !partOfSpeech.isEmpty {
                    Text(partOfSpeech)
                }

                Text(
                    VocabularyLearningPolicy.shared.statusTitle(
                        storageId: record.vocabularyStatusRawValue
                    )
                )
                .foregroundStyle(JerreaderTheme.accent)

                Spacer(minLength: 6)

                JerreaderRelativeTimeText(date: record.lastLookedUpAt)
            }
            .font(.caption)
            .foregroundStyle(.secondary)
        }
        .padding(.vertical, 2)
        .accessibilityElement(children: .combine)
    }
}

struct WordRecordCard: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Bindable var record: WordLookupRecord
    let onSpeak: () -> Void
    let onCopy: () -> Void
    let onToggleFavorite: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            VStack(alignment: .leading, spacing: 9) {
                HStack(alignment: .firstTextBaseline) {
                    Text(record.surfaceForm)
                        .font(.system(size: 36, weight: .bold, design: .serif))
                        .textSelection(.enabled)

                    Spacer(minLength: 12)

                    Text(record.language.displayName)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(JerreaderTheme.accent)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(JerreaderTheme.accentFill, in: Capsule())
                }

                if let lemma = record.lemma, lemma != record.surfaceForm {
                    Label("基本形：\(lemma)", systemImage: "arrow.trianglehead.counterclockwise")
                        .font(.headline)
                        .foregroundStyle(.primary)
                        .textSelection(.enabled)
                }

                HStack(spacing: 10) {
                    if let reading = record.reading, !reading.isEmpty {
                        Label(reading, systemImage: "character.book.closed")
                    }
                    if let partOfSpeech = record.partOfSpeech, !partOfSpeech.isEmpty {
                        Text(partOfSpeech)
                    }
                }
                .font(.subheadline)
                .foregroundStyle(.secondary)
            }

            Divider()

            VStack(alignment: .leading, spacing: 13) {
                Text("词典释义")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)

                if record.definitions.isEmpty {
                    Text("暂无释义")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(Array(record.definitions.enumerated()), id: \.offset) { index, definition in
                        HStack(alignment: .firstTextBaseline, spacing: 11) {
                            Text("\(index + 1)")
                                .font(.caption.bold())
                                .foregroundStyle(JerreaderTheme.accent)
                                .frame(width: 24, height: 24)
                                .background(JerreaderTheme.accentFill, in: Circle())

                            Text(definition)
                                .font(.system(.title3, design: .serif))
                                .textSelection(.enabled)
                        }
                    }
                }
            }

            if let usageNote = record.usageNote, !usageNote.isEmpty {
                detailSection(title: "用法", text: usageNote, icon: "lightbulb")
            }

            if let inflectionNote = record.inflectionNote, !inflectionNote.isEmpty {
                detailSection(
                    title: "活用说明",
                    text: inflectionNote,
                    icon: "arrow.trianglehead.branch"
                )
            }

            if !record.examples.isEmpty {
                VStack(alignment: .leading, spacing: 11) {
                    Label("例句", systemImage: "quote.bubble")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(JerreaderTheme.accent)

                    ForEach(
                        Array(record.examples.enumerated()),
                        id: \.offset
                    ) { _, example in
                        VStack(alignment: .leading, spacing: 4) {
                            if let label = example.sourceLabel, !label.isEmpty {
                                Text(label)
                                    .font(.caption2.weight(.semibold))
                                    .foregroundStyle(.secondary)
                            }
                            Text(example.sourceText)
                                .font(.system(.body, design: .serif))
                                .textSelection(.enabled)
                            if let translation = example.translatedText,
                               !translation.isEmpty
                            {
                                Text(translation)
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                                    .textSelection(.enabled)
                            }
                        }
                    }
                }
            }

            if let aiAnalysis = record.aiAnalysis, !aiAnalysis.isEmpty {
                detailSection(
                    title: "AI 日语深度解析",
                    text: aiAnalysis,
                    icon: "sparkles"
                )
            }

            if let sentenceContext = record.sentenceContext, !sentenceContext.isEmpty {
                detailSection(title: "查询上下文", text: sentenceContext, icon: "text.quote")
            }

            if let sourceBookTitle = record.sourceBookTitle, !sourceBookTitle.isEmpty {
                Label("来源：《\(sourceBookTitle)》", systemImage: "book.closed")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            Divider()

            HStack(spacing: 10) {
                if SpeechFeatureAvailability.isEnabled {
                    actionButton(
                        title: "播放",
                        systemImage: "speaker.wave.2",
                        action: onSpeak
                    )
                }
                actionButton(title: "复制", systemImage: "doc.on.doc", action: onCopy)
                actionButton(
                    title: record.isFavorite ? "已收藏" : "收藏",
                    systemImage: record.isFavorite ? "star.fill" : "star",
                    isActive: record.isFavorite,
                    action: onToggleFavorite
                )
            }
        }
        .jerreaderPaperCard(padding: 20, hasShadow: true)
        .animation(reduceMotion ? nil : JerreaderMotion.quick, value: record.isFavorite)
        .sensoryFeedback(.success, trigger: record.isFavorite)
    }

    private func detailSection(title: String, text: String, icon: String) -> some View {
        VStack(alignment: .leading, spacing: 7) {
            Label(title, systemImage: icon)
                .font(.caption.weight(.semibold))
                .foregroundStyle(JerreaderTheme.accent)
            Text(text)
                .font(.system(.body, design: .serif))
                .textSelection(.enabled)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.leading, 14)
        .overlay(alignment: .leading) {
            RoundedRectangle(cornerRadius: 2)
                .fill(JerreaderTheme.accentFill)
                .frame(width: 4)
        }
    }

    private func actionButton(
        title: String,
        systemImage: String,
        isActive: Bool = false,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            VStack(spacing: 7) {
                Image(systemName: systemImage)
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(isActive ? JerreaderTheme.accent : .primary)
                    .frame(width: 38, height: 38)
                    .background(
                        isActive ? JerreaderTheme.accentFill : JerreaderTheme.canvas,
                        in: RoundedRectangle(cornerRadius: 12, style: .continuous)
                    )

                Text(title)
                    .font(.caption.weight(.medium))
                    .foregroundStyle(.primary)
            }
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(JerreaderPressButtonStyle(pressedScale: 0.96))
        .accessibilityLabel(title)
    }
}

struct WordRecordDetailView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.modelContext) private var modelContext

    @Bindable var record: WordLookupRecord
    @State private var alert: LearningAlert?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                WordRecordCard(
                    record: record,
                    onSpeak: speak,
                    onCopy: copy,
                    onToggleFavorite: toggleFavorite
                )

                learningPanel

                HStack(alignment: .top, spacing: 18) {
                    dateItem(title: "首次查询", date: record.createdAt)
                    dateItem(title: "最近查询 · \(record.lookupCount) 次", date: record.lastLookedUpAt)
                }
                .padding(.horizontal, 4)
            }
            .frame(maxWidth: 760)
            .frame(maxWidth: .infinity)
            .padding(.horizontal, JerreaderTheme.pagePadding)
            .padding(.top, 14)
            .padding(.bottom, 32)
        }
        .background(JerreaderTheme.canvas)
        .navigationTitle("词条详情")
        .navigationBarTitleDisplayMode(.inline)
        .tint(JerreaderTheme.accent)
        .alert(item: $alert) { item in
            Alert(
                title: Text(item.title),
                message: Text(item.message),
                dismissButton: .default(Text("好"))
            )
        }
    }

    private var learningPanel: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Label("学习状态", systemImage: "graduationcap")
                    .font(.headline)
                Spacer()
                Picker("学习状态", selection: statusBinding) {
                    ForEach(
                        VocabularyLearningPolicy.shared.allStatuses(),
                        id: \.storageId
                    ) { status in
                        Text(status.title).tag(status.storageId)
                    }
                }
                .labelsHidden()
                .pickerStyle(.menu)
            }

            Text("累计查询 \(record.lookupCount) 次 · 保留 \(record.contextHistory.count) 条原文语境")
                .font(.caption)
                .foregroundStyle(.secondary)

            ForEach(Array(record.contextHistory.enumerated()), id: \.offset) { index, context in
                VStack(alignment: .leading, spacing: 4) {
                    Text(index == 0 ? "最近语境" : "较早语境")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(.tertiary)
                    Text(context)
                        .font(.system(.body, design: .serif))
                        .textSelection(.enabled)
                }
                if index < record.contextHistory.count - 1 {
                    Divider()
                }
            }
        }
        .jerreaderPaperCard(padding: 18, hasShadow: false)
    }

    private var statusBinding: Binding<String> {
        Binding(
            get: { record.vocabularyStatusRawValue },
            set: { rawValue in
                do {
                    let status = VocabularyStatus.companion
                        .fromStorageId(id: rawValue)
                    try WordLookupStore.setVocabularyStatus(
                        status,
                        for: record,
                        in: modelContext
                    )
                } catch {
                    alert = LearningAlert(
                        title: "无法更新学习状态",
                        message: "请稍后重试。"
                    )
                }
            }
        )
    }

    private func dateItem(title: String, date: Date) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title)
                .font(.caption2)
                .foregroundStyle(.tertiary)
            Text(date.formatted(date: .abbreviated, time: .shortened))
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func speak() {
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

    private func copy() {
        UIPasteboard.general.string = record.copyText
        alert = LearningAlert(title: "已复制", message: "词条内容已复制到剪贴板。")
    }

    private func toggleFavorite() {
        let shouldDismissAfterRemoval = record.isFavorite && !record.isInHistory
        do {
            try WordLookupStore.setFavorite(!record.isFavorite, for: record, in: modelContext)
            if shouldDismissAfterRemoval {
                dismiss()
            }
        } catch {
            alert = LearningAlert(
                title: "无法更新收藏",
                message: (error as? LocalizedError)?.errorDescription ?? "请稍后重试。"
            )
        }
    }
}
