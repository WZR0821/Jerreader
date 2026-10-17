import Foundation

struct AIModelOption: Identifiable, Equatable, Sendable {
    let id: String
    let displayName: String
    let created: Double
}
struct AIModelCatalog: Sendable {
    let models: [AIModelOption]
    let source: String
    let refreshedAt: Date
    let truncated: Bool
}
enum AIModelCatalogAPI: Sendable { case standard, anthropic, gemini }
struct AIModelCatalogConfiguration: Equatable, Sendable {
    let api: AIModelCatalogAPI
    let endpoint: String
    let key: String

    func request(cursor: String? = nil) throws -> URLRequest {
        guard var url = URLComponents(string: endpoint.trimmingCharacters(in: .whitespacesAndNewlines)),
              url.scheme == "https", url.host?.isEmpty == false,
              url.user == nil, url.password == nil, url.query == nil, url.fragment == nil,
              !key.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw AIModelCatalogError.configuration }
        var path = url.path
        while path.hasSuffix("/") { path.removeLast() }
        if api != .gemini {
            guard let suffix = ["/chat/completions", "/responses", "/messages"].first(where: { path.hasSuffix($0) }) else {
                throw AIModelCatalogError.configuration
            }
            path.removeLast(suffix.count)
        }
        url.path = path + "/models"
        if api == .anthropic {
            url.queryItems = [URLQueryItem(name: "limit", value: "1000")]
            if let cursor { url.queryItems?.append(URLQueryItem(name: "after_id", value: cursor)) }
        } else if api == .gemini {
            url.queryItems = [URLQueryItem(name: "pageSize", value: "1000")]
            if let cursor { url.queryItems?.append(URLQueryItem(name: "pageToken", value: cursor)) }
        }
        url.percentEncodedQuery = url.percentEncodedQuery?.replacingOccurrences(of: "+", with: "%2B")
        guard let target = url.url else { throw AIModelCatalogError.configuration }
        var request = URLRequest(url: target, timeoutInterval: 20)
        request.httpMethod = "GET"
        let credential = key.trimmingCharacters(in: .whitespacesAndNewlines)
        switch api {
        case .standard: request.setValue("Bearer " + credential, forHTTPHeaderField: "Authorization")
        case .anthropic:
            request.setValue(credential, forHTTPHeaderField: "x-api-key")
            request.setValue("2023-06-01", forHTTPHeaderField: "anthropic-version")
        case .gemini: request.setValue(credential, forHTTPHeaderField: "x-goog-api-key")
        }
        return request
    }
}
enum AIModelCatalogError: LocalizedError {
    case configuration, authentication, unavailable, invalidResponse
    var errorDescription: String? {
        switch self {
        case .configuration: "请先填写 API Key 和有效的 HTTPS 服务地址。"
        case .authentication: "模型列表验证失败，请检查此服务商的 API Key 和权限。"
        case .unavailable: "无法获取模型列表；可稍后刷新或手动填写模型 ID。"
        case .invalidResponse: "模型列表格式无法识别；可手动填写模型 ID。"
        }
    }
}
protocol AIModelCatalogHTTPClient: Sendable {
    func get(_ request: URLRequest) async throws -> (Data, Int)
}
private final class ModelCatalogRedirectPolicy: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}
struct URLSessionAIModelCatalogClient: AIModelCatalogHTTPClient {
    func get(_ request: URLRequest) async throws -> (Data, Int) {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForResource = 30
        let session = URLSession(configuration: configuration, delegate: ModelCatalogRedirectPolicy(), delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        let (bytes, response) = try await session.bytes(for: request)
        guard let response = response as? HTTPURLResponse else { throw AIModelCatalogError.invalidResponse }
        guard (200..<300).contains(response.statusCode) else { return (Data(), response.statusCode) }
        var data = Data()
        for try await byte in bytes {
            guard data.count < 2_097_152 else { throw AIModelCatalogError.invalidResponse }
            data.append(byte)
        }
        return (data, response.statusCode)
    }
}
protocol AIModelCatalogService: Sendable {
    func fetch(_ configuration: AIModelCatalogConfiguration) async throws -> AIModelCatalog
}
struct RemoteAIModelCatalogService: AIModelCatalogService {
    var client: any AIModelCatalogHTTPClient = URLSessionAIModelCatalogClient()
    func fetch(_ configuration: AIModelCatalogConfiguration) async throws -> AIModelCatalog {
        var options: [String: AIModelOption] = [:]
        var cursor: String?
        var seen = Set<String>()
        var truncated = false
        let first = try configuration.request()
        for page in 0..<10 {
            try Task.checkCancellation()
            let (data, status) = try await client.get(configuration.request(cursor: cursor))
            try Task.checkCancellation()
            if status == 401 || status == 403 { throw AIModelCatalogError.authentication }
            guard (200..<300).contains(status) else { throw AIModelCatalogError.unavailable }
            let parsed = try Self.parse(data, api: configuration.api)
            for option in parsed.0 where options[option.id] == nil {
                if options.count < 5000 { options[option.id] = option }
                else { truncated = true }
            }
            cursor = parsed.1
            if cursor == nil { break }
            guard seen.insert(cursor!).inserted else { throw AIModelCatalogError.invalidResponse }
            if page == 9 || options.count >= 5000 { truncated = true; break }
        }
        return AIModelCatalog(models: options.values.sorted {
            $0.created == $1.created ? $0.id < $1.id : $0.created > $1.created
        }, source: first.url!.host!, refreshedAt: Date(), truncated: truncated)
    }
    static func parse(_ data: Data, api: AIModelCatalogAPI) throws -> ([AIModelOption], String?) {
        guard data.count <= 2_097_152,
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let rows = object[api == .gemini ? "models" : "data"] as? [[String: Any]] else {
            throw AIModelCatalogError.invalidResponse
        }
        let formatter = ISO8601DateFormatter()
        let models = rows.compactMap { row -> AIModelOption? in
            if api == .gemini && !(row["supportedGenerationMethods"] as? [String] ?? []).contains("generateContent") { return nil }
            guard var id = row[api == .gemini ? "name" : "id"] as? String else { return nil }
            if api == .gemini && id.hasPrefix("models/") { id.removeFirst(7) }
            guard !id.isEmpty, id.count <= 256, !id.contains(where: { $0.isWhitespace || $0.isNewline }) else { return nil }
            let name = row[api == .gemini ? "displayName" : "display_name"] as? String
            let created = (row["created"] as? Double) ?? (row["created_at"] as? String).flatMap { formatter.date(from: $0)?.timeIntervalSince1970 } ?? 0
            return AIModelOption(id: id, displayName: name?.isEmpty == false ? String(name!.prefix(256)) : id, created: created)
        }
        var cursor: String?
        if api == .gemini { cursor = object["nextPageToken"] as? String }
        if api == .anthropic && object["has_more"] as? Bool == true {
            guard let value = object["last_id"] as? String, !value.isEmpty else { throw AIModelCatalogError.invalidResponse }
            cursor = value
        }
        return (models, cursor?.isEmpty == false ? cursor : nil)
    }
}
struct MockAIModelCatalogService: AIModelCatalogService {
    func fetch(_ configuration: AIModelCatalogConfiguration) async throws -> AIModelCatalog {
        AIModelCatalog(models: [AIModelOption(id: "mock-text-latest", displayName: "示例文本模型", created: 0)],
                       source: "离线示例", refreshedAt: Date(), truncated: false)
    }
}
