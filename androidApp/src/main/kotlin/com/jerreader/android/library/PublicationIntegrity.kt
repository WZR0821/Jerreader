package com.jerreader.android.library

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

data class PublicationSnapshot(
    val path: String,
    val sha256: String,
    val lastModified: Long,
    val byteCount: Long? = null
)

object PublicationIntegrity {
    fun capture(file: File): PublicationSnapshot {
        require(file.isFile) { "出版物副本不存在。" }
        return PublicationSnapshot(
            path = file.absolutePath,
            sha256 = sha256(file),
            lastModified = file.lastModified(),
            byteCount = file.length()
        )
    }

    fun isUnchanged(expected: PublicationSnapshot): Boolean {
        val file = File(expected.path)
        if (!file.isFile || file.lastModified() != expected.lastModified ||
            (expected.byteCount != null && file.length() != expected.byteCount)) {
            return false
        }
        return sha256(file) == expected.sha256
    }

    /** One source read computes the fingerprint while making the immutable staging copy. */
    fun copyAndCapture(input: InputStream, destination: File, checkCancelled: () -> Unit = {}): PublicationSnapshot {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var bytes = 0L
        FileOutputStream(destination).use { output ->
            while (true) {
                checkCancelled()
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                output.write(buffer, 0, count)
                digest.update(buffer, 0, count)
                bytes += count
            }
            output.fd.sync()
        }
        return PublicationSnapshot(destination.absolutePath,
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) },
            destination.lastModified(), bytes)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        FileInputStream(file).use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
