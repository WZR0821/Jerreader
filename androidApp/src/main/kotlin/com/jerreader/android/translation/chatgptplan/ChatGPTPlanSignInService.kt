package com.jerreader.android.translation.chatgptplan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.jerreader.android.R

/**
 * Keeps the app's process alive while the browser is in front, so the
 * loopback listener is still there when OpenAI redirects to it. A short
 * service on Android 14 and later; the system ends it after a few minutes
 * at the latest, and so does every way the sign-in ends.
 *
 * It asks for no notification permission: where notifications are off, the
 * service runs without showing one.
 */
class ChatGPTPlanSignInService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A service started with startForegroundService must enter the
        // foreground even when the sign-in is already over.
        val entered = enterForeground()
        if (!entered || !wanted) end()
        return START_NOT_STICKY
    }

    @Deprecated("Replaced by onTimeout(Int, Int) on Android 15")
    override fun onTimeout(startId: Int) {
        end()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        end()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun enterForeground(): Boolean = try {
        val notification = notification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        true
    } catch (_: RuntimeException) {
        false
    }

    private fun end() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: RuntimeException) {
            // Not in the foreground.
        }
        stopSelf()
    }

    private fun notification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "ChatGPT 登录", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("正在等待 ChatGPT 登录")
            .setContentText("在浏览器里完成登录后会回到 Jerreader。")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "chatgpt-sign-in"
        private const val NOTIFICATION_ID = 7_301

        @Volatile
        private var wanted = false

        @Volatile
        private var instance: ChatGPTPlanSignInService? = null

        /** Called while the app is in front, just before the browser opens. */
        fun start(context: Context) {
            wanted = true
            val intent = Intent(context, ChatGPTPlanSignInService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: RuntimeException) {
                // The sign-in goes on without it; the process may only be
                // stopped sooner while the browser is in front.
                wanted = false
            }
        }

        fun stop() {
            wanted = false
            instance?.end()
        }
    }
}
