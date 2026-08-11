import Foundation

enum ReaderError: LocalizedError {
    case missingBookFile
    case invalidEPUB
    case invalidPDF
    case unsupportedFormat
    case protectedPublication
    case navigatorUnavailable
    case openInterrupted
    case openTimedOut

    var errorDescription: String? {
        switch self {
        case .missingBookFile:
            return "找不到这本书的本地副本，请从书架删除后重新导入。"
        case .invalidEPUB:
            return "这本 EPUB 无法解析，文件可能已经损坏或不符合 EPUB 标准。"
        case .invalidPDF:
            return "这本 PDF 无法解析，文件可能已经损坏。"
        case .unsupportedFormat:
            return "当前阅读器无法打开这种文档格式。"
        case .protectedPublication:
            return "这本书受 DRM 保护，Jerreader不会尝试绕过保护。"
        case .navigatorUnavailable:
            return "阅读器没有成功启动，请关闭后重试。"
        case .openInterrupted:
            return "打开过程被系统中断，请点“重新打开”。"
        case .openTimedOut:
            return "打开时间过长，Jerreader已停止本次等待。请点“重新打开”；如果仍然失败，请重新导入这本书。"
        }
    }
}
