package com.runcode.app

import android.app.Application
import com.chaquo.python.Python
import com.runcode.app.backup.BackupManager
import com.runcode.app.database.AppMetaDatabase
import com.runcode.app.database.ProjectDatabaseManager
import com.runcode.app.domain.models.LogLevel
import com.runcode.app.domain.models.ProjectProfile
import com.runcode.app.logging.LogManager
import com.runcode.app.mcp.McpServer
import com.runcode.app.mcp.McpToolHost
import com.runcode.app.network.PortManager
import com.runcode.app.runtime.PythonEngine
import com.runcode.app.runtime.RuntimeRegistry
import com.runcode.app.runtime.StaticWebEngine
import com.runcode.app.security.SecretStore
import com.runcode.app.storage.ProjectStorage
import com.runcode.app.supervisor.ServiceSupervisor
import com.runcode.app.system.CompatibilityManager
import com.runcode.app.terminal.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.security.SecureRandom

class RuncodeApp : Application() {

    lateinit var projectStorage: ProjectStorage
        private set
    lateinit var portManager: PortManager
        private set
    lateinit var secretStore: SecretStore
        private set
    lateinit var logManager: LogManager
        private set
    lateinit var appMetaDatabase: AppMetaDatabase
        private set
    lateinit var projectDatabaseManager: ProjectDatabaseManager
        private set
    lateinit var backupManager: BackupManager
        private set
    lateinit var compatibilityManager: CompatibilityManager
        private set
    lateinit var runtimeRegistry: RuntimeRegistry
        private set
    lateinit var serviceSupervisor: ServiceSupervisor
        private set
    lateinit var terminalSession: TerminalSession
        private set
    lateinit var mcpServer: McpServer
        private set

    var isPythonAvailable: Boolean = false
        private set

    override fun onCreate() {
        super.onCreate()

        projectStorage = ProjectStorage(this)
        portManager = PortManager()
        secretStore = SecretStore(this)
        logManager = LogManager(this)
        appMetaDatabase = AppMetaDatabase(this)
        projectDatabaseManager = ProjectDatabaseManager()
        backupManager = BackupManager(this, projectStorage)
        compatibilityManager = CompatibilityManager(this)
        terminalSession = TerminalSession(this)

        // The embedded CPython has to be started once per process, before any engine uses it.
        isPythonAvailable = PythonEngine.ensureStarted(this)

        val pythonEngine = PythonEngine(this, secretStore)
        val staticWebEngine = StaticWebEngine(portManager)
        runtimeRegistry = RuntimeRegistry(pythonEngine, staticWebEngine)

        serviceSupervisor = ServiceSupervisor(
            context = this,
            runtimeRegistry = runtimeRegistry,
            logManager = logManager,
            portManager = portManager
        )

        mcpServer = McpServer(
            tools = McpToolHost(
                projectStorage = projectStorage,
                appMetaDatabase = appMetaDatabase,
                projectDatabaseManager = projectDatabaseManager,
                serviceSupervisor = serviceSupervisor,
                logManager = logManager,
                terminalSession = terminalSession,
                runPython = ::runPythonSnippet
            ),
            onLog = { level, message -> logManager.log(MCP_LOG_ID, "MCP Bridge", level, message) }
        )

        CoroutineScope(Dispatchers.IO).launch {
            seedStarterProjectsIfEmpty()
        }
    }

    /** Bearer token for the MCP bridge. Generated once and kept in the Keystore-backed vault. */
    fun mcpToken(): String {
        secretStore.getSecret(MCP_TOKEN_KEY)?.let { return it }
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        return if (secretStore.setSecret(MCP_TOKEN_KEY, token)) {
            token
        } else {
            logManager.log(MCP_LOG_ID, "MCP Bridge", LogLevel.ERROR, "Could not persist the access token to the vault.")
            token
        }
    }

    fun regenerateMcpToken(): String {
        secretStore.removeSecret(MCP_TOKEN_KEY)
        return mcpToken()
    }

    private fun runPythonSnippet(code: String, workingDir: File): String {
        if (!Python.isStarted()) return "Embedded Python interpreter is not available on this device."
        return try {
            Python.getInstance()
                .getModule("runcode_runner")
                .callAttr("run_snippet", code, workingDir.absolutePath)
                .toString()
        } catch (e: Throwable) {
            "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private suspend fun seedStarterProjectsIfEmpty() {
        if (appMetaDatabase.getAllProjects().isNotEmpty()) return

        listOf(
            Triple("Python Quickstart", ProjectProfile.PYTHON_SCRIPT, 8080),
            Triple("Telegram Bot Starter", ProjectProfile.TELEGRAM_BOT, 8080),
            Triple("Local Web Dashboard", ProjectProfile.STATIC_WEB, 8080),
            Triple("Tasks SQLite App", ProjectProfile.SQLITE_APP, 8080)
        ).forEach { (name, profile, port) ->
            appMetaDatabase.insertOrUpdateProject(
                projectStorage.createProjectFromTemplate(name, profile, customPort = port)
            )
        }
    }

    private companion object {
        const val MCP_TOKEN_KEY = "MCP_BRIDGE_TOKEN"
        const val MCP_LOG_ID = "__mcp__"
    }
}
