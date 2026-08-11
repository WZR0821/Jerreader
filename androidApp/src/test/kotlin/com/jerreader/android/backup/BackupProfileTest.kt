package com.jerreader.android.backup

import com.jerreader.unified.library.LibraryBackupPolicy
import com.jerreader.unified.library.LibraryBackupProfile
import com.jerreader.unified.library.LibraryBackupScope
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The regimen is what makes a backup inheritable: the next installation reads
 * it back out of the manifest and continues the same schedule, retention and
 * folder instead of starting from the defaults. That only works if it survives
 * the JSON round trip exactly, and if an archive from another build cannot
 * leave the pickers on a value nothing can select.
 */
class BackupProfileTest {

    private fun manifest(profile: LibraryBackupProfile): JSONObject =
        JSONObject().put("profile", backupProfileJson(profile))

    @Test
    fun `a full regimen survives the round trip`() {
        val original = LibraryBackupProfile(
            policy = LibraryBackupPolicy(
                automaticEnabled = true,
                intervalDays = 3,
                retentionDays = 90,
                maximumBackupCount = 10,
                maximumTotalBytes = 500L * 1024 * 1024,
                automaticScopes = setOf(
                    LibraryBackupScope.READING,
                    LibraryBackupScope.LEARNING
                ),
                // Never written: it is this device's own bookkeeping.
                lastAutomaticBackupAtEpochMillis = 1_700_000_000_000
            ),
            folderDisplayName = "书房备份",
            folderUri = "content://com.android.externalstorage.documents/tree/primary%3ABackups"
        )

        val restored = parseBackupProfile(manifest(original))

        requireNotNull(restored)
        assertEquals(true, restored.policy.automaticEnabled)
        assertEquals(3, restored.policy.intervalDays)
        assertEquals(90, restored.policy.retentionDays)
        assertEquals(10, restored.policy.maximumBackupCount)
        assertEquals(500L * 1024 * 1024, restored.policy.maximumTotalBytes)
        assertEquals(
            setOf(LibraryBackupScope.READING, LibraryBackupScope.LEARNING),
            restored.policy.automaticScopes
        )
        assertEquals("书房备份", restored.folderDisplayName)
        assertEquals(original.folderUri, restored.folderUri)
        // The last run belongs to the device, not to the archive.
        assertNull(restored.policy.lastAutomaticBackupAtEpochMillis)
    }

    @Test
    fun `an archive written before regimens carried gives nothing back`() {
        assertNull(parseBackupProfile(JSONObject().put("format", "jerreader.backup")))
    }

    @Test
    fun `the app's own folder produces no folder hint`() {
        val restored = parseBackupProfile(
            manifest(LibraryBackupProfile(policy = LibraryBackupPolicy()))
        )
        requireNotNull(restored)
        assertNull(restored.folderDisplayName)
        assertNull(restored.folderUri)
    }

    @Test
    fun `values this build no longer offers fall back instead of sticking`() {
        val restored = parseBackupProfile(
            manifest(
                LibraryBackupProfile(
                    policy = LibraryBackupPolicy(
                        intervalDays = 2,
                        retentionDays = 12,
                        maximumBackupCount = 4,
                        maximumTotalBytes = 42,
                        automaticScopes = emptySet()
                    )
                )
            )
        )

        requireNotNull(restored)
        assertEquals(7, restored.policy.intervalDays)
        assertEquals(30, restored.policy.retentionDays)
        assertEquals(5, restored.policy.maximumBackupCount)
        assertEquals(2L * 1024 * 1024 * 1024, restored.policy.maximumTotalBytes)
        assertEquals(
            LibraryBackupPolicy().automaticScopes,
            restored.policy.automaticScopes
        )
    }

    @Test
    fun `a scope name this build does not know is dropped without losing the rest`() {
        val json = backupProfileJson(
            LibraryBackupProfile(
                policy = LibraryBackupPolicy(
                    automaticScopes = setOf(LibraryBackupScope.LIBRARY)
                )
            )
        )
        json.getJSONArray("automaticScopes").put("DREAMS")

        val restored = parseBackupProfile(JSONObject().put("profile", json))

        requireNotNull(restored)
        assertEquals(setOf(LibraryBackupScope.LIBRARY), restored.policy.automaticScopes)
    }

    @Test
    fun `an unreadable scope list leaves the defaults rather than nothing`() {
        val json = backupProfileJson(
            LibraryBackupProfile(policy = LibraryBackupPolicy())
        )
        json.put("automaticScopes", org.json.JSONArray().put("DREAMS"))

        val restored = parseBackupProfile(JSONObject().put("profile", json))

        requireNotNull(restored)
        assertTrue(restored.policy.automaticScopes.isNotEmpty())
        assertEquals(
            LibraryBackupPolicy().automaticScopes,
            restored.policy.automaticScopes
        )
    }
}
