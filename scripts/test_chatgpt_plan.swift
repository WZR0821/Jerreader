import Darwin
import Foundation

/// Offline checks of Sign in with ChatGPT: PKCE, the authorize URL, the
/// loopback callback, token requests, the streamed Responses events, the
/// model list, token refresh against a mock server, and the real loopback
/// listener on 127.0.0.1. Nothing here reaches the internet.
@main enum ChatGPTPlanTests {
    static func main() async throws {
        pkce()
        authorizationURL()
        callbacks()
        try requests()
        stream()
        try models()
        idToken()
        try await session()
        try await loopback()
        print("ChatGPT plan passed offline.")
    }

    // MARK: PKCE and the authorize URL

    static func pkce() {
        // RFC 7636, appendix B.
        let known = ChatGPTPlanPKCE(verifier: "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        precondition(known.challenge == "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
        let made = ChatGPTPlanPKCE.random()
        let allowed = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_")
        precondition(made.verifier.count == 43 && made.verifier.unicodeScalars.allSatisfy(allowed.contains))
        precondition(made.verifier != ChatGPTPlanPKCE.random().verifier)
        precondition(ChatGPTPlanEncoding.randomToken(bytes: 24).count == 32)
        precondition(ChatGPTPlanEncoding.base64URLDecoded("aGk") == Data("hi".utf8))
        precondition(ChatGPTPlanEncoding.formEncoded([("a b", "c/d:e"), ("x", "~._-")]) == "a%20b=c%2Fd%3Ae&x=~._-")
    }

    static func query(_ url: URL) -> [String: String] {
        var items: [String: String] = [:]
        for item in URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? [] {
            precondition(items[item.name] == nil, "duplicate \(item.name)")
            items[item.name] = item.value
        }
        return items
    }

    static func authorizationURL() {
        let pkce = ChatGPTPlanPKCE(verifier: "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        let first = ChatGPTPlanAuthorization(clientID: nil, hostID: "urn:uuid:host", port: 51_234,
                                             state: "s1", nonce: "n1", pkce: pkce)
        precondition(first.isRegistration && first.clientID == "dynamic_agent_client")
        precondition(first.url.scheme == "https" && first.url.host == "auth.openai.com")
        precondition(first.url.path == "/api/accounts/authorize")
        let items = query(first.url)
        precondition(items["response_type"] == "code" && items["client_id"] == "dynamic_agent_client")
        precondition(items["redirect_uri"] == "http://127.0.0.1:51234/auth/callback")
        precondition(items["scope"] == "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct")
        precondition(items["resource"] == "https://api.openai.com/v1")
        precondition(items["state"] == "s1" && items["nonce"] == "n1")
        precondition(items["code_challenge"] == pkce.challenge && items["code_challenge_method"] == "S256")
        precondition(items["ext_agent_host_id"] == "urn:uuid:host" && items["agent_name_hint"] == "Jerreader")
        precondition(items["login_hint"] == nil && items["client_secret"] == nil)
        // Strictly encoded, never `localhost`.
        let raw = first.url.absoluteString
        precondition(raw.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A51234%2Fauth%2Fcallback"))
        precondition(raw.contains("scope=openid%20profile%20email") && !raw.contains("localhost"))

        let later = ChatGPTPlanAuthorization(clientID: " issued-client ", hostID: "urn:uuid:host", port: 60_000,
                                             loginHint: "reader@example.test")
        precondition(!later.isRegistration && later.clientID == "issued-client")
        let laterItems = query(later.url)
        precondition(laterItems["agent_name_hint"] == nil && laterItems["client_id"] == "issued-client")
        precondition(laterItems["login_hint"] == "reader@example.test")
        precondition(laterItems["state"] != nil && laterItems["state"] != laterItems["nonce"])
        precondition(ChatGPTPlanAuthorization(clientID: "  ", hostID: "h", port: 1).isRegistration)
    }

    // MARK: Callbacks

    static func fails(_ expected: ChatGPTPlanError, _ body: () throws -> Void, line: Int = #line) {
        do {
            try body()
            preconditionFailure("line \(line): expected \(expected), nothing was thrown")
        } catch let error as ChatGPTPlanError {
            precondition(error == expected, "line \(line): expected \(expected), got \(error)")
        } catch {
            preconditionFailure("line \(line): expected \(expected), got \(error)")
        }
    }

    static func callbacks() {
        let first = ChatGPTPlanAuthorization(clientID: nil, hostID: "h", port: 50_000, state: "s1", nonce: "n1")
        let later = ChatGPTPlanAuthorization(clientID: "issued", hostID: "h", port: 50_000, state: "s2", nonce: "n2")

        let registered = try! ChatGPTPlanCallback.parse(
            target: "/auth/callback?code=a%2Fb&state=s1&client_id=client-9", for: first)
        precondition(registered == ChatGPTPlanCallback(code: "a/b", issuedClientID: "client-9"))
        let signedIn = try! ChatGPTPlanCallback.parse(target: "/auth/callback?state=s2&code=c2", for: later)
        precondition(signedIn == ChatGPTPlanCallback(code: "c2", issuedClientID: nil))
        // The first of a repeated parameter counts.
        let repeated = try! ChatGPTPlanCallback.parse(target: "/auth/callback?code=one&code=two&state=s2", for: later)
        precondition(repeated.code == "one")

        fails(.registrationIncomplete) { _ = try ChatGPTPlanCallback.parse(target: "/auth/callback?code=c&state=s1", for: first) }
        fails(.stateMismatch) { _ = try ChatGPTPlanCallback.parse(target: "/auth/callback?code=c&state=s1", for: later) }
        fails(.stateMismatch) { _ = try ChatGPTPlanCallback.parse(target: "/auth/callback?code=c", for: later) }
        fails(.cancelled) { _ = try ChatGPTPlanCallback.parse(target: "/auth/callback?error=access_denied&state=s2", for: later) }
        fails(.authorizationFailed("server_error")) {
            _ = try ChatGPTPlanCallback.parse(target: "/auth/callback?error=server_error", for: later)
        }
        fails(.invalidCallback) { _ = try ChatGPTPlanCallback.parse(target: "/auth/callback?state=s2", for: later) }
        fails(.invalidCallback) { _ = try ChatGPTPlanCallback.parse(target: "/auth/callback?state=s2&code=", for: later) }
        fails(.invalidCallback) { _ = try ChatGPTPlanCallback.parse(target: "/favicon.ico?code=c&state=s2", for: later) }
        fails(.invalidCallback) { _ = try ChatGPTPlanCallback.parse(target: "auth/callback?code=c&state=s2", for: later) }
        fails(.invalidCallback) { _ = try ChatGPTPlanCallback.parse(target: "/auth/callback/extra?code=c&state=s2", for: later) }
    }

    // MARK: Requests

    static func form(_ request: URLRequest) -> [String: String] {
        var items: [String: String] = [:]
        let text = String(decoding: request.httpBody ?? Data(), as: UTF8.self)
        for pair in text.split(separator: "&") {
            let parts = pair.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
            let name = String(parts[0]).removingPercentEncoding!
            precondition(items[name] == nil, "duplicate \(name)")
            items[name] = String(parts.count > 1 ? parts[1] : "").removingPercentEncoding!
        }
        return items
    }

    static func json(_ request: URLRequest) throws -> [String: Any] {
        try JSONSerialization.jsonObject(with: request.httpBody ?? Data()) as! [String: Any]
    }

    static func requests() throws {
        let exchange = ChatGPTPlanRequests.authorizationCode(
            code: "c", verifier: "v", redirectURI: "http://127.0.0.1:5/auth/callback", clientID: "issued")
        precondition(exchange.url == ChatGPTPlan.tokenURL && exchange.httpMethod == "POST")
        precondition(exchange.value(forHTTPHeaderField: "Content-Type") == "application/x-www-form-urlencoded")
        precondition(form(exchange) == [
            "grant_type": "authorization_code", "client_id": "issued", "code": "c", "code_verifier": "v",
            "redirect_uri": "http://127.0.0.1:5/auth/callback", "resource": "https://api.openai.com/v1",
        ])

        // A refresh keeps the granted scopes: no `scope`.
        let refresh = ChatGPTPlanRequests.refresh(refreshToken: "r1", clientID: "issued")
        precondition(form(refresh) == [
            "grant_type": "refresh_token", "client_id": "issued", "refresh_token": "r1",
            "resource": "https://api.openai.com/v1",
        ])

        let revoke = ChatGPTPlanRequests.revoke(endpoint: URL(string: "https://auth.openai.com/oauth/revoke")!,
                                                refreshToken: "r1", clientID: "issued")
        precondition(form(revoke) == ["token": "r1", "token_type_hint": "refresh_token", "client_id": "issued"])

        let list = ChatGPTPlanRequests.models(accessToken: "a1")
        precondition(list.url == ChatGPTPlan.modelsURL && list.httpMethod == "GET")
        precondition(list.value(forHTTPHeaderField: "Authorization") == "Bearer a1")

        // Only reasoning passes through; the fields the plan refuses never go out.
        let inference = try ChatGPTPlanRequests.responses(
            accessToken: "a1", model: "gpt-test", instructions: "Translate.", input: "Hello.",
            extra: ["reasoning": ["effort": "low"], "temperature": 0.2, "max_output_tokens": 100, "metadata": ["k": "v"]],
            timeoutInterval: 12)
        precondition(inference.url == URL(string: "https://api.openai.com/v1/responses"))
        precondition(inference.value(forHTTPHeaderField: "Accept") == "text/event-stream")
        precondition(inference.value(forHTTPHeaderField: "Authorization") == "Bearer a1")
        precondition(inference.timeoutInterval == 12)
        let body = try json(inference)
        precondition(Set(body.keys) == ["model", "instructions", "input", "store", "stream", "reasoning"])
        precondition(body["store"] as? Bool == false && body["stream"] as? Bool == true)
        precondition(body["instructions"] as? String == "Translate." && body["input"] as? String == "Hello.")
        precondition((body["reasoning"] as? [String: Any])?["effort"] as? String == "low")
        precondition(Set(body.keys).isDisjoint(with: ChatGPTPlanRequests.omittedFields))
        precondition(ChatGPTPlanRequests.omittedFields.isSuperset(of: ["temperature", "max_output_tokens", "previous_response_id"]))

        func dead(_ status: Int, _ text: String) -> Bool {
            ChatGPTPlanRequests.isDeadGrant(status: status, data: Data(text.utf8))
        }
        precondition(dead(400, #"{"error":"invalid_grant"}"#))
        precondition(dead(401, #"{"error":{"code":"invalid_client"}}"#))
        precondition(!dead(400, #"{"error":"invalid_request"}"#))
        precondition(!dead(500, #"{"error":"invalid_grant"}"#))
        precondition(!dead(400, "not json"))
    }

    // MARK: Streams and models

    static func stream() {
        func parse(_ text: String) -> ChatGPTPlanStreamOutcome { ChatGPTPlanStream.parse(Data(text.utf8)) }
        let completed = """
        event: response.created
        data: {"type":"response.created","response":{"id":"r"}}

        event: response.output_text.delta
        data: {"type":"response.output_text.delta","delta":"你"}

        event: response.output_text.delta
        data: {"type":"response.output_text.delta","delta":"好"}

        event: response.completed
        data: {"type":"response.completed","response":{"output":[{"type":"reasoning","summary":[]},{"type":"message","content":[{"type":"output_text","text":"你好。"}]}]}}

        """
        precondition(parse(completed) == .completed("你好。"))
        // Without output text in the final event, the deltas are the answer.
        precondition(parse("data: {\"type\":\"response.output_text.delta\",\"delta\":\"a\"}\r\n\r\ndata: {\"type\":\"response.completed\",\"response\":{\"output\":[]}}\r\n\r\n")
            == .completed("a"))
        // Deltas alone are not a success.
        precondition(parse("data: {\"type\":\"response.output_text.delta\",\"delta\":\"a\"}\n\n") == .interrupted)
        precondition(parse("") == .interrupted)
        precondition(parse("data: [DONE]\n\n") == .interrupted)
        precondition(parse("""
        data: {"type":"response.failed","response":{"error":{"code":"subscription_sharing_usage_limit_exceeded","message":"m"}}}

        """) == .failed(code: "subscription_sharing_usage_limit_exceeded"))
        precondition(parse("""
        data: {"type":"response.incomplete","response":{"incomplete_details":{"reason":"max_output_tokens"}}}

        """) == .incomplete(reason: "max_output_tokens"))
        precondition(parse("event: error\ndata: {\"code\":\"server_error\"}\n\n") == .failed(code: "server_error"))
        // The event name alone identifies an event whose data has no type.
        precondition(parse("event: response.completed\ndata: {\"response\":{\"output\":[]}}\n\n") == .completed(""))

        precondition(ChatGPTPlanStream.usageLimitCode(in: Data(#"{"detail":"subscription_sharing_usage_unavailable"}"#.utf8))
            == "subscription_sharing_usage_unavailable")
        precondition(ChatGPTPlanStream.usageLimitCode(in: Data(#"{"error":{"code":"rate_limit_exceeded"}}"#.utf8)) == nil)
        precondition(ChatGPTPlanStream.isUsageLimit("subscription_sharing_usage_limit_exceeded"))
        precondition(!ChatGPTPlanStream.isUsageLimit(nil) && !ChatGPTPlanStream.isUsageLimit("server_error"))
    }

    static func models() throws {
        let text = """
        {"object":"list","data":[
          {"slug":"gpt-b","display_name":"GPT B","visibility":"list"},
          {"slug":"gpt-hidden","display_name":"Hidden","visibility":"hide"},
          {"id":"gpt-a","visibility":"list"},
          {"slug":"gpt-b","display_name":"Again","visibility":"list"},
          {"slug":"  ","visibility":"list"},
          {"slug":"gpt-none"}
        ]}
        """
        let parsed = try ChatGPTPlanModels.parse(Data(text.utf8))
        // The server's order, listed models only, once each.
        precondition(parsed == [ChatGPTPlanModel(slug: "gpt-b", displayName: "GPT B"),
                                ChatGPTPlanModel(slug: "gpt-a", displayName: "gpt-a")])
        let nested = try ChatGPTPlanModels.parse(Data(#"{"models":[{"slug":"m","visibility":"list"}]}"#.utf8))
        precondition(nested.map(\.slug) == ["m"])
        let none = try ChatGPTPlanModels.parse(Data("{}".utf8))
        precondition(none.isEmpty)
        fails(.unexpectedResponse) { _ = try ChatGPTPlanModels.parse(Data("[]".utf8)) }
    }

    static func jwt(_ payload: [String: Any]) -> String {
        let header = ChatGPTPlanEncoding.base64URL(Data(#"{"alg":"none"}"#.utf8))
        let body = ChatGPTPlanEncoding.base64URL(try! JSONSerialization.data(withJSONObject: payload))
        return header + "." + body + ".sig"
    }

    static func idToken() {
        let token = jwt(["email": "reader@example.test", "https://api.openai.com/auth": ["chatgpt_plan_type": "plus"]])
        let account = ChatGPTPlanIDToken.account(from: token)
        precondition(account == ChatGPTPlanAccount(email: "reader@example.test", planType: "plus"))
        precondition(account.planTitle == "Plus")
        precondition(ChatGPTPlanAccount(planType: "pro").planTitle == "Pro")
        precondition(ChatGPTPlanAccount(planType: "newtier").planTitle == "Newtier")
        precondition(ChatGPTPlanAccount(planType: " ").planTitle == nil)
        precondition(ChatGPTPlanIDToken.account(from: "garbage") == ChatGPTPlanAccount())
        precondition(ChatGPTPlanIDToken.account(from: nil) == ChatGPTPlanAccount())
    }

    // MARK: The session against a mock server

    final class MemoryStore: ChatGPTPlanCredentialStoring, @unchecked Sendable {
        private let lock = NSLock()
        private var saved: ChatGPTPlanCredential?
        private(set) var saves = 0

        init(_ credential: ChatGPTPlanCredential? = nil) { saved = credential }

        var value: ChatGPTPlanCredential? { lock.locked { saved } }

        func load() throws -> ChatGPTPlanCredential? { lock.locked { saved } }

        func save(_ credential: ChatGPTPlanCredential?) throws {
            lock.locked {
                saved = credential
                saves += 1
            }
        }
    }

    /// Answers requests by URL path from a script, and records them.
    final class MockServer: ChatGPTPlanHTTPClient, @unchecked Sendable {
        typealias Reply = (status: Int, body: String, delay: UInt64)
        private let lock = NSLock()
        private var replies: [String: [Reply]] = [:]
        private var log: [URLRequest] = []

        func queue(_ path: String, _ status: Int, _ body: String, delay milliseconds: UInt64 = 0) {
            lock.locked { replies[path, default: []].append((status, body, milliseconds)) }
        }

        var requests: [URLRequest] { lock.locked { log } }
        func count(_ path: String) -> Int { requests.filter { $0.url?.path == path }.count }

        func data(for request: URLRequest) async throws -> (Data, HTTPURLResponse) {
            let path = request.url?.path ?? ""
            let reply: Reply? = lock.locked {
                log.append(request)
                guard var list = replies[path], !list.isEmpty else { return nil }
                let first = list.removeFirst()
                replies[path] = list
                return first
            }
            guard let reply else { throw URLError(.cannotConnectToHost) }
            if reply.delay > 0 { try await Task.sleep(nanoseconds: reply.delay * 1_000_000) }
            let response = HTTPURLResponse(url: request.url!, statusCode: reply.status, httpVersion: "HTTP/1.1",
                                           headerFields: ["Content-Type": "application/json"])!
            return (Data(reply.body.utf8), response)
        }
    }

    final class Clock: @unchecked Sendable {
        private let lock = NSLock()
        private var current = Date(timeIntervalSince1970: 1_800_000_000)
        var now: Date { lock.locked { current } }
        func advance(_ seconds: TimeInterval) { lock.locked { current += seconds } }
    }

    static func credential(_ clock: Clock, access: String = "a1", refresh: String = "r1",
                           expiresIn: TimeInterval = 3_600) -> ChatGPTPlanCredential {
        ChatGPTPlanCredential(clientID: "issued", accessToken: access, refreshToken: refresh, idToken: nil,
                              expiresAt: clock.now.addingTimeInterval(expiresIn), email: "reader@example.test",
                              planType: "plus", signedInAt: clock.now)
    }

    static func expectError(_ expected: ChatGPTPlanError, line: Int = #line, _ body: () async throws -> Void) async {
        do {
            try await body()
            preconditionFailure("line \(line): expected \(expected), nothing was thrown")
        } catch let error as ChatGPTPlanError {
            precondition(error == expected, "line \(line): expected \(expected), got \(error)")
        } catch {
            preconditionFailure("line \(line): expected \(expected), got \(error)")
        }
    }

    static func session() async throws {
        let clock = Clock()
        let tokenPath = "/api/accounts/oauth/token"

        // A valid token is used as it is.
        do {
            let store = MemoryStore(credential(clock))
            let server = MockServer()
            let session = ChatGPTPlanSession(store: store, client: server, now: { clock.now })
            let token = try await session.accessToken()
            precondition(token == "a1" && server.requests.isEmpty)
        }

        // Two requests that find the token about to expire share one refresh,
        // and the rotated refresh token is kept.
        do {
            let store = MemoryStore(credential(clock, expiresIn: 60))
            let server = MockServer()
            server.queue(tokenPath, 200, #"{"access_token":"a2","refresh_token":"r2","expires_in":3600}"#, delay: 150)
            let session = ChatGPTPlanSession(store: store, client: server, now: { clock.now })
            async let one = session.accessToken()
            async let two = session.accessToken()
            let tokens = try await [one, two]
            precondition(tokens == ["a2", "a2"], "\(tokens)")
            precondition(server.count(tokenPath) == 1, "refreshed \(server.count(tokenPath)) times")
            precondition(form(server.requests[0])["refresh_token"] == "r1")
            precondition(form(server.requests[0])["scope"] == nil)
            precondition(store.value?.refreshToken == "r2" && store.value?.accessToken == "a2")
            precondition(store.value?.expiresAt == clock.now.addingTimeInterval(3_600))
            // Then the new token is used without another refresh.
            let again = try await session.accessToken()
            precondition(again == "a2" && server.count(tokenPath) == 1)

            // A token a request was refused with is replaced even before it expires;
            // a refresh that sends no new refresh token keeps the old one.
            server.queue(tokenPath, 200, #"{"access_token":"a3"}"#)
            let replaced = try await session.accessToken(rejected: "a2")
            precondition(replaced == "a3")
            precondition(store.value?.refreshToken == "r2")
            // A token already replaced is not refreshed again.
            let kept = try await session.accessToken(rejected: "a2")
            precondition(kept == "a3" && server.count(tokenPath) == 2)
        }

        // A dead grant signs the account out here.
        do {
            let store = MemoryStore(credential(clock, expiresIn: 10))
            let server = MockServer()
            server.queue(tokenPath, 400, #"{"error":"invalid_grant"}"#)
            let session = ChatGPTPlanSession(store: store, client: server, now: { clock.now })
            await expectError(.signInRequired) { _ = try await session.accessToken() }
            precondition(store.value == nil)
            let afterDeadGrant = await session.current()
            precondition(afterDeadGrant == nil)
            await expectError(.signInRequired) { _ = try await session.accessToken() }
            precondition(server.count(tokenPath) == 1)
        }

        // A server error is not a sign-out.
        do {
            let store = MemoryStore(credential(clock, expiresIn: 10))
            let server = MockServer()
            server.queue(tokenPath, 503, "{}")
            let session = ChatGPTPlanSession(store: store, client: server, now: { clock.now })
            await expectError(.network) { _ = try await session.accessToken() }
            precondition(store.value?.refreshToken == "r1")
            // Nothing queued: the mock refuses to connect, which is also a network error.
            await expectError(.network) { _ = try await session.accessToken() }
        }

        // No account, no token.
        do {
            let session = ChatGPTPlanSession(store: MemoryStore(), client: MockServer(), now: { clock.now })
            await expectError(.signInRequired) { _ = try await session.accessToken() }
        }

        // The first sign-in exchanges its code with the client id OpenAI issued.
        do {
            let store = MemoryStore()
            let server = MockServer()
            let idToken = jwt(["email": "new@example.test", "https://api.openai.com/auth": ["chatgpt_plan_type": "pro"]])
            server.queue(tokenPath, 200, #"{"access_token":"a1","refresh_token":"r1","id_token":"\#(idToken)","expires_in":3600}"#)
            let session = ChatGPTPlanSession(store: store, client: server, now: { clock.now })
            let authorization = ChatGPTPlanAuthorization(clientID: nil, hostID: "h", port: 55_555, state: "s", nonce: "n")
            let made = try await session.completeSignIn(code: "code-1", authorization: authorization, issuedClientID: "client-7")
            precondition(made.clientID == "client-7" && made.email == "new@example.test" && made.planType == "pro")
            precondition(store.value == made && made.expiresAt == clock.now.addingTimeInterval(3_600))
            let sent = form(server.requests[0])
            precondition(sent["client_id"] == "client-7" && sent["code"] == "code-1")
            precondition(sent["code_verifier"] == authorization.pkce.verifier)
            precondition(sent["redirect_uri"] == "http://127.0.0.1:55555/auth/callback")

            // Without an issued id there is nothing to exchange it with.
            await expectError(.registrationIncomplete) {
                _ = try await session.completeSignIn(code: "c", authorization: authorization, issuedClientID: nil)
            }
            // A token response without a refresh token is refused.
            server.queue(tokenPath, 200, #"{"access_token":"a1"}"#)
            let later = ChatGPTPlanAuthorization(clientID: "client-7", hostID: "h", port: 1, state: "s", nonce: "n")
            await expectError(.unexpectedResponse) {
                _ = try await session.completeSignIn(code: "c", authorization: later, issuedClientID: nil)
            }
            server.queue(tokenPath, 400, #"{"error":"invalid_grant"}"#)
            await expectError(.authorizationFailed("invalid_grant")) {
                _ = try await session.completeSignIn(code: "c", authorization: later, issuedClientID: nil)
            }
            precondition(store.value == made)
        }

        // The model list is fetched again once after a refused token.
        do {
            let store = MemoryStore(credential(clock))
            let server = MockServer()
            server.queue("/v1/models", 401, "{}")
            server.queue(tokenPath, 200, #"{"access_token":"a2","refresh_token":"r2"}"#)
            server.queue("/v1/models", 200, #"{"data":[{"slug":"gpt-test","display_name":"GPT Test","visibility":"list"}]}"#)
            let session = ChatGPTPlanSession(store: store, client: server, now: { clock.now })
            let fetched = try await session.models()
            precondition(fetched == [ChatGPTPlanModel(slug: "gpt-test", displayName: "GPT Test")])
            let listed = server.requests.filter { $0.url?.path == "/v1/models" }
            precondition(listed.map { $0.value(forHTTPHeaderField: "Authorization") } == ["Bearer a1", "Bearer a2"])

            server.queue("/v1/models", 200, #"{"data":[]}"#)
            await expectError(.noModels) { _ = try await session.models() }
            server.queue("/v1/models", 403, "{}")
            await expectError(.signInRequired) { _ = try await session.models() }
        }

        // Signing out forgets the account first and revokes only at OpenAI.
        do {
            let store = MemoryStore(credential(clock))
            let server = MockServer()
            server.queue("/.well-known/openid-configuration", 200, #"{"revocation_endpoint":"https://auth.openai.com/oauth/revoke"}"#)
            server.queue("/oauth/revoke", 200, "{}")
            let session = ChatGPTPlanSession(store: store, client: server, now: { clock.now })
            await session.signOut()
            precondition(store.value == nil && server.count("/oauth/revoke") == 1)
            precondition(form(server.requests.last!)["token"] == "r1")

            let other = MemoryStore(credential(clock))
            let elsewhere = MockServer()
            elsewhere.queue("/.well-known/openid-configuration", 200, #"{"revocation_endpoint":"https://evil.example.test/revoke"}"#)
            let quiet = ChatGPTPlanSession(store: other, client: elsewhere, now: { clock.now })
            await quiet.signOut()
            precondition(other.value == nil && elsewhere.requests.count == 1)

            // With nothing to revoke, nothing is sent.
            let empty = MockServer()
            await ChatGPTPlanSession(store: MemoryStore(), client: empty, now: { clock.now }).signOut()
            precondition(empty.requests.isEmpty)
        }

        // A sign-out while a refresh is under way wins.
        do {
            let store = MemoryStore(credential(clock, expiresIn: 10))
            let server = MockServer()
            server.queue(tokenPath, 200, #"{"access_token":"a2","refresh_token":"r2"}"#, delay: 150)
            let session = ChatGPTPlanSession(store: store, client: server, now: { clock.now })
            let pending = Task { try await session.accessToken() }
            try await Task.sleep(nanoseconds: 40_000_000)
            await session.signOut()
            do {
                _ = try await pending.value
                preconditionFailure("a refresh outlived the sign-out")
            } catch {}
            let afterSignOut = await session.current()
            precondition(store.value == nil && afterSignOut == nil)
        }
    }

    // MARK: The loopback listener, for real

    /// One plain HTTP request to 127.0.0.1; returns the response head.
    static func get(port: UInt16, _ target: String) -> String? {
        let socket = Darwin.socket(AF_INET, SOCK_STREAM, 0)
        guard socket >= 0 else { return nil }
        defer { close(socket) }
        var timeout = timeval(tv_sec: 3, tv_usec: 0)
        setsockopt(socket, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
        var address = sockaddr_in()
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = port.bigEndian
        address.sin_addr.s_addr = inet_addr("127.0.0.1")
        let connected = withUnsafePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                connect(socket, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard connected == 0 else { return nil }
        let request = "GET \(target) HTTP/1.1\r\nHost: 127.0.0.1:\(port)\r\nUser-Agent: test\r\n\r\n"
        _ = request.withCString { send(socket, $0, strlen($0), 0) }
        var received = Data()
        var buffer = [UInt8](repeating: 0, count: 4_096)
        while true {
            let count = recv(socket, &buffer, buffer.count, 0)
            if count <= 0 { break }
            received.append(contentsOf: buffer[0 ..< count])
        }
        return String(decoding: received, as: UTF8.self)
    }

    static func onThread<T: Sendable>(_ work: @escaping @Sendable () -> T) async -> T {
        await withCheckedContinuation { continuation in
            DispatchQueue.global().async { continuation.resume(returning: work()) }
        }
    }

    static func loopback() async throws {
        // Request heads.
        typealias Head = ChatGPTPlanLoopbackServer.RequestHead
        precondition(ChatGPTPlanLoopbackServer.requestHead(in: Data("GET /a HTTP/1.1\r\nHost: x\r\n".utf8)) == nil)
        precondition(ChatGPTPlanLoopbackServer.requestHead(in: Data("GET /a?b=c HTTP/1.1\r\nHost: x\r\n\r\n".utf8))
            == Head(method: "GET", target: "/a?b=c"))
        precondition(ChatGPTPlanLoopbackServer.requestHead(in: Data("nonsense\r\n\r\n".utf8)) == Head(method: "", target: ""))
        precondition(ChatGPTPlanLoopbackServer.path(of: "/auth/callback?code=1") == "/auth/callback")
        let redirect = String(decoding: ChatGPTPlanLoopbackServer.response(status: "302 Found", location: "jerreader://x"),
                              as: UTF8.self)
        precondition(redirect.hasPrefix("HTTP/1.1 302 Found\r\nLocation: jerreader://x\r\n"))
        precondition(redirect.contains("Content-Length: 0\r\n") && redirect.hasSuffix("\r\n\r\n"))

        // A real listener: other paths get 404, the callback gets the redirect and ends the wait.
        let server = ChatGPTPlanLoopbackServer()
        let port = try await server.start()
        precondition(port > 0)
        let waiting = Task { try await server.callbackTarget() }
        let missing = await onThread { get(port: port, "/favicon.ico") }
        precondition(missing?.hasPrefix("HTTP/1.1 404") == true, missing ?? "no answer")
        let finished = await onThread { get(port: port, "/auth/callback?code=c%2F1&state=s1") }
        precondition(finished?.hasPrefix("HTTP/1.1 302 Found\r\nLocation: jerreader://chatgpt-auth/done\r\n") == true,
                     finished ?? "no answer")
        precondition(finished?.contains("Cache-Control: no-store") == true)
        let delivered = try await waiting.value
        precondition(delivered == "/auth/callback?code=c%2F1&state=s1")
        // The target stays available and the listener has stopped taking connections.
        let stillThere = try await server.callbackTarget()
        precondition(stillThere == "/auth/callback?code=c%2F1&state=s1")
        try await Task.sleep(nanoseconds: 100_000_000)
        let after = await onThread { get(port: port, "/auth/callback?code=again&state=s1") }
        precondition(after == nil || after == "", "the listener still answered: \(after ?? "")")
        server.stop()

        // A sheet closed by the user ends the wait with its error.
        let cancelled = ChatGPTPlanLoopbackServer()
        _ = try await cancelled.start()
        let wait = Task { try await cancelled.callbackTarget() }
        cancelled.fail(ChatGPTPlanError.cancelled)
        do {
            _ = try await wait.value
            preconditionFailure("the wait did not end")
        } catch let error as ChatGPTPlanError {
            precondition(error == .cancelled)
        }
        cancelled.stop()

        // A cancelled task ends the wait too.
        let abandoned = ChatGPTPlanLoopbackServer()
        _ = try await abandoned.start()
        let task = Task { try await abandoned.callbackTarget() }
        try await Task.sleep(nanoseconds: 50_000_000)
        task.cancel()
        do {
            _ = try await task.value
            preconditionFailure("the cancelled wait went on")
        } catch is CancellationError {}
        abandoned.stop()

        // Two listeners get two ports.
        let a = ChatGPTPlanLoopbackServer(), b = ChatGPTPlanLoopbackServer()
        let portA = try await a.start(), portB = try await b.start()
        precondition(portA != portB)
        a.stop()
        b.stop()
    }
}

extension NSLock {
    /// `withLock` needs macOS 14; the host tests target 13.
    func locked<T>(_ body: () throws -> T) rethrows -> T {
        lock()
        defer { unlock() }
        return try body()
    }
}
