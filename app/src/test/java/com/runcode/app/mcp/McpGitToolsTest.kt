package com.runcode.app.mcp

import android.content.Context
import com.runcode.app.RuncodeApp
import com.runcode.app.domain.models.ProjectProfile
import com.runcode.app.git.GitIdentity
import com.runcode.app.git.GitManager
import com.runcode.app.git.HostPythonGitBackend
import com.runcode.app.security.SecretVault
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = RuncodeApp::class)
class McpGitToolsTest {

    @Test fun `commit, status and push through the bridge`() = runBlocking {
        val python = HostPythonGitBackend.find()
        assumeTrue("No Python with dulwich", python != null)
        val app = RuntimeEnvironment.getApplication() as RuncodeApp
        val vault = object : SecretVault {
            override fun setSecret(key: String, plainText: String) = true
            override fun getSecret(key: String): String? = null
            override fun removeSecret(key: String) = Unit
        }
        val git = GitManager(HostPythonGitBackend(python!!), app.projectStorage, app.projectArchive, vault,
            app.getSharedPreferences("mcp-git-test", Context.MODE_PRIVATE))
        git.identity = GitIdentity("Bridge Bot", "bot@example.com")
        val changed = mutableListOf<String>()
        val host = McpToolHost(app.projectStorage, app.appMetaDatabase, app.projectDatabaseManager,
            app.serviceSupervisor, app.logManager, app.terminalSession, { _, _ -> "" },
            app.projectSettings, { changed += it }, { "" }, { _, _ -> "" }, { git }, app.debugger)

        val project = app.projectStorage.createProjectFromTemplate("MCP git", ProjectProfile.PYTHON_HTTP)
        app.appMetaDatabase.insertOrUpdateProject(project)

        fun call(name: String, args: JSONObject): JSONObject = McpTools.call(host, name, args.put("project_id", project.id))
        fun text(result: JSONObject) = result.getJSONArray("content").getJSONObject(0).getString("text")

        assertTrue(text(call("git_status", JSONObject())).startsWith("No repository yet"))

        val commit = call("git_commit", JSONObject().put("message", "From the bridge"))
        assertFalse(text(commit), commit.getBoolean("isError"))
        assertTrue(text(commit).startsWith("Committed "))
        assertEquals(listOf(project.id), changed)

        val status = JSONObject(text(call("git_status", JSONObject())))
        assertEquals("main", status.getString("branch"))
        assertEquals(0, status.getJSONArray("untracked").length())

        // No remote yet: a clear tool error, not an exception.
        val push = call("git_push", JSONObject())
        assertTrue(push.getBoolean("isError"))
        assertTrue(text(push).contains("remote"))

        val remote = Files.createTempDirectory("remote").toFile()
        val init = ProcessBuilder(python, "-c", "import sys; from dulwich.repo import Repo; Repo.init_bare(sys.argv[1]).close()", remote.path).start()
        assertTrue(init.waitFor(30, TimeUnit.SECONDS))
        git.setRemote(project, remote.path)
        val pushed = call("git_push", JSONObject())
        assertFalse(text(pushed), pushed.getBoolean("isError"))
        assertEquals("Already up to date", text(call("git_pull", JSONObject())))
    }
}
