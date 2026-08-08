import Foundation
import UniformTypeIdentifiers

enum LearningExportFormat: String, CaseIterable, Identifiable {
    case csv
    case markdown
    case anki

    var id: Self { self }

    var title: String {
        switch self {
        case .csv: return "CSV 表格"
        case .markdown: return "Markdown 笔记"
        case .anki: return "Anki 制卡文件"
        }
    }

    var systemImage: String {
        switch self {
        case .csv: return "tablecells"
        case .markdown: return "text.document"
        case .anki: return "rectangle.stack"
        }
    }

    var contentType: UTType {
        switch self {
        case .csv:
            return .commaSeparatedText
        case .markdown:
            return UTType(filenameExtension: "md") ?? .plainText
        case .anki:
            return UTType(filenameExtension: "tsv") ?? .plainText
        }
    }

    var fileExtension: String {
        switch self {
        case .csv: return "csv"
        case .markdown: return "md"
        case .anki: return "tsv"
        }
    }
}

struct LearningExportEntry: Equatable, Sendable {
    enum Kind: String, Sendable {
        case word = "生词"
        case translation = "译文"
    }

    let kind: Kind
    let front: String
    let back: String
    let reading: String?
    let context: String?
    let language: String
    let bookTitle: String?
    let tags: [String]
    let date: Date
}

enum LearningExportService {
    nonisolated static func data(
        entries: [LearningExportEntry],
        format: LearningExportFormat
    ) -> Data {
        let text: String
        switch format {
        case .csv:
            text = csv(entries)
            // A UTF-8 BOM helps spreadsheet apps recognize Chinese directly.
            return Data([0xEF, 0xBB, 0xBF]) + Data(text.utf8)
        case .markdown:
            text = markdown(entries)
        case .anki:
            text = anki(entries)
        }
        return Data(text.utf8)
    }

    nonisolated static func csv(_ entries: [LearningExportEntry]) -> String {
        let header = ["类型", "原文/词语", "译文/释义", "读音", "上下文", "语言", "书籍", "标签", "日期"]
        let rows = entries.map { entry in
            [
                entry.kind.rawValue,
                entry.front,
                entry.back,
                entry.reading ?? "",
                entry.context ?? "",
                entry.language,
                entry.bookTitle ?? "",
                entry.tags.joined(separator: " "),
                ISO8601DateFormatter().string(from: entry.date),
            ]
        }
        return ([header] + rows)
            .map { $0.map(csvField).joined(separator: ",") }
            .joined(separator: "\r\n") + "\r\n"
    }

    nonisolated static func markdown(_ entries: [LearningExportEntry]) -> String {
        var lines = ["# Jerreader学习导出", ""]
        for kind in [LearningExportEntry.Kind.word, .translation] {
            let values = entries.filter { $0.kind == kind }
            guard !values.isEmpty else { continue }
            lines.append("## \(kind == .word ? "生词本" : "译文收藏")")
            lines.append("")
            for entry in values {
                lines.append("### \(markdownInline(entry.front))")
                lines.append("")
                lines.append(entry.back)
                if let reading = entry.reading, !reading.isEmpty {
                    lines.append("")
                    lines.append("- 读音：\(reading)")
                }
                if let context = entry.context, !context.isEmpty {
                    lines.append("- 上下文：\(context.replacingOccurrences(of: "\n", with: " "))")
                }
                if let book = entry.bookTitle, !book.isEmpty {
                    lines.append("- 来源：《\(book)》")
                }
                if !entry.tags.isEmpty {
                    lines.append("- 标签：\(entry.tags.map { "#\($0.replacingOccurrences(of: " ", with: "-"))" }.joined(separator: " "))")
                }
                lines.append("")
            }
        }
        return lines.joined(separator: "\n")
    }

    nonisolated static func anki(_ entries: [LearningExportEntry]) -> String {
        var lines = [
            "#separator:tab",
            "#html:true",
            "#deck:Jerreader",
            "#columns:Front\tBack\tContext\tTags",
        ]
        lines += entries.map { entry in
            let backParts = [entry.back, entry.reading.map { "读音：\($0)" }]
                .compactMap { $0 }
                .map(htmlEscaped)
            return [
                htmlEscaped(entry.front),
                backParts.joined(separator: "<br>"),
                htmlEscaped(entry.context ?? ""),
                ankiField((["Jerreader", entry.kind.rawValue] + entry.tags).joined(separator: " ")),
            ]
            .map(ankiField)
            .joined(separator: "\t")
        }
        return lines.joined(separator: "\n") + "\n"
    }

    private nonisolated static func csvField(_ value: String) -> String {
        guard value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")
        else { return value }
        return "\"\(value.replacingOccurrences(of: "\"", with: "\"\""))\""
    }

    private nonisolated static func markdownInline(_ value: String) -> String {
        value
            .replacingOccurrences(of: "\n", with: " ")
            .replacingOccurrences(of: "#", with: "\\#")
    }

    private nonisolated static func htmlEscaped(_ value: String) -> String {
        value
            .replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
            .replacingOccurrences(of: "\"", with: "&quot;")
            .replacingOccurrences(of: "\n", with: "<br>")
    }

    private nonisolated static func ankiField(_ value: String) -> String {
        value
            .replacingOccurrences(of: "\t", with: " ")
            .replacingOccurrences(of: "\r", with: "")
            .replacingOccurrences(of: "\n", with: "<br>")
    }
}
