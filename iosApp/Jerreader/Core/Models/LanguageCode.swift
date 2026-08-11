import Foundation

enum LanguageCode: String, CaseIterable, Codable, Hashable, Sendable {
    case japanese = "ja"
    case english = "en"
    case simplifiedChinese = "zh-Hans"

    var displayName: String {
        switch self {
        case .japanese:
            return "日语"
        case .english:
            return "英语"
        case .simplifiedChinese:
            return "简体中文"
        }
    }
}
