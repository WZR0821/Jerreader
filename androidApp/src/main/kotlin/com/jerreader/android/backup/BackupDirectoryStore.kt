package com.jerreader.android.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Remembers the folder the user picked for backups. The Storage Access
 * Framework grant is device-local authorization, exactly like the iOS security
 * scoped bookmark: it is never written into an archive, so restoring onto
 * another device never carries someone else's folder access with it.
 */
class BackupDirectoryStore(private val context: Context) {

    private val storage = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val mutableFolder = MutableStateFlow(load())
    val folder: StateFlow<BackupFolder?> = mutableFolder.asStateFlow()

    data class BackupFolder(val treeUri: Uri, val displayName: String)

    /**
     * Takes the persistable grant so the folder still resolves after a restart,
     * and releases the previous one so the app does not hold on to access it no
     * longer uses.
     */
    fun select(treeUri: Uri): BackupFolder {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(treeUri, flags)
        mutableFolder.value?.takeIf { it.treeUri != treeUri }?.let { previous ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(previous.treeUri, flags)
            }
        }
        val name = DocumentFile.fromTreeUri(context, treeUri)?.name ?: "已选择的文件夹"
        storage.edit()
            .putString(KEY_TREE_URI, treeUri.toString())
            .putString(KEY_DISPLAY_NAME, name)
            .apply()
        val folder = BackupFolder(treeUri, name)
        mutableFolder.value = folder
        return folder
    }

    /**
     * The tree an imported archive says it came from, if this installation can
     * still write there.
     *
     * A grant survives a reinstall only when the user re-issues it, so this
     * succeeds exactly when the tree is one the app already holds — a folder
     * granted earlier in this installation, most often. Everything else is left
     * to the folder picker, which [BackupCenterScreen] can open directly on the
     * remembered tree so re-granting it is one confirmation.
     */
    fun adoptIfPermitted(treeUri: Uri): BackupFolder? {
        if (mutableFolder.value?.treeUri == treeUri) return mutableFolder.value
        val held = context.contentResolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isWritePermission && it.isReadPermission
        }
        if (!held) return null
        val writable = DocumentFile.fromTreeUri(context, treeUri)?.canWrite() == true
        if (!writable) return null
        return runCatching { select(treeUri) }.getOrNull()
    }

    fun clear() {
        mutableFolder.value?.let { current ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    current.treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
        storage.edit().remove(KEY_TREE_URI).remove(KEY_DISPLAY_NAME).apply()
        mutableFolder.value = null
    }

    /** The folder as a writable document, or null once the grant is gone. */
    fun directory(): DocumentFile? {
        val current = mutableFolder.value ?: return null
        val held = context.contentResolver.persistedUriPermissions.any {
            it.uri == current.treeUri && it.isWritePermission
        }
        if (!held) return null
        return DocumentFile.fromTreeUri(context, current.treeUri)?.takeIf { it.canWrite() }
    }

    private fun load(): BackupFolder? {
        val raw = storage.getString(KEY_TREE_URI, null) ?: return null
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        return BackupFolder(uri, storage.getString(KEY_DISPLAY_NAME, null) ?: "已选择的文件夹")
    }

    private companion object {
        const val PREFERENCES = "jerreader_backup_directory"
        const val KEY_TREE_URI = "tree_uri"
        const val KEY_DISPLAY_NAME = "display_name"
    }
}
