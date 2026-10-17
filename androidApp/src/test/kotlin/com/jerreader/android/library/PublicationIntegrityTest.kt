package com.jerreader.android.library

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class PublicationIntegrityTest {
    @Test fun readOnlySessionKeepsBytesAndModificationTimeUnchanged() {
        val directory = Files.createTempDirectory("jerreader-integrity-").toFile()
        val publication = File(directory, "fixture.epub")
        try {
            publication.writeBytes("immutable-publication".encodeToByteArray())
            publication.setLastModified(1_700_000_000_000)
            val snapshot = PublicationIntegrity.capture(publication)
            publication.inputStream().use { it.readBytes() }
            assertTrue(PublicationIntegrity.isUnchanged(snapshot))
        } finally { directory.deleteRecursively() }
    }

    @Test fun detectsModificationTimeChangeWithIdenticalContent() {
        val directory = Files.createTempDirectory("jerreader-integrity-").toFile()
        val publication = File(directory, "fixture.epub")
        try {
            publication.writeText("unchanged bytes")
            publication.setLastModified(1_700_000_000_000)
            val snapshot = PublicationIntegrity.capture(publication)
            assertTrue(publication.setLastModified(1_700_000_010_000))
            assertFalse(PublicationIntegrity.isUnchanged(snapshot))
        } finally { directory.deleteRecursively() }
    }

    @Test fun streamedCopyMatchesIndependentHashAndDoesNotChangeMtimeWhenVerified() {
        val directory = Files.createTempDirectory("publication-test").toFile()
        try {
            val bytes = ByteArray(256 * 1024 + 7) { (it % 251).toByte() }
            val destination = File(directory, "book.epub")
            val snapshot = bytes.inputStream().use { PublicationIntegrity.copyAndCapture(it, destination) }
            val independentHash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(independentHash, snapshot.sha256)
            assertEquals(bytes.size.toLong(), snapshot.byteCount)
            repeat(3) { assertTrue(PublicationIntegrity.isUnchanged(snapshot)) }
            assertEquals(snapshot.lastModified, destination.lastModified())
            assertArrayEquals(bytes, destination.readBytes())
        } finally { directory.deleteRecursively() }
    }
    @Test fun detectsSameLengthTamperEvenIfModificationTimeRestored() {
        val directory = Files.createTempDirectory("publication-test").toFile()
        try {
            val file = File(directory, "book.epub").apply { writeText("original") }
            val snapshot = PublicationIntegrity.capture(file)
            file.writeText("tampered"); file.setLastModified(snapshot.lastModified)
            assertFalse(PublicationIntegrity.isUnchanged(snapshot))
        } finally { directory.deleteRecursively() }
    }
}
