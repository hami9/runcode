package com.runcode.app.backup

import android.app.Application
import android.net.Uri
import com.runcode.app.domain.models.Project
import com.runcode.app.domain.models.ProjectProfile
import com.runcode.app.storage.ProjectStorage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

/** A folder on disk standing in for the SAF folder. [truncate] imitates a broken upload. */
private class DirStore(val dir: File, var truncate: Boolean = false, var refuse: String? = null) : BackupStore {
    override fun list() = dir.listFiles()!!.filter { it.name.endsWith(".rcpkg") }
        .map { StoredBackup(it.absolutePath, it.name, it.length(), it.lastModified()) }

    override fun write(name: String, source: File): StoredBackup {
        refuse?.let { if (name.contains(it)) throw IOException("quota exceeded") }
        val target = File(dir, name)
        val bytes = source.readBytes()
        target.writeBytes(if (truncate) bytes.copyOf(bytes.size / 2) else bytes)
        return StoredBackup(target.absolutePath, name, target.length(), target.lastModified())
    }

    override fun open(id: String): InputStream = File(id).inputStream()

    override fun delete(id: String) {
        if (!File(id).delete()) throw IOException("cannot delete $id")
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class FolderBackupsTest {

    private lateinit var storage: ProjectStorage
    private lateinit var manager: BackupManager
    private lateinit var store: DirStore
    private var clock = 1_760_000_000_000L
    private lateinit var folder: FolderBackups

    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        storage = ProjectStorage(app)
        manager = BackupManager(app, storage)
        store = DirStore(Files.createTempDirectory("saf").toFile())
        folder = FolderBackups(manager, app.cacheDir) { clock }
    }

    private fun project(name: String = "Notes App"): Project {
        val project = storage.createProjectFromTemplate(name, ProjectProfile.PYTHON_SCRIPT)
        File(storage.getDataDir(project.id), "notes.txt").writeText("remember the milk")
        return project
    }

    @Test fun `export is verified, listed and restorable with identical files`() = runBlocking {
        val original = project()
        val stored = folder.export(store, original)

        assertTrue(stored.name.startsWith("Notes_App-${original.id}-"))
        assertEquals(listOf(stored.name), folder.list(store).map { it.name })
        assertTrue(folder.verify(store, stored).isValid)

        val restored = folder.restore(store, stored)
        assertNotEquals(original.id, restored.id)
        assertEquals("remember the milk", File(storage.getDataDir(restored.id), "notes.txt").readText())
        assertEquals(
            File(storage.getSourceDir(original.id), original.entryPoint).readText(),
            File(storage.getSourceDir(restored.id), restored.entryPoint).readText()
        )
        // A manual export keeps the local copy too.
        assertEquals(1, storage.getBackupsDir(original.id).listFiles()!!.size)
    }

    @Test fun `a copy that does not read back identically is removed and reported`() = runBlocking {
        store.truncate = true
        val error = runCatching { folder.export(store, project()) }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(store.list().isEmpty())
    }

    @Test fun `automatic runs keep seven copies, never touch manual ones and leave nothing locally`() = runBlocking {
        val p = project()
        val manual = folder.export(store, p)
        repeat(9) {
            clock += 24L * 60 * 60 * 1000
            folder.export(store, p, automatic = true)
        }
        val names = store.list().map { it.name }
        assertEquals(FolderBackups.KEEP_AUTOMATIC, names.count { it.startsWith("auto-") })
        assertTrue(manual.name in names)
        // Only the manual export's local copy remains.
        assertEquals(1, storage.getBackupsDir(p.id).listFiles()!!.size)
    }

    @Test fun `pruning only applies to the same project`() = runBlocking {
        val a = project("A")
        val b = project("B")
        repeat(3) { clock += 1000; folder.export(store, a, automatic = true) }
        folder.export(store, b, automatic = true)
        folder.prune(store, a, keep = 1)
        val names = store.list().map { it.name }
        assertEquals(1, names.count { it.contains(a.id) })
        assertEquals(1, names.count { it.contains(b.id) })
    }

    @Test fun `exportAll keeps going after a failure and reports it`() = runBlocking {
        val good = project("Good")
        val bad = project("Bad")
        store.refuse = "Bad"
        val (done, error) = folder.exportAll(store, listOf(bad, good))
        assertEquals(1, done)
        assertEquals("Bad: quota exceeded", error)
        assertTrue(store.list().single().name.contains(good.id))
    }

    @Test fun `a quote in the project name no longer breaks the manifest`() = runBlocking {
        val p = project("The \"best\" app\\v2")
        val local = manager.createProjectBackup(p)
        val preview = manager.verifyBackup(local)
        assertTrue(preview.error, preview.isValid)
        assertEquals("The \"best\" app\\v2", preview.manifest!!.projectName)
        assertEquals("The \"best\" app\\v2", manager.restoreProjectFromBackup(local).name)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BackupFolderSettingsTest {

    private val tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ABackups")

    @Test fun `choosing keeps access, forgetting releases it`() {
        val settings = BackupFolderSettings(RuntimeEnvironment.getApplication())
        assertNull(settings.store())
        assertNull(settings.state.value.label)

        settings.choose(tree)
        assertNotNull(settings.store())
        assertEquals("Backups", settings.state.value.label)

        // A new instance (app restart) still has it.
        assertNotNull(BackupFolderSettings(RuntimeEnvironment.getApplication()).store())

        settings.clear()
        assertNull(settings.store())
        assertTrue(RuntimeEnvironment.getApplication().contentResolver.persistedUriPermissions.isEmpty())
    }

    @Test fun `automatic backup is due once a day and only when switched on`() {
        val settings = BackupFolderSettings(RuntimeEnvironment.getApplication())
        settings.choose(tree)
        val now = 1_760_000_000_000L
        assertFalse(settings.isAutoDue(now))

        settings.setAutoEnabled(true)
        assertTrue(settings.isAutoDue(now))
        settings.recordAutoRun(now, "Backed up 2 project(s)")
        assertFalse(settings.isAutoDue(now + 60_000))
        assertTrue(settings.isAutoDue(now + FolderBackups.AUTO_INTERVAL_MS))
        assertEquals("Backed up 2 project(s)", settings.state.value.lastAutoResult)
    }
}
