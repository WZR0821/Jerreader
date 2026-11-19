import AuthenticationServices
import SwiftUI
import UIKit

/// Runs one Sign in with ChatGPT: a listener on 127.0.0.1, the system
/// sign-in sheet on auth.openai.com, the code exchange, then the account's
/// models. The password is typed on OpenAI's page only; Jerreader sees
/// neither it nor the browser's cookies.
@MainActor
final class ChatGPTPlanSignIn: NSObject, ASWebAuthenticationPresentationContextProviding {
    struct Outcome {
        let account: ChatGPTPlanAccount
        let models: [ChatGPTPlanModel]
        /// Set when the account signed in but its models could not be read.
        let modelsError: Error?
    }

    private let session: ChatGPTPlanSession
    private let defaults: UserDefaults
    private var browser: ASWebAuthenticationSession?

    init(session: ChatGPTPlanSession = .shared, defaults: UserDefaults = .standard) {
        self.session = session
        self.defaults = defaults
    }

    func run(loginHint: String? = nil) async throws -> Outcome {
        let server = ChatGPTPlanLoopbackServer()
        defer { server.stop() }
        let port = try await server.start()
        let authorization = ChatGPTPlanAuthorization(
            clientID: ChatGPTPlanRegistration.clientID(defaults),
            hostID: ChatGPTPlanRegistration.hostID(defaults),
            port: port, loginHint: loginHint,
            forceConsent: ChatGPTPlanRegistration.consentNeeded(defaults)
        )

        let browser = ASWebAuthenticationSession(
            url: authorization.url, callback: .customScheme(ChatGPTPlan.finishScheme)
        ) { @Sendable _, error in
            // When the sheet closes by itself the listener already has the
            // callback, and these are ignored. Sendable: the system may call
            // this off the main thread.
            if let error = error as? ASWebAuthenticationSessionError, error.code == .canceledLogin {
                server.fail(ChatGPTPlanError.cancelled)
            } else if error != nil {
                server.fail(ChatGPTPlanError.browserUnavailable)
            } else {
                server.fail(ChatGPTPlanError.invalidCallback)
            }
        }
        browser.presentationContextProvider = self
        // Shares Safari's sign-in with chatgpt.com, so a signed-in user only confirms.
        browser.prefersEphemeralWebBrowserSession = false
        self.browser = browser
        guard browser.start() else {
            self.browser = nil
            throw ChatGPTPlanError.browserUnavailable
        }

        let callback: ChatGPTPlanCallback
        do {
            let target = try await server.callbackTarget()
            callback = try ChatGPTPlanCallback.parse(target: target, for: authorization)
        } catch {
            browser.cancel()
            self.browser = nil
            throw error
        }
        // The listener redirected the sheet to jerreader://, which closes it;
        // close it here too in case that redirect was not followed. The task
        // holds this sheet itself: the sign-in object is usually gone by then.
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(1.5))
            browser.cancel()
        }
        self.browser = nil

        if let issued = callback.issuedClientID { ChatGPTPlanRegistration.saveClientID(issued, defaults) }
        let credential: ChatGPTPlanCredential
        do {
            credential = try await session.completeSignIn(
                code: callback.code, authorization: authorization, issuedClientID: callback.issuedClientID
            )
        } catch ChatGPTPlanError.planUseNotGranted {
            // The next sign-in asks for the plan's use again.
            ChatGPTPlanRegistration.setConsentNeeded(true, defaults)
            throw ChatGPTPlanError.planUseNotGranted
        }
        ChatGPTPlanRegistration.setConsentNeeded(false, defaults)
        do {
            return Outcome(account: credential.account, models: try await session.models(), modelsError: nil)
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            return Outcome(account: credential.account, models: [], modelsError: error)
        }
    }

    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let windows = scenes.sorted { $0.activationState == .foregroundActive && $1.activationState != .foregroundActive }
            .flatMap(\.windows)
        if let window = windows.first(where: \.isKeyWindow) ?? windows.first { return window }
        return scenes.first.map { UIWindow(windowScene: $0) } ?? ASPresentationAnchor()
    }
}

/// Signs in, out and refreshes the plan's models for the settings pages,
/// and reports the outcome in the page's own words.
@MainActor
enum ChatGPTPlanActions {
    /// Signs in and puts the plan in use. Returns the notice to show.
    static func signIn(store: TranslationSettingsStore, loginHint: String? = nil) async throws -> String {
        // The sheet holds its presentation provider weakly, so the sign-in
        // object is kept until the whole sign-in has finished.
        let signIn = ChatGPTPlanSignIn()
        defer { withExtendedLifetime(signIn) {} }
        let outcome = try await signIn.run(loginHint: loginHint)
        store.chatGPTPlanSignedIn(outcome.account, models: outcome.models)
        let who = outcome.account.email.map { "（\($0)）" } ?? ""
        if let modelsError = outcome.modelsError {
            return "已登录 ChatGPT\(who)，但没能取到模型列表：\(AIProviderRequests.message(for: modelsError)) 到“ChatGPT 套餐”页面点“刷新模型”重试。"
        }
        // The plan becomes the AI model in use, but reading only calls it when
        // the translation service is the AI one; say which case this is.
        guard store.provider == .directAPI else {
            return "已登录 ChatGPT\(who)。翻译服务还不是“AI 大模型”，到“AI 大模型”页点“用它翻译”后，才会用套餐额度翻译。"
        }
        return "已登录 ChatGPT\(who)，现在用套餐额度翻译。"
    }

    static func refreshModels(store: TranslationSettingsStore) async throws -> String {
        let models = try await ChatGPTPlanSession.shared.models()
        store.chatGPTPlanModelsRefreshed(models)
        return "已刷新，这个账号可以用 \(models.count) 个模型。"
    }

    static func signOut(store: TranslationSettingsStore) async {
        store.chatGPTPlanSignedOut()
        await ChatGPTPlanSession.shared.signOut()
    }

    /// Brings the settings in line with the session, which may have ended
    /// on its own (a refused refresh) or renamed the account after a refresh.
    static func sync(store: TranslationSettingsStore) async {
        store.chatGPTPlanSessionChanged(await ChatGPTPlanSession.shared.current()?.account)
    }
}
