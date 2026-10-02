package com.runcode.app.git

import android.app.Application
import android.content.Context
import com.runcode.app.domain.models.ProjectProfile
import com.runcode.app.security.SecretVault
import com.runcode.app.storage.ProjectArchive
import com.runcode.app.storage.ProjectStorage
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Runs the real runcode_git.py in the host's Python, standing in for Chaquopy, so the Kotlin
 * side is tested against actual dulwich behaviour rather than a mock.
 */
class HostPythonGitBackend(private val python: String) : GitBackend {
    private val moduleDir = File("src/main/python").absoluteFile

    override fun call(function: String, vararg args: Any): String {
        val shim = "import json,sys; sys.path.insert(0, sys.argv[1]); import runcode_git as g; " +
            "r = json.load(sys.stdin); print(getattr(g, r['f'])(*r['a']))"
        val process = ProcessBuilder(python, "-c", shim, moduleDir.path).redirectErrorStream(false).start()
        process.outputStream.use { it.write(JSONObject().put("f", function).put("a", JSONArray(args.toList())).toString().toByteArray()) }
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "python failed: $err" }
        return out.trim().lines().last()
    }

    companion object {
        /** A Python 3 that can import dulwich, or null. */
        fun find(): String? = listOfNotNull(System.getenv("RUNCODE_TEST_PYTHON"), System.getenv("RUNCODE_BUILD_PYTHON"), "python3")
            .firstOrNull { candidate ->
                runCatching {
                    val p = ProcessBuilder(candidate, "-c", "import dulwich").start()
                    p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0
                }.getOrDefault(false)
            }
    }
}

private class MapVault : SecretVault {
    val values = mutableMapOf<String, String>()
    override fun setSecret(key: String, plainText: String): Boolean { values[key] = plainText; return true }
    override fun getSecret(key: String) = values[key]
    override fun removeSecret(key: String) { values.remove(key) }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class GitManagerTest {

    private lateinit var storage: ProjectStorage
    private lateinit var vault: MapVault
    private lateinit var git: GitManager

    @Before fun setup() {
        val python = HostPythonGitBackend.find()
        assumeTrue("No Python with dulwich; set RUNCODE_TEST_PYTHON or pip install dulwich", python != null)
        val app = RuntimeEnvironment.getApplication()
        storage = ProjectStorage(app)
        vault = MapVault()
        git = GitManager(HostPythonGitBackend(python!!), storage, ProjectArchive(app, storage), vault,
            app.getSharedPreferences("git-test-${System.nanoTime()}", Context.MODE_PRIVATE))
        git.identity = GitIdentity("Ada Lovelace", "ada@example.com")
    }

    private fun bareRemote(): File {
        val dir = Files.createTempDirectory("remote").toFile()
        val p = ProcessBuilder(HostPythonGitBackend.find()!!, "-c",
            "import sys; from dulwich.repo import Repo; r = Repo.init_bare(sys.argv[1]); " +
                "r.refs.set_symbolic_ref(b'HEAD', b'refs/heads/main'); r.close()", dir.path).start()
        assertTrue(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0)
        return dir
    }

    @Test fun `init, stage, commit and log in source only`() = runBlocking {
        val project = storage.createProjectFromTemplate("Git demo", ProjectProfile.PYTHON_SCRIPT)
        File(storage.getDataDir(project.id), "app.db").writeText("not for git")
        assertFalse(git.isRepository(project))

        git.init(project)
        assertTrue(git.isRepository(project))
        val before = git.status(project)
        assertEquals("main", before.branch)
        assertTrue(project.entryPoint in before.untracked)
        assertTrue("data/ must stay out of the repository", before.untracked.none { it.contains("app.db") })

        git.stage(project)
        val sha = git.commit(project, "First commit")
        assertTrue(git.status(project).isClean)
        val log = git.log(project)
        assertEquals(sha, log.single().sha)
        assertEquals("Ada Lovelace <ada@example.com>", log.single().author)
    }

    @Test fun `missing identity is a readable error`() = runBlocking {
        val project = storage.createProjectFromTemplate("No author", ProjectProfile.PYTHON_SCRIPT)
        git.identity = GitIdentity("", "")
        git.init(project)
        git.stage(project)
        val error = runCatching { git.commit(project, "x") }.exceptionOrNull()
        assertTrue(error is GitException)
        assertTrue(error!!.message!!.contains("name and email"))
    }

    @Test fun `push, then clone it back as a new project with its entry point`() = runBlocking {
        val remote = bareRemote()
        val project = storage.createProjectFromTemplate("Bot", ProjectProfile.TELEGRAM_BOT)
        git.init(project)
        git.stage(project)
        git.commit(project, "bot")
        git.setRemote(project, remote.path)
        git.push(project)
        assertEquals(0, git.status(project).ahead)

        val cloned = git.cloneProject(remote.path + "/")
        assertEquals(remote.name, cloned.name)
        assertEquals(project.entryPoint, cloned.entryPoint)
        assertTrue(git.isRepository(cloned))
        assertEquals(
            File(storage.getSourceDir(project.id), project.entryPoint).readText(),
            File(storage.getSourceDir(cloned.id), cloned.entryPoint).readText()
        )
    }

    @Test fun `a failed clone leaves no project behind`() = runBlocking {
        val before = storage.getProjectDir("x").parentFile!!.list()!!.toSet()
        val error = runCatching { git.cloneProject("/nonexistent/repo.git") }.exceptionOrNull()
        assertTrue(error is GitException)
        assertEquals(before, storage.getProjectDir("x").parentFile!!.list()!!.toSet())
    }

    @Test fun `the token is used for push but never appears in errors`() = runBlocking {
        val token = "ghp_secretsecretsecret123"
        git.setToken(token)
        assertTrue(git.hasToken)
        val project = storage.createProjectFromTemplate("Leak check", ProjectProfile.PYTHON_SCRIPT)
        git.init(project)
        git.stage(project)
        git.commit(project, "x")
        val closedPort = java.net.ServerSocket(0).use { it.localPort }
        git.setRemote(project, "http://127.0.0.1:$closedPort/repo.git")
        val error = runCatching { git.push(project) }.exceptionOrNull()
        assertTrue(error is GitException)
        assertFalse(error!!.message!!.contains(token))

        git.setToken("")
        assertFalse(git.hasToken)
    }

    @Test fun `invalid branch names are refused before reaching git`() = runBlocking {
        val project = storage.createProjectFromTemplate("Branches", ProjectProfile.PYTHON_SCRIPT)
        git.init(project)
        assertTrue(runCatching { git.checkout(project, "bad name; rm -rf", true) }.exceptionOrNull() is IllegalArgumentException)
    }
}
