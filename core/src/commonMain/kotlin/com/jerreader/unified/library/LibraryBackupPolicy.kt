package com.jerreader.unified.library

/**
 * What a backup archive may contain. Mirrors the iOS `LibraryBackupScope`, and
 * the titles are the ones the iOS backup centre shows so a user moving between
 * the two apps is choosing the same four things.
 */
enum class LibraryBackupScope(val title: String, val detail: String) {
    LIBRARY(
        title = "书籍与封面",
        detail = "包含书架元数据和实际 EPUB、PDF、DOCX/TXT 转换副本。"
    ),
    READING(
        title = "进度、书签与批注",
        detail = "包含每本书的阅读位置、排版、时长、书签和批注。"
    ),
    LEARNING(
        title = "生词与翻译收藏",
        detail = "包含生词本、查词历史、AI 解析和译文收藏。"
    ),
    SETTINGS(
        title = "阅读与翻译偏好",
        detail = "包含非敏感的全局阅读与翻译设置，不包含 API Key。"
    )
}

data class LibraryBackupPolicy(
    val automaticEnabled: Boolean = false,
    val intervalDays: Int = 7,
    val retentionDays: Int = 30,
    val maximumBackupCount: Int = 5,
    val maximumTotalBytes: Long = 2L * 1024 * 1024 * 1024,
    val automaticScopes: Set<LibraryBackupScope> = setOf(
        LibraryBackupScope.READING,
        LibraryBackupScope.LEARNING,
        LibraryBackupScope.SETTINGS
    ),
    val lastAutomaticBackupAtEpochMillis: Long? = null
) {
    fun isDue(nowEpochMillis: Long): Boolean {
        if (!automaticEnabled || automaticScopes.isEmpty()) return false
        val last = lastAutomaticBackupAtEpochMillis ?: return true
        val interval = maxOf(intervalDays, 1) * 24L * 60 * 60 * 1000
        return nowEpochMillis - last >= interval
    }

    companion object {
        val INTERVAL_CHOICES = listOf(1, 3, 7, 14, 30)

        /** 0 means "不按时间删除". */
        val RETENTION_CHOICES = listOf(7, 14, 30, 90, 365, 0)
        val COUNT_CHOICES = listOf(2, 3, 5, 10, 20)

        /** 0 means "不限容量". */
        val SIZE_CHOICES = listOf(
            250L * 1024 * 1024,
            500L * 1024 * 1024,
            1L * 1024 * 1024 * 1024,
            2L * 1024 * 1024 * 1024,
            5L * 1024 * 1024 * 1024,
            0L
        )
    }
}

data class LibraryBackupArchiveInfo(
    val name: String,
    val createdAtEpochMillis: Long,
    val sizeBytes: Long
)

data class LibraryBackupPrunePlan(
    val removed: List<LibraryBackupArchiveInfo>,
    val remaining: List<LibraryBackupArchiveInfo>,
    /** True when the newest archive alone already exceeds the size limit. */
    val isOverLimit: Boolean
) {
    val reclaimedBytes: Long get() = removed.sumOf { it.sizeBytes }
    val remainingBytes: Long get() = remaining.sumOf { it.sizeBytes }
}

/**
 * What a backup archive is called.
 *
 * Both apps read each other's archives, so both write the same name. It ends
 * in `.zip` because that is what the file is, and because a custom extension
 * has to be resolved to a declared type before a document picker will let the
 * file be chosen — iCloud's File Provider often will not, which left iOS
 * backups visible in Files and greyed out in the picker. `jerbackup` in front
 * of it keeps the archive recognisable at a glance and lets each app list its
 * own managed folder without inspecting every zip it finds there.
 */
object LibraryBackupNaming {
    const val ARCHIVE_SUFFIX = ".jerbackup.zip"

    /** Archives written by iOS before the rename. Restored, never written. */
    const val LEGACY_IOS_EXTENSION = "jerreader-backup"

    fun isArchiveName(name: String): Boolean {
        val lowered = name.lowercase()
        return lowered.endsWith(ARCHIVE_SUFFIX) ||
            lowered.endsWith(".$LEGACY_IOS_EXTENSION")
    }
}

/**
 * Deletes oldest-first by age, then by count, then by total size — the same
 * order iOS applies. The newest archive is never deleted: a policy that is too
 * strict for a single backup must not leave the user with nothing to restore.
 */
object LibraryBackupPruner {
    fun plan(
        backups: List<LibraryBackupArchiveInfo>,
        policy: LibraryBackupPolicy,
        nowEpochMillis: Long
    ): LibraryBackupPrunePlan {
        if (backups.isEmpty()) {
            return LibraryBackupPrunePlan(emptyList(), emptyList(), isOverLimit = false)
        }
        // Newest first, so "the last one" is always the oldest survivor.
        val remaining = backups.sortedByDescending { it.createdAtEpochMillis }.toMutableList()
        val removed = mutableListOf<LibraryBackupArchiveInfo>()

        fun dropOldestWhile(shouldDrop: () -> Boolean) {
            while (remaining.size > 1 && shouldDrop()) {
                removed.add(remaining.removeAt(remaining.lastIndex))
            }
        }

        if (policy.retentionDays > 0) {
            val cutoff = nowEpochMillis - policy.retentionDays * 24L * 60 * 60 * 1000
            dropOldestWhile { remaining.last().createdAtEpochMillis < cutoff }
        }
        if (policy.maximumBackupCount > 0) {
            dropOldestWhile { remaining.size > policy.maximumBackupCount }
        }
        if (policy.maximumTotalBytes > 0) {
            dropOldestWhile { remaining.sumOf { it.sizeBytes } > policy.maximumTotalBytes }
        }

        val isOverLimit = policy.maximumTotalBytes > 0 &&
            remaining.sumOf { it.sizeBytes } > policy.maximumTotalBytes
        return LibraryBackupPrunePlan(removed, remaining, isOverLimit)
    }
}

/**
 * The backup regimen an archive carries with it, mirroring the iOS
 * `LibraryBackupProfile`.
 *
 * A backup is only half useful if the next installation has to be told all over
 * again how often to back up, what to include and where to put it. This travels
 * inside every archive — independently of whether the user asked for the
 * settings scope — so importing one continues the same backup chain instead of
 * starting from the defaults.
 *
 * The folder is a *hint*: a label and a URI, never an authorization. The
 * Storage Access Framework grant stays on the device that was given it.
 */
data class LibraryBackupProfile(
    val policy: LibraryBackupPolicy,
    val folderDisplayName: String? = null,
    val folderUri: String? = null
) {
    /**
     * The regimen as this build can actually honour it. A value some other
     * build offered would otherwise sit in the pickers as a row nothing can
     * select, leaving the user unable to change it.
     */
    fun normalized(): LibraryBackupProfile = copy(
        policy = policy.copy(
            intervalDays = choice(
                policy.intervalDays,
                LibraryBackupPolicy.INTERVAL_CHOICES,
                7
            ),
            retentionDays = choice(
                policy.retentionDays,
                LibraryBackupPolicy.RETENTION_CHOICES,
                30
            ),
            maximumBackupCount = choice(
                policy.maximumBackupCount,
                LibraryBackupPolicy.COUNT_CHOICES,
                5
            ),
            maximumTotalBytes = choice(
                policy.maximumTotalBytes,
                LibraryBackupPolicy.SIZE_CHOICES,
                2L * 1024 * 1024 * 1024
            ),
            automaticScopes = policy.automaticScopes.ifEmpty {
                LibraryBackupPolicy().automaticScopes
            }
        )
    )

    private fun <T> choice(value: T, choices: List<T>, fallback: T): T =
        if (value in choices) value else fallback
}
