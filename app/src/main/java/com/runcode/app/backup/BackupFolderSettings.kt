package com.runcode.app.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BackupFolderState(
    /** Folder name as the provider shows it, or null when none is chosen or access was lost. */
    val label: String? = null,
    val autoEnabled: Boolean = false,
    val lastAutoRun: Long = 0L,
    val lastAutoResult: String? = null
)

/** The chosen backup folder and the automatic-backup switch, kept across restarts. */
class BackupFolderSettings(private val context: Context) {

    private val prefs = context.getSharedPreferences("backup_folder", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<BackupFolderState> = _state.asStateFlow()

    private val treeUri: Uri?
        get() = prefs.getString(KEY_TREE, null)?.let(Uri::parse)

    /** The folder as a store, or null when none is chosen or Android revoked access. */
    fun store(): BackupStore? = treeUri?.takeIf(::hasAccess)?.let { SafBackupStore(context, it) }

    /** Keeps access to [uri] across reboots and forgets the previous folder. */
    fun choose(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, flags)
        treeUri?.takeIf { it != uri }?.let { old ->
            runCatching { context.contentResolver.releasePersistableUriPermission(old, flags) }
        }
        prefs.edit().putString(KEY_TREE, uri.toString()).apply()
        _state.value = load()
    }

    fun clear() {
        treeUri?.let { old ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    old, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
        prefs.edit().remove(KEY_TREE).putBoolean(KEY_AUTO, false).apply()
        _state.value = load()
    }

    fun setAutoEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO, enabled).apply()
        _state.value = load()
    }

    fun isAutoDue(now: Long): Boolean =
        prefs.getBoolean(KEY_AUTO, false) && now - prefs.getLong(KEY_LAST_AUTO, 0L) >= FolderBackups.AUTO_INTERVAL_MS

    fun recordAutoRun(time: Long, result: String) {
        prefs.edit().putLong(KEY_LAST_AUTO, time).putString(KEY_LAST_RESULT, result).apply()
        _state.value = load()
    }

    private fun hasAccess(uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }

    private fun load(): BackupFolderState {
        val uri = treeUri?.takeIf(::hasAccess)
        return BackupFolderState(
            label = uri?.let(::folderName),
            autoEnabled = uri != null && prefs.getBoolean(KEY_AUTO, false),
            lastAutoRun = prefs.getLong(KEY_LAST_AUTO, 0L),
            lastAutoResult = prefs.getString(KEY_LAST_RESULT, null)
        )
    }

    private fun folderName(tree: Uri): String {
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val name = runCatching {
            context.contentResolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
        return name ?: DocumentsContract.getTreeDocumentId(tree).substringAfterLast(':').ifEmpty { "Chosen folder" }
    }

    private companion object {
        const val KEY_TREE = "tree_uri"
        const val KEY_AUTO = "auto_enabled"
        const val KEY_LAST_AUTO = "last_auto_run"
        const val KEY_LAST_RESULT = "last_auto_result"
    }
}
