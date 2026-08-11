package com.jerreader.unified.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An inherited regimen comes from another build — possibly a newer one, possibly
 * the iOS app. Normalizing is what keeps a value this build cannot offer from
 * landing in the pickers as a row the user can see but never change.
 */
class LibraryBackupProfileTest {

    @Test
    fun `a regimen this build offers is left alone`() {
        val profile = LibraryBackupProfile(
            policy = LibraryBackupPolicy(
                automaticEnabled = true,
                intervalDays = 14,
                retentionDays = 365,
                maximumBackupCount = 20,
                maximumTotalBytes = 0,
                automaticScopes = setOf(LibraryBackupScope.LIBRARY)
            ),
            folderDisplayName = "书房备份",
            folderUri = "content://tree/primary%3ABackups"
        )

        assertEquals(profile, profile.normalized())
    }

    @Test
    fun `values outside the choices fall back to the defaults`() {
        val normalized = LibraryBackupProfile(
            policy = LibraryBackupPolicy(
                intervalDays = 2,
                retentionDays = 12,
                maximumBackupCount = 4,
                maximumTotalBytes = 42
            )
        ).normalized()

        assertEquals(7, normalized.policy.intervalDays)
        assertEquals(30, normalized.policy.retentionDays)
        assertEquals(5, normalized.policy.maximumBackupCount)
        assertEquals(2L * 1024 * 1024 * 1024, normalized.policy.maximumTotalBytes)
    }

    @Test
    fun `an empty scope set becomes the default selection`() {
        val normalized = LibraryBackupProfile(
            policy = LibraryBackupPolicy(automaticScopes = emptySet())
        ).normalized()

        assertEquals(LibraryBackupPolicy().automaticScopes, normalized.policy.automaticScopes)
        // An automatic backup of nothing would otherwise silently never run.
        assertTrue(normalized.policy.automaticScopes.isNotEmpty())
    }

    @Test
    fun `normalizing keeps the folder hint and the schedule untouched`() {
        val normalized = LibraryBackupProfile(
            policy = LibraryBackupPolicy(
                intervalDays = 2,
                lastAutomaticBackupAtEpochMillis = 1_700_000_000_000
            ),
            folderDisplayName = "书房备份",
            folderUri = "content://tree/primary%3ABackups"
        ).normalized()

        assertEquals("书房备份", normalized.folderDisplayName)
        assertEquals("content://tree/primary%3ABackups", normalized.folderUri)
        assertEquals(1_700_000_000_000, normalized.policy.lastAutomaticBackupAtEpochMillis)
    }

    @Test
    fun `a folderless regimen stays folderless`() {
        val normalized = LibraryBackupProfile(policy = LibraryBackupPolicy()).normalized()

        assertNull(normalized.folderDisplayName)
        assertNull(normalized.folderUri)
    }
}
