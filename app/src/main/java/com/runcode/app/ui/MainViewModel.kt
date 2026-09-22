package com.runcode.app.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
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
import com.runcode.app.storage.EntryPointEffect
import com.runcode.app.storage.EntryPoints
import com.runcode.app.storage.FileKind
import com.runcode.app.storage.FileNode
import com.runcode.app.storage.ProjectStorage
import com.runcode.app.terminal.TerminalLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /** Process-wide resource snapshot, refreshed alongside device capabilities. */
    private val _processStats = MutableStateFlow(ProcessStats())
    val processStats: StateFlow<ProcessStats> = _processStats.asStateFlow()

    data class ProcessStats(
        val pid: Int = 0,
        val pssMb: Double = 0.0,
        val javaHeapMb: Double = 0.0,
        val heapLimitMb: Int = 0,
        val threads: Int = 0
    )

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
        viewModelScope.launch {
            app.projectChanges.collect { projectId -> onExternalProjectChange(projectId) }
        }
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
            val nodes = withContext(Dispatchers.IO) { app.projectStorage.listProjectFiles(projectId) }
            if (_selectedProject.value?.id == projectId) _projectFiles.value = nodes
        }
    }

    fun openFile(projectId: String, relativePath: String) {
        viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) {
                    app.projectStorage.inspect(projectId, relativePath, EDITOR_MAX_BYTES)
                }
                val name = File(relativePath).name
                when (info.kind) {
                    FileKind.DIRECTORY -> return@launch
                    FileKind.BINARY -> {
                        _userMessage.value = "$name is a binary file (${ProjectStorage.formatSize(info.size)}) and can't be " +
                            "edited as text. Use ⋮ → Save a copy to get it out."
                        return@launch
                    }
                    FileKind.TOO_LARGE -> {
                        _userMessage.value = "$name is ${ProjectStorage.formatSize(info.size)}, too large for the editor " +
                            "(limit ${ProjectStorage.formatSize(EDITOR_MAX_BYTES)}). Use ⋮ → Save a copy instead."
                        return@launch
                    }
                    FileKind.TEXT, FileKind.MISSING -> Unit
                }

                // Switching tabs used to drop unsaved edits silently. Save them instead.
                if (_isDirty.value && _activeTab.value != relativePath) {
                    persistCurrentFile(announce = false)
                }

                val content = withContext(Dispatchers.IO) { app.projectStorage.readFile(projectId, relativePath) }
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
        viewModelScope.launch {
            // Closing the tab you are typing in should not throw the typing away.
            if (_isDirty.value && _activeTab.value == tab) persistCurrentFile(announce = false)
            removeTab(tab)
        }
    }

    private fun removeTab(tab: String) {
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

    // ---------------------------------------------------------------- file manager

    /** Creates an empty file. [name] may contain folders, e.g. `utils/helpers.py`. */
    fun createNewFile(parentDir: String, name: String) {
        val project = _selectedProject.value ?: return
        viewModelScope.launch {
            try {
                val rel = withContext(Dispatchers.IO) {
                    val path = joinPath(parentDir, name)
                    if (app.projectStorage.resolveInProject(project.id, path).exists()) {
                        // This used to overwrite the existing file with an empty one.
                        throw IllegalArgumentException("'$path' already exists")
                    }
                    app.projectStorage.writeFileAtomically(project.id, path, "")
                    app.projectStorage.normalize(project.id, path)
                }
                refreshProjectFiles(project.id)
                openFile(project.id, rel)
            } catch (e: Exception) {
                _userMessage.value = "Create file failed: ${e.message}"
            }
        }
    }

    fun createFolder(parentDir: String, name: String) {
        val project = _selectedProject.value ?: return
        viewModelScope.launch {
            try {
                val rel = withContext(Dispatchers.IO) {
                    val path = joinPath(parentDir, name)
                    if (app.projectStorage.resolveInProject(project.id, path).exists()) {
                        throw IllegalArgumentException("'$path' already exists")
                    }
                    app.projectStorage.createDirectory(project.id, path)
                }
                refreshProjectFiles(project.id)
                _userMessage.value = "Created folder $rel"
            } catch (e: Exception) {
                _userMessage.value = "Create folder failed: ${e.message}"
            }
        }
    }

    fun renamePath(relPath: String, newName: String) {
        val to = try {
            joinPath(relPath.substringBeforeLast('/', ""), ProjectStorage.validateName(newName))
        } catch (e: IllegalArgumentException) {
            _userMessage.value = "Rename failed: ${e.message}"
            return
        }
        relocate(relPath, to, "Renamed to")
    }

    fun movePath(relPath: String, destinationDir: String) {
        val to = try {
            joinPath(destinationDir, File(relPath).name)
        } catch (e: IllegalArgumentException) {
            _userMessage.value = "Move failed: ${e.message}"
            return
        }
        relocate(relPath, to, "Moved to")
    }

    private fun relocate(from: String, to: String, verb: String) {
        val project = _selectedProject.value ?: return
        viewModelScope.launch {
            try {
                val (source, target) = withContext(Dispatchers.IO) {
                    val source = app.projectStorage.normalize(project.id, from)
                    source to app.projectStorage.movePath(project.id, source, to)
                }
                remapOpenTabs(project.id, source, target)
                val entryNote = applyEntryPointEffect(EntryPoints.follow(project, source, target))
                refreshProjectFiles(project.id)
                _userMessage.value = listOfNotNull("$verb $target", entryNote).joinToString(". ")
            } catch (e: Exception) {
                _userMessage.value = "${verb.substringBefore(' ')} failed: ${e.message}"
            }
        }
    }

    fun deletePath(relPath: String) {
        val project = _selectedProject.value ?: return
        viewModelScope.launch {
            try {
                val source = withContext(Dispatchers.IO) {
                    val source = app.projectStorage.normalize(project.id, relPath)
                    if (!app.projectStorage.deleteFile(project.id, source)) {
                        throw IllegalStateException("could not delete $source")
                    }
                    source
                }
                remapOpenTabs(project.id, source, null)
                val entryNote = applyEntryPointEffect(EntryPoints.follow(project, source, null))
                refreshProjectFiles(project.id)
                _userMessage.value = listOfNotNull("Deleted $source", entryNote).joinToString(". ")
            } catch (e: Exception) {
                _userMessage.value = "Delete failed: ${e.message}"
            }
        }
    }

    fun setEntryPoint(relPath: String) {
        val project = _selectedProject.value ?: return
        EntryPoints.problemWith(project, relPath)?.let {
            _userMessage.value = it
            return
        }
        viewModelScope.launch {
            val updated = project.copy(entryPoint = relPath.removePrefix("source/"), updatedAt = System.currentTimeMillis())
            saveProjectSettings(updated)
            _userMessage.value = "Entry point is now ${updated.entryPoint}"
        }
    }

    /** Copies files chosen in the system picker into [destinationDir]. */
    fun importFiles(uris: List<Uri>, destinationDir: String) {
        val project = _selectedProject.value ?: return
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val results = withContext(Dispatchers.IO) {
                uris.map { uri ->
                    runCatching {
                        val name = displayNameOf(uri) ?: uri.lastPathSegment ?: "imported_file"
                        app.contentResolver.openInputStream(uri)?.use { input ->
                            app.projectStorage.importFile(project.id, destinationDir, name, input)
                        } ?: throw IllegalStateException("could not open $name")
                    }
                }
            }
            refreshProjectFiles(project.id)
            val imported = results.mapNotNull { it.getOrNull() }
            val failures = results.mapNotNull { it.exceptionOrNull()?.message }
            _userMessage.value = when {
                failures.isEmpty() && imported.size == 1 -> "Imported ${imported.first()}"
                failures.isEmpty() -> "Imported ${imported.size} files into $destinationDir"
                else -> "Imported ${imported.size}, ${failures.size} failed: ${failures.first()}"
            }
        }
    }

    /** Writes one project file to a document the user created in the system picker. */
    fun exportFile(relPath: String, destination: Uri) {
        val project = _selectedProject.value ?: return
        viewModelScope.launch {
            try {
                if (_isDirty.value && _activeTab.value == relPath) persistCurrentFile(announce = false)
                withContext(Dispatchers.IO) {
                    app.projectStorage.openForExport(project.id, relPath).use { input ->
                        val output = app.contentResolver.openOutputStream(destination)
                            ?: throw IllegalStateException("the destination could not be opened")
                        output.use { input.copyTo(it) }
                    }
                }
                _userMessage.value = "Saved a copy of ${File(relPath).name}"
            } catch (e: Exception) {
                _userMessage.value = "Save a copy failed: ${e.message}"
            }
        }
    }

    fun exportProject(destination: Uri) {
        val project = _selectedProject.value ?: return
        viewModelScope.launch {
            try {
                if (_isDirty.value) persistCurrentFile(announce = false)
                withContext(Dispatchers.IO) {
                    val output = app.contentResolver.openOutputStream(destination)
                        ?: throw IllegalStateException("the destination could not be opened")
                    output.use { app.projectArchive.export(project, it) }
                }
                _userMessage.value = "Exported '${project.name}'. Secret values stay on this device."
            } catch (e: Exception) {
                _userMessage.value = "Export failed: ${e.message}"
            }
        }
    }

    /** [onImported] runs only on success, so a failed import does not navigate anywhere. */
    fun importProject(source: Uri, onImported: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                val fallbackName = displayNameOf(source)?.substringBeforeLast('.') ?: "Imported project"
                val project = withContext(Dispatchers.IO) {
                    val input = app.contentResolver.openInputStream(source)
                        ?: throw IllegalStateException("the file could not be opened")
                    input.use { app.projectArchive.import(it, fallbackName) }
                }
                app.appMetaDatabase.insertOrUpdateProject(project)
                _projects.value = app.appMetaDatabase.getAllProjects()
                selectProject(project)
                onImported()
                _userMessage.value = "Imported '${project.name}' (entry point ${project.entryPoint}). Read the code before running it."
            } catch (e: Exception) {
                _userMessage.value = "Import failed: ${e.message}"
            }
        }
    }

    /** Keeps open tabs pointing at files after a move, and drops tabs whose file is gone. */
    private fun remapOpenTabs(projectId: String, from: String, to: String?) {
        fun mapped(tab: String): String? = when {
            tab == from -> to
            tab.startsWith("$from/") -> to?.let { it + tab.removePrefix(from) }
            else -> tab
        }

        val active = _activeTab.value
        val tabs = _openTabs.value.mapNotNull { mapped(it) }.distinct()
        _openTabs.value = tabs
        if (active == null) return

        val newActive = mapped(active)
        if (newActive != null) {
            // Same buffer, new path: unsaved edits are kept and will be saved to the new location.
            _activeTab.value = newActive
            return
        }

        // The open file was deleted, and its unsaved edits with it.
        _isDirty.value = false
        undoStack.clear()
        redoStack.clear()
        val next = tabs.firstOrNull()
        if (next != null) {
            openFile(projectId, next)
        } else {
            _activeTab.value = null
            _editorContent.value = ""
        }
    }

    /** Returns a note for the user when the entry point moved or disappeared. */
    private suspend fun applyEntryPointEffect(effect: EntryPointEffect): String? = when (effect) {
        EntryPointEffect.Unaffected -> null
        is EntryPointEffect.Moved -> {
            saveProjectSettings(effect.project)
            "Entry point is now ${effect.project.entryPoint}"
        }
        EntryPointEffect.Lost -> "That was the entry point: pick a new one with ⋮ → Set as entry point before running"
    }

    private suspend fun saveProjectSettings(project: Project) {
        app.appMetaDatabase.insertOrUpdateProject(project)
        _projects.value = _projects.value.map { if (it.id == project.id) project else it }
        if (_selectedProject.value?.id == project.id) _selectedProject.value = project
    }

    /** Something outside the UI (the MCP bridge) changed a project's files or settings. */
    private suspend fun onExternalProjectChange(projectId: String) {
        val list = app.appMetaDatabase.getAllProjects()
        _projects.value = list
        val selected = _selectedProject.value ?: return
        if (selected.id != projectId) return
        val fresh = list.find { it.id == projectId } ?: return
        _selectedProject.value = fresh
        refreshProjectFiles(projectId)

        // Show an AI client's edit to the open file, unless there are unsaved edits here.
        val tab = _activeTab.value ?: return
        if (_isDirty.value) return
        val onDisk = withContext(Dispatchers.IO) {
            val info = app.projectStorage.inspect(projectId, tab, EDITOR_MAX_BYTES)
            if (info.kind == FileKind.TEXT) app.projectStorage.readFile(projectId, tab) else null
        } ?: return
        if (onDisk != _editorContent.value && !_isDirty.value && _activeTab.value == tab) {
            _editorContent.value = onDisk
            undoStack.clear()
            redoStack.clear()
        }
    }

    private fun displayNameOf(uri: Uri): String? = try {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    /** Joins a folder and a name that may itself contain folders, validating every segment. */
    private fun joinPath(parentDir: String, name: String): String {
        val segments = name.trim().trim('/').split('/').map { ProjectStorage.validateName(it) }
        val parent = parentDir.trim('/')
        return (if (parent.isEmpty()) segments else listOf(parent) + segments).joinToString("/")
    }

    fun createProject(
        name: String,
        profile: ProjectProfile,
        port: Int = 8080,
        maxCpuPercent: Int = 0,
        maxHeapMb: Int = 0,
        idleTimeoutMinutes: Int = 0
    ) {
        viewModelScope.launch {
            val project = app.projectStorage.createProjectFromTemplate(name, profile, port).copy(
                maxCpuPercent = maxCpuPercent,
                maxHeapMb = maxHeapMb,
                idleTimeoutMinutes = idleTimeoutMinutes
            )
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
            _processStats.value = withContext(Dispatchers.IO) {
                val monitor = app.processMonitor
                ProcessStats(
                    pid = monitor.pid(),
                    pssMb = monitor.processMemoryMb(),
                    javaHeapMb = monitor.javaHeapMb(),
                    heapLimitMb = monitor.heapLimitMb(app),
                    threads = monitor.threadCount()
                )
            }
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

        /** Above this the editor gets sluggish, and every undo step keeps a full copy. */
        const val EDITOR_MAX_BYTES = 512L * 1024
    }
}
