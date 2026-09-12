package com.runcode.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.runcode.app.RuncodeApp
import com.runcode.app.backup.BackupPreview
import com.runcode.app.domain.models.DeviceCapabilities
import com.runcode.app.domain.models.Project
import com.runcode.app.domain.models.ProjectProfile
import com.runcode.app.domain.models.QueryResult
import com.runcode.app.domain.models.RuntimeEvent
import com.runcode.app.domain.models.RuntimeInstance
import com.runcode.app.domain.models.TableInfo
import com.runcode.app.mcp.McpServerState
import com.runcode.app.runtime.PythonEngine
import com.runcode.app.storage.FileNode
import com.runcode.app.terminal.TerminalLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as RuncodeApp

    // Projects
    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = _projects.asStateFlow()

    private val _selectedProject = MutableStateFlow<Project?>(null)
    val selectedProject: StateFlow<Project?> = _selectedProject.asStateFlow()

    // File Tree & Editor
    private val _projectFiles = MutableStateFlow<List<FileNode>>(emptyList())
    val projectFiles: StateFlow<List<FileNode>> = _projectFiles.asStateFlow()

    private val _openTabs = MutableStateFlow<List<String>>(emptyList()) // relative file paths
    val openTabs: StateFlow<List<String>> = _openTabs.asStateFlow()

    private val _activeTab = MutableStateFlow<String?>(null)
    val activeTab: StateFlow<String?> = _activeTab.asStateFlow()

    private val _editorContent = MutableStateFlow("")
    val editorContent: StateFlow<String> = _editorContent.asStateFlow()

    private val _isDirty = MutableStateFlow(false)
    val isDirty: StateFlow<Boolean> = _isDirty.asStateFlow()

    // Undo / Redo history for active file
    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()

    // Supervisor & Instances
    val instances: StateFlow<Map<String, RuntimeInstance>> = app.serviceSupervisor.instances

    // Logs
    val logs: StateFlow<List<RuntimeEvent>> = app.logManager.eventsFlow

    // Database tools
    private val _discoveredDbs = MutableStateFlow<List<File>>(emptyList())
    val discoveredDbs: StateFlow<List<File>> = _discoveredDbs.asStateFlow()

    private val _selectedDb = MutableStateFlow<File?>(null)
    val selectedDb: StateFlow<File?> = _selectedDb.asStateFlow()

    private val _dbTables = MutableStateFlow<List<TableInfo>>(emptyList())
    val dbTables: StateFlow<List<TableInfo>> = _dbTables.asStateFlow()

    private val _queryResult = MutableStateFlow<QueryResult?>(null)
    val queryResult: StateFlow<QueryResult?> = _queryResult.asStateFlow()

    // Backups
    private val _backupList = MutableStateFlow<List<File>>(emptyList())
    val backupList: StateFlow<List<File>> = _backupList.asStateFlow()

    private val _backupPreview = MutableStateFlow<BackupPreview?>(null)
    val backupPreview: StateFlow<BackupPreview?> = _backupPreview.asStateFlow()

    // System Health
    private val _capabilities = MutableStateFlow<DeviceCapabilities?>(null)
    val capabilities: StateFlow<DeviceCapabilities?> = _capabilities.asStateFlow()

    // Terminal
    val terminalLines: StateFlow<List<TerminalLine>> = app.terminalSession.lines
    val terminalRunning: StateFlow<Boolean> = app.terminalSession.isRunning
    val terminalWorkingDir: StateFlow<String> = app.terminalSession.workingDirectory

    // MCP bridge
    val mcpState: StateFlow<McpServerState> = app.mcpServer.state

    private val _mcpToken = MutableStateFlow(app.mcpToken())
    val mcpToken: StateFlow<String> = _mcpToken.asStateFlow()

    private val _mcpAllowLan = MutableStateFlow(false)
    val mcpAllowLan: StateFlow<Boolean> = _mcpAllowLan.asStateFlow()

    private val _lastCrash = MutableStateFlow(app.lastCrashReport())
    val lastCrash: StateFlow<String?> = _lastCrash.asStateFlow()

    val isPythonAvailable: Boolean = app.isPythonAvailable
    val pythonVersion: String = if (app.isPythonAvailable) PythonEngine.pythonVersion() else "unavailable"

    // Messages & Alerts
    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage.asStateFlow()

    init {
        loadProjects()
        refreshCapabilities()
    }

    // ---------------------------------------------------------------- terminal

    fun startTerminal() {
        val project = _selectedProject.value
        val dir = project?.let { File(it.projectRoot) } ?: app.filesDir
        val env = project?.environment?.associate { it.key to app.secretStore.resolveValue(it.value) } ?: emptyMap()
        app.terminalSession.start(dir, env)
    }

    fun sendTerminalCommand(command: String) = app.terminalSession.send(command)

    fun stopTerminal() = app.terminalSession.stop()

    fun clearTerminal() = app.terminalSession.clear()

    // ---------------------------------------------------------------- MCP bridge

    fun setMcpAllowLan(allow: Boolean) {
        _mcpAllowLan.value = allow
        if (app.mcpServer.state.value.isRunning) {
            // Rebind so the change actually takes effect instead of silently waiting for a restart.
            app.mcpServer.stop()
            app.mcpServer.start(MCP_PORT, _mcpToken.value, allow)
            app.serviceSupervisor.setExternalHold(app.mcpServer.state.value.isRunning)
        }
    }

    fun toggleMcpServer() {
        if (app.mcpServer.state.value.isRunning) {
            app.mcpServer.stop()
            _userMessage.value = "MCP bridge stopped"
        } else {
            app.mcpServer.start(MCP_PORT, _mcpToken.value, _mcpAllowLan.value)
            val state = app.mcpServer.state.value
            _userMessage.value = state.lastError?.let { "MCP bridge failed: $it" }
                ?: "MCP bridge listening on ${state.boundAddress}:${state.port}"
        }
        // Hold the process in the foreground while the bridge is up, otherwise Android
        // reclaims it as soon as the user leaves the app and the client loses its server.
        app.serviceSupervisor.setExternalHold(app.mcpServer.state.value.isRunning)
    }

    fun regenerateMcpToken() {
        _mcpToken.value = app.regenerateMcpToken()
        if (app.mcpServer.state.value.isRunning) {
            app.mcpServer.stop()
            app.mcpServer.start(MCP_PORT, _mcpToken.value, _mcpAllowLan.value)
        }
        _userMessage.value = "New MCP token generated. Existing clients must be updated."
    }

    fun mcpLanAddress(): String = app.portManager.getLanIp()

    fun dismissCrashReport() {
        app.clearCrashReport()
        _lastCrash.value = null
    }

    fun loadProjects() {
        viewModelScope.launch {
            val list = app.appMetaDatabase.getAllProjects()
            _projects.value = list
            if (_selectedProject.value == null && list.isNotEmpty()) {
                selectProject(list.first())
            }
        }
    }

    fun selectProject(project: Project) {
        _selectedProject.value = project
        refreshProjectFiles(project.id)
        refreshDatabases(project)
        refreshBackups(project.id)

        // Set default open file to entrypoint
        val entry = "source/${project.entryPoint}"
        openFile(project.id, entry)
    }

    fun refreshProjectFiles(projectId: String) {
        viewModelScope.launch {
            val nodes = app.projectStorage.listProjectFiles(projectId)
            _projectFiles.value = nodes
        }
    }

    fun openFile(projectId: String, relativePath: String) {
        viewModelScope.launch {
            try {
                val content = app.projectStorage.readFile(projectId, relativePath)
                val currentTabs = _openTabs.value.toMutableList()
                if (!currentTabs.contains(relativePath)) {
                    currentTabs.add(relativePath)
                    _openTabs.value = currentTabs
                }
                _activeTab.value = relativePath
                _editorContent.value = content
                _isDirty.value = false
                undoStack.clear()
                redoStack.clear()
            } catch (e: Exception) {
                _userMessage.value = "Failed to open file: ${e.message}"
            }
        }
    }

    fun closeTab(tab: String) {
        val currentTabs = _openTabs.value.toMutableList()
        val index = currentTabs.indexOf(tab)
        if (index != -1) {
            currentTabs.removeAt(index)
            _openTabs.value = currentTabs
            if (_activeTab.value == tab) {
                val nextTab = currentTabs.getOrNull(index.coerceAtMost(currentTabs.size - 1))
                if (nextTab != null) {
                    _selectedProject.value?.let { openFile(it.id, nextTab) }
                } else {
                    _activeTab.value = null
                    _editorContent.value = ""
                    _isDirty.value = false
                }
            }
        }
    }

    fun updateEditorContent(newText: String) {
        if (newText != _editorContent.value) {
            undoStack.addLast(_editorContent.value)
            if (undoStack.size > 50) undoStack.removeFirst()
            redoStack.clear()
            _editorContent.value = newText
            _isDirty.value = true
        }
    }

    fun undo() {
        if (undoStack.isNotEmpty()) {
            val previous = undoStack.removeLast()
            redoStack.addLast(_editorContent.value)
            _editorContent.value = previous
            _isDirty.value = true
        }
    }

    fun redo() {
        if (redoStack.isNotEmpty()) {
            val next = redoStack.removeLast()
            undoStack.addLast(_editorContent.value)
            _editorContent.value = next
            _isDirty.value = true
        }
    }

    fun saveCurrentFile() {
        viewModelScope.launch { persistCurrentFile(announce = true) }
    }

    /**
     * Writes the editor buffer to disk and only returns once it is there, so callers that are
     * about to execute the file cannot race ahead of the save.
     */
    private suspend fun persistCurrentFile(announce: Boolean) {
        val project = _selectedProject.value ?: return
        val tab = _activeTab.value ?: return
        try {
            app.projectStorage.writeFileAtomically(project.id, tab, _editorContent.value)
            _isDirty.value = false
            if (announce) _userMessage.value = "Saved ${File(tab).name}"
            refreshProjectFiles(project.id)
        } catch (e: Exception) {
            _userMessage.value = "Save error: ${e.message}"
        }
    }

    fun createNewFile(name: String) {
        val project = _selectedProject.value ?: return
        viewModelScope.launch {
            try {
                val rel = "source/$name"
                app.projectStorage.writeFileAtomically(project.id, rel, "")
                refreshProjectFiles(project.id)
                openFile(project.id, rel)
            } catch (e: Exception) {
                _userMessage.value = "Create file error: ${e.message}"
            }
        }
    }

    fun deleteCurrentFile(relPath: String) {
        val project = _selectedProject.value ?: return
        viewModelScope.launch {
            try {
                app.projectStorage.deleteFile(project.id, relPath)
                closeTab(relPath)
                refreshProjectFiles(project.id)
                _userMessage.value = "Deleted ${File(relPath).name}"
            } catch (e: Exception) {
                _userMessage.value = "Delete error: ${e.message}"
            }
        }
    }

    fun createProject(name: String, profile: ProjectProfile, port: Int = 8080) {
        viewModelScope.launch {
            val project = app.projectStorage.createProjectFromTemplate(name, profile, port)
            app.appMetaDatabase.insertOrUpdateProject(project)
            loadProjects()
            selectProject(project)
            _userMessage.value = "Created project '${project.name}'"
        }
    }

    fun deleteProject(project: Project) {
        viewModelScope.launch {
            app.serviceSupervisor.stopProject(project.id)
            app.projectStorage.deleteProject(project.id)
            app.appMetaDatabase.deleteProject(project.id)
            loadProjects()
            _userMessage.value = "Project '${project.name}' deleted."
        }
    }

    // Supervisor controls
    fun runProject(project: Project) {
        viewModelScope.launch {
            if (_isDirty.value) {
                persistCurrentFile(announce = false)
            }
            val started = app.serviceSupervisor.startProject(project)
            if (!started) {
                _userMessage.value = "Could not start service. Check console logs."
            }
        }
    }

    fun stopProject(projectId: String) {
        viewModelScope.launch {
            app.serviceSupervisor.stopProject(projectId)
        }
    }

    fun restartProject(project: Project) {
        viewModelScope.launch {
            app.serviceSupervisor.restartProject(project)
        }
    }

    // Database actions
    fun refreshDatabases(project: Project) {
        viewModelScope.launch {
            val dbs = app.projectDatabaseManager.discoverDatabases(project)
            _discoveredDbs.value = dbs
            if (dbs.isNotEmpty()) {
                selectDatabase(dbs.first())
            } else {
                _selectedDb.value = null
                _dbTables.value = emptyList()
                _queryResult.value = null
            }
        }
    }

    fun selectDatabase(file: File) {
        _selectedDb.value = file
        viewModelScope.launch {
            val tables = app.projectDatabaseManager.getTables(file)
            _dbTables.value = tables
            if (tables.isNotEmpty()) {
                executeSql("SELECT * FROM `${tables.first().name}` LIMIT 25")
            } else {
                _queryResult.value = null
            }
        }
    }

    fun executeSql(sql: String) {
        val db = _selectedDb.value ?: return
        viewModelScope.launch {
            val res = app.projectDatabaseManager.executeQuery(db, sql)
            _queryResult.value = res
        }
    }

    // Backup actions
    fun refreshBackups(projectId: String) {
        viewModelScope.launch {
            val dir = app.projectStorage.getBackupsDir(projectId)
            _backupList.value = dir.listFiles { _, name -> name.endsWith(".rcpkg") }?.sortedByDescending { it.lastModified() } ?: emptyList()
        }
    }

    fun createBackup(project: Project) {
        viewModelScope.launch {
            try {
                val file = app.backupManager.createProjectBackup(project)
                refreshBackups(project.id)
                _userMessage.value = "Backup created: ${file.name} (${file.length() / 1024} KB)"
            } catch (e: Exception) {
                _userMessage.value = "Backup error: ${e.message}"
            }
        }
    }

    fun verifyBackup(file: File) {
        viewModelScope.launch {
            val preview = app.backupManager.verifyBackup(file)
            _backupPreview.value = preview
        }
    }

    fun restoreBackup(file: File, newName: String? = null) {
        viewModelScope.launch {
            try {
                val restored = app.backupManager.restoreProjectFromBackup(file, newName)
                app.appMetaDatabase.insertOrUpdateProject(restored)
                loadProjects()
                selectProject(restored)
                _userMessage.value = "Successfully restored project '${restored.name}'"
            } catch (e: Exception) {
                _userMessage.value = "Restore failed: ${e.message}"
            }
        }
    }

    fun refreshCapabilities() {
        viewModelScope.launch {
            val runningCount = app.serviceSupervisor.instances.value.values.count { it.isRunning }
            val caps = app.compatibilityManager.getCapabilities(runningCount, app.serviceSupervisor.isAnyRunning())
            _capabilities.value = caps
        }
    }

    fun clearMessage() {
        _userMessage.value = null
    }

    fun clearLogs(projectId: String? = null) {
        if (projectId != null) {
            app.logManager.clearProject(projectId)
        } else {
            app.logManager.clear()
        }
    }

    private companion object {
        const val MCP_PORT = 8765
    }
}
