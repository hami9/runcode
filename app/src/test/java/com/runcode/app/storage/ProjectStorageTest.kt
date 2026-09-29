package com.runcode.app.storage

import android.app.Application
import com.runcode.app.database.AppMetaDatabase
import com.runcode.app.domain.models.EnvironmentVariable
import com.runcode.app.domain.models.ProjectProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ProjectStorageTest {
    private lateinit var storage: ProjectStorage
    private lateinit var archive: ProjectArchive

    @Before fun setup() {
        storage = ProjectStorage(RuntimeEnvironment.getApplication())
        archive = ProjectArchive(RuntimeEnvironment.getApplication(), storage)
    }

    private fun zip(vararg entries: Pair<String, String>): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { output -> entries.forEach { (path, text) ->
            output.putNextEntry(ZipEntry(path))
            output.write(text.toByteArray())
            output.closeEntry()
        } }
        return ByteArrayInputStream(bytes.toByteArray())
    }

    @Test fun `reject traversal and sibling paths`() {
        storage.initializeProjectDirectories("a")
        listOf("../b/data.txt", "source/../../outside", "../a-other/file").forEach {
            assertThrows(SecurityException::class.java) { storage.resolveInProject("a", it) }
        }
    }

    @Test fun `layout directories cannot be removed or renamed`() {
        storage.initializeProjectDirectories("layout")
        ProjectStorage.STRUCTURAL_DIRS.forEach {
            assertThrows(SecurityException::class.java) { storage.deleteFile("layout", it) }
            assertThrows(SecurityException::class.java) { storage.movePath("layout", it, "other") }
        }
    }

    @Test fun `archive rejects a traversing entry point`() {
        val manifest = """{"format":"runcode-project","entry_point":"../../outside.py"}"""
        assertThrows(IllegalArgumentException::class.java) {
            archive.import(zip("runcode.json" to manifest, "source/main.py" to "print(1)"), "bad")
        }
    }

    @Test fun `archive drops plaintext secrets and foreign references`() {
        val manifest = """{"format":"runcode-project","environment":[
            {"key":"TOKEN","value":"plaintext-test-value","is_secret":true},
            {"key":"OTHER","value":"${'$'}{SEC_MCP_BRIDGE_TOKEN}","is_secret":true}
        ]}"""
        val project = archive.import(zip("runcode.json" to manifest, "source/main.py" to "print(1)"), "imported")
        assertTrue(project.environment.all { it.isSecret && it.value.isEmpty() })
        assertFalse(project.startOnBoot)
    }

    @Test fun `archive rejects zip slip`() {
        assertThrows(SecurityException::class.java) {
            archive.import(zip("../../outside.py" to "print(1)"), "bad")
        }
    }

    @Test fun `metadata rejects secret plaintext`() {
        val project = storage.createProjectFromTemplate("meta", ProjectProfile.PYTHON_SCRIPT)
        AppMetaDatabase(RuntimeEnvironment.getApplication()).use { db ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { db.insertOrUpdateProject(project.copy(environment = listOf(EnvironmentVariable("TOKEN", "plaintext", true)))) }
            }
        }
    }

    @Test fun `entry point follows folder moves without matching sibling names`() {
        val project = storage.createProjectFromTemplate("entry", ProjectProfile.PYTHON_SCRIPT).copy(entryPoint = "pkg/main.py")
        val moved = EntryPoints.follow(project, "source/pkg", "source/new") as EntryPointEffect.Moved
        assertEquals("new/main.py", moved.project.entryPoint)
        assertEquals(EntryPointEffect.Unaffected, EntryPoints.follow(project, "source/p", null))
        assertEquals(EntryPointEffect.Lost, EntryPoints.follow(project, "source/pkg", null))
    }

    @Test fun `new service templates execute real libraries`() {
        val bot = storage.createProjectFromTemplate("bot", ProjectProfile.TELEGRAM_BOT)
        assertTrue(File(bot.projectRoot, "source/bot.py").readText().contains("application.run_polling"))
        val api = storage.createProjectFromTemplate("api", ProjectProfile.PYTHON_HTTP)
        assertTrue(File(api.projectRoot, "source/server.py").readText().contains("HTTPServer((address, port)"))
    }
}
