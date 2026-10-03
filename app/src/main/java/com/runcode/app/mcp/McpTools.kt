package com.runcode.app.mcp

import com.runcode.app.domain.models.Project
import com.runcode.app.git.GitException
import com.runcode.app.domain.models.RestartPolicy
import com.runcode.app.security.SecretRedactor
import com.runcode.app.settings.EnvironmentEdit
import com.runcode.app.settings.ProjectSettings
import com.runcode.app.storage.EntryPointEffect
import com.runcode.app.storage.EntryPoints
import com.runcode.app.storage.FileKind
import com.runcode.app.storage.FileNode
import com.runcode.app.storage.ProjectStorage
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The MCP tool catalogue and its dispatcher.
 *
 * Tool results follow the MCP content shape: `{ content: [{type:"text", text:"..."}], isError }`.
 * Handlers run on the HTTP worker thread, so the suspending supervisor calls are bridged with
 * runBlocking rather than leaking coroutine scope into the transport.
 */
object McpTools {

    fun descriptors(): JSONArray {
        val tools = JSONArray()

        tools.put(tool(
            "update_project_settings",
            "Update a stopped project's settings. environment replaces only plain variables; secrets are preserved and managed in the app.",
            properties(
                "project_id" to stringProp("Project id"),
                "port" to intProp("Port from 1024 to 65535"),
                "restart_policy" to stringProp("NEVER, ON_FAILURE or ALWAYS"),
                "start_on_boot" to JSONObject().put("type", "boolean"),
                "max_cpu_percent" to intProp("0 (unlimited) to 100"),
                "max_heap_mb" to intProp("Process-wide Java heap limit; 0 means unlimited"),
                "idle_timeout_minutes" to intProp("Static web idle timeout; 0 means unlimited"),
                "environment" to JSONObject().put("type", "object")
                    .put("additionalProperties", JSONObject().put("type", "string"))
            ), required = listOf("project_id")
        ))

        tools.put(
            tool(
                "list_projects",
                "List every project on the device with its id, profile, entrypoint, port and current service state.",
                JSONObject()
            )
        )

        tools.put(
            tool(
                "get_project",
                "Full detail for one project, including environment variables and restart policy.",
                properties("project_id" to stringProp("Project id from list_projects")),
                required = listOf("project_id")
            )
        )

        tools.put(
            tool(
                "list_files",
                "List every file and folder in a project as paths relative to the project root. " +
                    "Folders end in '/'. Code lives in source/, the app's databases and outputs in data/.",
                properties("project_id" to stringProp("Project id")),
                required = listOf("project_id")
            )
        )

        tools.put(
            tool(
                "read_file",
                "Read a text file inside a project. Paths are relative to the project root, e.g. source/main.py. " +
                    "Binary files and files over 1 MB are refused with their size.",
                properties(
                    "project_id" to stringProp("Project id"),
                    "path" to stringProp("Path relative to the project root")
                ),
                required = listOf("project_id", "path")
            )
        )

        tools.put(
            tool(
                "write_file",
                "Create or overwrite a text file inside a project. Writes atomically.",
                properties(
                    "project_id" to stringProp("Project id"),
                    "path" to stringProp("Path relative to the project root"),
                    "content" to stringProp("Full new file content")
                ),
                required = listOf("project_id", "path", "content")
            )
        )

        tools.put(
            tool(
                "create_directory",
                "Create a folder (and any missing parents) inside a project.",
                properties(
                    "project_id" to stringProp("Project id"),
                    "path" to stringProp("Folder path relative to the project root, e.g. source/utils")
                ),
                required = listOf("project_id", "path")
            )
        )

        tools.put(
            tool(
                "rename_path",
                "Rename or move a file or folder inside a project. Refuses to overwrite. If the project's " +
                    "entry point moves, the project is updated to follow it.",
                properties(
                    "project_id" to stringProp("Project id"),
                    "from" to stringProp("Current path relative to the project root"),
                    "to" to stringProp("New path relative to the project root")
                ),
                required = listOf("project_id", "from", "to")
            )
        )

        tools.put(
            tool(
                "delete_path",
                "Delete a file, or a folder and everything in it. The top-level source/, data/, config/, " +
                    "logs/, cache/ and backups/ folders themselves cannot be deleted.",
                properties(
                    "project_id" to stringProp("Project id"),
                    "path" to stringProp("Path relative to the project root")
                ),
                required = listOf("project_id", "path")
            )
        )

        tools.put(
            tool(
                "set_entry_point",
                "Choose which file in source/ a project runs. Python profiles need a .py file.",
                properties(
                    "project_id" to stringProp("Project id"),
                    "path" to stringProp("Path relative to the project root, e.g. source/main.py")
                ),
                required = listOf("project_id", "path")
            )
        )

        tools.put(
            tool(
                "start_service",
                "Start the supervised runtime for a project (Python script/bot or static web server).",
                properties("project_id" to stringProp("Project id")),
                required = listOf("project_id")
            )
        )

        tools.put(
            tool(
                "stop_service",
                "Stop the supervised runtime for a project.",
                properties("project_id" to stringProp("Project id")),
                required = listOf("project_id")
            )
        )

        tools.put(
            tool(
                "service_status",
                "State, uptime, port and restart count for every known service.",
                JSONObject()
            )
        )

        tools.put(
            tool(
                "get_logs",
                "Recent runtime log lines. Secrets are redacted before they reach you.",
                properties(
                    "project_id" to stringProp("Optional: restrict to one project"),
                    "limit" to intProp("How many of the most recent lines to return (default 100)")
                )
            )
        )

        tools.put(
            tool(
                "run_command",
                "Run a shell command in the app sandbox and return its combined output. No root; " +
                    "the working directory defaults to the project root when project_id is given.",
                properties(
                    "command" to stringProp("Shell command line"),
                    "project_id" to stringProp("Optional: run inside this project's directory"),
                    "timeout_ms" to intProp("Timeout in milliseconds (default 30000, max 120000)")
                ),
                required = listOf("command")
            )
        )

        tools.put(
            tool(
                "run_python",
                "Execute a Python snippet on the embedded CPython interpreter and return stdout/stderr.",
                properties(
                    "code" to stringProp("Python source to execute"),
                    "project_id" to stringProp("Optional: run with this project's directory as cwd")
                ),
                required = listOf("code")
            )
        )

        tools.put(
            tool(
                "sql_query",
                "Run SQL against a project's SQLite database. SELECT/PRAGMA/EXPLAIN open read-only; " +
                    "anything else opens read-write and modifies data.",
                properties(
                    "project_id" to stringProp("Project id"),
                    "sql" to stringProp("SQL statement"),
                    "database" to stringProp("Optional: database file name; defaults to the first one found")
                ),
                required = listOf("project_id", "sql")
            )
        )

        tools.put(
            tool(
                "backup_project",
                "Create a checksummed backup of a project's source/ and data/ (never secrets). With to_folder, " +
                    "also copy it to the backup folder chosen in the app and verify the copy.",
                properties(
                    "project_id" to stringProp("Project id"),
                    "to_folder" to JSONObject().put("type", "boolean")
                ),
                required = listOf("project_id")
            )
        )

        tools.put(
            tool(
                "debug_start",
                "Run a Python project under the debugger. breakpoints is a list of {path, line} with paths " +
                    "relative to the project root (source/main.py) and 1-based lines; each listed file's breakpoints " +
                    "replace its previous ones. Waits up to wait_ms (default 10000) for the first pause or the end " +
                    "and returns the debugger state: file, line, stack, locals, globals.",
                properties(
                    "project_id" to stringProp("Project id"),
                    "breakpoints" to JSONObject().put("type", "array").put("items", JSONObject().put("type", "object")
                        .put("properties", JSONObject().put("path", stringProp("source/…")).put("line", intProp("1-based line")))),
                    "wait_ms" to intProp("How long to wait for a pause, up to 60000")
                ),
                required = listOf("project_id")
            )
        )

        tools.put(
            tool(
                "debug_control",
                "Drive a paused debug run: continue, step (into), next (over), return (out) or stop. Waits up to " +
                    "wait_ms (default 10000) for the next pause or the end and returns the debugger state.",
                properties(
                    "command" to stringProp("continue, step, next, return or stop"),
                    "wait_ms" to intProp("How long to wait, up to 60000")
                ),
                required = listOf("command")
            )
        )

        tools.put(tool("debug_status", "The current debugger state: idle, starting, running, paused (with file, line, stack and variables) or finished (with the outcome).", JSONObject()))

        tools.put(
            tool(
                "debug_eval",
                "Evaluate a Python expression in the paused frame and return its type and value.",
                properties("expression" to stringProp("A Python expression")),
                required = listOf("expression")
            )
        )

        tools.put(
            tool(
                "git_status",
                "Git state of a project's source/ folder: branch, staged/unstaged/untracked files, " +
                    "commits ahead of or behind origin, and the remote URL.",
                properties("project_id" to stringProp("Project id")),
                required = listOf("project_id")
            )
        )

        tools.put(
            tool(
                "git_commit",
                "Commit in a project's source/ folder using the author set in the app. Stages every change " +
                    "first unless stage_all is false. Initialises the repository if there is none.",
                properties(
                    "project_id" to stringProp("Project id"),
                    "message" to stringProp("Commit message"),
                    "stage_all" to JSONObject().put("type", "boolean")
                ),
                required = listOf("project_id", "message")
            )
        )

        tools.put(
            tool(
                "git_push",
                "Push the current branch to origin with the GitHub token stored in the app. The token is never returned.",
                properties("project_id" to stringProp("Project id")),
                required = listOf("project_id")
            )
        )

        tools.put(
            tool(
                "git_pull",
                "Fetch and fast-forward the current branch from origin. Refuses when histories have diverged.",
                properties("project_id" to stringProp("Project id")),
                required = listOf("project_id")
            )
        )

        tools.put(
            tool(
                "run_diagnostics",
                "Check the device and network: storage, memory, notification and battery settings, Python, " +
                    "the foreground service, the last crash, DNS, HTTPS, Telegram API reachability, whether each " +
                    "running web service accepts connections, and the bridge's own health. Takes up to ~10 s.",
                JSONObject()
            )
        )

        return tools
    }

    fun call(host: McpToolHost, name: String, args: JSONObject): JSONObject {
        return try {
            when (name) {
                "list_projects" -> listProjects(host)
                "get_project" -> getProject(host, args.getString("project_id"))
                "update_project_settings" -> updateSettings(host, args)
                "list_files" -> listFiles(host, args.getString("project_id"))
                "read_file" -> readFile(host, args.getString("project_id"), args.getString("path"))
                "write_file" -> writeFile(
                    host,
                    args.getString("project_id"),
                    args.getString("path"),
                    args.getString("content")
                )
                "create_directory" -> createDirectory(host, args.getString("project_id"), args.getString("path"))
                "rename_path" -> renamePath(host, args.getString("project_id"), args.getString("from"), args.getString("to"))
                "delete_path" -> deletePath(host, args.getString("project_id"), args.getString("path"))
                "set_entry_point" -> setEntryPoint(host, args.getString("project_id"), args.getString("path"))
                "start_service" -> startService(host, args.getString("project_id"))
                "stop_service" -> stopService(host, args.getString("project_id"))
                "service_status" -> serviceStatus(host)
                "get_logs" -> getLogs(host, args.optString("project_id").ifBlank { null }, args.optInt("limit", 100))
                "run_command" -> runCommand(
                    host,
                    args.getString("command"),
                    args.optString("project_id").ifBlank { null },
                    args.optLong("timeout_ms", 30_000L)
                )
                "run_python" -> runPython(host, args.getString("code"), args.optString("project_id").ifBlank { null })
                "sql_query" -> sqlQuery(
                    host,
                    args.getString("project_id"),
                    args.getString("sql"),
                    args.optString("database").ifBlank { null }
                )
                "run_diagnostics" -> textResult(host.runDiagnostics())
                "debug_start" -> debugStart(host, args)
                "debug_control" -> debugControl(host, args.getString("command"), waitMs(args))
                "debug_status" -> textResult(host.debugger.toJson().toString(2))
                "debug_eval" -> host.debugger.evaluate(args.getString("expression")).fold(
                    onSuccess = { textResult(it) }, onFailure = { errorResult(it.message ?: "evaluation failed") })
                "git_status" -> gitStatus(host, args.getString("project_id"))
                "git_commit" -> gitCommit(host, args.getString("project_id"), args.getString("message"), args.optBoolean("stage_all", true))
                "git_push" -> gitPush(host, args.getString("project_id"))
                "git_pull" -> gitPull(host, args.getString("project_id"))
                "backup_project" -> backupProject(host, args.getString("project_id"), args.optBoolean("to_folder", false))
                else -> errorResult("Unknown tool: $name")
            }
        } catch (e: Exception) {
            errorResult("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- handlers

    private fun updateSettings(host: McpToolHost, args: JSONObject): JSONObject = runBlocking {
        val projectId = args.getString("project_id")
        val project = host.projectOrNull(projectId) ?: return@runBlocking errorResult("Project not found")
        val current = ProjectSettings.from(project)
        val environment = if (args.has("environment")) {
            val values = args.getJSONObject("environment")
            current.environment.filter { it.isSecret } + values.keys().asSequence().map { key ->
                require(values.get(key) is String) { "Environment values must be strings" }
                EnvironmentEdit(key, values.getString(key), false)
            }.toList()
        } else current.environment
        fun intValue(key: String, fallback: Int): Int {
            if (!args.has(key)) return fallback
            val value = args.get(key)
            require(value is Number && value.toDouble() == value.toInt().toDouble()) { "$key must be an integer" }
            return value.toInt()
        }
        if (args.has("start_on_boot")) require(args.get("start_on_boot") is Boolean) { "start_on_boot must be a boolean" }
        val policy = if (args.has("restart_policy")) {
            RestartPolicy.entries.find { it.name == args.getString("restart_policy") }
                ?: throw IllegalArgumentException("restart_policy must be NEVER, ON_FAILURE or ALWAYS")
        } else current.restartPolicy
        host.projectSettings.update(projectId, current.copy(
            port = intValue("port", current.port), restartPolicy = policy,
            startOnBoot = if (args.has("start_on_boot")) args.getBoolean("start_on_boot") else current.startOnBoot,
            maxCpuPercent = intValue("max_cpu_percent", current.maxCpuPercent),
            maxHeapMb = intValue("max_heap_mb", current.maxHeapMb),
            idleTimeoutMinutes = intValue("idle_timeout_minutes", current.idleTimeoutMinutes),
            environment = environment
        ), allowSecrets = false)
        host.onProjectChanged(projectId)
        textResult("Project settings saved")
    }

    private fun listProjects(host: McpToolHost): JSONObject = runBlocking {
        val instances = host.serviceSupervisor.instances.value
        val array = JSONArray()
        host.appMetaDatabase.getAllProjects().forEach { project ->
            array.put(
                JSONObject()
                    .put("id", project.id)
                    .put("name", project.name)
                    .put("profile", project.profile.id)
                    .put("entry_point", project.entryPoint)
                    .put("port", project.network.port)
                    .put("state", instances[project.id]?.state?.name ?: "STOPPED")
            )
        }
        textResult(array.toString(2))
    }

    private fun getProject(host: McpToolHost, projectId: String): JSONObject = runBlocking {
        val project = host.projectOrNull(projectId) ?: return@runBlocking errorResult("No project with id $projectId")
        val instance = host.serviceSupervisor.instances.value[projectId]
        val env = JSONArray()
        project.environment.forEach { variable ->
            env.put(
                JSONObject()
                    .put("key", variable.key)
                    // Secret values are never echoed back over the bridge.
                    .put("value", if (variable.isSecret) "<secret>" else variable.value)
                    .put("is_secret", variable.isSecret)
            )
        }
        textResult(
            JSONObject()
                .put("id", project.id)
                .put("name", project.name)
                .put("description", project.description)
                .put("profile", project.profile.id)
                .put("project_root", project.projectRoot)
                .put("entry_point", project.entryPoint)
                .put("working_directory", project.workingDirectory)
                .put("restart_policy", project.restartPolicy.name)
                .put("start_on_boot", project.startOnBoot)
                .put("port", project.network.port)
                .put("allow_lan", project.network.allowLan)
                .put("max_cpu_percent", project.maxCpuPercent)
                .put("max_heap_mb", project.maxHeapMb)
                .put("idle_timeout_minutes", project.idleTimeoutMinutes)
                .put("environment", env)
                .put("state", instance?.state?.name ?: "STOPPED")
                .put("uptime_seconds", instance?.uptimeSeconds ?: 0)
                .toString(2)
        )
    }

    private fun listFiles(host: McpToolHost, projectId: String): JSONObject = runBlocking {
        host.projectOrNull(projectId) ?: return@runBlocking errorResult("No project with id $projectId")
        val paths = JSONArray()
        flatten(host.projectStorage.listProjectFiles(projectId)).forEach { paths.put(it) }
        textResult(paths.toString(2))
    }

    private fun flatten(nodes: List<FileNode>): List<String> {
        val out = mutableListOf<String>()
        nodes.forEach { node ->
            if (node.isDirectory) {
                out.add("${node.relativePath}/")
                out.addAll(flatten(node.children))
            } else {
                out.add(node.relativePath)
            }
        }
        return out
    }

    private fun readFile(host: McpToolHost, projectId: String, path: String): JSONObject = runBlocking {
        host.projectOrNull(projectId) ?: return@runBlocking errorResult("No project with id $projectId")
        val info = host.projectStorage.inspect(projectId, path)
        when (info.kind) {
            FileKind.MISSING -> errorResult("No such file: $path")
            FileKind.DIRECTORY -> errorResult("$path is a folder; use list_files")
            FileKind.BINARY -> errorResult("$path is a binary file (${ProjectStorage.formatSize(info.size)}); it cannot be returned as text")
            FileKind.TOO_LARGE -> errorResult("$path is ${ProjectStorage.formatSize(info.size)}, over the 1 MB limit for read_file")
            FileKind.TEXT -> {
                val content = host.projectStorage.readFile(projectId, path)
                textResult(content.ifEmpty { "(empty file: $path)" })
            }
        }
    }

    private fun writeFile(host: McpToolHost, projectId: String, path: String, content: String): JSONObject = runBlocking {
        host.projectOrNull(projectId) ?: return@runBlocking errorResult("No project with id $projectId")
        host.projectStorage.writeFileAtomically(projectId, path, content)
        host.onProjectChanged(projectId)
        textResult("Wrote ${content.toByteArray().size} bytes to ${host.projectStorage.normalize(projectId, path)}")
    }

    private fun createDirectory(host: McpToolHost, projectId: String, path: String): JSONObject = runBlocking {
        host.projectOrNull(projectId) ?: return@runBlocking errorResult("No project with id $projectId")
        val created = host.projectStorage.createDirectory(projectId, path)
        host.onProjectChanged(projectId)
        textResult("Created folder $created/")
    }

    private fun renamePath(host: McpToolHost, projectId: String, from: String, to: String): JSONObject = runBlocking {
        val project = host.projectOrNull(projectId) ?: return@runBlocking errorResult("No project with id $projectId")
        val source = host.projectStorage.normalize(projectId, from)
        val target = host.projectStorage.movePath(projectId, source, to)
        val note = when (val effect = EntryPoints.follow(project, source, target)) {
            EntryPointEffect.Unaffected -> ""
            is EntryPointEffect.Moved -> {
                host.appMetaDatabase.insertOrUpdateProject(effect.project)
                " The project's entry point now follows it: ${effect.project.entryPoint}."
            }
            EntryPointEffect.Lost -> " Warning: that was the entry point and it is no longer in source/, " +
                "so the service will not start until it is moved back or set_entry_point picks another file."
        }
        host.onProjectChanged(projectId)
        textResult("Moved $source -> $target.$note")
    }

    private fun deletePath(host: McpToolHost, projectId: String, path: String): JSONObject = runBlocking {
        val project = host.projectOrNull(projectId) ?: return@runBlocking errorResult("No project with id $projectId")
        val source = host.projectStorage.normalize(projectId, path)
        if (!host.projectStorage.resolveInProject(projectId, source).exists()) {
            return@runBlocking errorResult("No such file or folder: $source")
        }
        if (!host.projectStorage.deleteFile(projectId, source)) {
            return@runBlocking errorResult("Could not delete $source")
        }
        val note = if (EntryPoints.follow(project, source, null) == EntryPointEffect.Lost) {
            " Warning: that was the project's entry point, so the service will not start until it is " +
                "recreated or set_entry_point picks another file."
        } else {
            ""
        }
        host.onProjectChanged(projectId)
        textResult("Deleted $source.$note")
    }

    private fun setEntryPoint(host: McpToolHost, projectId: String, path: String): JSONObject = runBlocking {
        val project = host.projectOrNull(projectId) ?: return@runBlocking errorResult("No project with id $projectId")
        val target = host.projectStorage.normalize(projectId, path)
        EntryPoints.problemWith(project, target)?.let { return@runBlocking errorResult(it) }
        if (!host.projectStorage.resolveInProject(projectId, target).isFile) {
            return@runBlocking errorResult("No such file: $target")
        }
        val updated = project.copy(entryPoint = target.removePrefix("source/"), updatedAt = System.currentTimeMillis())
        host.appMetaDatabase.insertOrUpdateProject(updated)
        host.onProjectChanged(projectId)
        textResult("Entry point of '${project.name}' is now ${updated.entryPoint}")
    }

    private fun startService(host: McpToolHost, projectId: String): JSONObject = runBlocking {
        val project = host.projectOrNull(projectId) ?: return@runBlocking errorResult("No project with id $projectId")
        val started = host.serviceSupervisor.startProject(project)
        val instance = host.serviceSupervisor.instances.value[projectId]
        if (started) {
            textResult("Started '${project.name}' — state ${instance?.state?.name}, port ${instance?.port}")
        } else {
            errorResult("Could not start '${project.name}': ${instance?.lastError ?: "see logs"}")
        }
    }

    private fun stopService(host: McpToolHost, projectId: String): JSONObject = runBlocking {
        host.projectOrNull(projectId) ?: return@runBlocking errorResult("No project with id $projectId")
        if (host.serviceSupervisor.stopProject(projectId)) textResult("Stopped service for $projectId")
        else errorResult("The runtime is still stopping; wait for its blocking call to return")
    }

    private fun serviceStatus(host: McpToolHost): JSONObject {
        val array = JSONArray()
        host.serviceSupervisor.instances.value.values.forEach { instance ->
            array.put(
                JSONObject()
                    .put("project_id", instance.projectId)
                    .put("name", instance.projectName)
                    .put("state", instance.state.name)
                    .put("port", instance.port)
                    .put("uptime_seconds", instance.uptimeSeconds)
                    .put("restart_count", instance.restartCount)
                    .put("last_error", instance.lastError ?: JSONObject.NULL)
            )
        }
        return textResult(array.toString(2))
    }

    private fun waitMs(args: JSONObject) = args.optLong("wait_ms", 10_000L).coerceIn(0L, 60_000L)

    private fun debugStart(host: McpToolHost, args: JSONObject): JSONObject = runBlocking {
        val project = host.projectOrNull(args.getString("project_id")) ?: return@runBlocking errorResult("Project not found")
        val debugger = host.debugger
        args.optJSONArray("breakpoints")?.let { list ->
            val byFile = mutableMapOf<String, MutableSet<Int>>()
            for (i in 0 until list.length()) {
                val entry = list.getJSONObject(i)
                val path = entry.getString("path").removePrefix("./")
                require(path.startsWith("source/") && path.endsWith(".py")) { "Breakpoints go on .py files under source/: $path" }
                byFile.getOrPut(path.removePrefix("source/")) { mutableSetOf() } += entry.getInt("line")
            }
            byFile.forEach { (file, lines) -> debugger.setBreakpoints(project.id, file, lines) }
        }
        val before = debugger.state.value
        debugger.arm(project.id)
        val started = try {
            host.serviceSupervisor.startProject(project)
        } finally {
            debugger.disarm(project.id)
        }
        if (!started) return@runBlocking errorResult("Could not start the project. Stop it first if it is running.")
        textResult(debugger.toJson(debugger.awaitSettled(before, waitMs(args))).toString(2))
    }

    private fun debugControl(host: McpToolHost, command: String, waitMs: Long): JSONObject = runBlocking {
        val debugger = host.debugger
        val before = debugger.state.value
        debugger.command(command)?.let { return@runBlocking errorResult(it) }
        textResult(debugger.toJson(debugger.awaitSettled(before, waitMs)).toString(2))
    }

    private fun <T> withRepo(host: McpToolHost, projectId: String, block: suspend (Project) -> T): JSONObject = runBlocking {
        val project = host.projectOrNull(projectId) ?: return@runBlocking errorResult("Project not found")
        try {
            val result = block(project)
            if (result is JSONObject) result else textResult(result.toString())
        } catch (e: GitException) {
            errorResult(e.message ?: "git failed")
        }
    }

    private fun gitStatus(host: McpToolHost, projectId: String) = withRepo(host, projectId) { project ->
        val git = host.git()
        if (!git.isRepository(project)) return@withRepo "No repository yet. git_commit creates one."
        val s = git.status(project)
        JSONObject()
            .put("branch", s.branch ?: JSONObject.NULL)
            .put("head", s.head ?: JSONObject.NULL)
            .put("staged", JSONArray(s.staged))
            .put("unstaged", JSONArray(s.unstaged))
            .put("untracked", JSONArray(s.untracked))
            .put("ahead", s.ahead ?: JSONObject.NULL)
            .put("behind", s.behind ?: JSONObject.NULL)
            .put("remote", s.remote ?: JSONObject.NULL)
            .toString(2)
    }

    private fun gitCommit(host: McpToolHost, projectId: String, message: String, stageAll: Boolean) =
        withRepo(host, projectId) { project ->
            val git = host.git()
            if (!git.isRepository(project)) git.init(project)
            if (stageAll) git.stage(project)
            val sha = git.commit(project, message)
            host.onProjectChanged(project.id)
            "Committed $sha on ${git.status(project).branch}"
        }

    private fun gitPush(host: McpToolHost, projectId: String) = withRepo(host, projectId) { project ->
        host.git().push(project)
        "Pushed ${host.git().status(project).branch} to origin"
    }

    private fun gitPull(host: McpToolHost, projectId: String) = withRepo(host, projectId) { project ->
        val count = host.git().pull(project)
        host.onProjectChanged(project.id)
        if (count == 0) "Already up to date" else "Pulled $count new commit(s)"
    }

    private fun backupProject(host: McpToolHost, projectId: String, toFolder: Boolean): JSONObject = runBlocking {
        val project = host.projectOrNull(projectId) ?: return@runBlocking errorResult("Project not found")
        textResult(host.backupProject(project, toFolder))
    }

    private fun getLogs(host: McpToolHost, projectId: String?, limit: Int): JSONObject {
        val capped = limit.coerceIn(1, 1000)
        val events = host.logManager.eventsFlow.value
            .filter { projectId == null || it.projectId == projectId }
            .takeLast(capped)
        val text = events.joinToString("\n") { "[${it.level}] ${it.serviceName}: ${it.message}" }
        return textResult(text.ifBlank { "(no log lines)" })
    }

    private fun runCommand(host: McpToolHost, command: String, projectId: String?, timeoutMs: Long): JSONObject = runBlocking {
        val dir = projectId?.let { id -> host.projectOrNull(id)?.let { host.projectDir(it) } }
            ?: host.projectStorage.getProjectDir("")
        val output = host.terminalSession.runOneShot(
            command,
            dir,
            timeoutMs.coerceIn(1_000L, 120_000L)
        )
        textResult(output.ifBlank { "(no output)" })
    }

    private fun runPython(host: McpToolHost, code: String, projectId: String?): JSONObject = runBlocking {
        val dir = projectId?.let { id -> host.projectOrNull(id)?.let { host.projectDir(it) } }
            ?: host.projectStorage.getProjectDir("")
        textResult(host.runPython(code, dir).ifBlank { "(no output)" })
    }

    private fun sqlQuery(host: McpToolHost, projectId: String, sql: String, database: String?): JSONObject = runBlocking {
        val project: Project = host.projectOrNull(projectId)
            ?: return@runBlocking errorResult("No project with id $projectId")

        val candidates = host.projectDatabaseManager.discoverDatabases(project)
        if (candidates.isEmpty()) return@runBlocking errorResult("No SQLite database found in project $projectId")

        val target: File = database?.let { wanted -> candidates.find { it.name == wanted } } ?: candidates.first()
        val result = host.projectDatabaseManager.executeQuery(target, sql)

        if (result.error != null) return@runBlocking errorResult("SQL error: ${result.error}")

        val payload = JSONObject()
            .put("database", target.name)
            .put("duration_ms", result.durationMs)
            .put("affected_rows", result.affectedRows)
        if (result.isQuery) {
            val columns = JSONArray()
            result.columns.forEach { columns.put(it) }
            val rows = JSONArray()
            result.rows.forEach { row ->
                val jsonRow = JSONArray()
                row.forEach { jsonRow.put(it) }
                rows.put(jsonRow)
            }
            payload.put("columns", columns).put("rows", rows)
        }
        textResult(payload.toString(2))
    }

    // ---------------------------------------------------------------- helpers

    private fun textResult(text: String): JSONObject =
        JSONObject()
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", SecretRedactor.redact(text))))
            .put("isError", false)

    private fun errorResult(message: String): JSONObject =
        JSONObject()
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", SecretRedactor.redact(message))))
            .put("isError", true)

    private fun tool(
        name: String,
        description: String,
        properties: JSONObject,
        required: List<String> = emptyList()
    ): JSONObject {
        val schema = JSONObject()
            .put("type", "object")
            .put("properties", properties)
        if (required.isNotEmpty()) {
            val array = JSONArray()
            required.forEach { array.put(it) }
            schema.put("required", array)
        }
        return JSONObject()
            .put("name", name)
            .put("description", description)
            .put("inputSchema", schema)
    }

    private fun properties(vararg entries: Pair<String, JSONObject>): JSONObject {
        val obj = JSONObject()
        entries.forEach { (key, value) -> obj.put(key, value) }
        return obj
    }

    private fun stringProp(description: String): JSONObject =
        JSONObject().put("type", "string").put("description", description)

    private fun intProp(description: String): JSONObject =
        JSONObject().put("type", "integer").put("description", description)
}
