package com.jerreader.android.library

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

data class PublicationSnapshot(
    val path: String,
    val sha256: String,
    val lastModified: Long
)

object PublicationIntegrity {
    fun capture(file: File): PublicationSnapshot {
        require(file.isFile) { "出版物副本不存在。" }
        return PublicationSnapshot(
            path = file.absolutePath,
            sha256 = sha256(file),
            lastModified = file.lastModified()
        )
    }

    fun isUnchanged(expected: PublicationSnapshot): Boolean {
        val file = File(expected.path)
        if (!file.isFile || file.lastModified() != expected.lastModified) {
            return false
        }
        return sha256(file) == expected.sha256
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
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
