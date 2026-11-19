package com.jerreader.android.translation.chatgptplan

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Opened by `jerreader://chatgpt-auth/done`, where the loopback listener
 * sends the browser once it has the sign-in result. It shows nothing: it
 * brings back the screen the sign-in started from, which closes a Custom Tab
 * above it, and finishes. The result itself came through the listener.
 */
class ChatGPTAuthRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val component = ChatGPTPlanSignIn.returnComponent
        val target = if (component != null) {
            Intent().setComponent(component)
        } else {
            packageManager.getLaunchIntentForPackage(packageName)
        }
        if (target != null) {
            target.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            try {
                startActivity(target)
            } catch (_: RuntimeException) {
                // The app is reopened from the launcher instead.
            }
        }
        finish()
    }
}
