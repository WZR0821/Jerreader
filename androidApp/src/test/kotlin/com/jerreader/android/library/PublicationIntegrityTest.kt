package com.jerreader.android.library

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicationIntegrityTest {
    @Test
    fun readOnlySessionKeepsBytesAndModificationTimeUnchanged() {
        val directory = Files.createTempDirectory("jerreader-integrity-").toFile()
        val publication = File(directory, "fixture.epub")
        try {
            publication.writeBytes("immutable-publication".encodeToByteArray())
            publication.setLastModified(1_700_000_000_000)
            val snapshot = PublicationIntegrity.capture(publication)

            publication.inputStream().use { input -> input.readBytes() }

            assertTrue(PublicationIntegrity.isUnchanged(snapshot))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun detectsContentAndModificationTimeChanges() {
        val directory = Files.createTempDirectory("jerreader-integrity-").toFile()
        val publication = File(directory, "fixture.epub")
        try {
            publication.writeBytes("before".encodeToByteArray())
            publication.setLastModified(1_700_000_000_000)
            val snapshot = PublicationIntegrity.capture(publication)

            publication.writeBytes("after".encodeToByteArray())

            assertFalse(PublicationIntegrity.isUnchanged(snapshot))
        } finally {
            directory.deleteRecursively()
        }
    }
}
