package com.runcode.app.backup

import com.runcode.app.domain.models.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Backups in a user-chosen folder: export with read-back verification, listing, restore, and
 * the optional daily automatic run.
 */
class FolderBackups(
    private val backupManager: BackupManager,
    private val cacheDir: File,
    private val now: () -> Long = System::currentTimeMillis
) {

    /**
     * Creates a fresh backup of [project] and copies it to [store]. The copy is read back and
     * compared byte for byte by hash, so a truncated upload is caught here, not at restore time.
     */
    suspend fun export(store: BackupStore, project: Project, automatic: Boolean = false): StoredBackup =
        withContext(Dispatchers.IO) {
            val local = backupManager.createProjectBackup(project)
            try {
                val stored = store.write(fileName(project, automatic), local)
                val expected = sha256(local.inputStream())
                val actual = try {
                    sha256(store.open(stored.id))
                } catch (e: IOException) {
                    null
                }
                if (actual != expected) {
                    runCatching { store.delete(stored.id) }
                    throw IOException("The copy in the backup folder does not match the original. It was removed; try again.")
                }
                if (automatic) prune(store, project, KEEP_AUTOMATIC)
                stored
            } finally {
                // Automatic runs leave nothing behind locally; manual ones keep the local copy too.
                if (automatic) local.delete()
            }
        }

    suspend fun list(store: BackupStore): List<StoredBackup> = withContext(Dispatchers.IO) {
        store.list().sortedByDescending { it.modifiedAt }
    }

    suspend fun verify(store: BackupStore, backup: StoredBackup): BackupPreview =
        withLocalCopy(store, backup) { backupManager.verifyBackup(it) }

    suspend fun restore(store: BackupStore, backup: StoredBackup): Project =
        withLocalCopy(store, backup) { backupManager.restoreProjectFromBackup(it) }

    /** Backs up every project. Returns how many succeeded and the first error, if any. */
    suspend fun exportAll(store: BackupStore, projects: List<Project>): Pair<Int, String?> {
        var done = 0
        var firstError: String? = null
        projects.forEach { project ->
            try {
                export(store, project, automatic = true)
                done++
            } catch (e: Exception) {
                if (firstError == null) firstError = "${project.name}: ${e.message}"
            }
        }
        return done to firstError
    }

    /** Deletes this project's oldest automatic backups beyond [keep]. Manual ones are never touched. */
    fun prune(store: BackupStore, project: Project, keep: Int) {
        store.list()
            .filter { it.name.startsWith(autoPrefix(project)) }
            .sortedByDescending { it.name } // names carry a sortable timestamp
            .drop(keep)
            .forEach { runCatching { store.delete(it.id) } }
    }

    private suspend fun <T> withLocalCopy(store: BackupStore, backup: StoredBackup, block: suspend (File) -> T): T =
        withContext(Dispatchers.IO) {
            val dir = File(cacheDir, "folder_backups").apply { mkdirs() }
            val copy = File(dir, "import_${now()}${SafBackupStore.BACKUP_EXTENSION}")
            try {
                store.open(backup.id).use { input -> copy.outputStream().use { input.copyTo(it) } }
                block(copy)
            } finally {
                copy.delete()
            }
        }

    fun fileName(project: Project, automatic: Boolean): String {
        // UTC, so names sort in creation order across time-zone and daylight-saving changes;
        // pruning relies on that order.
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(now()))
        return if (automatic) "${autoPrefix(project)}$stamp${SafBackupStore.BACKUP_EXTENSION}"
        else "${safeName(project)}-${project.id}-$stamp${SafBackupStore.BACKUP_EXTENSION}"
    }

    private fun autoPrefix(project: Project) = "auto-${safeName(project)}-${project.id}-"

    private fun safeName(project: Project): String =
        project.name.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').take(40).ifEmpty { "project" }

    private fun sha256(input: InputStream): String = input.use { stream ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) {
            val read = stream.read(buffer)
            if (read == -1) break
            digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val KEEP_AUTOMATIC = 7
        const val AUTO_INTERVAL_MS = 24L * 60 * 60 * 1000
    }
}
