import SwiftData
import SwiftUI
import UIKit

struct LearningAlert: Identifiable {
    let id = UUID()
    let title: String
    let message: String
}

/// The search field of 生词本 and 历史: a magnifying glass, the field and a
/// clear button that scales in once there is text, on mutedSurface, radius 14.
struct LearningSearchField: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Binding var text: String
    let prompt: String

    var body: some View {
        HStack(spacing: 8) {
            Image(uiImage: JerreaderIconImage.image("magnifyingglass"))
                .resizable().scaledToFit().frame(width: 16, height: 16)
                .foregroundStyle(.secondary)
                .accessibilityHidden(true)

            TextField(prompt, text: $text)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.search)

            if !text.isEmpty {
                Button {
                    text = ""
                } label: {
                    Image(uiImage: JerreaderIconImage.image("xmark"))
                        .resizable().scaledToFit().frame(width: 10, height: 10)
                        .foregroundStyle(.secondary)
                        .frame(width: 22, height: 22)
                        .background(Color.primary.opacity(0.08), in: Circle())
                        .frame(width: 32, height: 32)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("清除搜索")
                .transition(clearTransition)
            }
        }
        .padding(.leading, 14)
        .padding(.trailing, 6)
        .frame(minHeight: 44)
        .background(
            JerreaderTheme.mutedSurface,
            in: RoundedRectangle(cornerRadius: JerreaderRadius.control, style: .continuous)
        )
        // Scoped to the clear button coming and going.
        .animation(JerreaderMotion.choose(JerreaderMotion.quick, reduceMotion: reduceMotion), value: text.isEmpty)
    }

    private var clearTransition: AnyTransition {
        reduceMotion
            ? AnyTransition.opacity
            : AnyTransition.opacity.combined(with: .scale(scale: 0.6))
    }
}

/// The number of records beside a search field; it rolls when it changes.
struct LearningCountBadge: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let count: Int
    let noun: String

    var body: some View {
        Text("\(count)")
            .font(.system(.caption, design: .rounded, weight: .bold))
            .foregroundStyle(JerreaderTheme.accent)
            .monospacedDigit()
            .contentTransition(.numericText(value: Double(count)))
            .animation(reduceMotion ? nil : JerreaderMotion.quick, value: count)
            .frame(minWidth: 32, minHeight: 32)
            .padding(.horizontal, 4)
            .background(JerreaderTheme.accentFill, in: Capsule())
            .accessibilityLabel("共 \(count) 个\(noun)")
    }
}

/// A 44pt square action beside a search field, on mutedSurface, radius 14.
struct LearningToolbarIcon: View {
    @Environment(\.isEnabled) private var isEnabled
    let systemImage: String
    let accessibilityLabel: String
    var tint: Color = JerreaderTheme.accent

    var body: some View {
        Image(uiImage: JerreaderIconImage.image(systemImage))
            .resizable().scaledToFit().frame(width: 18, height: 18)
            .foregroundStyle(tint)
            .frame(width: 44, height: 44)
            .background(
                JerreaderTheme.mutedSurface,
                in: RoundedRectangle(cornerRadius: JerreaderRadius.control, style: .continuous)
            )
            .opacity(isEnabled ? 1 : 0.45)
            .accessibilityLabel(accessibilityLabel)
    }
}

/// A word in 生词本 or 历史: the word in 22 Bold serif, its reading in 15
/// secondary, the meaning in 17, then language and part-of-speech chips. In
/// 历史 an 8pt dot before the word tells the kind of record.
struct WordRecordRow: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Bindable var record: WordLookupRecord
    var dotTint: Color?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                if let dotTint {
                    // Sits a little above the baseline, level with the glyphs.
                    LearningKindDot(tint: dotTint)
                        .alignmentGuide(.firstTextBaseline) { $0[VerticalAlignment.bottom] + 5 }
                }

                Text(record.surfaceForm)
                    .font(.system(.title2, design: .serif, weight: .bold))

                if let lemma = record.lemma, lemma != record.surfaceForm {
                    Text("→ \(lemma)")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }

                Spacer(minLength: 8)

                if record.isFavorite {
                    Image(uiImage: JerreaderIconImage.image("star.fill"))
                        .resizable().scaledToFit().frame(width: 18, height: 18)
                        .foregroundStyle(JerreaderTheme.accent)
                        .accessibilityLabel("已收藏")
                        .transition(starTransition)
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

            HStack(spacing: 6) {
                LearningChip(text: record.language.displayName)

                if let partOfSpeech = record.partOfSpeech, !partOfSpeech.isEmpty {
                    LearningChip(text: partOfSpeech, tint: Color.secondary)
                }

                Spacer(minLength: 6)

                JerreaderRelativeTimeText(date: record.lastLookedUpAt)
                    .font(.caption)
                    .foregroundStyle(.tertiary)
            }
            .padding(.top, 2)
        }
        // Scoped to the star coming and going.
        .animation(JerreaderMotion.choose(JerreaderMotion.stateChange, reduceMotion: reduceMotion), value: record.isFavorite)
        .accessibilityElement(children: .combine)
    }

    private var starTransition: AnyTransition {
        reduceMotion
            ? AnyTransition.opacity
            : AnyTransition.opacity.combined(with: .scale(scale: 0.5))
    }
}

struct WordRecordCard: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Bindable var record: WordLookupRecord
    let onSpeak: () -> Void
    let onCopy: () -> Void
    let onToggleFavorite: () -> Void
    /// 「第 N 次查 · 上次 MM-dd」 under the headword (2.1.0 §8).
    var lookupHint: String? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: JerreaderSpacing.lg) {
            VStack(alignment: .leading, spacing: JerreaderSpacing.xs) {
                HStack(alignment: .firstTextBaseline) {
                    Text(record.surfaceForm)
                        .font(.system(.largeTitle, design: .serif, weight: .bold))
                        .textSelection(.enabled)

                    Spacer(minLength: 12)

                    LearningChip(text: record.language.displayName)
                }

                if let lookupHint {
                    Text(lookupHint)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .accessibilityIdentifier("lookup-count-hint")
                }

                if let lemma = record.lemma, lemma != record.surfaceForm {
                    JerreaderLabel(title: "基本形：\(lemma)", icon: "SWAP", size: 18)
                        .font(.headline)
                        .foregroundStyle(.primary)
                        .textSelection(.enabled)
                }

                HStack(spacing: 10) {
                    if let reading = record.reading, !reading.isEmpty {
                        JerreaderLabel(title: reading, icon: "character.book.closed", size: 18)
                            .font(.title3)
                    }
                    if let partOfSpeech = record.partOfSpeech, !partOfSpeech.isEmpty {
                        LearningChip(text: partOfSpeech, tint: Color.secondary)
                    }
                }
                .foregroundStyle(.secondary)
            }

            hairline

            VStack(alignment: .leading, spacing: JerreaderSpacing.sm) {
                JerreaderEyebrow(
                    text: record.usageNote?.contains("译文") == true || record.usageNote?.contains("AI") == true
                        ? "参考释义 · 中文" : "词典释义 · 中文"
                )

                if record.definitions.isEmpty {
                    Text("暂无释义")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(Array(record.definitions.enumerated()), id: \.offset) { index, definition in
                        HStack(alignment: .firstTextBaseline, spacing: 12) {
                            Text("\(index + 1)")
                                .font(.system(.caption, design: .rounded, weight: .bold))
                                .foregroundStyle(JerreaderTheme.accent)
                                .frame(width: 24, height: 24)
                                .background(JerreaderTheme.accentFill, in: Circle())

                            Text(definition)
                                .font(.system(.title3, design: .serif))
                                .textSelection(.enabled)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }
            }

            if let usageNote = record.usageNote, !usageNote.isEmpty {
                detailSection(title: "来源与用法", text: usageNote, icon: "lightbulb")
            }

            if let inflectionNote = record.inflectionNote, !inflectionNote.isEmpty {
                detailSection(
                    title: "活用说明",
                    text: inflectionNote,
                    icon: "arrow.trianglehead.branch"
                )
            }

            if !record.examples.isEmpty {
                DisclosureGroup("展开词典例句") {
                    VStack(alignment: .leading, spacing: JerreaderSpacing.sm) {
                        JerreaderLabel(title: "例句", icon: "text.quote", size: 14)
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
                            .padding(.leading, 12)
                            .overlay(alignment: .leading) { quoteBar }
                        }
                    }
                    .padding(.top, JerreaderSpacing.xs)
                }
                .tint(JerreaderTheme.accent)
            }

            if let aiAnalysis = record.aiAnalysis, !aiAnalysis.isEmpty {
                DisclosureGroup("展开 AI 语境建议") {
                    VStack(alignment: .leading, spacing: JerreaderSpacing.sm) {
                        detailSection(
                            title: "AI 语境建议（需结合原句核对）",
                            text: aiAnalysis,
                            icon: "sparkles"
                        )
                        SaveGrammarNoteButton(draft: GrammarNoteDraft(
                            title: record.displayTerm, sentence: record.sentenceContext ?? record.surfaceForm,
                            explanation: aiAnalysis, language: record.languageRawValue,
                            bookID: record.sourceBookID, bookTitle: record.sourceBookTitle))
                            .buttonStyle(JerreaderTintedButtonStyle())
                    }
                    .padding(.top, JerreaderSpacing.xs)
                }
                .tint(JerreaderTheme.accent)
            }

            if let sentenceContext = record.sentenceContext, !sentenceContext.isEmpty {
                detailSection(title: "查询上下文", text: sentenceContext, icon: "text.quote")
            }

            if let sourceBookTitle = record.sourceBookTitle, !sourceBookTitle.isEmpty {
                JerreaderLabel(title: "来源：《\(sourceBookTitle)》", icon: "book.closed", size: 16)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            hairline

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
        .jerreaderPaperCard(padding: JerreaderSpacing.lg, radius: JerreaderRadius.card, hasShadow: true)
        .animation(reduceMotion ? nil : JerreaderMotion.quick, value: record.isFavorite)
        // Success when a word is saved, a light tick when it is removed.
        .sensoryFeedback(trigger: record.isFavorite) { _, isFavorite in
            isFavorite ? SensoryFeedback.success : SensoryFeedback.selection
        }
    }

    private var hairline: some View {
        Rectangle()
            .fill(JerreaderTheme.line)
            .frame(height: 0.5)
            .accessibilityHidden(true)
    }

    private var quoteBar: some View {
        Capsule()
            .fill(JerreaderTheme.accent.opacity(0.35))
            .frame(width: 3)
            .accessibilityHidden(true)
    }

    private func detailSection(title: String, text: String, icon: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            JerreaderLabel(title: title, icon: ["lightbulb": "INFO", "arrow.trianglehead.branch": "SWAP", "text.quote": "SENTENCES", "sparkles": "NOTE"][icon] ?? icon, size: 14)
                .font(.caption.weight(.semibold))
                .foregroundStyle(JerreaderTheme.accent)
            Text(text)
                .font(.system(.body, design: .serif))
                .textSelection(.enabled)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.leading, 14)
        .overlay(alignment: .leading) { quoteBar }
    }

    /// A 44pt tile with its title under it. The favourite's icon bounces
    /// when it is set or cleared.
    private func actionButton(
        title: String,
        systemImage: String,
        isActive: Bool = false,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            VStack(spacing: 6) {
                Image(uiImage: JerreaderIconImage.image(systemImage))
                    .resizable().scaledToFit().frame(width: 18, height: 18)
                    .foregroundStyle(isActive ? JerreaderTheme.accent : Color.primary)
                    .jerreaderBounce(trigger: isActive)
                    .frame(width: 44, height: 44)
                    .background(
                        isActive ? JerreaderTheme.accentFill : JerreaderTheme.mutedSurface,
                        in: RoundedRectangle(cornerRadius: JerreaderRadius.control, style: .continuous)
                    )

                Text(title)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(isActive ? JerreaderTheme.accent : Color.primary)
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
            VStack(alignment: .leading, spacing: JerreaderSpacing.md) {
                WordRecordCard(
                    record: record,
                    onSpeak: speak,
                    onCopy: copy,
                    onToggleFavorite: toggleFavorite
                )

                HStack(alignment: .top, spacing: JerreaderSpacing.md) {
                    dateItem(title: "首次查询", date: record.createdAt)
                    Rectangle()
                        .fill(JerreaderTheme.line)
                        .frame(width: 0.5)
                        .accessibilityHidden(true)
                    dateItem(title: "最近查询 · \(record.lookupCount) 次", date: record.lastLookedUpAt)
                }
                .fixedSize(horizontal: false, vertical: true)
                .padding(JerreaderSpacing.md)
                .background(
                    JerreaderTheme.mutedSurface,
                    in: RoundedRectangle(cornerRadius: JerreaderRadius.control, style: .continuous)
                )
            }
            .frame(maxWidth: 760)
            .frame(maxWidth: .infinity)
            .padding(.horizontal, JerreaderTheme.pagePadding)
            .padding(.top, JerreaderSpacing.md)
            .padding(.bottom, JerreaderSpacing.xxl)
        }
        .background(JerreaderCanvasBackground())
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

    private func dateItem(title: String, date: Date) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(date.formatted(date: .abbreviated, time: .shortened))
                .font(.subheadline.weight(.semibold))
                .monospacedDigit()
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
