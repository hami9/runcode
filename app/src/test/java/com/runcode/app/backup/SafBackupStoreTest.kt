package com.runcode.app.backup

import android.app.Application
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/** A minimal file-backed provider, the shape of what Drive or the device's storage expose. */
class FolderDocumentsProvider : DocumentsProvider() {
    companion object {
        const val AUTHORITY = "com.runcode.test.documents"
        lateinit var root: File
        private val COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS
        )
    }

    override fun onCreate() = true

    private fun file(id: String) = if (id == "root") root else File(root, id)

    private fun row(cursor: MatrixCursor, id: String) {
        val f = file(id)
        cursor.addRow(arrayOf(
            id, if (id == "root") "Backups" else f.name, f.length(), f.lastModified(),
            if (f.isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream",
            Document.FLAG_DIR_SUPPORTS_CREATE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_WRITE
        ))
    }

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(arrayOf("root_id"))

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(COLUMNS).also { row(it, documentId) }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(COLUMNS).also { c -> file(parentDocumentId).listFiles()!!.forEach { row(c, it.name) } }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode))

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        var name = displayName
        var n = 1
        while (File(root, name).exists()) name = displayName.replace(".rcpkg", " (${n++}).rcpkg")
        File(root, name).createNewFile()
        return name
    }

    override fun deleteDocument(documentId: String) {
        file(documentId).delete()
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String) = parentDocumentId == "root"
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SafBackupStoreTest {

    private lateinit var store: SafBackupStore
    private lateinit var source: File

    @Before fun setup() {
        FolderDocumentsProvider.root = Files.createTempDirectory("tree").toFile()
        // DocumentsProvider insists on exactly the manifest attributes a real one declares.
        val info = ProviderInfo().apply {
            authority = FolderDocumentsProvider.AUTHORITY
            exported = true
            grantUriPermissions = true
            readPermission = android.Manifest.permission.MANAGE_DOCUMENTS
            writePermission = android.Manifest.permission.MANAGE_DOCUMENTS
        }
        Robolectric.buildContentProvider(FolderDocumentsProvider::class.java).create(info)
        val tree = DocumentsContract.buildTreeDocumentUri(FolderDocumentsProvider.AUTHORITY, "root")
        store = SafBackupStore(RuntimeEnvironment.getApplication(), tree)
        source = File.createTempFile("backup", ".rcpkg").apply { writeBytes(ByteArray(50_000) { (it % 251).toByte() }) }
    }

    @Test fun `write, list, read back and delete through the documents provider`() {
        val stored = store.write("one.rcpkg", source)
        assertEquals("one.rcpkg", stored.name)
        assertTrue(Uri.parse(stored.id).toString().startsWith("content://${FolderDocumentsProvider.AUTHORITY}/tree/root/document/"))

        File(FolderDocumentsProvider.root, "notes.txt").writeText("not a backup")
        val listed = store.list()
        assertEquals(listOf("one.rcpkg"), listed.map { it.name })
        assertEquals(source.length(), listed.single().sizeBytes)

        assertArrayEquals(source.readBytes(), store.open(stored.id).use { it.readBytes() })

        store.delete(stored.id)
        assertTrue(store.list().isEmpty())
    }

    @Test fun `a provider rename on collision is reported back`() {
        store.write("same.rcpkg", source)
        val second = store.write("same.rcpkg", source)
        assertEquals("same (1).rcpkg", second.name)
    }
}
