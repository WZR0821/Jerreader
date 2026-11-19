package com.jerreader.android.translation.chatgptplan

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.jerreader.android.translation.AndroidTranslationSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

class ChatGPTPlanSignInOutcome(
    val account: ChatGPTPlanAccount,
    val models: List<ChatGPTPlanModel>,
    /** Set when the account signed in but its models could not be read. */
    val modelsError: Throwable?
)

/**
 * Runs one Sign in with ChatGPT: a listener on 127.0.0.1, OpenAI's sign-in
 * page in the default browser (a Custom Tab where the browser has them), the
 * code exchange, then the account's models. The password is typed on
 * OpenAI's page only; the app sees neither it nor the browser's cookies.
 *
 * The wait ends with the callback, when the user comes back to the app
 * without one (after a short grace, since the callback arrives just before
 * the app is brought back), when the caller is cancelled, or after
 * [SIGN_IN_TIMEOUT_MILLIS]. Every way out stops the listener and the
 * foreground service.
 */
object ChatGPTPlanSignIn {
    /** The screen the sign-in started from; [ChatGPTAuthRedirectActivity] brings it back. */
    @Volatile
    var returnComponent: ComponentName? = null
        private set

    private const val SIGN_IN_TIMEOUT_MILLIS = 10 * 60 * 1_000L
    private const val RETURN_GRACE_MILLIS = 1_500L
    private const val CUSTOM_TABS_SESSION = "android.support.customtabs.extra.SESSION"
    private val running = AtomicBoolean(false)

    suspend fun run(
        activity: Activity,
        store: AndroidTranslationSettingsStore,
        loginHint: String? = null
    ): ChatGPTPlanSignInOutcome {
        // A second tap while a sign-in is open is ignored like a cancelled one.
        if (!running.compareAndSet(false, true)) throw ChatGPTPlanError.Cancelled()
        try {
            return withContext(Dispatchers.Main.immediate) { attempt(activity, store, loginHint) }
        } finally {
            running.set(false)
        }
    }

    private suspend fun attempt(
        activity: Activity,
        store: AndroidTranslationSettingsStore,
        loginHint: String?
    ): ChatGPTPlanSignInOutcome = coroutineScope {
        val link = ChatGPTPlanLoopbackServer.intentURL(activity.packageName)
        val server = ChatGPTPlanLoopbackServer(link, ChatGPTPlanLoopbackServer.finishedPage(link))
        val lifecycle = (activity as? LifecycleOwner)?.lifecycle
        val returns = Channel<Unit>(Channel.CONFLATED)
        var left = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                left = true
            } else if (event == Lifecycle.Event.ON_RESUME && left) {
                returns.trySend(Unit)
            }
        }
        var watcher: Job? = null
        try {
            val port = server.start()
            val authorization = ChatGPTPlanAuthorization(
                clientID = store.chatGPTPlanClientID(),
                hostID = store.chatGPTPlanHostID(),
                port = port,
                loginHint = loginHint
            )
            returnComponent = activity.componentName
            ChatGPTPlanSignInService.start(activity)
            lifecycle?.addObserver(observer)
            watcher = launch {
                returns.receive()
                delay(RETURN_GRACE_MILLIS)
                server.fail(ChatGPTPlanError.Cancelled())
            }
            openBrowser(activity, authorization.url)
            val target = withTimeoutOrNull(SIGN_IN_TIMEOUT_MILLIS) { server.callbackTarget() }
                ?: throw ChatGPTPlanError.Cancelled()
            val callback = ChatGPTPlanCallback.parse(target, authorization)
            watcher.cancel()
            val issued = callback.issuedClientID
            if (issued != null) store.saveChatGPTPlanClientID(issued)
            val credential = store.chatGPTPlanSession.completeSignIn(callback.code, authorization, issued)
            val models = try {
                store.chatGPTPlanSession.models()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                return@coroutineScope ChatGPTPlanSignInOutcome(credential.account, emptyList(), error)
            }
            ChatGPTPlanSignInOutcome(credential.account, models, null)
        } finally {
            watcher?.cancel()
            lifecycle?.removeObserver(observer)
            server.stop()
            ChatGPTPlanSignInService.stop()
        }
    }

    private fun openBrowser(activity: Activity, url: String) {
        val uri = Uri.parse(url)
        // The Custom Tabs session extra without the androidx.browser library;
        // pinning the default browser keeps an app that claims OpenAI's links
        // from taking the page, which must load in a browser to reach 127.0.0.1.
        val tab = Intent(Intent.ACTION_VIEW, uri)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .putExtras(Bundle().apply { putBinder(CUSTOM_TABS_SESSION, null) })
        val browser = defaultBrowserPackage(activity)
        if (browser != null) tab.setPackage(browser)
        try {
            activity.startActivity(tab)
            return
        } catch (_: RuntimeException) {
            // Tried again below without the pinned browser.
        }
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: RuntimeException) {
            throw ChatGPTPlanError.BrowserUnavailable()
        }
    }

    private fun defaultBrowserPackage(activity: Activity): String? = try {
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        @Suppress("DEPRECATION")
        val info = activity.packageManager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)
        // "android" is the chooser, shown when no default browser is set.
        info?.activityInfo?.packageName?.takeIf { it.isNotEmpty() && it != "android" }
    } catch (_: RuntimeException) {
        null
    }
}
