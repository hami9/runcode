package com.runcode.app

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import androidx.annotation.VisibleForTesting
import com.chaquo.python.Python
import com.runcode.app.backup.BackupFolderSettings
import com.runcode.app.backup.BackupManager
import com.runcode.app.backup.FolderBackups
import com.runcode.app.database.AppMetaDatabase
import com.runcode.app.database.ProjectDatabaseManager
import com.runcode.app.diagnostics.DiagnosticsRunner
import com.runcode.app.git.ChaquopyGitBackend
import com.runcode.app.git.GitBackend
import com.runcode.app.git.GitManager
import com.runcode.app.domain.models.LogLevel
import com.runcode.app.domain.models.Project
import com.runcode.app.domain.models.ProjectProfile
import com.runcode.app.logging.LogManager
import com.runcode.app.mcp.McpServer
import com.runcode.app.mcp.McpToolHost
import com.runcode.app.mcp.McpTunnel
import com.runcode.app.mcp.SshTunnelConnector
import com.runcode.app.network.PortManager
import com.runcode.app.runtime.PythonEngine
import com.runcode.app.runtime.RuntimeRegistry
import com.runcode.app.runtime.StaticWebEngine
import com.runcode.app.security.SecretStore
import com.runcode.app.settings.ProjectSettingsManager
import com.runcode.app.storage.ProjectArchive
import com.runcode.app.storage.ProjectStorage
import com.runcode.app.supervisor.ServiceSupervisor
import com.runcode.app.system.CompatibilityManager
import com.runcode.app.system.CrashReporter
import com.runcode.app.system.ProcessMonitor
import com.runcode.app.terminal.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.delay
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
    lateinit var backupFolder: BackupFolderSettings
        private set
    lateinit var folderBackups: FolderBackups
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
    lateinit var mcpTunnel: McpTunnel
        private set

    val diagnostics: DiagnosticsRunner by lazy { DiagnosticsRunner(this) }

    /** Tests swap this for the host's Python before first use of [git]. */
    @VisibleForTesting
    var gitBackend: GitBackend = ChaquopyGitBackend()

    val git: GitManager by lazy {
        GitManager(gitBackend, projectStorage, projectArchive, secretStore, getSharedPreferences("git", MODE_PRIVATE))
    }
    lateinit var processMonitor: ProcessMonitor
        private set
    lateinit var projectArchive: ProjectArchive
        private set
    lateinit var projectSettings: ProjectSettingsManager
        private set

    private val _projectChanges = MutableSharedFlow<String>(extraBufferCapacity = 64)

    /**
     * Ids of projects changed by something other than the UI, i.e. the MCP bridge, so the
     * editor and file tree can show an AI client's edits without a manual refresh.
     */
    val projectChanges: SharedFlow<String> = _projectChanges

    /** Something other than the editor rewrote a project's files (MCP, git pull, checkout). */
    fun notifyProjectChanged(projectId: String) {
        _projectChanges.tryEmit(projectId)
    }

    var isPythonAvailable: Boolean = false
        private set

    override fun onCreate() {
        super.onCreate()

        // Install first: a crash during the rest of startup is exactly the one worth keeping.
        CrashReporter.install(this)

        projectStorage = ProjectStorage(this)
        portManager = PortManager()
        secretStore = SecretStore(this)
        secretStore.getAllSecretKeys().forEach { secretStore.getSecret(it) }
        logManager = LogManager(this)
        appMetaDatabase = AppMetaDatabase(this)
        projectDatabaseManager = ProjectDatabaseManager()
        backupManager = BackupManager(this, projectStorage)
        backupFolder = BackupFolderSettings(this)
        folderBackups = FolderBackups(backupManager, cacheDir)
        projectArchive = ProjectArchive(this, projectStorage)
        compatibilityManager = CompatibilityManager(this)
        terminalSession = TerminalSession(this)
        processMonitor = ProcessMonitor()

        // The embedded CPython has to be started once per process, before any engine uses it.
        isPythonAvailable = PythonEngine.ensureStarted(this)

        val pythonEngine = PythonEngine(this, secretStore, processMonitor)
        val staticWebEngine = StaticWebEngine(portManager, processMonitor)
        runtimeRegistry = RuntimeRegistry(pythonEngine, staticWebEngine)

        serviceSupervisor = ServiceSupervisor(
            context = this,
            runtimeRegistry = runtimeRegistry,
            logManager = logManager,
            portManager = portManager
        )

        projectSettings = ProjectSettingsManager(
            secretStore,
            appMetaDatabase::getProjectById,
            { appMetaDatabase.insertOrUpdateProject(it) },
            serviceSupervisor::hasActiveWork
        )

        mcpServer = McpServer(
            tools = McpToolHost(
                projectStorage = projectStorage,
                appMetaDatabase = appMetaDatabase,
                projectDatabaseManager = projectDatabaseManager,
                serviceSupervisor = serviceSupervisor,
                logManager = logManager,
                terminalSession = terminalSession,
                runPython = ::runPythonSnippet,
                projectSettings = projectSettings,
                onProjectChanged = { projectId -> _projectChanges.tryEmit(projectId) },
                runDiagnostics = { diagnostics.run().toText() },
                backupProject = ::backupProjectForMcp,
                git = { git }
            ),
            onLog = { level, message -> logManager.log(MCP_LOG_ID, "MCP Bridge", level, message) }
        )
        mcpTunnel = McpTunnel(
            connector = SshTunnelConnector(),
            onLog = { level, message -> logManager.log(MCP_LOG_ID, "MCP Bridge", level, message) }
        )
        watchDefaultNetwork()

        CoroutineScope(Dispatchers.IO).launch {
            seedStarterProjectsIfEmpty()
        }
        // Daily backups only happen while the process is alive; the switch in the UI says so.
        CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                runAutoBackupIfDue()
                delay(AUTO_BACKUP_CHECK_MS)
            }
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

    private suspend fun backupProjectForMcp(project: Project, toFolder: Boolean): String {
        if (!toFolder) {
            val file = backupManager.createProjectBackup(project)
            return "Created ${file.name} (${file.length()} bytes) in the project's backups/ folder."
        }
        val store = backupFolder.store()
            ?: throw IllegalStateException("No backup folder is chosen. Pick one under Backups in the app.")
        val stored = folderBackups.export(store, project)
        return "Created a local backup and copied it to ${backupFolder.state.value.label} as ${stored.name}; the copy was read back and verified."
    }

    /** Backs every project up to the chosen folder when the daily run is switched on and due. */
    suspend fun runAutoBackupIfDue() {
        val now = System.currentTimeMillis()
        if (!backupFolder.isAutoDue(now)) return
        val store = backupFolder.store()
        val result = if (store == null) {
            "Skipped: the backup folder is no longer accessible. Choose it again."
        } else {
            val projects = appMetaDatabase.getAllProjects()
            val (done, error) = folderBackups.exportAll(store, projects)
            if (error == null) "Backed up $done project(s)" else "Backed up $done of ${projects.size}. $error"
        }
        backupFolder.recordAutoRun(now, result)
        logManager.log(BACKUP_LOG_ID, "Backups", LogLevel.SYSTEM, "Automatic backup: $result")
    }

    /**
     * Reconnects the public tunnel when the default network changes, e.g. Wi-Fi to mobile
     * data or a VPN coming up or reconnecting, instead of waiting for keepalives to time out.
     */
    private fun watchDefaultNetwork() {
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return
        try {
            connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                // The first call reports the network at registration, not a change.
                private var registered = false

                override fun onAvailable(network: Network) {
                    if (registered) mcpTunnel.onNetworkChanged()
                    registered = true
                }
            })
        } catch (e: RuntimeException) {
            logManager.log(MCP_LOG_ID, "MCP Bridge", LogLevel.WARN, "Network changes are not tracked: ${e.message}")
        }
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

    fun lastCrashReport(): String? = CrashReporter.lastCrash(this)

    fun clearCrashReport() = CrashReporter.clear(this)

    private companion object {
        const val MCP_TOKEN_KEY = "MCP_BRIDGE_TOKEN"
        const val MCP_LOG_ID = "__mcp__"
        const val BACKUP_LOG_ID = "__backup__"
        const val AUTO_BACKUP_CHECK_MS = 60L * 60L * 1000L
    }
}
