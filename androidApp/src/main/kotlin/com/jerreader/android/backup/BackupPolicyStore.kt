package com.jerreader.android.backup

import android.content.Context
import com.jerreader.unified.library.LibraryBackupPolicy
import com.jerreader.unified.library.LibraryBackupProfile
import com.jerreader.unified.library.LibraryBackupScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Device-local backup schedule and retention settings. */
class BackupPolicyStore(context: Context) {

    private val storage = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val mutablePolicy = MutableStateFlow(load())
    val policy: StateFlow<LibraryBackupPolicy> = mutablePolicy.asStateFlow()

    fun update(transform: (LibraryBackupPolicy) -> LibraryBackupPolicy) {
        val updated = normalize(transform(mutablePolicy.value))
        storage.edit()
            .putBoolean(KEY_ENABLED, updated.automaticEnabled)
            .putInt(KEY_INTERVAL, updated.intervalDays)
            .putInt(KEY_RETENTION, updated.retentionDays)
            .putInt(KEY_COUNT, updated.maximumBackupCount)
            .putLong(KEY_BYTES, updated.maximumTotalBytes)
            .putStringSet(KEY_SCOPES, updated.automaticScopes.map { it.name }.toSet())
            .apply()
        updated.lastAutomaticBackupAtEpochMillis?.let {
            storage.edit().putLong(KEY_LAST_RUN, it).apply()
        }
        mutablePolicy.value = updated
    }

    /**
     * Adopts the regimen an imported archive carried.
     *
     * The archive's own creation time becomes the last automatic run so the
     * inherited schedule continues from where the previous installation left
     * off instead of firing the moment the restore finishes.
     */
    fun applyInherited(profile: LibraryBackupProfile, archiveCreatedAtEpochMillis: Long) {
        val inherited = profile.normalized().policy
        update {
            inherited.copy(
                lastAutomaticBackupAtEpochMillis = archiveCreatedAtEpochMillis
            )
        }
    }

    fun recordAutomaticRun(atEpochMillis: Long) {
        storage.edit().putLong(KEY_LAST_RUN, atEpochMillis).apply()
        mutablePolicy.value = mutablePolicy.value.copy(
            lastAutomaticBackupAtEpochMillis = atEpochMillis
        )
    }

    /**
     * A stored value that is no longer offered — after an upgrade changes the
     * choices, say — would otherwise be impossible to change from the UI,
     * because no row would ever look selected.
     */
    private fun normalize(policy: LibraryBackupPolicy) = policy.copy(
        intervalDays = closest(policy.intervalDays, LibraryBackupPolicy.INTERVAL_CHOICES, 7),
        retentionDays = closest(policy.retentionDays, LibraryBackupPolicy.RETENTION_CHOICES, 30),
        maximumBackupCount = closest(policy.maximumBackupCount, LibraryBackupPolicy.COUNT_CHOICES, 5),
        maximumTotalBytes = closest(
            policy.maximumTotalBytes,
            LibraryBackupPolicy.SIZE_CHOICES,
            2L * 1024 * 1024 * 1024
        ),
        automaticScopes = policy.automaticScopes.ifEmpty {
            setOf(LibraryBackupScope.READING, LibraryBackupScope.LEARNING, LibraryBackupScope.SETTINGS)
        }
    )

    private fun <T : Comparable<T>> closest(value: T, choices: List<T>, fallback: T): T =
        if (value in choices) value else fallback

    private fun load(): LibraryBackupPolicy {
        val default = LibraryBackupPolicy()
        val scopes = storage.getStringSet(KEY_SCOPES, null)
            ?.mapNotNull { name -> LibraryBackupScope.entries.firstOrNull { it.name == name } }
            ?.toSet()
        return normalize(
            LibraryBackupPolicy(
                automaticEnabled = storage.getBoolean(KEY_ENABLED, default.automaticEnabled),
                intervalDays = storage.getInt(KEY_INTERVAL, default.intervalDays),
                retentionDays = storage.getInt(KEY_RETENTION, default.retentionDays),
                maximumBackupCount = storage.getInt(KEY_COUNT, default.maximumBackupCount),
                maximumTotalBytes = storage.getLong(KEY_BYTES, default.maximumTotalBytes),
                automaticScopes = scopes ?: default.automaticScopes,
                lastAutomaticBackupAtEpochMillis = storage
                    .getLong(KEY_LAST_RUN, 0L)
                    .takeIf { it > 0L }
            )
        )
    }

    private companion object {
        const val PREFERENCES = "jerreader_backup_policy"
        const val KEY_ENABLED = "automatic_enabled"
        const val KEY_INTERVAL = "interval_days"
        const val KEY_RETENTION = "retention_days"
        const val KEY_COUNT = "maximum_count"
        const val KEY_BYTES = "maximum_bytes"
        const val KEY_SCOPES = "automatic_scopes"
        const val KEY_LAST_RUN = "last_automatic_run"
    }
}
