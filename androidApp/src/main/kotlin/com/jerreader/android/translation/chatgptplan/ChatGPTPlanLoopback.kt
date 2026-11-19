package com.jerreader.android.translation.chatgptplan

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Receives the one redirect a ChatGPT sign-in ends with,
 * `http://127.0.0.1:<port>/auth/callback?…`, and answers it with a redirect
 * to [finishLocation], which brings the app back to the front, with
 * [page] for a browser that does not follow it at once.
 *
 * It listens on the loopback address only and on a port the system picks,
 * takes the first request to the callback path and then stops listening.
 * Any other request gets a 404 and the wait goes on. The request target is
 * handed over as it came; [ChatGPTPlanCallback.parse] checks it.
 *
 * Plain java.net, so the unit tests can run it on the JVM.
 */
class ChatGPTPlanLoopbackServer(
    private val finishLocation: String,
    private val page: String
) {
    /** The method and target of a request head. A head that cannot be read has an empty method. */
    data class RequestHead(val method: String, val target: String)

    private val lock = Any()
    private var started = false
    private var listener: ServerSocket? = null
    private val connections = HashSet<Socket>()
    private val outcome = CompletableDeferred<String>()

    /** Starts listening on 127.0.0.1 and returns the port the system chose. */
    suspend fun start(): Int = withContext(Dispatchers.IO) {
        val server = synchronized(lock) {
            if (started || outcome.isCompleted) throw ChatGPTPlanError.ListenerUnavailable()
            started = true
            val made = try {
                ServerSocket(0, BACKLOG, InetAddress.getByName(LOOPBACK_ADDRESS))
            } catch (_: IOException) {
                throw ChatGPTPlanError.ListenerUnavailable()
            } catch (_: SecurityException) {
                throw ChatGPTPlanError.ListenerUnavailable()
            }
            listener = made
            made
        }
        val port = server.localPort
        if (port <= 0) {
            closeListener()
            throw ChatGPTPlanError.ListenerUnavailable()
        }
        thread(isDaemon = true, name = "chatgpt-plan-loopback") { acceptLoop(server) }
        port
    }

    /**
     * The request target of the callback, such as `/auth/callback?code=…`.
     * Ends early with the error given to [fail], or when the caller is cancelled.
     */
    suspend fun callbackTarget(): String = try {
        outcome.await()
    } catch (error: CancellationException) {
        fail(ChatGPTPlanError.Cancelled())
        throw error
    }

    /** Ends the wait with an error. Ignored once the callback has arrived. */
    fun fail(error: Throwable) {
        outcome.completeExceptionally(error)
    }

    /** Stops listening and closes any connection still open. */
    fun stop() {
        outcome.completeExceptionally(ChatGPTPlanError.Cancelled())
        val open = synchronized(lock) {
            val list = connections.toList()
            connections.clear()
            list
        }
        closeListener()
        for (socket in open) closeQuietly(socket)
    }

    private fun acceptLoop(server: ServerSocket) {
        while (true) {
            val socket = try {
                server.accept()
            } catch (_: Exception) {
                return
            }
            val accepted = synchronized(lock) {
                if (outcome.isCompleted) {
                    false
                } else {
                    connections.add(socket)
                    true
                }
            }
            if (!accepted) {
                closeQuietly(socket)
                continue
            }
            thread(isDaemon = true, name = "chatgpt-plan-loopback-request") { serve(socket) }
        }
    }

    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = READ_TIMEOUT_MILLIS
            val input = socket.getInputStream()
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(CHUNK_BYTES)
            while (true) {
                val count = input.read(chunk)
                if (count < 0) return
                buffer.write(chunk, 0, count)
                val head = requestHead(buffer.toByteArray())
                if (head != null) {
                    respond(head, socket)
                    return
                }
                if (buffer.size() > MAXIMUM_REQUEST_BYTES) {
                    write(socket, response("431 Request Header Fields Too Large"))
                    return
                }
            }
        } catch (_: Exception) {
            // A connection that breaks or stalls is simply dropped.
        } finally {
            synchronized(lock) { connections.remove(socket) }
            closeQuietly(socket)
        }
    }

    private fun respond(head: RequestHead, socket: Socket) {
        if (head.method != "GET") {
            write(socket, response(if (head.method.isEmpty()) "400 Bad Request" else "405 Method Not Allowed"))
            return
        }
        // Hand the target over before the browser is redirected, so the
        // sign-in has it by the time the app is back in front.
        val delivered = path(head.target) == ChatGPTPlan.CALLBACK_PATH && outcome.complete(head.target)
        if (!delivered) {
            write(socket, response("404 Not Found"))
            return
        }
        closeListener()
        write(socket, response("302 Found", finishLocation, page))
    }

    private fun write(socket: Socket, bytes: ByteArray) {
        val output = socket.getOutputStream()
        output.write(bytes)
        output.flush()
        try {
            socket.shutdownOutput()
        } catch (_: Exception) {
            // Closed right after anyway.
        }
    }

    private fun closeListener() {
        val server = synchronized(lock) {
            val current = listener
            listener = null
            current
        }
        if (server != null) closeQuietly(server)
    }

    companion object {
        /** A request head larger than this is refused. */
        const val MAXIMUM_REQUEST_BYTES = 16 * 1024
        private const val LOOPBACK_ADDRESS = "127.0.0.1"
        private const val BACKLOG = 8
        private const val CHUNK_BYTES = 4_096
        private const val READ_TIMEOUT_MILLIS = 10_000
        private const val CR: Byte = 13
        private const val LF: Byte = 10

        /** The method and target of a complete request head, or null while the head has not fully arrived. */
        fun requestHead(data: ByteArray): RequestHead? {
            val end = headEnd(data)
            if (end < 0) return null
            val head = data.decodeToString(0, end)
            val line = head.substringBefore("\r\n")
            val parts = line.split(' ').filter { it.isNotEmpty() }
            if (parts.size != 3 || !parts[2].startsWith("HTTP/")) return RequestHead("", "")
            return RequestHead(parts[0], parts[1])
        }

        /** The path of a request target, without its query. */
        fun path(target: String): String = target.substringBefore('?')

        fun response(status: String, location: String? = null, body: String = ""): ByteArray {
            val bytes = body.toByteArray(Charsets.UTF_8)
            val lines = mutableListOf("HTTP/1.1 $status")
            if (location != null) lines.add("Location: $location")
            lines.add("Content-Type: text/html; charset=utf-8")
            lines.add("Content-Length: ${bytes.size}")
            lines.add("Cache-Control: no-store")
            lines.add("Referrer-Policy: no-referrer")
            lines.add("Connection: close")
            return (lines.joinToString("\r\n") + "\r\n\r\n").toByteArray(Charsets.UTF_8) + bytes
        }

        /**
         * An `intent:` address of the finish link for [packageName]: browsers on
         * Android open it in this app only.
         */
        fun intentURL(packageName: String): String =
            "intent://${ChatGPTPlan.FINISH_HOST}${ChatGPTPlan.FINISH_PATH}" +
                "#Intent;scheme=${ChatGPTPlan.FINISH_SCHEME};package=$packageName;end"

        /** What the browser shows for a moment before the app is back in front. */
        fun finishedPage(link: String): String =
            "<!doctype html><html lang=\"zh-Hans\"><meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Jerreader</title>" +
                "<body style=\"font:17px sans-serif;padding:48px 24px;text-align:center\">" +
                "<p>已收到 ChatGPT 的登录结果，正在回到 Jerreader…</p>" +
                "<p><a href=\"${htmlEscaped(link)}\">回到 Jerreader</a></p></body></html>"

        private fun htmlEscaped(text: String): String = text
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

        private fun headEnd(data: ByteArray): Int {
            var index = 0
            while (index + 3 < data.size) {
                if (data[index] == CR && data[index + 1] == LF && data[index + 2] == CR && data[index + 3] == LF) {
                    return index
                }
                index++
            }
            return -1
        }

        private fun closeQuietly(socket: Socket) {
            try {
                socket.close()
            } catch (_: Exception) {
                // Nothing left to do.
            }
        }

        private fun closeQuietly(server: ServerSocket) {
            try {
                server.close()
            } catch (_: Exception) {
                // Nothing left to do.
            }
        }
    }
}
