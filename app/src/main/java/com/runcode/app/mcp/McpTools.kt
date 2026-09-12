package com.runcode.app.mcp

import com.runcode.app.domain.models.Project
import com.runcode.app.storage.FileNode
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
                "List the source files of a project as a flat array of relative paths.",
                properties("project_id" to stringProp("Project id")),
                required = listOf("project_id")
            )
        )

        tools.put(
            tool(
                "read_file",
                "Read a text file inside a project. Paths are relative to the project root, e.g. source/main.py.",
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

        return tools
    }

    fun call(host: McpToolHost, name: String, args: JSONObject): JSONObject {
        return try {
            when (name) {
                "list_projects" -> listProjects(host)
                "get_project" -> getProject(host, args.getString("project_id"))
                "list_files" -> listFiles(host, args.getString("project_id"))
                "read_file" -> readFile(host, args.getString("project_id"), args.getString("path"))
                "write_file" -> writeFile(
                    host,
                    args.getString("project_id"),
                    args.getString("path"),
                    args.getString("content")
                )
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
                else -> errorResult("Unknown tool: $name")
            }
        } catch (e: Exception) {
            errorResult("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- handlers

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
            if (node.isDirectory) out.addAll(flatten(node.children)) else out.add(node.relativePath)
        }
        return out
    }

    private fun readFile(host: McpToolHost, projectId: String, path: String): JSONObject = runBlocking {
        host.projectOrNull(projectId) ?: return@runBlocking errorResult("No project with id $projectId")
        val content = host.projectStorage.readFile(projectId, path)
        if (content.isEmpty()) textResult("(empty or missing file: $path)") else textResult(content)
    }

    private fun writeFile(host: McpToolHost, projectId: String, path: String, content: String): JSONObject = runBlocking {
        host.projectOrNull(projectId) ?: return@runBlocking errorResult("No project with id $projectId")
        host.projectStorage.writeFileAtomically(projectId, path, content)
        textResult("Wrote ${content.toByteArray().size} bytes to $path")
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
        host.serviceSupervisor.stopProject(projectId)
        textResult("Stopped service for $projectId")
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
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
            .put("isError", false)

    private fun errorResult(message: String): JSONObject =
        JSONObject()
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", message)))
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
