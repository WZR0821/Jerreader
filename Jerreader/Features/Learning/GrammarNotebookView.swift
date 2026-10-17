import SwiftData
import SwiftUI

struct GrammarNotebookView: View {
    @Environment(\.modelContext) private var context
    @ObservedObject var translationSettings: TranslationSettingsStore
    @Query(sort: \GrammarNoteRecord.updatedAt, order: .reverse) private var notes: [GrammarNoteRecord]
    @Query private var books: [BookRecord]
    @State private var search = ""
    @State private var editor: GrammarNoteDraft?
    @State private var reading: GrammarNoteDraft?
    @State private var error: String?
    @State private var pendingDeletion: GrammarNoteRecord?

    private var filteredNotes: [GrammarNoteRecord] {
        notes.filter { search.isEmpty || [$0.title, $0.sentence, $0.explanation, $0.tags, $0.language,
            learningLanguageName($0.language)].contains { $0.localizedCaseInsensitiveContains(search) } }
    }

    var body: some View {
        let visibleNotes = filteredNotes
        let bookIDs = Set(books.map(\.id))
        LearningPage {
            LearningHint(text: "在解析结果中保存已有解释，可在这里编辑标题、语言、解释和标签。")
            LearningTextField(title: "搜索标题、原句、解释或标签", text: $search)
                .accessibilityIdentifier("grammar-search")
            if let error { Text(error).foregroundStyle(.red) }
            ForEach(visibleNotes) { note in
                LearningCard {
                    Text(note.title).font(.headline)
                    Text(note.sentence).textSelection(.enabled)
                    Text(note.explanation).textSelection(.enabled)
                    Text([learningLanguageName(note.language), note.tags, note.bookTitle ?? ""].filter { !$0.isEmpty }.joined(separator: " · "))
                        .font(.caption).foregroundStyle(.secondary)
                    Button("编辑") { editor = note.draft }
                    if note.locatorJSON != nil, let bookID = note.bookID, bookIDs.contains(bookID) {
                        Button("回到原书原句") { reading = note.draft }
                    } else { Text("无可用原书位置；已保存的内容仍可阅读。").font(.caption).foregroundStyle(.secondary) }
                    Button("删除", role: .destructive) { pendingDeletion = note }
                }
            }
            if visibleNotes.isEmpty {
                LearningCard { Text(notes.isEmpty ? "还没有语法收藏。" : "没有找到匹配的语法收藏。") }
            }
        }
        .navigationTitle("语法知识库")
        .alert("删除这条语法收藏？", isPresented: Binding(
            get: { pendingDeletion != nil }, set: { if !$0 { pendingDeletion = nil } }
        )) {
            Button("取消", role: .cancel) { pendingDeletion = nil }
            Button("删除", role: .destructive) {
                guard let note = pendingDeletion else { return }
                pendingDeletion = nil
                context.delete(note)
                do { try context.save() } catch { context.rollback(); self.error = "删除失败，请重试。" }
            }
        } message: { Text("只删除这条收藏，原书内容不会改变。") }
        .sheet(item: $editor) { draft in GrammarNoteEditor(draft: draft) }
        .fullScreenCover(item: $reading) { draft in
            if let book = books.first(where: { $0.id == draft.bookID }) {
                EPUBReaderScreen(book: book, modelContext: context, translationSettings: translationSettings,
                    initialLocatorJSON: draft.locatorJSON)
            }
        }
    }
}

struct SaveGrammarNoteButton: View {
    let draft: GrammarNoteDraft
    @State private var editing: GrammarNoteDraft?
    var body: some View {
        Button("保存到语法知识库", systemImage: "bookmark") { editing = draft }
            .sheet(item: $editing) { GrammarNoteEditor(draft: $0) }
    }
}

struct GrammarNoteEditor: View {
    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    @State var draft: GrammarNoteDraft
    @State private var error: String?
    var body: some View {
        NavigationStack {
            LearningPage {
                if let error { Text(error).foregroundStyle(.red) }
                LearningCard {
                LearningTextField(title: "标题", text: $draft.title)
                Picker("语言", selection: $draft.language) {
                    Text("日语").tag("ja")
                    Text("中文").tag("zh-Hans")
                    Text("英语").tag("en")
                    if !["ja", "zh-Hans", "en"].contains(draft.language) {
                        Text(learningLanguageName(draft.language)).tag(draft.language)
                    }
                }
                LearningTextField(title: "标签，用逗号分隔", text: $draft.tags)
                }
                LearningCard { Text("原句").font(.headline); Text(draft.sentence).textSelection(.enabled) }
                LearningCard {
                    Text("解释，可编辑").font(.headline)
                    TextEditor(text: $draft.explanation).frame(minHeight: 180)
                        .scrollContentBackground(.hidden).accessibilityLabel("解释，可编辑")
                }
            }
            .scrollContentBackground(.hidden)
            .background(JerreaderCanvasBackground())
            .navigationTitle("保存语法")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        do {
                            let id = draft.id
                            let existing = try context.fetch(FetchDescriptor<GrammarNoteRecord>(predicate: #Predicate { $0.id == id })).first
                            if let existing { existing.apply(draft) } else { context.insert(GrammarNoteRecord(draft)) }
                            try context.save(); dismiss()
                        } catch { context.rollback(); self.error = "语法收藏未能保存，请重试。" }
                    }.disabled(!draft.isValid)
                }
            }
        }
    }
}

func learningLanguageName(_ value: String) -> String {
    switch value {
    case "ja", "JAPANESE": return "日语"
    case "zh-Hans", "zh-CN", "CHINESE_SIMPLIFIED": return "中文"
    case "en", "ENGLISH": return "英语"
    default: return value
    }
}
