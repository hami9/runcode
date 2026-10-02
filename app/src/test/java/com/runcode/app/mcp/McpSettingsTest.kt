package com.runcode.app.mcp

import com.runcode.app.RuncodeApp
import com.runcode.app.domain.models.EnvironmentVariable
import com.runcode.app.domain.models.Project
import com.runcode.app.domain.models.ProjectProfile
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = RuncodeApp::class)
class McpSettingsTest {
    private lateinit var app: RuncodeApp
    private lateinit var host: McpToolHost
    private lateinit var project: Project
    private val notifications = mutableListOf<String>()

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication() as RuncodeApp
        project = app.projectStorage.createProjectFromTemplate("MCP settings", ProjectProfile.PYTHON_SCRIPT).copy(
            environment = listOf(EnvironmentVariable("TOKEN", "\${SEC_PROJECT_test_reference}", true),
                EnvironmentVariable("OLD", "old", false))
        )
        runBlocking { app.appMetaDatabase.insertOrUpdateProject(project) }
        host = McpToolHost(app.projectStorage, app.appMetaDatabase, app.projectDatabaseManager,
            app.serviceSupervisor, app.logManager, app.terminalSession, { _, _ -> "unused" },
            app.projectSettings, { notifications.add(it) }, { "diagnostics report" })
    }

    @Test fun `mcp replaces plain variables but preserves secret references`() = runBlocking {
        val result = McpTools.call(host, "update_project_settings", JSONObject()
            .put("project_id", project.id).put("port", 9123)
            .put("environment", JSONObject().put("NEW", "hello")))
        assertFalse(result.getBoolean("isError"))
        val updated = app.appMetaDatabase.getProjectById(project.id)!!
        assertEquals(9123, updated.network.port)
        assertEquals(project.environment.first(), updated.environment.first())
        assertEquals(listOf("TOKEN", "NEW"), updated.environment.map { it.key })
        assertEquals(listOf(project.id), notifications)
        val detail = McpTools.call(host, "get_project", JSONObject().put("project_id", project.id))
        assertFalse(detail.toString().contains("PROJECT_test_reference"))
    }

    @Test fun `mcp cannot overwrite a secret by reusing its name`() {
        val result = McpTools.call(host, "update_project_settings", JSONObject()
            .put("project_id", project.id).put("environment", JSONObject().put("TOKEN", "replacement")))
        assertTrue(result.getBoolean("isError"))
        assertTrue(notifications.isEmpty())
    }

    @Test fun `mcp rejects fractional ports instead of truncating them`() {
        val result = McpTools.call(host, "update_project_settings", JSONObject()
            .put("project_id", project.id).put("port", 8080.5))
        assertTrue(result.getBoolean("isError"))
        assertTrue(notifications.isEmpty())
    }
}
