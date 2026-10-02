package com.runcode.app.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import java.io.File
import java.io.IOException
import java.io.InputStream

/** A backup archive stored outside the app. [id] is whatever the store needs to find it again. */
data class StoredBackup(val id: String, val name: String, val sizeBytes: Long, val modifiedAt: Long)

/** Somewhere backups can be copied to and read back from. */
interface BackupStore {
    fun list(): List<StoredBackup>
    fun write(name: String, source: File): StoredBackup
    fun open(id: String): InputStream
    fun delete(id: String)
}

/**
 * A folder the user picked with the system folder picker (Storage Access Framework). It can be
 * on the device, a memory card, or a cloud app that offers folders — Google Drive, OneDrive and
 * the like — with no account or API key in this app.
 */
class SafBackupStore(private val context: Context, private val treeUri: Uri) : BackupStore {

    private val resolver get() = context.contentResolver

    private val folderDocument: Uri
        get() = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))

    override fun list(): List<StoredBackup> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val found = mutableListOf<StoredBackup>()
        resolver.query(children, columns, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1) ?: continue
                if (!name.endsWith(BACKUP_EXTENSION)) continue
                val document = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(0))
                found += StoredBackup(document.toString(), name, cursor.getLong(2), cursor.getLong(3))
            }
        } ?: throw IOException("The backup folder cannot be read. Choose it again.")
        return found
    }

    override fun write(name: String, source: File): StoredBackup {
        val document = DocumentsContract.createDocument(resolver, folderDocument, MIME_TYPE, name)
            ?: throw IOException("The backup folder refused a new file. Choose it again.")
        try {
            resolver.openOutputStream(document, "w")?.use { output ->
                source.inputStream().use { it.copyTo(output) }
            } ?: throw IOException("Cannot write to the backup folder")
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, document) }
            throw e
        }
        // Providers may rename on collision, e.g. "name (1).rcpkg".
        val shownName = displayName(document) ?: name
        return StoredBackup(document.toString(), shownName, source.length(), System.currentTimeMillis())
    }

    override fun open(id: String): InputStream =
        resolver.openInputStream(id.toUri()) ?: throw IOException("Cannot read $id")

    override fun delete(id: String) {
        if (!DocumentsContract.deleteDocument(resolver, id.toUri())) throw IOException("Cannot delete $id")
    }

    private fun displayName(document: Uri): String? =
        resolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }

    companion object {
        const val BACKUP_EXTENSION = ".rcpkg"
        // Not a registered type; octet-stream keeps providers from appending an extension.
        const val MIME_TYPE = "application/octet-stream"
    }
}
