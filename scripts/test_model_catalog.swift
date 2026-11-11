import Foundation

private actor MockHTTP: AIModelCatalogHTTPClient {
    var responses: [(String, Int)]
    var requests: [URLRequest] = []
    init(_ responses: [(String, Int)]) { self.responses = responses }
    func get(_ request: URLRequest) async throws -> (Data, Int) {
        requests.append(request)
        precondition(!responses.isEmpty)
        let response = responses.removeFirst()
        return (Data(response.0.utf8), response.1)
    }
}
@main enum ModelCatalogTests {
    static func main() async throws {
        let endpoint = "https://api.example.test/v1/responses"
        let config = AIModelCatalogConfiguration(api: .standard, endpoint: endpoint, key: "offline-test-key")
        let firstRequest = try config.request()
        precondition(firstRequest.url!.absoluteString == "https://api.example.test/v1/models")
        for suffix in ["chat/completions", "messages", "responses"] {
            let request = try AIModelCatalogConfiguration(api: .standard, endpoint: "https://example.test/custom/" + suffix, key: "fixture").request()
            precondition(request.url!.path == "/custom/models")
            precondition(request.value(forHTTPHeaderField: "Authorization") == "Bearer fixture")
        }
        let mock = MockHTTP([(#"{"data":[{"id":"future-text-v99","created":20},{"id":"legacy","created":10},{"id":"future-text-v99","created":20}]}"#, 200)])
        let result = try await RemoteAIModelCatalogService(client: mock).fetch(config)
        precondition(result.models.map(\.id) == ["future-text-v99", "legacy"])
        precondition(result.source == "api.example.test" && !result.truncated)
        let claude = AIModelCatalogConfiguration(api: .anthropic, endpoint: "https://example.test/v1/messages", key: "fixture")
        let pages = MockHTTP([
            (#"{"data":[{"id":"first","display_name":"Readable name","created_at":"2026-01-01T00:00:00Z"}],"has_more":true,"last_id":"first"}"#, 200),
            (#"{"data":[{"id":"second"}],"has_more":false}"#, 200)])
        let paged = try await RemoteAIModelCatalogService(client: pages).fetch(claude)
        precondition(paged.models.first?.displayName == "Readable name")
        let requests = await pages.requests
        precondition(requests.count == 2 && requests[1].url!.query!.contains("after_id=first"))
        precondition(requests[0].value(forHTTPHeaderField: "anthropic-version") == "2023-06-01")
        let gemini = AIModelCatalogConfiguration(api: .gemini, endpoint: "https://example.test/v1beta", key: "fixture")
        let request = try gemini.request(cursor: "a+b&c")
        precondition(request.value(forHTTPHeaderField: "x-goog-api-key") == "fixture")
        precondition(!request.url!.absoluteString.contains("fixture"))
        let parsed = try RemoteAIModelCatalogService.parse(Data(#"{"models":[{"name":"models/new-text","displayName":"New Text","supportedGenerationMethods":["generateContent"]},{"name":"models/embedding","supportedGenerationMethods":["embedContent"]}],"nextPageToken":"page2"}"#.utf8), api: .gemini)
        precondition(parsed.0.map(\.id) == ["new-text"] && parsed.1 == "page2")
        for bad in ["http://example.test/v1/responses", "https://user:secret@example.test/v1/responses", "https://example.test/v1/responses?key=secret", "https://example.test/arbitrary"] {
            do { _ = try AIModelCatalogConfiguration(api: .standard, endpoint: bad, key: "fixture").request(); preconditionFailure("invalid endpoint accepted") }
            catch AIModelCatalogError.configuration { }
        }
        for status in [401, 403, 404, 429, 500, 302] {
            do { _ = try await RemoteAIModelCatalogService(client: MockHTTP([("sensitive upstream error", status)])).fetch(config); preconditionFailure("bad status accepted") }
            catch { precondition(!error.localizedDescription.contains("sensitive")) }
        }
        do { _ = try RemoteAIModelCatalogService.parse(Data("{}".utf8), api: .standard); preconditionFailure("invalid shape accepted") }
        catch AIModelCatalogError.invalidResponse { }
        let repeated = #"{"data":[],"has_more":true,"last_id":"same"}"#
        do { _ = try await RemoteAIModelCatalogService(client: MockHTTP([(repeated,200),(repeated,200)])).fetch(claude); preconditionFailure("repeated cursor accepted") }
        catch AIModelCatalogError.invalidResponse { }
        let oversized = "{\"data\":[" + (0...5000).map { "{\"id\":\"model-\($0)\"}" }.joined(separator: ",") + "]}"
        let limited = try await RemoteAIModelCatalogService(client: MockHTTP([(oversized, 200)])).fetch(config)
        precondition(limited.models.count == 5000 && limited.truncated)
        print("Model catalog: endpoints, authentication headers, unknown future IDs, sorting, deduplication, pagination, Gemini filtering and failure isolation passed offline.")
    }
}
