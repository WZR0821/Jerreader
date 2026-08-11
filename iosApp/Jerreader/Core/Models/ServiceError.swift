import Foundation

enum ServiceError: LocalizedError, Equatable, Sendable {
    case emptyText
    case unsupportedLanguage
    case resultNotFound
    case voiceUnavailable
    case temporarilyUnavailable
    case textTooLong
    case languageDownloadDeclined
    case translationUnavailable
    case invalidConfiguration
    case authenticationFailed

    var errorDescription: String? {
        switch self {
        case .emptyText:
            return "请选择需要查询或翻译的文字。"
        case .unsupportedLanguage:
            return "暂时无法识别这段文字的语言。"
        case .resultNotFound:
            return "暂未找到可靠的解释，请稍后重试。"
        case .voiceUnavailable:
            return "设备上没有适合当前语言的语音。"
        case .temporarilyUnavailable:
            return "服务暂时不可用，请稍后重试。"
        case .textTooLong:
            return "一次最多翻译 2,000 个字符，请缩短选择范围。"
        case .languageDownloadDeclined:
            return "需要下载翻译语言，请重新尝试并允许下载。"
        case .translationUnavailable:
            return "系统翻译暂时无法使用。请先联网下载对应语言包后重试；下载完成后可离线翻译。"
        case .invalidConfiguration:
            return "翻译服务配置无效，请检查 HTTPS 地址、模型和请求格式。"
        case .authenticationFailed:
            return "翻译服务拒绝了访问，请检查钥匙串中的 API Key 或代理凭据。"
        }
    }
}
