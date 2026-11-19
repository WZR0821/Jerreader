import Foundation
import Network

/// Receives the one redirect a ChatGPT sign-in ends with,
/// `http://127.0.0.1:<port>/auth/callback?…`, and answers it with a redirect
/// to `jerreader://chatgpt-auth/done` so the sign-in sheet closes by itself.
///
/// It listens on the loopback address only and on a port the system picks,
/// takes the first request to the callback path and then stops listening.
/// Any other request gets a 404 and the wait goes on. The request target is
/// handed over as it came; `ChatGPTPlanCallback.parse` checks it.
///
/// Network and Foundation only, so the host tests can run it on the Mac.
final class ChatGPTPlanLoopbackServer: @unchecked Sendable {
    /// A request head larger than this is refused.
    static let maximumRequestBytes = 16 * 1024

    // Everything below is touched on `queue` only; that is what makes the
    // class safe to share.
    private let queue = DispatchQueue(label: "Jerreader.chatgpt-plan.loopback")
    private var listener: NWListener?
    private var portWaiter: CheckedContinuation<UInt16, Error>?
    private var targetWaiter: CheckedContinuation<String, Error>?
    private var outcome: Result<String, Error>?
    private var connections: [ObjectIdentifier: NWConnection] = [:]

    init() {}

    /// Starts listening on 127.0.0.1 and returns the port the system chose.
    func start() async throws -> UInt16 {
        try await withCheckedThrowingContinuation { continuation in
            queue.async { self.begin(continuation) }
        }
    }

    /// The request target of the callback, such as `/auth/callback?code=…`.
    /// Ends early with the error given to `fail(_:)`, or when the task is cancelled.
    func callbackTarget() async throws -> String {
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                queue.async {
                    if let outcome = self.outcome {
                        continuation.resume(with: outcome)
                    } else {
                        self.targetWaiter = continuation
                    }
                }
            }
        } onCancel: {
            self.fail(CancellationError())
        }
    }

    /// Ends the wait with an error. Ignored once the callback has arrived.
    func fail(_ error: Error) {
        queue.async { self.finish(.failure(error)) }
    }

    /// Stops listening and closes any connection still open.
    func stop() {
        queue.async {
            self.finish(.failure(ChatGPTPlanError.cancelled))
            self.resumePort(.failure(ChatGPTPlanError.listenerUnavailable))
            self.closeListener()
            for connection in self.connections.values { connection.cancel() }
            self.connections = [:]
        }
    }

    // MARK: Request heads

    struct RequestHead: Equatable, Sendable {
        let method: String
        let target: String
    }

    /// The method and target of a complete request head, or nil while the
    /// head has not fully arrived. A head that cannot be read gives an empty method.
    static func requestHead(in data: Data) -> RequestHead? {
        guard let end = data.range(of: Data("\r\n\r\n".utf8)) else { return nil }
        let head = String(decoding: data[data.startIndex ..< end.lowerBound], as: UTF8.self)
        let line = head.components(separatedBy: "\r\n").first ?? ""
        let parts = line.split(separator: " ", omittingEmptySubsequences: true)
        guard parts.count == 3, parts[2].hasPrefix("HTTP/") else { return RequestHead(method: "", target: "") }
        return RequestHead(method: String(parts[0]), target: String(parts[1]))
    }

    /// The path of a request target, without its query.
    static func path(of target: String) -> String {
        String(target.split(separator: "?", maxSplits: 1, omittingEmptySubsequences: false).first ?? "")
    }

    static func response(status: String, location: String? = nil, body: String = "") -> Data {
        let bytes = Data(body.utf8)
        var lines = ["HTTP/1.1 \(status)"]
        if let location { lines.append("Location: \(location)") }
        lines += [
            "Content-Type: text/html; charset=utf-8",
            "Content-Length: \(bytes.count)",
            "Cache-Control: no-store",
            "Referrer-Policy: no-referrer",
            "Connection: close",
        ]
        return Data((lines.joined(separator: "\r\n") + "\r\n\r\n").utf8) + bytes
    }

    /// What the browser sees for a moment before the sheet closes.
    static let finishedPage = """
    <!doctype html><html lang="zh-Hans"><meta charset="utf-8">\
    <meta name="viewport" content="width=device-width,initial-scale=1"><title>Jerreader</title>\
    <body style="font:17px -apple-system,sans-serif;padding:48px 24px;text-align:center">\
    <p>已收到 ChatGPT 的登录结果，正在回到 Jerreader…</p>\
    <p><a href="\(ChatGPTPlan.finishURL)">回到 Jerreader</a></p></body></html>
    """

    // MARK: On the queue

    private func begin(_ continuation: CheckedContinuation<UInt16, Error>) {
        guard listener == nil, outcome == nil else {
            continuation.resume(throwing: ChatGPTPlanError.listenerUnavailable)
            return
        }
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: .ipv4(.loopback), port: .any)
        parameters.allowLocalEndpointReuse = true
        parameters.includePeerToPeer = false
        let made: NWListener
        do {
            made = try NWListener(using: parameters)
        } catch {
            continuation.resume(throwing: ChatGPTPlanError.listenerUnavailable)
            return
        }
        listener = made
        portWaiter = continuation
        made.stateUpdateHandler = { [weak self] state in self?.listenerChanged(state) }
        made.newConnectionHandler = { [weak self] connection in self?.accept(connection) }
        made.start(queue: queue)
    }

    private func listenerChanged(_ state: NWListener.State) {
        switch state {
        case .ready:
            if let port = listener?.port?.rawValue, port != 0 {
                resumePort(.success(port))
            } else {
                resumePort(.failure(ChatGPTPlanError.listenerUnavailable))
                closeListener()
            }
        case .waiting, .failed:
            // A loopback listener that cannot start at once will not start.
            resumePort(.failure(ChatGPTPlanError.listenerUnavailable))
            finish(.failure(ChatGPTPlanError.listenerUnavailable))
            closeListener()
        case .cancelled:
            resumePort(.failure(ChatGPTPlanError.listenerUnavailable))
        default:
            break
        }
    }

    private func accept(_ connection: NWConnection) {
        guard outcome == nil else {
            connection.cancel()
            return
        }
        let id = ObjectIdentifier(connection)
        connections[id] = connection
        connection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .failed, .cancelled: self?.connections[id] = nil
            default: break
            }
        }
        connection.start(queue: queue)
        receive(id, buffer: Data())
    }

    private func receive(_ id: ObjectIdentifier, buffer: Data) {
        guard let connection = connections[id] else { return }
        connection.receive(minimumIncompleteLength: 1, maximumLength: 4_096) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            var buffer = buffer
            if let data { buffer.append(data) }
            if let head = Self.requestHead(in: buffer) {
                self.respond(to: head, on: id)
            } else if buffer.count > Self.maximumRequestBytes {
                self.send(Self.response(status: "431 Request Header Fields Too Large"), on: id)
            } else if error != nil || isComplete {
                self.connections[id]?.cancel()
            } else {
                self.receive(id, buffer: buffer)
            }
        }
    }

    private func respond(to head: RequestHead, on id: ObjectIdentifier) {
        guard head.method == "GET" else {
            send(Self.response(status: head.method.isEmpty ? "400 Bad Request" : "405 Method Not Allowed"), on: id)
            return
        }
        guard Self.path(of: head.target) == ChatGPTPlan.callbackPath, outcome == nil else {
            send(Self.response(status: "404 Not Found"), on: id)
            return
        }
        // Hand the target over before the browser is redirected, so the
        // sign-in has it by the time the sheet closes.
        finish(.success(head.target))
        closeListener()
        send(Self.response(status: "302 Found", location: ChatGPTPlan.finishURL, body: Self.finishedPage), on: id)
    }

    private func send(_ data: Data, on id: ObjectIdentifier) {
        guard let connection = connections[id] else { return }
        connection.send(content: data, contentContext: .finalMessage, isComplete: true, completion: .contentProcessed { [weak self] _ in
            self?.connections[id]?.cancel()
        })
    }

    private func finish(_ result: Result<String, Error>) {
        guard outcome == nil else { return }
        outcome = result
        if let waiter = targetWaiter {
            targetWaiter = nil
            waiter.resume(with: result)
        }
    }

    private func resumePort(_ result: Result<UInt16, Error>) {
        guard let waiter = portWaiter else { return }
        portWaiter = nil
        waiter.resume(with: result)
    }

    private func closeListener() {
        listener?.stateUpdateHandler = nil
        listener?.newConnectionHandler = nil
        listener?.cancel()
        listener = nil
    }
}
