import Foundation

enum BookImportError: LocalizedError, Equatable, Sendable {
    case unsupportedFormat
    case inaccessibleFile
    case duplicateBook
    case invalidEPUB
    case invalidPDF
    case invalidDOCX
    case unreadableText
    case emptyDocument
    case fileTooLarge
    case legacyWordUnsupported
    case protectedPublication
    case storageUnavailable
    case persistenceFailed
    case deletionFailed
    case deletionPersistenceFailed
    case deletionCleanupPending

    var errorDescription: String? {
        switch self {
        case .unsupportedFormat:
            return "请选择 EPUB、PDF、DOCX 或 TXT 文件。"
        case .inaccessibleFile:
            return "无法读取所选文件。请确认文件仍在“文件”App 中并重新选择。"
        case .duplicateBook:
            return "这本电子书已经在书架中，无需重复导入。"
        case .invalidEPUB:
            return "这个文件不是有效的 EPUB，或文件已经损坏。"
        case .invalidPDF:
            return "这个文件不是有效的 PDF，或文件已经损坏。"
        case .invalidDOCX:
            return "无法读取这个 DOCX。请确认文件没有损坏，并且不是旧式 .doc 文件。"
        case .unreadableText:
            return "无法识别这个 TXT 的文字编码。建议另存为 UTF-8 后重试。"
        case .emptyDocument:
            return "文档中没有找到可以阅读的正文。"
        case .fileTooLarge:
            return "文件过大。当前上限为 TXT 20 MB、DOCX 100 MB、EPUB/PDF 300 MB。"
        case .legacyWordUnsupported:
            return "暂不支持旧式 .doc。请先在 Word 中另存为 .docx。"
        case .protectedPublication:
            return "这本电子书受 DRM 或其他加密保护，Jerreader不会尝试绕过保护。"
        case .storageUnavailable:
            return "无法将电子书保存到本机。请检查可用空间后重试。"
        case .persistenceFailed:
            return "电子书已经解析，但书架信息保存失败。请重新导入。"
        case .deletionFailed:
            return "无法删除这本电子书的本地副本，请稍后重试。"
        case .deletionPersistenceFailed:
            return "书架记录删除失败，电子书和封面仍保留在本机。请稍后重试。"
        case .deletionCleanupPending:
            return "书籍已从书架移除，但部分本地文件未能清理，可能暂时占用少量存储空间。"
        }
    }
}

/// Carries the already-computed digest back to the shelf so opening an
/// existing book never has to coordinate and read the provider file twice.
struct DuplicateBookImportError: LocalizedError, Equatable, Sendable {
    let fingerprint: String

    var errorDescription: String? {
        BookImportError.duplicateBook.localizedDescription
    }
}
