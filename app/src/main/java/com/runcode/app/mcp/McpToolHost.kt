package com.runcode.app.mcp

import com.runcode.app.database.AppMetaDatabase
import com.runcode.app.database.ProjectDatabaseManager
import com.runcode.app.domain.models.Project
import com.runcode.app.logging.LogManager
import com.runcode.app.storage.ProjectStorage
import com.runcode.app.supervisor.ServiceSupervisor
import com.runcode.app.terminal.TerminalSession
import java.io.File

/**
 * Everything the MCP tools are allowed to reach. Keeping it to one surface makes the blast
 * radius of the bridge explicit: this is exactly what an AI client can do to the device.
 */
class McpToolHost(
    val projectStorage: ProjectStorage,
    val appMetaDatabase: AppMetaDatabase,
    val projectDatabaseManager: ProjectDatabaseManager,
    val serviceSupervisor: ServiceSupervisor,
    val logManager: LogManager,
    val terminalSession: TerminalSession,
    val runPython: (code: String, workingDir: File) -> String
) {
    suspend fun projectOrNull(projectId: String): Project? =
        appMetaDatabase.getAllProjects().find { it.id == projectId }

    fun projectDir(project: Project): File = File(project.projectRoot)
}
