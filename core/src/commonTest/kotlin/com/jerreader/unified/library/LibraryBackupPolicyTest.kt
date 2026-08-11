package com.jerreader.unified.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryBackupPolicyTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_000L * day

    private fun archive(name: String, ageDays: Long, megabytes: Long) = LibraryBackupArchiveInfo(
        name = name,
        createdAtEpochMillis = now - ageDays * day,
        sizeBytes = megabytes * 1024 * 1024
    )

    @Test
    fun `automatic backup waits for the interval`() {
        val policy = LibraryBackupPolicy(
            automaticEnabled = true,
            intervalDays = 7,
            lastAutomaticBackupAtEpochMillis = now - 6 * day
        )
        assertFalse(policy.isDue(now))
        assertTrue(policy.isDue(now + 2 * day))
    }

    @Test
    fun `a never-backed-up library is due immediately`() {
        val policy = LibraryBackupPolicy(automaticEnabled = true)
        assertTrue(policy.isDue(now))
    }

    @Test
    fun `automatic backup with no scope selected never runs`() {
        val policy = LibraryBackupPolicy(automaticEnabled = true, automaticScopes = emptySet())
        assertFalse(policy.isDue(now))
    }

    @Test
    fun `disabled automatic backup never runs`() {
        val policy = LibraryBackupPolicy(automaticEnabled = false)
        assertFalse(policy.isDue(now))
    }

    @Test
    fun `expired archives are removed oldest first`() {
        val plan = LibraryBackupPruner.plan(
            backups = listOf(
                archive("new", ageDays = 1, megabytes = 10),
                archive("old", ageDays = 40, megabytes = 10),
                archive("older", ageDays = 90, megabytes = 10)
            ),
            policy = LibraryBackupPolicy(retentionDays = 30, maximumBackupCount = 0, maximumTotalBytes = 0),
            nowEpochMillis = now
        )
        assertEquals(listOf("older", "old"), plan.removed.map { it.name })
        assertEquals(listOf("new"), plan.remaining.map { it.name })
    }

    @Test
    fun `count limit keeps the newest archives`() {
        val plan = LibraryBackupPruner.plan(
            backups = (1..5).map { archive("backup-$it", ageDays = it.toLong(), megabytes = 1) },
            policy = LibraryBackupPolicy(retentionDays = 0, maximumBackupCount = 2, maximumTotalBytes = 0),
            nowEpochMillis = now
        )
        assertEquals(listOf("backup-1", "backup-2"), plan.remaining.map { it.name })
        assertEquals(3, plan.removed.size)
    }

    @Test
    fun `size limit drops the oldest until the folder fits`() {
        val plan = LibraryBackupPruner.plan(
            backups = listOf(
                archive("new", ageDays = 1, megabytes = 400),
                archive("mid", ageDays = 2, megabytes = 400),
                archive("old", ageDays = 3, megabytes = 400)
            ),
            policy = LibraryBackupPolicy(
                retentionDays = 0,
                maximumBackupCount = 0,
                maximumTotalBytes = 900L * 1024 * 1024
            ),
            nowEpochMillis = now
        )
        assertEquals(listOf("old"), plan.removed.map { it.name })
        assertEquals(listOf("new", "mid"), plan.remaining.map { it.name })
        assertFalse(plan.isOverLimit)
    }

    @Test
    fun `the newest archive survives a limit it cannot meet on its own`() {
        val plan = LibraryBackupPruner.plan(
            backups = listOf(
                archive("new", ageDays = 1, megabytes = 900),
                archive("old", ageDays = 200, megabytes = 900)
            ),
            policy = LibraryBackupPolicy(
                retentionDays = 30,
                maximumBackupCount = 5,
                maximumTotalBytes = 100L * 1024 * 1024
            ),
            nowEpochMillis = now
        )
        assertEquals(listOf("new"), plan.remaining.map { it.name })
        assertTrue(plan.isOverLimit)
    }

    @Test
    fun `an empty folder needs no pruning`() {
        val plan = LibraryBackupPruner.plan(
            backups = emptyList(),
            policy = LibraryBackupPolicy(),
            nowEpochMillis = now
        )
        assertTrue(plan.removed.isEmpty())
        assertTrue(plan.remaining.isEmpty())
        assertFalse(plan.isOverLimit)
    }

    @Test
    fun `zero limits disable their rule`() {
        val backups = (1..9).map { archive("backup-$it", ageDays = 500L * it, megabytes = 900) }
        val plan = LibraryBackupPruner.plan(
            backups = backups,
            policy = LibraryBackupPolicy(
                retentionDays = 0,
                maximumBackupCount = 0,
                maximumTotalBytes = 0
            ),
            nowEpochMillis = now
        )
        assertTrue(plan.removed.isEmpty())
        assertEquals(9, plan.remaining.size)
    }

    @Test
    fun `both platforms agree on what a backup file is called`() {
        // The suffix ends in .zip so a document picker never has to resolve a
        // custom extension to a declared type before letting the file be
        // chosen — the whole of 「备份还是不能手动选择文件」.
        assertTrue(LibraryBackupNaming.ARCHIVE_SUFFIX.endsWith(".zip"))
        assertTrue(LibraryBackupNaming.isArchiveName("Jerreader-20260807.jerbackup.zip"))
        assertTrue(LibraryBackupNaming.isArchiveName("Jerreader备份-20260807-AB12.JERBACKUP.ZIP"))
        // Archives written by older iOS builds still restore.
        assertTrue(LibraryBackupNaming.isArchiveName("Jerreader备份-1.jerreader-backup"))
        assertFalse(LibraryBackupNaming.isArchiveName("book.epub"))
        assertFalse(LibraryBackupNaming.isArchiveName("photos.zip"))
        assertFalse(LibraryBackupNaming.isArchiveName(""))
    }
}
