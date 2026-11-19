import CryptoKit
import Foundation
import Security

/// Sign in with ChatGPT for open-source apps: the signed-in account's ChatGPT
/// plan pays for translations, so no API key is needed.
///
/// The flow follows developers.openai.com/siwc (open-source, locally hosted
/// apps): an authorization code with PKCE on auth.openai.com and a
/// `127.0.0.1` loopback redirect. The first sign-in registers this install
/// (`dynamic_agent_client` with `agent_name_hint` and `ext_agent_host_id`);
/// later sign-ins reuse the client id OpenAI issued. Inference is the
/// Responses API with `store: false` and `stream: true`, successful only on
/// `response.completed`. Usage limits stop the request; nothing switches to
/// another billing path.
///
/// Everything in this file is Foundation-only so the host tests can check it
/// offline. The loopback listener is in `ChatGPTPlanLoopback.swift`, the
/// sign-in sheet in `App/ChatGPTPlanSignIn.swift`.
enum ChatGPTPlan {
    static let authorizeURL = URL(string: "https://auth.openai.com/api/accounts/authorize")!
    static let tokenURL = URL(string: "https://auth.openai.com/api/accounts/oauth/token")!
    static let discoveryURL = URL(string: "https://auth.openai.com/.well-known/openid-configuration")!
    /// The only API the plan's tokens are sent to.
    static let resource = "https://api.openai.com/v1"
    static let modelsURL = URL(string: "https://api.openai.com/v1/models")!
    static let responsesURL = URL(string: "https://api.openai.com/v1/responses")!
    /// Where the 管理用量 links go; also where a usage-limit error sends the user.
    static let manageUsageURL = URL(string: "https://chatgpt.com/settings/usage")!
    static let documentationURL = URL(string: "https://developers.openai.com/siwc/token-sharing-open-source")!
    static let dynamicClientID = "dynamic_agent_client"
    /// The name shown on OpenAI's consent page. Kept the same on every install.
    static let agentName = "Jerreader"
    static let scope = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
    static let callbackPath = "/auth/callback"
    /// Where the loopback listener sends the browser once it has the code, so
    /// the sign-in sheet closes by itself.
    static let finishURL = "jerreader://chatgpt-auth/done"
    static let finishScheme = "jerreader"
    /// The keychain account of the signed-in credential, beside the API keys.
    static let credentialAccount = "chatgpt-plan-credential"
    static let keychainService = "Jerreader.translation-backend"
    /// Error codes that mean the plan cannot pay for more requests right now.
    static let usageLimitCodes = [
        "subscription_sharing_usage_limit_exceeded",
        "subscription_sharing_usage_unavailable",
    ]
    /// A token this close to expiry is refreshed before it is used.
    static let refreshMargin: TimeInterval = 120
}

// MARK: - Errors

enum ChatGPTPlanError: LocalizedError, Equatable, Sendable {
    case cancelled
    case stateMismatch
    case invalidCallback
    /// A first sign-in came back without the client id it should register.
    case registrationIncomplete
    case authorizationFailed(String)
    case signInRequired
    case usageLimitReached
    case noModels
    case listenerUnavailable
    /// The system sign-in sheet could not be shown.
    case browserUnavailable
    case keychainUnavailable
    case network
    case unexpectedResponse

    var errorDescription: String? {
        switch self {
        case .cancelled:
            return "已取消登录。"
        case .stateMismatch, .invalidCallback:
            return "登录回调无效，请重新登录。"
        case .registrationIncomplete:
            return "OpenAI 没有完成这台设备的注册，请重新登录。"
        case .authorizationFailed(let reason):
            return "ChatGPT 拒绝了登录（\(reason)），请重新登录。"
        case .signInRequired:
            return "ChatGPT 登录已失效，请重新登录。"
        case .usageLimitReached:
            return "ChatGPT 套餐的额度已用完或暂时不可用。到 chatgpt.com/settings/usage 查看用量；Plus 的 5 小时额度和 ChatGPT、Codex 等共用。"
        case .noModels:
            return "这个 ChatGPT 账号没有可用的模型。"
        case .listenerUnavailable:
            return "无法在本机接收登录回调，请重试。"
        case .browserUnavailable:
            return "无法打开 ChatGPT 登录页，请重试。"
        case .keychainUnavailable:
            return "无法把 ChatGPT 登录信息安全保存到钥匙串。"
        case .network:
            return "无法连接 OpenAI，请检查网络后重试。"
        case .unexpectedResponse:
            return "OpenAI 返回了无法识别的内容，请稍后重试。"
        }
    }
}

// MARK: - PKCE and the authorization request

struct ChatGPTPlanPKCE: Equatable, Sendable {
    let verifier: String
    let challenge: String

    init(verifier: String) {
        self.verifier = verifier
        challenge = ChatGPTPlanEncoding.base64URL(Data(SHA256.hash(data: Data(verifier.utf8))))
    }

    static func random() -> ChatGPTPlanPKCE {
        ChatGPTPlanPKCE(verifier: ChatGPTPlanEncoding.randomToken(bytes: 32))
    }
}

enum ChatGPTPlanEncoding {
    static func base64URL(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    static func base64URLDecoded(_ text: String) -> Data? {
        var value = text.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while value.count % 4 != 0 { value += "=" }
        return Data(base64Encoded: value)
    }

    static func randomToken(bytes count: Int) -> String {
        var bytes = [UInt8](repeating: 0, count: count)
        if SecRandomCopyBytes(kSecRandomDefault, count, &bytes) != errSecSuccess {
            var generator = SystemRandomNumberGenerator()
            bytes = (0 ..< count).map { _ in UInt8.random(in: .min ... .max, using: &generator) }
        }
        return base64URL(Data(bytes))
    }

    /// Strict form and query encoding: only unreserved characters stay as they are.
    static func formEncoded(_ pairs: [(String, String)]) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return pairs.map { name, value in
            (name.addingPercentEncoding(withAllowedCharacters: allowed) ?? name) + "="
                + (value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value)
        }.joined(separator: "&")
    }
}

/// One sign-in attempt: what goes into the authorize URL and what the
/// callback and the token exchange are checked against.
struct ChatGPTPlanAuthorization: Sendable {
    let clientID: String
    let hostID: String
    let redirectURI: String
    let state: String
    let nonce: String
    let pkce: ChatGPTPlanPKCE
    var loginHint: String?

    /// The first sign-in on this install registers it.
    var isRegistration: Bool { clientID == ChatGPTPlan.dynamicClientID }

    init(clientID: String?, hostID: String, port: UInt16, loginHint: String? = nil,
         state: String = ChatGPTPlanEncoding.randomToken(bytes: 24),
         nonce: String = ChatGPTPlanEncoding.randomToken(bytes: 24),
         pkce: ChatGPTPlanPKCE = .random()) {
        let issued = clientID?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        self.clientID = issued.isEmpty ? ChatGPTPlan.dynamicClientID : issued
        self.hostID = hostID
        redirectURI = Self.redirectURI(port: port)
        self.state = state
        self.nonce = nonce
        self.pkce = pkce
        self.loginHint = loginHint
    }

    /// Only the port may change between sign-ins; the host is always 127.0.0.1.
    static func redirectURI(port: UInt16) -> String {
        "http://127.0.0.1:\(port)\(ChatGPTPlan.callbackPath)"
    }

    var url: URL {
        var pairs: [(String, String)] = [
            ("response_type", "code"),
            ("client_id", clientID),
            ("redirect_uri", redirectURI),
            ("scope", ChatGPTPlan.scope),
            ("resource", ChatGPTPlan.resource),
            ("state", state),
            ("nonce", nonce),
            ("code_challenge", pkce.challenge),
            ("code_challenge_method", "S256"),
            ("ext_agent_host_id", hostID),
        ]
        if isRegistration { pairs.append(("agent_name_hint", ChatGPTPlan.agentName)) }
        if let loginHint = loginHint?.trimmingCharacters(in: .whitespacesAndNewlines), !loginHint.isEmpty {
            pairs.append(("login_hint", loginHint))
        }
        var components = URLComponents(url: ChatGPTPlan.authorizeURL, resolvingAgainstBaseURL: false)!
        components.percentEncodedQuery = ChatGPTPlanEncoding.formEncoded(pairs)
        return components.url!
    }
}

/// What the browser brought back to the loopback listener.
struct ChatGPTPlanCallback: Equatable, Sendable {
    let code: String
    /// Present when this sign-in registered the install.
    let issuedClientID: String?

    /// - Parameter target: the request target the listener received, such as
    ///   `/auth/callback?code=…&state=…`.
    static func parse(target: String, for authorization: ChatGPTPlanAuthorization) throws -> ChatGPTPlanCallback {
        guard target.hasPrefix("/"),
              let components = URLComponents(string: "http://127.0.0.1" + target),
              components.path == ChatGPTPlan.callbackPath
        else { throw ChatGPTPlanError.invalidCallback }
        var items: [String: String] = [:]
        for item in components.queryItems ?? [] where items[item.name] == nil {
            items[item.name] = item.value ?? ""
        }
        if let error = items["error"], !error.isEmpty {
            throw error == "access_denied" ? ChatGPTPlanError.cancelled : ChatGPTPlanError.authorizationFailed(error)
        }
        guard items["state"] == authorization.state else { throw ChatGPTPlanError.stateMismatch }
        guard let code = items["code"], !code.isEmpty else { throw ChatGPTPlanError.invalidCallback }
        let issued = items["client_id"].flatMap { $0.isEmpty ? nil : $0 }
        if authorization.isRegistration, issued == nil { throw ChatGPTPlanError.registrationIncomplete }
        return ChatGPTPlanCallback(code: code, issuedClientID: issued)
    }
}

// MARK: - Tokens

struct ChatGPTPlanTokenResponse: Decodable, Sendable {
    let accessToken: String
    let refreshToken: String?
    let idToken: String?
    let expiresIn: Double?

    enum CodingKeys: String, CodingKey {
        case accessToken = "access_token"
        case refreshToken = "refresh_token"
        case idToken = "id_token"
        case expiresIn = "expires_in"
    }
}

/// The signed-in account. Kept in the keychain on this device only; never in
/// backups, logs or settings.
struct ChatGPTPlanCredential: Codable, Equatable, Sendable {
    var clientID: String
    var accessToken: String
    var refreshToken: String
    var idToken: String?
    var expiresAt: Date
    var email: String?
    var planType: String?
    var signedInAt: Date

    var account: ChatGPTPlanAccount { ChatGPTPlanAccount(email: email, planType: planType) }
}

/// What the settings pages show of the signed-in account.
struct ChatGPTPlanAccount: Equatable, Sendable {
    var email: String?
    var planType: String?

    /// `plus` → Plus; an unknown plan is shown as it came.
    var planTitle: String? {
        guard let plan = planType?.trimmingCharacters(in: .whitespacesAndNewlines), !plan.isEmpty else { return nil }
        switch plan.lowercased() {
        case "plus": return "Plus"
        case "pro": return "Pro"
        case "team": return "Team"
        case "business": return "Business"
        case "enterprise": return "Enterprise"
        case "edu": return "Edu"
        case "go": return "Go"
        case "free": return "Free"
        default: return plan.prefix(1).uppercased() + plan.dropFirst()
        }
    }
}

/// The display claims of an ID token. The token is not verified here: it
/// only labels the account on the settings page and decides nothing.
enum ChatGPTPlanIDToken {
    static func account(from token: String?) -> ChatGPTPlanAccount {
        guard let token else { return ChatGPTPlanAccount() }
        let parts = token.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count >= 2, let data = ChatGPTPlanEncoding.base64URLDecoded(String(parts[1])),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return ChatGPTPlanAccount() }
        let auth = object["https://api.openai.com/auth"] as? [String: Any]
        return ChatGPTPlanAccount(
            email: (object["email"] as? String).flatMap { $0.isEmpty ? nil : $0 },
            planType: (auth?["chatgpt_plan_type"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        )
    }
}

/// Requests to auth.openai.com and api.openai.com. None of them carries a
/// client secret: this is a public client.
enum ChatGPTPlanRequests {
    static func authorizationCode(code: String, verifier: String, redirectURI: String, clientID: String) -> URLRequest {
        form(ChatGPTPlan.tokenURL, [
            ("grant_type", "authorization_code"),
            ("client_id", clientID),
            ("code", code),
            ("code_verifier", verifier),
            ("redirect_uri", redirectURI),
            ("resource", ChatGPTPlan.resource),
        ])
    }

    /// No `scope`: a refresh keeps the scopes that were granted.
    static func refresh(refreshToken: String, clientID: String) -> URLRequest {
        form(ChatGPTPlan.tokenURL, [
            ("grant_type", "refresh_token"),
            ("client_id", clientID),
            ("refresh_token", refreshToken),
            ("resource", ChatGPTPlan.resource),
        ])
    }

    static func revoke(endpoint: URL, refreshToken: String, clientID: String) -> URLRequest {
        form(endpoint, [
            ("token", refreshToken),
            ("token_type_hint", "refresh_token"),
            ("client_id", clientID),
        ])
    }

    static func models(accessToken: String) -> URLRequest {
        var request = URLRequest(url: ChatGPTPlan.modelsURL)
        request.httpMethod = "GET"
        request.timeoutInterval = 30
        request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        return request
    }

    /// The Responses request of the plan: `store: false` and `stream: true`,
    /// instructions apart from the input, and none of the fields the plan
    /// does not take. `extra` may only add reasoning.
    static func responses(accessToken: String, model: String, instructions: String, input: String,
                          extra: [String: Any] = [:], timeoutInterval: TimeInterval = 30) throws -> URLRequest {
        var body: [String: Any] = [
            "model": model,
            "instructions": instructions,
            "input": input,
            "store": false,
            "stream": true,
        ]
        if let reasoning = extra["reasoning"] { body["reasoning"] = reasoning }
        var request = URLRequest(url: ChatGPTPlan.responsesURL)
        request.httpMethod = "POST"
        request.timeoutInterval = timeoutInterval
        request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        request.httpBody = try JSONSerialization.data(withJSONObject: body, options: [.sortedKeys])
        return request
    }

    /// Fields the plan's inference requests must leave out.
    static let omittedFields: Set<String> = [
        "background", "conversation", "max_output_tokens", "max_tool_calls", "metadata", "moderation",
        "multi_agent", "prompt", "prompt_cache_retention", "safety_identifier", "temperature",
        "top_logprobs", "top_p", "truncation", "user", "previous_response_id",
    ]

    /// An OAuth error that means the grant is gone and only a new sign-in helps.
    static func isDeadGrant(status: Int, data: Data) -> Bool {
        guard status == 400 || status == 401 else { return false }
        let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        let code = (object?["error"] as? String) ?? ((object?["error"] as? [String: Any])?["code"] as? String)
        return ["invalid_grant", "invalid_client", "unauthorized_client"].contains(code ?? "")
    }

    private static func form(_ url: URL, _ pairs: [(String, String)]) -> URLRequest {
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 30
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.httpBody = Data(ChatGPTPlanEncoding.formEncoded(pairs).utf8)
        return request
    }
}

// MARK: - Models and streamed responses

struct ChatGPTPlanModel: Codable, Equatable, Identifiable, Sendable {
    /// Sent as `model`.
    let slug: String
    /// Shown in the app.
    let displayName: String

    var id: String { slug }
}

enum ChatGPTPlanModels {
    /// The account's models in the server's order, only those meant to be listed.
    static func parse(_ data: Data) throws -> [ChatGPTPlanModel] {
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw ChatGPTPlanError.unexpectedResponse
        }
        let items = (object["data"] as? [[String: Any]]) ?? (object["models"] as? [[String: Any]]) ?? []
        var seen = Set<String>()
        return items.compactMap { item in
            guard (item["visibility"] as? String) == "list",
                  let slug = ((item["slug"] as? String) ?? (item["id"] as? String))?
                      .trimmingCharacters(in: .whitespacesAndNewlines),
                  !slug.isEmpty, seen.insert(slug).inserted
            else { return nil }
            let name = (item["display_name"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            return ChatGPTPlanModel(slug: slug, displayName: name.isEmpty ? slug : name)
        }
    }
}

enum ChatGPTPlanStreamOutcome: Equatable, Sendable {
    case completed(String)
    case failed(code: String?)
    case incomplete(reason: String?)
    /// The stream ended without `response.completed`, `failed` or `incomplete`.
    case interrupted
}

/// Reads a whole Server-Sent Events body of a Responses stream.
enum ChatGPTPlanStream {
    static func parse(_ data: Data) -> ChatGPTPlanStreamOutcome {
        guard let text = String(data: data, encoding: .utf8) else { return .interrupted }
        var deltas = ""
        let normalized = text.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
        for block in normalized.components(separatedBy: "\n\n") {
            var eventName: String?
            var lines: [String] = []
            for line in block.split(separator: "\n", omittingEmptySubsequences: true) {
                if line.hasPrefix("data:") {
                    lines.append(String(line.dropFirst(5)).trimmingLeadingSpace)
                } else if line.hasPrefix("event:") {
                    eventName = String(line.dropFirst(6)).trimmingCharacters(in: .whitespaces)
                }
            }
            guard !lines.isEmpty else { continue }
            let payload = lines.joined(separator: "\n")
            guard payload != "[DONE]",
                  let object = try? JSONSerialization.jsonObject(with: Data(payload.utf8)) as? [String: Any]
            else { continue }
            switch (object["type"] as? String) ?? eventName ?? "" {
            case "response.output_text.delta":
                deltas += object["delta"] as? String ?? ""
            case "response.completed":
                let final = outputText(of: object["response"] as? [String: Any])
                return .completed(final.isEmpty ? deltas : final)
            case "response.failed":
                let error = (object["response"] as? [String: Any])?["error"] as? [String: Any]
                return .failed(code: error?["code"] as? String)
            case "response.incomplete":
                let details = (object["response"] as? [String: Any])?["incomplete_details"] as? [String: Any]
                return .incomplete(reason: details?["reason"] as? String)
            case "error":
                let nested = object["error"] as? [String: Any]
                return .failed(code: (object["code"] as? String) ?? (nested?["code"] as? String))
            default:
                continue
            }
        }
        return .interrupted
    }

    /// The usage-limit code of an error body sent before any stream, such as
    /// `{"detail": "…"}` or `{"error": {"code": "…"}}`.
    static func usageLimitCode(in data: Data) -> String? {
        guard let text = String(data: data, encoding: .utf8) else { return nil }
        return ChatGPTPlan.usageLimitCodes.first { text.contains($0) }
    }

    static func isUsageLimit(_ code: String?) -> Bool {
        guard let code else { return false }
        return ChatGPTPlan.usageLimitCodes.contains(code)
    }

    private static func outputText(of response: [String: Any]?) -> String {
        let output = response?["output"] as? [[String: Any]] ?? []
        return output
            .filter { ($0["type"] as? String ?? "message") == "message" }
            .flatMap { $0["content"] as? [[String: Any]] ?? [] }
            .filter { ($0["type"] as? String) == "output_text" }
            .compactMap { $0["text"] as? String }
            .joined()
    }
}

private extension String {
    var trimmingLeadingSpace: String { hasPrefix(" ") ? String(dropFirst()) : self }
}

// MARK: - Storage and the session

protocol ChatGPTPlanHTTPClient: Sendable {
    func data(for request: URLRequest) async throws -> (Data, HTTPURLResponse)
}

struct ChatGPTPlanURLSessionClient: ChatGPTPlanHTTPClient {
    /// No cookies or cache for sign-in requests.
    private static let session = URLSession(configuration: .ephemeral)

    func data(for request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let (data, response) = try await Self.session.data(for: request)
        guard let response = response as? HTTPURLResponse else { throw ChatGPTPlanError.unexpectedResponse }
        return (data, response)
    }
}

protocol ChatGPTPlanCredentialStoring: Sendable {
    func load() throws -> ChatGPTPlanCredential?
    /// `nil` removes the credential.
    func save(_ credential: ChatGPTPlanCredential?) throws
}

/// The credential as JSON in the keychain, readable after first unlock on this device only.
struct ChatGPTPlanKeychain: ChatGPTPlanCredentialStoring {
    var service = ChatGPTPlan.keychainService
    var account = ChatGPTPlan.credentialAccount

    func load() throws -> ChatGPTPlanCredential? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else { throw ChatGPTPlanError.keychainUnavailable }
        return try? JSONDecoder().decode(ChatGPTPlanCredential.self, from: data)
    }

    func save(_ credential: ChatGPTPlanCredential?) throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        let deleted = SecItemDelete(query as CFDictionary)
        guard deleted == errSecSuccess || deleted == errSecItemNotFound else {
            throw ChatGPTPlanError.keychainUnavailable
        }
        guard let credential else { return }
        var item = query
        item[kSecValueData as String] = try JSONEncoder().encode(credential)
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        guard SecItemAdd(item as CFDictionary, nil) == errSecSuccess else {
            throw ChatGPTPlanError.keychainUnavailable
        }
    }
}

/// This install's registration: a host id made once, and the client id
/// OpenAI issued at the first sign-in. Neither is a secret; both stay out of
/// backups so a restored device registers as itself.
enum ChatGPTPlanRegistration {
    static let hostIDKey = "translation.chatgptPlan.hostID"
    static let clientIDKey = "translation.chatgptPlan.clientID"
    static let welcomeShownKey = "translation.chatgptPlan.welcomeShown"
    static let modelNamesKey = "translation.chatgptPlan.modelNames"

    static func hostID(_ defaults: UserDefaults = .standard) -> String {
        if let saved = defaults.string(forKey: hostIDKey), saved.hasPrefix("urn:uuid:") { return saved }
        let made = "urn:uuid:" + UUID().uuidString.lowercased()
        defaults.set(made, forKey: hostIDKey)
        return made
    }

    static func clientID(_ defaults: UserDefaults = .standard) -> String? {
        defaults.string(forKey: clientIDKey).flatMap { $0.isEmpty ? nil : $0 }
    }

    static func saveClientID(_ id: String, _ defaults: UserDefaults = .standard) {
        defaults.set(id, forKey: clientIDKey)
    }
}

/// Owns the signed-in credential: hands out a current access token,
/// refreshes it one request at a time and keeps the newest refresh token.
actor ChatGPTPlanSession {
    static let shared = ChatGPTPlanSession()

    private let store: any ChatGPTPlanCredentialStoring
    private let client: any ChatGPTPlanHTTPClient
    private let now: @Sendable () -> Date
    private var loaded = false
    private var credential: ChatGPTPlanCredential?
    private var refreshing: Task<ChatGPTPlanCredential, Error>?

    init(store: any ChatGPTPlanCredentialStoring = ChatGPTPlanKeychain(),
         client: any ChatGPTPlanHTTPClient = ChatGPTPlanURLSessionClient(),
         now: @escaping @Sendable () -> Date = { Date() }) {
        self.store = store
        self.client = client
        self.now = now
    }

    func current() -> ChatGPTPlanCredential? {
        if !loaded {
            credential = try? store.load()
            loaded = true
        }
        return credential
    }

    /// A token that is valid now. Pass the token a request was refused with
    /// to get a fresh one; a token another request already replaced is
    /// returned without a second refresh.
    func accessToken(rejected: String? = nil) async throws -> String {
        guard let saved = current() else { throw ChatGPTPlanError.signInRequired }
        if saved.accessToken != rejected, saved.expiresAt.timeIntervalSince(now()) > ChatGPTPlan.refreshMargin {
            return saved.accessToken
        }
        return try await refreshed(saved).accessToken
    }

    /// Exchanges the code of a finished sign-in and saves the account.
    func completeSignIn(code: String, authorization: ChatGPTPlanAuthorization,
                        issuedClientID: String?) async throws -> ChatGPTPlanCredential {
        let clientID = issuedClientID ?? authorization.clientID
        guard clientID != ChatGPTPlan.dynamicClientID else { throw ChatGPTPlanError.registrationIncomplete }
        let (data, response) = try await send(ChatGPTPlanRequests.authorizationCode(
            code: code, verifier: authorization.pkce.verifier,
            redirectURI: authorization.redirectURI, clientID: clientID))
        guard (200 ..< 300).contains(response.statusCode) else {
            if ChatGPTPlanRequests.isDeadGrant(status: response.statusCode, data: data) {
                throw ChatGPTPlanError.authorizationFailed("invalid_grant")
            }
            throw ChatGPTPlanError.network
        }
        guard let token = try? JSONDecoder().decode(ChatGPTPlanTokenResponse.self, from: data),
              !token.accessToken.isEmpty, let refresh = token.refreshToken, !refresh.isEmpty
        else { throw ChatGPTPlanError.unexpectedResponse }
        let account = ChatGPTPlanIDToken.account(from: token.idToken)
        let made = ChatGPTPlanCredential(
            clientID: clientID, accessToken: token.accessToken, refreshToken: refresh, idToken: token.idToken,
            expiresAt: now().addingTimeInterval(token.expiresIn ?? 3_600),
            email: account.email, planType: account.planType, signedInAt: now())
        try store.save(made)
        refreshing?.cancel()
        refreshing = nil
        credential = made
        loaded = true
        return made
    }

    /// Forgets the account here first, then revokes its refresh token as a
    /// courtesy; a failed revocation changes nothing on this device.
    func signOut() async {
        let saved = current()
        try? store.save(nil)
        credential = nil
        loaded = true
        refreshing?.cancel()
        refreshing = nil
        guard let saved, let endpoint = await revocationEndpoint() else { return }
        _ = try? await client.data(for: ChatGPTPlanRequests.revoke(
            endpoint: endpoint, refreshToken: saved.refreshToken, clientID: saved.clientID))
    }

    /// The models the signed-in account may use.
    func models() async throws -> [ChatGPTPlanModel] {
        var token = try await accessToken()
        var (data, response) = try await send(ChatGPTPlanRequests.models(accessToken: token))
        if response.statusCode == 401 {
            token = try await accessToken(rejected: token)
            (data, response) = try await send(ChatGPTPlanRequests.models(accessToken: token))
        }
        switch response.statusCode {
        case 200 ..< 300: break
        case 401, 403: throw ChatGPTPlanError.signInRequired
        default: throw ChatGPTPlanError.network
        }
        let models = try ChatGPTPlanModels.parse(data)
        guard !models.isEmpty else { throw ChatGPTPlanError.noModels }
        return models
    }

    private func refreshed(_ saved: ChatGPTPlanCredential) async throws -> ChatGPTPlanCredential {
        if let refreshing { return try await refreshing.value }
        let task = Task { try await self.refresh(saved) }
        refreshing = task
        defer { if refreshing == task { refreshing = nil } }
        return try await task.value
    }

    private func refresh(_ saved: ChatGPTPlanCredential) async throws -> ChatGPTPlanCredential {
        let (data, response) = try await send(ChatGPTPlanRequests.refresh(
            refreshToken: saved.refreshToken, clientID: saved.clientID))
        guard (200 ..< 300).contains(response.statusCode) else {
            if ChatGPTPlanRequests.isDeadGrant(status: response.statusCode, data: data) {
                try? store.save(nil)
                credential = nil
                throw ChatGPTPlanError.signInRequired
            }
            throw ChatGPTPlanError.network
        }
        guard let token = try? JSONDecoder().decode(ChatGPTPlanTokenResponse.self, from: data),
              !token.accessToken.isEmpty
        else { throw ChatGPTPlanError.unexpectedResponse }
        // A sign-out while the refresh was in flight wins, and so does a new sign-in.
        guard let latest = credential else { throw ChatGPTPlanError.signInRequired }
        guard latest.refreshToken == saved.refreshToken else { return latest }
        var updated = saved
        updated.accessToken = token.accessToken
        if let rotated = token.refreshToken, !rotated.isEmpty { updated.refreshToken = rotated }
        if let idToken = token.idToken, !idToken.isEmpty {
            updated.idToken = idToken
            let account = ChatGPTPlanIDToken.account(from: idToken)
            updated.email = account.email ?? updated.email
            updated.planType = account.planType ?? updated.planType
        }
        updated.expiresAt = now().addingTimeInterval(token.expiresIn ?? 3_600)
        // The old refresh token no longer works; keep the new one even if the
        // keychain refuses it, so this run can go on.
        try? store.save(updated)
        credential = updated
        return updated
    }

    private func revocationEndpoint() async -> URL? {
        var request = URLRequest(url: ChatGPTPlan.discoveryURL)
        request.timeoutInterval = 15
        guard let (data, response) = try? await client.data(for: request),
              (200 ..< 300).contains(response.statusCode),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let text = object["revocation_endpoint"] as? String,
              let url = URL(string: text), url.scheme == "https", url.host?.hasSuffix("openai.com") == true
        else { return nil }
        return url
    }

    private func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        do {
            return try await client.data(for: request)
        } catch is CancellationError {
            throw CancellationError()
        } catch let error as ChatGPTPlanError {
            throw error
        } catch {
            throw ChatGPTPlanError.network
        }
    }
}
